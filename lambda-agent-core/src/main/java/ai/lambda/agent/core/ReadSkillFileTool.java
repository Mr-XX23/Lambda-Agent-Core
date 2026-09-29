package ai.lambda.agent.core;

import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Reads a file inside a skill's folder (a template, reference doc or example). Paths are
 * relative to the skill folder and cannot leave it, including through symbolic links.
 */
final class ReadSkillFileTool implements AgentTool {

    private static final long MAX_FILE_BYTES = 1024 * 1024;

    private final Skills skills;

    ReadSkillFileTool(Skills skills) {
        this.skills = skills;
    }

    @Override
    public String getName() { return "read_skill_file"; }

    @Override
    public String getDescription() {
        return "Reads a file that belongs to a skill, such as a template or reference document "
                + "mentioned in the skill's instructions.";
    }

    @Override
    public String getJsonSchema() {
        return """
               {
                 "type": "object",
                 "properties": {
                   "skill": { "type": "string", "description": "The skill's name" },
                   "path": { "type": "string", "description": "Path of the file, relative to the skill's folder" }
                 },
                 "required": ["skill", "path"]
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
            JSONObject args = new JSONObject(argumentsJson);
            if (args.optString("skill", "").isBlank()) throw new IllegalArgumentException("skill is required");
            if (args.optString("path", "").isBlank()) throw new IllegalArgumentException("path is required");
        };
    }

    @Override
    public ToolResult execute(ToolInvocationContext context) throws Exception {
        JSONObject args = new JSONObject(context.getArgumentsJson());
        Skill skill = skills.require(args.getString("skill"));
        String requested = args.getString("path");

        Path root = skill.directory();
        Path candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("Path is outside the skill folder: " + requested);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("No such file in skill '" + skill.name() + "': " + requested);
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root.toRealPath())) {
            throw new SecurityException("Path resolves outside the skill folder: " + requested);
        }
        if (Files.size(real) > MAX_FILE_BYTES) {
            throw new SecurityException("File exceeds the 1 MiB limit: " + requested);
        }
        return ToolResult.of(Files.readString(real));
    }
}
