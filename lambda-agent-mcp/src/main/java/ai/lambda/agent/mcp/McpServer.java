package ai.lambda.agent.mcp;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.McpToolAdapter;
import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolPolicy;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A connection to one MCP server whose tools the agent can use.
 *
 * <pre>
 * try (McpServer files = McpServer.stdio("files", "npx", "-y",
 *         "@modelcontextprotocol/server-filesystem", "/path/to/folder").connect()) {
 *     AgentConfig config = new AgentConfig(prompt, model, files.tools(), 10);
 *     ...
 * }
 * </pre>
 *
 * Each MCP tool becomes an {@link AgentTool} named {@code <server>__<tool>}. Its capabilities
 * come from the server's tool hints (read-only, destructive, open world), so the agent's
 * {@code ToolPermissionPolicy} and approval handler apply to MCP tools too. Close the server
 * when the agent is done with it; for stdio servers this stops the server process.
 */
public final class McpServer implements AutoCloseable {

    private final String name;
    private final McpSyncClient client;
    private final McpSchema.Implementation serverInfo;
    private final List<AgentTool> tools;
    private volatile boolean closed;

    private McpServer(String name, McpSyncClient client, McpSchema.Implementation serverInfo,
                      List<McpSchema.Tool> mcpTools, Builder options) {
        this.name = name;
        this.client = client;
        this.serverInfo = serverInfo;

        List<AgentTool> converted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (McpSchema.Tool tool : mcpTools) {
            if (!options.include.isEmpty() && !options.include.contains(tool.name())) continue;
            String toolName = McpConversions.toolName(name, tool.name(), options.prefixToolNames);
            if (!seen.add(toolName)) {
                throw new IllegalStateException("MCP server '" + name + "' has two tools named '" + toolName
                        + "' after cleaning up names; use include(...) to pick one");
            }
            Set<ToolCapability> capabilities = McpConversions.capabilities(tool.annotations());
            boolean approval = options.requireApproval
                    || (options.requireApprovalForWrites && !capabilities.contains(ToolCapability.READ));
            ToolPolicy policy = new ToolPolicy(approval, options.requestTimeout.plusSeconds(5),
                    options.maxResultLength, capabilities);
            String mcpName = tool.name();
            converted.add(new McpToolAdapter(toolName, McpConversions.description(tool),
                    McpConversions.schemaJson(tool.inputSchema()), policy,
                    (argumentsJson, context) -> call(mcpName, argumentsJson)));
        }
        for (String wanted : options.include) {
            if (mcpTools.stream().noneMatch(t -> t.name().equals(wanted))) {
                throw new IllegalStateException("MCP server '" + name + "' has no tool named '" + wanted + "'");
            }
        }
        this.tools = List.copyOf(converted);
    }

    /** Starts a server as a local process that talks over stdin/stdout. */
    public static Builder stdio(String name, String command, String... args) {
        Objects.requireNonNull(command, "command must not be null");
        return new Builder(name, options -> {
            ServerParameters.Builder params = ServerParameters.builder(command).args(args);
            options.env.forEach(params::addEnvVar);
            return new StdioClientTransport(params.build(), McpJsonDefaults.getMapper());
        });
    }

