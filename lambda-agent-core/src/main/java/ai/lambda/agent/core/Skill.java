package ai.lambda.agent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A skill: a folder of instructions (and optional extra files) for one kind of task.
 * The folder holds a {@code SKILL.md} file that starts with YAML frontmatter:
 *
 * <pre>
 * ---
 * name: release-notes
 * description: Writes release notes from a list of merged changes. Use when asked for a changelog.
 * ---
 * # Release notes
 * Step-by-step instructions for the agent...
 * </pre>
 *
 * Only {@code name} and {@code description} are read from the frontmatter; other keys are ignored.
 *
 * @param name         lowercase letters, digits and hyphens, at most 64 characters
 * @param description  what the skill does and when to use it; shown to the model up front
 * @param directory    the skill's folder (absolute, real path)
 * @param instructions the body of SKILL.md, sent to the model when it loads the skill
 */
public record Skill(String name, String description, Path directory, String instructions) {

    public static final String FILE_NAME = "SKILL.md";

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    public Skill {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        instructions = instructions == null ? "" : instructions;
        if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Skill name '" + name
                    + "' must be at most 64 lowercase letters, digits and hyphens");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("Skill '" + name + "' needs a description");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Skill '" + name + "' description exceeds 1024 characters");
        }
    }

    /** Reads {@code directory/SKILL.md}. */
    public static Skill load(Path directory) {
        Path dir;
        String content;
        try {
            dir = directory.toRealPath();
            content = Files.readString(dir.resolve(FILE_NAME));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + directory.resolve(FILE_NAME), e);
        }
        try {
            return parse(dir, content);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(dir.resolve(FILE_NAME) + ": " + e.getMessage(), e);
        }
    }

    static Skill parse(Path directory, String content) {
        Frontmatter frontmatter = Frontmatter.parse(content);
        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("frontmatter needs a 'name'");
        if (description == null) throw new IllegalArgumentException("frontmatter needs a 'description'");
        return new Skill(name.strip(), description.strip(), directory, frontmatter.body());
    }
}
