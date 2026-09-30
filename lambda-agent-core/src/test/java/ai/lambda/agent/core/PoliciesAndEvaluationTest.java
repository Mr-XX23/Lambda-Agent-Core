package ai.lambda.agent.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Permission policies, retry timing, MCP tool adapters and the evaluator. */
class PoliciesAndEvaluationTest {

    private static AgentTool tool(String name, ToolCapability... capabilities) {
        return new McpToolAdapter(name, "test tool", "{\"type\":\"object\",\"properties\":{}}",
                new ToolPolicy(false, Duration.ofSeconds(5), 1000, Set.of(capabilities)),
                (arguments, context) -> ToolResult.of("ran " + name + " with " + arguments));
    }

    // --- ToolPolicyComposition

    private static final ToolPermissionPolicy NO_WRITES = new ToolPermissionPolicy() {
        public boolean allowed(String sessionId, AgentTool tool, Set<ToolCapability> capabilities) {
            return !capabilities.contains(ToolCapability.WRITE);
        }

        public PermissionDecision evaluate(String sessionId, AgentTool tool, Set<ToolCapability> capabilities) {
            return allowed(sessionId, tool, capabilities) ? PermissionDecision.allow("read only") : PermissionDecision.deny("writes are off");
        }
    };
    private static final ToolPermissionPolicy ADMIN_ONLY = (sessionId, tool, capabilities) -> sessionId.startsWith("admin-");

    @Test
    void allOfDeniesWhenAnyPolicyDeniesAndReportsWhy() {
        ToolPermissionPolicy policy = ToolPolicyComposition.allOf(List.of(NO_WRITES, ADMIN_ONLY));
        AgentTool reader = tool("read", ToolCapability.READ);
        AgentTool writer = tool("write", ToolCapability.WRITE);

        assertTrue(policy.allowed("admin-1", reader, reader.getCapabilities()));
        assertEquals("all policies allowed", policy.evaluate("admin-1", reader, reader.getCapabilities()).reason());

        PermissionDecision writeDenied = policy.evaluate("admin-1", writer, writer.getCapabilities());
        assertFalse(writeDenied.allowed());
        assertEquals("writes are off", writeDenied.reason());
        assertFalse(policy.allowed("guest-1", reader, reader.getCapabilities()));
        assertEquals("policy denied tool", policy.evaluate("guest-1", reader, reader.getCapabilities()).reason());

        assertTrue(ToolPolicyComposition.allOf(List.of()).allowed("anyone", writer, writer.getCapabilities()),
                "no policies: nothing denies");
    }

    @Test
    void anyOfAllowsWhenOnePolicyAllowsAndCollectsDenials() {
        ToolPermissionPolicy policy = ToolPolicyComposition.anyOf(List.of(NO_WRITES, ADMIN_ONLY));
        AgentTool writer = tool("write", ToolCapability.WRITE);

        assertTrue(policy.allowed("admin-1", writer, writer.getCapabilities()), "admins may write");
        assertTrue(policy.allowed("guest-1", tool("read"), Set.of()), "anyone may read");

        PermissionDecision denied = policy.evaluate("guest-1", writer, writer.getCapabilities());
        assertFalse(denied.allowed());
        assertEquals("writes are off; policy denied tool", denied.reason());
        assertFalse(ToolPolicyComposition.anyOf(List.of()).allowed("anyone", writer, Set.of()), "no policies: nothing allows");
    }

    @Test
    void permissionDecisionsHaveADefaultReason() {
        assertEquals("allowed", PermissionDecision.allow(null).reason());
        assertEquals("denied", PermissionDecision.deny(null).reason());
        assertTrue(ToolPermissionPolicy.allowAll().allowed("s", tool("t"), Set.of()));
    }

    // --- RetryPolicy

    @Test
    void retryDelaysGrowByTheMultiplier() {
        RetryPolicy policy = RetryPolicy.exponential(4, Duration.ofMillis(100));
        assertEquals(Duration.ZERO, policy.delayBeforeAttempt(1), "no wait before the first attempt");
        assertEquals(Duration.ofMillis(100), policy.delayBeforeAttempt(2));
        assertEquals(Duration.ofMillis(200), policy.delayBeforeAttempt(3));
        assertEquals(Duration.ofMillis(400), policy.delayBeforeAttempt(4));
        assertEquals(Duration.ofMillis(150), new RetryPolicy(3, Duration.ofMillis(100), 1.5).delayBeforeAttempt(3));

        assertEquals(1, RetryPolicy.none().maxAttempts());
        assertEquals(Duration.ZERO, RetryPolicy.none().delayBeforeAttempt(5));
        assertTrue(new RetryPolicy(100, Duration.ofDays(1), 10).delayBeforeAttempt(100).toMillis() > 0, "very large delays do not overflow");
    }

