package ai.lambda.agent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Runs one or more tasks, each in a fresh subagent, in parallel (up to
 * {@link Subagents#maxParallel()} at a time), and returns every subagent's final answer.
 * One task failing does not stop the others.
 */
final class InvokeSubagentTool implements AgentTool {

    static final String NAME = "invoke_subagent";

    private static final String SUBAGENT_NOTE = """


            ## Working as a subagent
            You are a subagent: another agent gave you one task and will only see your final reply. \
            There is no user to answer questions, so work with what you have and state any assumptions. \
            End with a complete answer that stands on its own.""";

    private final AgentConfig rootConfig;
    private final Subagents subagents;
    private final int depth;
    private final List<AgentEventListener> observers;

    /**
     * @param rootConfig the main agent's config; subagents are built from its tools and settings
     * @param depth      how deep the agent that owns this tool is (0 for the main agent)
     * @param observers  the main agent's listeners, which receive subagent events
     */
    InvokeSubagentTool(AgentConfig rootConfig, Subagents subagents, int depth, List<AgentEventListener> observers) {
        this.rootConfig = rootConfig;
        this.subagents = subagents;
        this.depth = depth;
        this.observers = observers;
    }

    @Override
    public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "Hands tasks to subagents and returns their final answers. Pass several independent tasks "
                + "in one call to run them in parallel. Subagents cannot see this conversation, so each "
                + "task must contain everything the subagent needs. Available subagents: "
                + String.join(", ", subagents.names()) + ".";
    }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "tasks": {
                     "type": "array",
                     "description": "Tasks to run in parallel, one subagent each",
                     "items": {
                       "type": "object",
                       "properties": {
                         "agent": { "type": "string", "description": "Name of the subagent to use" },
                         "task": { "type": "string", "description": "Complete, self-contained instructions for the subagent" }
                       },
                       "required": ["agent", "task"]
                     }
                   }
                 },
                 "required": ["tasks"]
               }
               """;
    }

    @Override
    public ToolPolicy getPolicy() {
        // Subagent runs can take as long as a whole agent run. Their own tool calls are checked
        // against the inherited permission policy, so this tool needs no capabilities itself.
        return new ToolPolicy(false, rootConfig.getRunTimeout(), 128 * 1024, Set.of());
    }

    @Override
    public ToolArgumentValidator getArgumentValidator() {
        return argumentsJson -> parseTasks(argumentsJson);
    }

    private record Task(String agent, String text) {
    }

    private record Outcome(String agent, String answer, String error) {
    }

    private List<Task> parseTasks(String argumentsJson) {
        JSONArray array = new JSONObject(argumentsJson).optJSONArray("tasks");
        if (array == null || array.isEmpty()) {
            throw new IllegalArgumentException("tasks must be a non-empty array of {agent, task}");
        }
        if (array.length() > subagents.maxTasksPerCall()) {
            throw new IllegalArgumentException("at most " + subagents.maxTasksPerCall()
                    + " tasks per call; got " + array.length());
        }
        List<String> names = subagents.names();
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            String agent = item == null ? "" : item.optString("agent", "").strip();
            String text = item == null ? "" : item.optString("task", "").strip();
            if (!names.contains(agent)) {
                throw new IllegalArgumentException("task " + (i + 1) + ": unknown subagent '" + agent
                        + "'. Available subagents: " + String.join(", ", names));
            }
            if (text.isEmpty()) {
                throw new IllegalArgumentException("task " + (i + 1) + ": task text is empty");
            }
            tasks.add(new Task(agent, text));
        }
        return tasks;
    }

    @Override
    public ToolResult execute(ToolInvocationContext context) {
        List<Task> tasks = parseTasks(context.getArgumentsJson());
        String parentSession = context.getSession().getId();

        // Stops the subagents when the main run is cancelled, or when only this tool call is given up.
        CancellationToken token = context.getCancellationToken().child();
        Semaphore permits = new Semaphore(subagents.maxParallel());
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                String sessionId = parentSession + ":" + task.agent() + "-" + (i + 1) + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
                futures.add(pool.submit(() -> runOne(task, sessionId, token, permits)));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) outcomes.add(future.get());
            return ToolResult.of(format(outcomes));
        } catch (InterruptedException e) {
            // The main agent gave up on this tool call (timeout or cancellation): stop the subagents.
            token.cancel();
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new CancellationException("Subagents were cancelled");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Subagent task failed unexpectedly", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome runOne(Task task, String sessionId, CancellationToken token, Semaphore permits) {
        int childDepth = depth + 1;
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(task.agent(), null, "cancelled before it started");
        }
        try {
            token.throwIfCancelled();
            for (AgentEventListener l : observers) l.onSubagentStart(task.agent(), task.text(), childDepth);
            Agent child = new Agent(childConfig(task.agent()), new InMemorySessionStore(),
                    childDepth, rootConfig, observers);
            AgentResult result = child.run(sessionId, task.text(), token);
            for (AgentEventListener l : observers) l.onSubagentEnd(task.agent(), childDepth, result);
            return new Outcome(task.agent(), result.getFinalText(), null);
        } catch (Exception e) {
            for (AgentEventListener l : observers) l.onSubagentError(task.agent(), childDepth, e);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Outcome(task.agent(), null, message);
        } finally {
            permits.release();
        }
    }

    private AgentConfig childConfig(String agent) {
        if (agent.equals(Subagents.SELF)) {
            // A copy of the main agent that may delegate further (the Agent adds the tool if depth allows).
            return rootConfig.forSubagent(rootConfig.getSystemPrompt() + SUBAGENT_NOTE,
                    rootConfig.getTools(), rootConfig.getModelClient(), subagents);
        }
        Subagent spec = subagents.find(agent).orElseThrow();
        List<AgentTool> tools = rootConfig.getTools().stream()
                .filter(t -> spec.tools().contains(t.getName()))
                .toList();
        // Specialists do not delegate further.
        return rootConfig.forSubagent(spec.instructions() + SUBAGENT_NOTE, tools,
                spec.model() != null ? spec.model() : rootConfig.getModelClient(), null);
    }

    private static String format(List<Outcome> outcomes) {
        long failed = outcomes.stream().filter(o -> o.error() != null).count();
        StringBuilder sb = new StringBuilder()
                .append(outcomes.size()).append(outcomes.size() == 1 ? " subagent task" : " subagent tasks")
                .append(" finished").append(failed > 0 ? " (" + failed + " failed)" : "").append(".\n");
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome o = outcomes.get(i);
            sb.append("\n### Task ").append(i + 1).append(": ").append(o.agent()).append('\n');
            sb.append(o.error() != null ? "FAILED: " + o.error() : o.answer()).append('\n');
        }
        return sb.toString();
    }
}
