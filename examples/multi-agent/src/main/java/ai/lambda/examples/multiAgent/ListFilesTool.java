package ai.lambda.examples.multiAgent;

import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolCapability;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.ToolPolicy;
import ai.lambda.agent.core.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lists the files under a root folder, so the lead agent can see what there is to work on. */
final class ListFilesTool implements AgentTool {

    private final Path root;

    ListFilesTool(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String getName() { return "list_files"; }

    @Override
    public String getDescription() { return "Lists all files in the workspace, as paths relative to it."; }

    @Override
    public String getJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public ToolPolicy getPolicy() {
        return new ToolPolicy(false, Duration.ofSeconds(10), 64 * 1024, Set.of(ToolCapability.READ));
    }

    @Override
    public ToolResult execute(ToolInvocationContext context) throws Exception {
        try (Stream<Path> paths = Files.walk(root, 8)) {
            return ToolResult.of(paths.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.joining("\n")));
        }
    }
}