    @Test
    void retryPolicyRejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofMillis(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ZERO, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ZERO, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ZERO, Double.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class, () -> new RetryPolicy(1, null, 1));
    }

    // --- McpToolAdapter and McpToolRegistry

    @Test
    void mcpAdapterExposesTheToolAndPassesArgumentsThrough() throws Exception {
        AgentTool adapted = tool("search", ToolCapability.NETWORK);

        assertEquals("search", adapted.getName());
        assertEquals("test tool", adapted.getDescription());
        assertEquals(Set.of(ToolCapability.NETWORK), adapted.getCapabilities());
        assertEquals("ran search with {\"q\":\"java\"}",
                adapted.execute(new ToolInvocationContext("c1", "{\"q\":\"java\"}", new AgentSession("s"))).getContent());

        AgentTool defaults = new McpToolAdapter("plain", "d", "{}", (arguments, context) -> ToolResult.of("ok"));
        assertEquals(ToolPolicy.unrestricted(), defaults.getPolicy());
        assertThrows(NullPointerException.class, () -> new McpToolAdapter("x", "d", "{}", null));
    }

    @Test
    void mcpRegistryKeepsToolNamesUnique() {
        McpToolRegistry registry = new McpToolRegistry();
        AgentTool search = tool("search");
        registry.register(search);

        assertSame(search, registry.require("search"));
        assertEquals(Set.of("search"), registry.snapshot().keySet());
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool("search")));
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
    }

    @Test
    void anAgentCanUseAnAdaptedTool() {
        FakeModelClient model = new FakeModelClient().replyToolCall("c1", "search", "{\"q\":\"java\"}").replyText("found it");
        Agent agent = new Agent(new AgentConfig("system", model, List.of(tool("search")), 5), new InMemorySessionStore());

        AgentResult result = agent.run("s", "find java");

        assertEquals("found it", result.getFinalText());
        assertTrue(result.getSession().getMessages().stream()
                .anyMatch(message -> message.getContent().equals("ran search with {\"q\":\"java\"}")));
    }

    // --- AgentEvaluator

    @Test
    void evaluatorComparesTextAndToolCallCount() {
        FakeModelClient model = new FakeModelClient()
                .replyText("4")                                   // case 1
                .replyToolCall("c1", "search", "{}").replyText("found")  // case 2
                .replyText("5")                                   // case 3: wrong text
                .replyText("ok");                                 // case 4: a tool call was expected
        Agent agent = new Agent(new AgentConfig("system", model, List.of(tool("search")), 5), new InMemorySessionStore());

        List<EvaluationResult> results = new AgentEvaluator().evaluate(agent, List.of(
                new EvaluationCase("adds", "s1", "2+2?", "4", 0),
                new EvaluationCase("uses the tool", "s2", "search", null, 1),
                new EvaluationCase("wrong answer", "s3", "2+2?", "4", 0),
                new EvaluationCase("skipped the tool", "s4", "search", "ok", 1)));

        assertEquals(List.of(true, true, false, false), results.stream().map(EvaluationResult::passed).toList());
        assertNull(results.get(0).failure());
        assertEquals("found", results.get(1).actualText());
        assertEquals(1, results.get(1).actualToolCalls());
        assertEquals("5", results.get(2).actualText());
        assertNotNull(results.get(2).failure());
        assertEquals(0, results.get(3).actualToolCalls());
        assertEquals("skipped the tool", results.get(3).name());
    }

    @Test
    void evaluationCasesValidateTheirFields() {
        assertThrows(NullPointerException.class, () -> new EvaluationCase(null, "s", "in", null, 0));
        assertThrows(NullPointerException.class, () -> new EvaluationCase("n", null, "in", null, 0));
        assertThrows(NullPointerException.class, () -> new EvaluationCase("n", "s", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationCase("n", "s", "in", null, -1));
    }
}
