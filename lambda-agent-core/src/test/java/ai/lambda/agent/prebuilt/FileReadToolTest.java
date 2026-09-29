package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentSession;
import ai.lambda.agent.core.ToolInvocationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileReadToolTest {
    @TempDir
    Path root;

    @Test
    void readsOnlyFilesUnderConfiguredRoot() throws Exception {
        Path allowed = root.resolve("allowed.txt");
        Files.writeString(allowed, "hello");
        FileReadTool tool = new FileReadTool(root);

        assertEquals("hello", tool.execute(new ToolInvocationContext(
                "call", "{" + "\"filePath\":\"allowed.txt\"" + "}", new AgentSession("s"))).getContent());
        assertThrows(SecurityException.class, () -> tool.execute(new ToolInvocationContext(
                "call", "{\"filePath\":\"../outside.txt\"}", new AgentSession("s"))));
    }
}
