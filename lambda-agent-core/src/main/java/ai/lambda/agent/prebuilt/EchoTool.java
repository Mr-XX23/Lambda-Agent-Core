package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolResult;

import org.json.JSONObject;

public final class EchoTool implements AgentTool {

    @Override
    public String getName() {
        return "echo";
    }

    @Override
    public String getDescription() {
        return "Echoes back the given text.";
    }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "text": {
                     "type": "string",
                     "description": "Text to echo back"
                   }
                 },
                 "required": ["text"]
               }
               """;
    }

    @Override
    public ToolResult execute(ToolInvocationContext context) {
        JSONObject args = new JSONObject(context.getArgumentsJson());
        String text = args.optString("text", "");
        String echoed = "[echo] " + text;
        return ToolResult.of(echoed);
    }
}
