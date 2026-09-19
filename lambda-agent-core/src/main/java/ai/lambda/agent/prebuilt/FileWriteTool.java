package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.*;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public final class FileWriteTool implements AgentTool {
    private final Path root;

    public FileWriteTool(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override public String getName() { return "write_file"; }
    @Override public String getDescription() { return "Writes bounded text to a file beneath the configured root."; }
    @Override public String getJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},"
                + "\"required\":[\"filePath\",\"content\"]}";
    }
    @Override public ToolPolicy getPolicy() {
        return new ToolPolicy(true, Duration.ofSeconds(10), 4096, Set.of(ToolCapability.WRITE));
    }
    @Override public TypedToolInput<?> getTypedInputSchema() {
        return json -> {
            JSONObject object = new JSONObject(json);
            String path = object.getString("filePath");
            String content = object.getString("content");
            if (path.isBlank() || content.length() > 1024 * 1024) {
                throw new IllegalArgumentException("filePath must be non-blank and content must be <= 1 MiB");
            }
            return object;
        };
    }
    @Override public ToolResult execute(ToolInvocationContext context) throws Exception {
        JSONObject args = new JSONObject(context.getArgumentsJson());
        Path path = root.resolve(args.getString("filePath")).normalize();
        if (!path.startsWith(root)) throw new SecurityException("File path is outside the configured root");
        Files.createDirectories(path.getParent() == null ? root : path.getParent());
        Files.writeString(path, args.getString("content"));
        return ToolResult.of("Wrote " + args.getString("filePath"));
    }
}