    /** Connects to a remote server over Streamable HTTP, for example {@code https://example.com/mcp}. */
    public static Builder http(String name, String url) {
        URI uri = URI.create(Objects.requireNonNull(url, "url must not be null"));
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an absolute http(s) URL: " + url);
        }
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/mcp" : uri.getRawPath();
        if (uri.getRawQuery() != null) endpoint += "?" + uri.getRawQuery();
        String path = endpoint;
        return new Builder(name, options -> {
            Map<String, String> headers = Map.copyOf(options.headers);
            return HttpClientStreamableHttpTransport.builder(base)
                    .endpoint(path)
                    .connectTimeout(options.initializationTimeout)
                    .httpRequestCustomizer((request, method, target, body, context) -> headers.forEach(request::header))
                    .build();
        });
    }

    /** Uses any MCP SDK transport, for transports this class has no shortcut for. */
    public static Builder transport(String name, McpClientTransport transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        return new Builder(name, options -> transport);
    }

    public String name() {
        return name;
    }

    /** The server's own name and version, as it reported them. */
    public String serverInfo() {
        return serverInfo == null ? "unknown" : serverInfo.name() + " " + serverInfo.version();
    }

    /** The server's tools as agent tools. Pass them to {@code AgentConfig}. */
    public List<AgentTool> tools() {
        return tools;
    }

    private ai.lambda.agent.core.ToolResult call(String tool, String argumentsJson) {
        if (closed) throw new IllegalStateException("MCP server '" + name + "' is closed");
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, McpConversions.arguments(argumentsJson)));
        return McpConversions.toResult(result);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (!client.closeGracefully()) client.close();
        } catch (RuntimeException e) {
            client.close();
        }
    }

    public static final class Builder {
        private final String name;
        private final Function<Builder, McpClientTransport> transportFactory;
        private final Map<String, String> env = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Set<String> include = new HashSet<>();
        private Duration requestTimeout = Duration.ofSeconds(60);
        private Duration initializationTimeout = Duration.ofSeconds(30);
        private boolean prefixToolNames = true;
        private boolean requireApproval;
        private boolean requireApprovalForWrites;
        private int maxResultLength = 128 * 1024;

        private Builder(String name, Function<Builder, McpClientTransport> transportFactory) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            this.transportFactory = transportFactory;
        }

        /** Sets an environment variable for a stdio server process (for example an API token). */
        public Builder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        /** Adds a header to every HTTP request (for example {@code Authorization}). */
        public Builder header(String key, String value) {
            headers.put(key, value);
            return this;
        }

        /** Only expose these tools (by their MCP names). By default all tools are exposed. */
        public Builder include(String... toolNames) {
            include.addAll(List.of(toolNames));
            return this;
        }

        /** Maximum time for one tool call (default 60 seconds). */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** Maximum time to connect and complete the MCP handshake (default 30 seconds). */
        public Builder initializationTimeout(Duration timeout) {
            this.initializationTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** Whether tool names get a {@code <server>__} prefix (default true). */
        public Builder prefixToolNames(boolean prefix) {
            this.prefixToolNames = prefix;
            return this;
        }

        /** Require the agent's approval handler to approve every call to this server's tools. */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /**
         * Require approval only for tools that are not marked read-only, so reads run freely
         * while every change goes through the agent's approval handler.
         */
        public Builder requireApprovalForWrites(boolean requireApprovalForWrites) {
            this.requireApprovalForWrites = requireApprovalForWrites;
            return this;
        }

        /** Longest tool result passed to the model; longer results are shortened (default 128 KiB). */
        public Builder maxResultLength(int maxResultLength) {
            this.maxResultLength = maxResultLength;
            return this;
        }

        /** Starts or connects to the server, runs the MCP handshake and loads its tools. */
        public McpServer connect() {
            McpSyncClient client = McpClient.sync(transportFactory.apply(this))
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(initializationTimeout)
                    .clientInfo(new McpSchema.Implementation("lambda-agent", "0.1.0"))
                    .build();
            try {
                McpSchema.InitializeResult init = client.initialize();
                List<McpSchema.Tool> tools = new ArrayList<>();
                String cursor = null;
                do {
                    McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                    tools.addAll(page.tools());
                    cursor = page.nextCursor();
                } while (cursor != null && !cursor.isEmpty());
                return new McpServer(name, client, init.serverInfo(), tools, this);
            } catch (RuntimeException e) {
                client.close();
                throw new IllegalStateException("Could not connect to MCP server '" + name + "': " + e.getMessage(), e);
            }
        }
    }
}
