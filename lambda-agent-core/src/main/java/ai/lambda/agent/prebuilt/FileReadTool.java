package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolResult;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FileReadTool implements AgentTool {

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

        Path path = Path.of(filePathStr);
        if (!Files.exists(path)) {
            throw new Exception("File does not exist: " + filePathStr);
        }

        String content = Files.readString(path);
        return ToolResult.of(content);
    }
}
