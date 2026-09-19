package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolResult;
import ai.lambda.agent.core.ToolArgumentValidator;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class FileReadTool implements AgentTool {
    private final Path root;

    public FileReadTool() {
        this(Path.of("."));
    }

    public FileReadTool(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
    }

    @Override
    public String getName() { return "read_file"; }

    @Override
    public String getDescription() { return "Reads the contents of a text file from the local file system."; }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "filePath": { "type": "string", "description": "Absolute or relative path to the file" }
                 },
                 "required": ["filePath"]
               }
               """;
    }

    @Override
    public ToolResult execute(ToolInvocationContext context) throws Exception {
        JSONObject args = new JSONObject(context.getArgumentsJson());
        String filePathStr = args.getString("filePath");

        Path path = root.resolve(filePathStr).normalize();
        if (!path.startsWith(root)) {
            throw new SecurityException("File path is outside the configured root");
        }
        if (!Files.exists(path)) {
            throw new Exception("File does not exist: " + filePathStr);
        }

        String content = Files.readString(path);
        return ToolResult.of(content);
    }

    @Override
    public ToolArgumentValidator getArgumentValidator() {
        return argumentsJson -> {
            JSONObject args = new JSONObject(argumentsJson);
            if (args.optString("filePath", "").isBlank()) {
                throw new IllegalArgumentException("filePath is required");
            }
        };
    }
}
