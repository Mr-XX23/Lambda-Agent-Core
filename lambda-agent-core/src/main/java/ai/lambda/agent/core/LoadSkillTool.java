package ai.lambda.agent.core;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Returns a skill's full instructions, plus the list of other files in its folder.
 */
final class LoadSkillTool implements AgentTool {

    private static final int MAX_LISTED_FILES = 50;
    private static final int MAX_DEPTH = 4;

    private final Skills skills;

    LoadSkillTool(Skills skills) {
        this.skills = skills;
    }

    @Override
    public String getName() { return "load_skill"; }

    @Override
    public boolean isParallelSafe() { return true; }

    @Override
    public String getDescription() {
        return "Loads the full instructions of a skill listed in the system prompt. "
                + "Call it before starting a task that matches the skill's description.";
    }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "name": { "type": "string", "description": "The skill's name, as listed in the system prompt" }
                 },
                 "required": ["name"]
               }
               """;
    }

    @Override
    public ToolPolicy getPolicy() {
        return new ToolPolicy(false, Duration.ofSeconds(10), 128 * 1024, Set.of(ToolCapability.READ));
    }

    @Override
    public ToolArgumentValidator getArgumentValidator() {
        return argumentsJson -> {
            if (new JSONObject(argumentsJson).optString("name", "").isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
        };
    }

    @Override
    public ToolResult execute(ToolInvocationContext context) throws IOException {
        Skill skill = skills.require(new JSONObject(context.getArgumentsJson()).getString("name"));

        StringBuilder out = new StringBuilder()
                .append("# Skill: ").append(skill.name()).append("\n\n")
                .append(skill.instructions());

        List<String> files = otherFiles(skill.directory());
        if (!files.isEmpty()) {
            out.append("\n\n---\nOther files in this skill (open them with read_skill_file):\n");
            files.stream().limit(MAX_LISTED_FILES).forEach(f -> out.append("- ").append(f).append('\n'));
            if (files.size() > MAX_LISTED_FILES) {
                out.append("- ... and ").append(files.size() - MAX_LISTED_FILES).append(" more\n");
            }
        }
        return ToolResult.of(out.toString());
    }

    private static List<String> otherFiles(Path dir) throws IOException {
        Path skillFile = dir.resolve(Skill.FILE_NAME);
        try (Stream<Path> paths = Files.walk(dir, MAX_DEPTH)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(skillFile))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
