package ai.lambda.agent.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A real MCP server for tests, built with the MCP SDK and started as a separate process.
 * It must never print to stdout: stdout carries the protocol.
 */
public final class TestMcpServer {

    private static final McpSchema.ToolAnnotations SAFE = McpSchema.ToolAnnotations.builder()
            .readOnlyHint(true).destructiveHint(false).openWorldHint(false).build();

    public static void main(String[] args) throws InterruptedException {
        io.modelcontextprotocol.server.McpServer
                .sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("test-server", "1.2.3")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(
                        tool("echo", "Repeats the given text.", Map.of(
                                "type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text"),
                                "additionalProperties", false), SAFE,
                                request -> text("echo: " + request.arguments().get("text"))),
                        tool("add", "Adds two numbers.", Map.of(
                                "type", "object",
                                "properties", Map.of("a", Map.of("type", "number"), "b", Map.of("type", "number"))),
                                SAFE,
                                request -> text(String.valueOf(
                                        ((Number) request.arguments().get("a")).doubleValue()
                                                + ((Number) request.arguments().get("b")).doubleValue()))),
                        tool("fail", "Always reports an error.", null, SAFE,
                                request -> McpSchema.CallToolResult.builder()
                                        .addTextContent("disk is full").isError(true).build()),
                        tool("structured", "Returns structured data only.", null, SAFE,
                                request -> McpSchema.CallToolResult.builder()
                                        .structuredContent(Map.of("answer", 42)).build()),
                        // No annotations: must be treated as the riskiest kind of tool.
                        tool("delete-all", "Deletes everything.", null, null,
                                request -> text("deleted")))
                .build();

        new CountDownLatch(1).await(); // stay alive until the client stops this process
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name, String description, Map<String, Object> schema, McpSchema.ToolAnnotations annotations,
            java.util.function.Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        McpSchema.Tool.Builder tool = McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(schema != null ? schema : Map.of("type", "object", "properties", Map.of()));
        if (annotations != null) tool.annotations(annotations);
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool.build())
                .callHandler((exchange, request) -> handler.apply(request))
                .build();
    }

    private static McpSchema.CallToolResult text(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }
}
