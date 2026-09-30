package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentSession;
import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolInvocationContext;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrebuiltToolsTest {

    @TempDir
    Path root;

    @AfterEach
    void removeLink() throws IOException {
        Files.deleteIfExists(root.resolve("link")); // removes the link only, so the temp folder can be cleaned up
    }

    private static String run(AgentTool tool, JSONObject arguments) throws Exception {
        return tool.execute(new ToolInvocationContext("call-1", arguments.toString(), new AgentSession("s"))).getContent();
    }

    private static JSONObject file(String path) {
        return new JSONObject().put("filePath", path);
    }

    /** A link inside the root pointing outside it; skips the test where links cannot be created. */
    private Path linkToOutside(Path outside) {
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Windows needs a privilege for symbolic links, but not for directory junctions.
            boolean junction = false;
            if (System.getProperty("os.name").startsWith("Windows")) {
                try {
                    junction = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                            .redirectErrorStream(true).start().waitFor() == 0;
                } catch (IOException | InterruptedException ignored) {
                    // falls through to skipping the test
                }
            }
            assumeTrue(junction, "links are not available here");
        }
        return link;
    }

    // --- FileReadTool

    @Test
    void readsFilesUnderTheRoot() throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/notes.txt"), "remember the milk");

        assertEquals("remember the milk", run(new FileReadTool(root), file("docs/notes.txt")));
    }

    @Test
    void readRefusesPathsOutsideTheRoot(@TempDir Path elsewhere) throws Exception {
        Path secret = Files.writeString(elsewhere.resolve("secret.txt"), "private");
        FileReadTool tool = new FileReadTool(root);

        assertThrows(SecurityException.class, () -> run(tool, file("../" + elsewhere.getFileName() + "/secret.txt")));
        assertThrows(SecurityException.class, () -> run(tool, file(secret.toString())));
    }

    @Test
    void readRefusesALinkThatLeavesTheRoot(@TempDir Path elsewhere) throws Exception {
        Files.writeString(elsewhere.resolve("secret.txt"), "private");
        linkToOutside(elsewhere);

        assertThrows(SecurityException.class, () -> run(new FileReadTool(root), file("link/secret.txt")));
    }

    @Test
    void readReportsMissingAndOversizedFilesAndBlankPaths() throws Exception {
        FileReadTool tool = new FileReadTool(root);
        Exception missing = assertThrows(Exception.class, () -> run(tool, file("nope.txt")));
        assertTrue(missing.getMessage().contains("does not exist"));

        Files.write(root.resolve("big.bin"), new byte[1024 * 1024 + 1]);
        assertThrows(SecurityException.class, () -> run(tool, file("big.bin")));

        assertThrows(IllegalArgumentException.class, () -> tool.getArgumentValidator().validate("{\"filePath\":\" \"}"));
        assertDoesNotThrow(() -> tool.getArgumentValidator().validate("{\"filePath\":\"a.txt\"}"));
    }

    // --- FileWriteTool

    @Test
    void writesFilesAndCreatesFolders() throws Exception {
        FileWriteTool tool = new FileWriteTool(root);

        assertEquals("Wrote out/report.md", run(tool, file("out/report.md").put("content", "# Done")));
        assertEquals("# Done", Files.readString(root.resolve("out/report.md")));

        run(tool, file("out/report.md").put("content", "replaced"));
        assertEquals("replaced", Files.readString(root.resolve("out/report.md")));
    }

    @Test
    void writeRefusesPathsOutsideTheRoot(@TempDir Path elsewhere) {
        FileWriteTool tool = new FileWriteTool(root);
        Path target = elsewhere.resolve("planted.txt");

        assertThrows(SecurityException.class,
                () -> run(tool, file("../" + elsewhere.getFileName() + "/planted.txt").put("content", "x")));
        assertThrows(SecurityException.class, () -> run(tool, file(target.toString()).put("content", "x")));
        assertFalse(Files.exists(target));
    }

    @Test
    void writeRefusesALinkThatLeavesTheRoot(@TempDir Path elsewhere) {
        linkToOutside(elsewhere);

        assertThrows(SecurityException.class,
                () -> run(new FileWriteTool(root), file("link/planted.txt").put("content", "x")));
        assertFalse(Files.exists(elsewhere.resolve("planted.txt")));
    }

    @Test
    void writeNeedsApprovalAndValidatesInput() {
        FileWriteTool tool = new FileWriteTool(root);
        assertTrue(tool.getPolicy().requiresApproval());
        assertEquals(Set.of(ToolCapability.WRITE), tool.getPolicy().capabilities());

        assertThrows(IllegalArgumentException.class,
                () -> tool.getTypedInputSchema().parse(file(" ").put("content", "x").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> tool.getTypedInputSchema().parse(file("a.txt").put("content", "x".repeat(1024 * 1024 + 1)).toString()));
        assertThrows(RuntimeException.class, () -> tool.getTypedInputSchema().parse("{\"filePath\":\"a.txt\"}"));
        assertDoesNotThrow(() -> tool.getTypedInputSchema().parse(file("a.txt").put("content", "ok").toString()));
    }

    // --- ProcessTool

    @Test
    void runsOnlyAllowlistedCommands() throws Exception {
        ProcessTool tool = new ProcessTool(Set.of("hostname"));
        assertTrue(tool.getPolicy().requiresApproval());
        assertEquals(Set.of(ToolCapability.PROCESS), tool.getPolicy().capabilities());

        assertEquals("hostname", tool.getTypedInputSchema().parse("{\"command\":\"hostname\"}"));
        assertThrows(SecurityException.class, () -> tool.getTypedInputSchema().parse("{\"command\":\"rm\"}"));
        assertThrows(SecurityException.class,
                () -> tool.getTypedInputSchema().parse("{\"command\":\"hostname && whoami\"}"));

        assertFalse(run(tool, new JSONObject().put("command", "hostname")).isBlank());
    }

    @Test
    void processToolItselfRefusesCommandsNotOnTheList() {
        // Even when called without the typed-input check, only listed commands may start.
        ProcessTool tool = new ProcessTool(Set.of("hostname"));
        assertThrows(SecurityException.class, () -> run(tool, new JSONObject().put("command", "whoami")));
    }

    // --- NetworkFetchTool

    @Test
    void fetchAllowsOnlyHttpsToListedHosts() {
        NetworkFetchTool tool = new NetworkFetchTool(Set.of("api.example.com"));
        assertTrue(tool.getPolicy().requiresApproval());
        assertEquals(Set.of(ToolCapability.NETWORK), tool.getPolicy().capabilities());

        assertDoesNotThrow(() -> tool.getTypedInputSchema().parse("{\"url\":\"https://api.example.com/v1/items\"}"));
        for (String url : new String[]{
                "http://api.example.com/v1",                 // not https
                "https://evil.example.org/",                 // host not listed
                "https://api.example.com@evil.example.org/", // listed name is only the user part
                "https://api.example.com.evil.org/",         // listed name is only a prefix
                "file:///etc/passwd",
                "https:///no-host"}) {
            assertThrows(SecurityException.class,
                    () -> tool.getTypedInputSchema().parse(new JSONObject().put("url", url).toString()), url);
        }
    }

    @Test
    void fetchItselfRefusesUrlsNotOnTheList() {
        NetworkFetchTool tool = new NetworkFetchTool(Set.of("api.example.com"));
        assertThrows(SecurityException.class, () -> run(tool, new JSONObject().put("url", "https://evil.example.org/")));
    }
}
