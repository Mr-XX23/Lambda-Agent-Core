package ai.lambda.agent.mcp;

import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts between MCP SDK types and Lambda types. */
final class McpConversions {

    private static final int MAX_TOOL_NAME = 64;

    private McpConversions() {
    }

    /**
     * The name the model sees. Model providers only accept letters, digits and underscores
     * (at most 64), so other characters become underscores. With a prefix the name is
     * {@code <server>__<tool>}, which keeps tools from different servers apart.
     */
    static String toolName(String server, String tool, boolean prefix) {
        String full = prefix ? sanitize(server) + "__" + sanitize(tool) : sanitize(tool);
        if (full.length() <= MAX_TOOL_NAME) return full;
        // Too long: keep the start and add a hash of the whole name so it stays unique.
        String hash = String.format("%08x", full.hashCode());
        return full.substring(0, MAX_TOOL_NAME - hash.length() - 1) + "_" + hash;
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "_" : s;
    }

    /**
     * Maps the server's tool hints to capabilities, so permission policies can decide on MCP
     * tools like on any other tool. Missing hints use the MCP defaults (not read-only,
     * destructive, open world), so an unannotated tool is treated as the riskiest kind.
     * Hints come from the server and are only as trustworthy as the server itself.
     */
    static Set<ToolCapability> capabilities(McpSchema.ToolAnnotations hints) {
        boolean readOnly = hints != null && Boolean.TRUE.equals(hints.readOnlyHint());
        boolean destructive = !readOnly && (hints == null || !Boolean.FALSE.equals(hints.destructiveHint()));
        boolean openWorld = hints == null || !Boolean.FALSE.equals(hints.openWorldHint());

        Set<ToolCapability> capabilities = EnumSet.noneOf(ToolCapability.class);
        capabilities.add(readOnly ? ToolCapability.READ : ToolCapability.WRITE);
        if (openWorld) capabilities.add(ToolCapability.NETWORK);
        if (destructive) capabilities.add(ToolCapability.SENSITIVE);
        return capabilities;
    }

    static String schemaJson(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        return new JSONObject(inputSchema).toString();
    }

    static String description(McpSchema.Tool tool) {
        if (tool.description() != null && !tool.description().isBlank()) return tool.description();
        if (tool.title() != null && !tool.title().isBlank()) return tool.title();
        return "MCP tool " + tool.name();
    }

    static Map<String, Object> arguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        return new JSONObject(argumentsJson).toMap();
    }

    /**
     * Turns a tool result into text for the model. A result the server marks as an error is
     * returned (not thrown), as MCP intends, so the model can see it and try something else.
     */
    static ToolResult toResult(McpSchema.CallToolResult result) {
        List<String> parts = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                parts.add(describe(content));
            }
        }
        if (parts.isEmpty() && result.structuredContent() != null) {
            parts.add(String.valueOf(JSONObject.wrap(result.structuredContent())));
        }
        String text = String.join("\n\n", parts);
        boolean isError = Boolean.TRUE.equals(result.isError());
        if (isError) text = "MCP tool error: " + (text.isEmpty() ? "(no details)" : text);
        return new ToolResult(text, Map.of("isError", isError));
    }

    private static String describe(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent text) {
            return text.text();
        }
        if (content instanceof McpSchema.ImageContent image) {
            return "[image: " + image.mimeType() + ", " + base64Bytes(image.data()) + " bytes]";
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            McpSchema.ResourceContents resource = embedded.resource();
            if (resource instanceof McpSchema.TextResourceContents textResource) {
                return "[resource " + textResource.uri() + "]\n" + textResource.text();
            }
            return "[binary resource " + resource.uri() + " (" + resource.mimeType() + ")]";
        }
        if (content instanceof McpSchema.ResourceLink link) {
            return "[resource link: " + link.name() + " " + link.uri() + "]";
        }
        return "[" + content.type() + " content]";
    }

    private static long base64Bytes(String data) {
        return data == null ? 0 : data.length() * 3L / 4;
    }
}
