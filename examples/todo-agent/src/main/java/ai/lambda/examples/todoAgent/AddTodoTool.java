package ai.lambda.examples.todoAgent;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolResult;
import ai.lambda.agent.core.AgentSession;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool that adds a new todo item to the session's metadata.
 */
public final class AddTodoTool implements AgentTool {

    @Override
    public String getName() {
        return "add_todo";
    }

    @Override
    public String getDescription() {
        return "Adds a new todo item to the list. Requires a 'task' parameter with the todo description.";
    }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "task": {
                     "type": "string",
                     "description": "The todo item to add"
                   }
                 },
                 "required": ["task"]
               }
               """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInvocationContext context) {
        JSONObject args = new JSONObject(context.getArgumentsJson());
        String task = args.optString("task", "").trim();

        if (task.isEmpty()) {
            return ToolResult.of("Error: 'task' must not be empty.");
        }

        AgentSession session = context.getSession();
        Map<String, Object> metadata = session.getMetadata();

        List<String> todos = (List<String>) metadata.get("todos");
        if (todos == null) {
            todos = new ArrayList<>();
            metadata.put("todos", todos);
        }
        todos.add(task);

        return ToolResult.of("Added todo: \"" + task + "\". Total todos: " + todos.size());
    }
}
