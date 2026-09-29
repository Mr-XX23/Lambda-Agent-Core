package ai.lambda.agent.mcp;

import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpConversionsTest {

    @Test
    void toolNamesUseOnlyCharactersEveryProviderAccepts() {
        assertEquals("github__create_issue", McpConversions.toolName("github", "create_issue", true));
        assertEquals("my_server__search_repos_v2", McpConversions.toolName("my-server", "search.repos-v2", true));
        assertEquals("search_repos", McpConversions.toolName("x", "search-repos", false));
    }

    @Test
    void longToolNamesAreCutToSixtyFourButStayDistinct() {
        String a = McpConversions.toolName("server", "a".repeat(80) + "_one", true);
        String b = McpConversions.toolName("server", "a".repeat(80) + "_two", true);

        assertEquals(64, a.length());
        assertEquals(64, b.length());
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-Za-z0-9_]+"), a);
    }

    @Test
    void capabilitiesFollowHintsAndDefaultToTheRiskiestKind() {
        assertEquals(Set.of(ToolCapability.WRITE, ToolCapability.NETWORK, ToolCapability.SENSITIVE),
                McpConversions.capabilities(null));
        assertEquals(Set.of(ToolCapability.READ, ToolCapability.NETWORK),
                McpConversions.capabilities(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build()));
        assertEquals(Set.of(ToolCapability.WRITE),
                McpConversions.capabilities(McpSchema.ToolAnnotations.builder()
                        .destructiveHint(false).openWorldHint(false).build()));
    }

    @Test
    void missingSchemaBecomesAnEmptyObjectSchema() {
        assertEquals("{\"type\":\"object\",\"properties\":{}}", McpConversions.schemaJson(null));
    }

    @Test
    void everyContentTypeBecomesReadableText() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .addTextContent("plain text")
                .addContent(new McpSchema.ImageContent(null, "AAAA", "image/png"))
                .addContent(new McpSchema.EmbeddedResource(null,
                        new McpSchema.TextResourceContents("file:///notes.md", "text/markdown", "# Notes")))
                .build();

        ToolResult out = McpConversions.toResult(result);

        assertEquals("plain text\n\n[image: image/png, 3 bytes]\n\n[resource file:///notes.md]\n# Notes",
                out.getContent());
        assertEquals(false, out.getDetails().get("isError"));
    }

    @Test
    void emptyErrorStillSaysItFailed() {
        ToolResult out = McpConversions.toResult(McpSchema.CallToolResult.builder().isError(true).build());

        assertEquals("MCP tool error: (no details)", out.getContent());
    }

    @Test
    void argumentsJsonBecomesAMap() {
        assertEquals(Map.of("q", "java", "tags", List.of("a", "b")),
                McpConversions.arguments("{\"q\":\"java\",\"tags\":[\"a\",\"b\"]}"));
        assertEquals(Map.of(), McpConversions.arguments(""));
    }
}
