package ai.lambda.agent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A set of {@link Skill}s the agent can use. Skills are loaded in stages to keep the context small:
 * <ol>
 *   <li>Only each skill's name and description go into the system prompt.</li>
 *   <li>When a task matches, the model calls {@code load_skill} to get the full instructions.</li>
 *   <li>If the instructions mention extra files, the model reads them with {@code read_skill_file}.</li>
 * </ol>
 *
 * <pre>
 * Skills skills = Skills.load(Path.of("skills"));
 * AgentConfig config = new AgentConfig(prompt, model, tools, 8).withSkills(skills);
 * </pre>
 */
public final class Skills {

    private final Map<String, Skill> byName;

    private Skills(List<Skill> skills) {
        Map<String, Skill> map = new LinkedHashMap<>();
        skills.stream().sorted(Comparator.comparing(Skill::name)).forEach(skill -> {
            Skill previous = map.putIfAbsent(skill.name(), skill);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate skill name '" + skill.name() + "' in "
                        + previous.directory() + " and " + skill.directory());
            }
        });
        this.byName = map;
    }

    public static Skills of(List<Skill> skills) {
        return new Skills(List.copyOf(skills));
    }

    /**
     * Loads skills from each directory. A directory that holds a {@code SKILL.md} is one skill;
     * otherwise each of its subdirectories that holds a {@code SKILL.md} is a skill.
     */
    public static Skills load(Path... roots) {
        List<Skill> skills = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Skills directory does not exist: " + root.toAbsolutePath());
            }
            if (Files.isRegularFile(root.resolve(Skill.FILE_NAME))) {
                skills.add(Skill.load(root));
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(dir -> Files.isRegularFile(dir.resolve(Skill.FILE_NAME)))
                        .sorted()
                        .forEach(dir -> skills.add(Skill.load(dir)));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list skills in " + root, e);
            }
        }
        return new Skills(skills);
    }

    public List<Skill> all() {
        return List.copyOf(byName.values());
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    Skill require(String name) {
        Skill skill = byName.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Unknown skill '" + name + "'. Available skills: "
                    + String.join(", ", byName.keySet()));
        }
        return skill;
    }

    /** The text added to the system prompt: how to use skills, and one line per skill. */
    public String promptSection() {
        StringBuilder sb = new StringBuilder()
                .append("## Skills\n\n")
                .append("Skills are instructions for specific kinds of tasks. When a task matches a skill ")
                .append("below, call `load_skill` with its name before you start, then follow the ")
                .append("instructions it returns. If they refer to other files in the skill, read them ")
                .append("with `read_skill_file`.\n\n")
                .append("Available skills:\n");
        for (Skill skill : byName.values()) {
            sb.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
        }
        return sb.toString();
    }

    /** The {@code load_skill} and {@code read_skill_file} tools for these skills. */
    public List<AgentTool> tools() {
        return List.of(new LoadSkillTool(this), new ReadSkillFileTool(this));
    }
}
