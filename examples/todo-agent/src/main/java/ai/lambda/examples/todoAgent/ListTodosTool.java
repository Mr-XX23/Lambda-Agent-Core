package ai.lambda.examples.todoAgent;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolResult;
import ai.lambda.agent.core.AgentSession;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A tool that lists all current todo items stored in the session metadata.
 */
public final class ListTodosTool implements AgentTool {

    @Override
    public String getName() {
        return "list_todos";
    }

    @Override
    public String getDescription() {
        return "Lists all current todo items.";
    }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {},
                 "required": []
               }
               """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInvocationContext context) {
        AgentSession session = context.getSession();
        Map<String, Object> metadata = session.getMetadata();

        List<String> todos = (List<String>) metadata.get("todos");

        if (todos == null || todos.isEmpty()) {
            return ToolResult.of("No todos found. Your list is currently empty.");
        }

        String formatted = IntStream.range(0, todos.size())
                .mapToObj(i -> (i + 1) + ". " + todos.get(i))
                .collect(Collectors.joining("\n"));

        return ToolResult.of("Current todos:\n" + formatted);
    }
}
