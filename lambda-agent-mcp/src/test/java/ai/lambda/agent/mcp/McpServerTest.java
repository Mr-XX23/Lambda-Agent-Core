package ai.lambda.agent.mcp;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentConfig;
import ai.lambda.agent.core.AgentResult;
import ai.lambda.agent.core.AgentSession;
import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.InMemorySessionStore;
import ai.lambda.agent.core.RetryPolicy;
import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolErrorStrategy;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolPermissionPolicy;
import ai.lambda.agent.core.ToolResult;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: starts {@link TestMcpServer} as a real process and talks MCP to it over stdio. */
class McpServerTest {

    private static McpServer server;

    static McpServer.Builder testServer() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return McpServer.stdio("test", java, "-cp", System.getProperty("java.class.path"),
                TestMcpServer.class.getName());
    }

    @BeforeAll
    static void start() {
        server = testServer().connect();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static AgentTool tool(McpServer server, String name) {
        return server.tools().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    private static ToolResult call(AgentTool tool, String argsJson) throws Exception {
        return tool.execute(new ToolInvocationContext("call", argsJson, new AgentSession("s")));
    }

    @Test
    void connectsAndListsToolsWithPrefixedSafeNames() {
        assertEquals("test-server 1.2.3", server.serverInfo());
        assertEquals(List.of("test__echo", "test__add", "test__fail", "test__structured", "test__delete_all"),
                server.tools().stream().map(AgentTool::getName).toList());
    }

    @Test
    void schemasAndDescriptionsComeFromTheServer() {
        AgentTool echo = tool(server, "test__echo");

        assertEquals("Repeats the given text.", echo.getDescription());
        JSONObject schema = new JSONObject(echo.getJsonSchema());
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("text").getString("type"));
        assertFalse(schema.getBoolean("additionalProperties"));
    }

    @Test
    void callsToolsAndReturnsTheirResults() throws Exception {
        assertEquals("echo: hi", call(tool(server, "test__echo"), "{\"text\":\"hi\"}").getContent());
        assertEquals("5.5", call(tool(server, "test__add"), "{\"a\":2,\"b\":3.5}").getContent());
        assertEquals("{\"answer\":42}", call(tool(server, "test__structured"), "{}").getContent());
    }

    @Test
    void toolErrorsAreReturnedForTheModelToSee() throws Exception {
        ToolResult result = call(tool(server, "test__fail"), "{}");

        assertEquals("MCP tool error: disk is full", result.getContent());
        assertEquals(true, result.getDetails().get("isError"));
    }

    @Test
    void hintsBecomeCapabilities() {
        assertEquals(Set.of(ToolCapability.READ), tool(server, "test__echo").getCapabilities());
        assertEquals(Set.of(ToolCapability.WRITE, ToolCapability.NETWORK, ToolCapability.SENSITIVE),
                tool(server, "test__delete_all").getCapabilities(), "no hints means the riskiest kind");
    }

    @Test
    void approvalCanBeRequiredForWritesOnly() {
        assertFalse(tool(server, "test__delete_all").getPolicy().requiresApproval(), "off by default");
        try (McpServer guarded = testServer().requireApprovalForWrites(true).connect()) {
            assertFalse(tool(guarded, "test__echo").getPolicy().requiresApproval(), "read-only tools run freely");
            assertTrue(tool(guarded, "test__delete_all").getPolicy().requiresApproval());
        }
    }

    @Test
    void includeFiltersToolsAndUnknownNamesFail() {
        try (McpServer onlyEcho = testServer().include("echo").prefixToolNames(false).connect()) {
            assertEquals(List.of("echo"), onlyEcho.tools().stream().map(AgentTool::getName).toList());
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> testServer().include("nope").connect());
        assertTrue(e.getMessage().contains("no tool named 'nope'"), e.getMessage());
    }

    @Test
    void closedServerRejectsCalls() {
        McpServer temporary = testServer().connect();
        AgentTool echo = tool(temporary, "test__echo");
        temporary.close();

        assertThrows(IllegalStateException.class, () -> call(echo, "{\"text\":\"hi\"}"));
    }

    @Test
    void failedConnectionNamesTheServer() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> McpServer.stdio("broken", "definitely-not-a-real-command-xyz")
                        .initializationTimeout(Duration.ofSeconds(5)).connect());
        assertTrue(e.getMessage().contains("MCP server 'broken'"), e.getMessage());
    }

    // --- With an agent ---

    /** A model that calls one tool, then repeats the tool's result. */
    private static ModelClient callsThenReports(String tool, String argsJson) {
        return new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                Message last = messages.get(messages.size() - 1);
                if (last.getRole() == Role.TOOL) {
                    return new ChatResponse(new Message(Role.ASSISTANT, "result: " + last.getContent(), null), List.of());
                }
                ToolCall call = new ToolCall("c1", tool, argsJson);
                return new ChatResponse(new Message(Role.ASSISTANT, "", null, null, List.of(call)), List.of(call));
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };
    }

    @Test
    void agentUsesMcpTools() {
        var config = new AgentConfig("sys", callsThenReports("test__echo", "{\"text\":\"from the agent\"}"),
                server.tools(), 5);

        AgentResult result = new Agent(config, new InMemorySessionStore()).run("s", "go");

        assertEquals("result: echo: from the agent", result.getFinalText());
    }

    @Test
    void onlyToolsTheServerMarksReadOnlyMayRunAtTheSameTimeAsOthers() {
        for (AgentTool tool : server.tools()) {
            boolean readOnly = tool.getCapabilities().contains(ToolCapability.READ);
            assertEquals(readOnly, tool.isParallelSafe(), tool.getName());
        }
        AgentTool delete = server.tools().stream().filter(t -> t.getName().equals("test__delete_all")).findFirst().orElseThrow();
        assertFalse(delete.isParallelSafe(), "a tool without hints is treated as one that changes things");
        assertTrue(server.tools().stream().anyMatch(AgentTool::isParallelSafe));
    }

    @Test
    void permissionPoliciesApplyToMcpTools() {
        ToolPermissionPolicy noDestructiveTools = (session, tool, capabilities) ->
                !capabilities.contains(ToolCapability.SENSITIVE);
        var config = new AgentConfig("sys", callsThenReports("test__delete_all", "{}"), server.tools(), 5,
                ToolErrorStrategy.SEND_TO_MODEL, Duration.ofMinutes(1), 1024, RetryPolicy.none(),
                (s, c) -> false, noDestructiveTools);

        AgentResult result = new Agent(config, new InMemorySessionStore()).run("s", "go");

        assertTrue(result.getFinalText().contains("was denied"), result.getFinalText());
    }
}
