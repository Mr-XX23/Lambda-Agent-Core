package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.*;
import org.json.JSONObject;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ProcessTool implements AgentTool {
    private final Set<String> allowedCommands;

    public ProcessTool(Set<String> allowedCommands) {
        this.allowedCommands = Set.copyOf(allowedCommands);
    }
    @Override public String getName() { return "run_process"; }
    @Override public String getDescription() { return "Runs an explicitly allowlisted process with a bounded timeout."; }
    @Override public String getJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}";
    }
    @Override public ToolPolicy getPolicy() {
        return new ToolPolicy(true, Duration.ofSeconds(30), 16384, Set.of(ToolCapability.PROCESS));
    }
    @Override public TypedToolInput<?> getTypedInputSchema() {
        return json -> {
            String command = new JSONObject(json).getString("command");
            if (!allowedCommands.contains(command)) throw new SecurityException("Command is not allowlisted");
            return command;
        };
    }
    @Override public ToolResult execute(ToolInvocationContext context) throws Exception {
        String command = new JSONObject(context.getArgumentsJson()).getString("command");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Process timed out");
        }
        return ToolResult.of(new String(process.getInputStream().readAllBytes()));
    }
}
