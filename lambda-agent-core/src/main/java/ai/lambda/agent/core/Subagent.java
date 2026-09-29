package ai.lambda.agent.core;

import ai.lambda.ai.core.ModelClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A specialist agent the main agent can hand tasks to with the {@code invoke_subagent} tool.
 * A subagent starts every task with a clean slate: it sees its own instructions and the task
 * text, not the main agent's conversation.
 *
 * <pre>
 * var reviewer = new Subagent("code-reviewer",
 *         "Reviews Java code for bugs and style problems. Give it file paths to review.",
 *         "You are a careful code reviewer. Report problems as a numbered list...",
 *         List.of("read_file"));
 * </pre>
 *
 * It can also be defined in a Markdown file (see {@link Subagents#load(Path)}):
 *
 * <pre>
 * ---
 * name: code-reviewer
 * description: Reviews Java code for bugs and style problems. Give it file paths to review.
 * tools: [read_file]
 * model: pro
 * ---
 * You are a careful code reviewer. ...
 * </pre>
 *
 * @param name         lowercase letters, digits, hyphens and underscores; at most 64 characters
 * @param description  what the subagent is good at; the main agent uses it to decide when to delegate
 * @param instructions the subagent's system prompt
 * @param tools        names of the main agent's tools this subagent may use (none if empty)
 * @param model        the model to use, or null to use the main agent's model
 */
public record Subagent(String name, String description, String instructions, List<String> tools, ModelClient model) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+([-_][a-z0-9]+)*");

    public Subagent {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(instructions, "instructions must not be null");
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (name.length() > 64 || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Subagent name '" + name
                    + "' must be at most 64 lowercase letters, digits, hyphens and underscores");
        }
        if (name.equals(Subagents.SELF)) {
            throw new IllegalArgumentException("'" + Subagents.SELF + "' is reserved for self-cloning");
        }
        if (description.isBlank()) throw new IllegalArgumentException("Subagent '" + name + "' needs a description");
        if (instructions.isBlank()) throw new IllegalArgumentException("Subagent '" + name + "' needs instructions");
    }

    public Subagent(String name, String description, String instructions, List<String> tools) {
        this(name, description, instructions, tools, null);
    }

    public Subagent withModel(ModelClient model) {
        return new Subagent(name, description, instructions, tools, model);
    }

    /**
     * Reads a subagent definition file. {@code model} may be omitted or {@code inherit} (use the
     * main agent's model), or a key of {@code models}.
     */
    static Subagent load(Path file, Map<String, ModelClient> models) {
        try {
            Frontmatter frontmatter = Frontmatter.parse(Files.readString(file));
            String name = frontmatter.get("name");
            String description = frontmatter.get("description");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("frontmatter needs a 'name'");
            if (description == null) throw new IllegalArgumentException("frontmatter needs a 'description'");

            ModelClient model = null;
            String modelKey = frontmatter.get("model");
            if (modelKey != null && !modelKey.isBlank() && !modelKey.strip().equals("inherit")) {
                model = models.get(modelKey.strip());
                if (model == null) {
                    throw new IllegalArgumentException("unknown model '" + modelKey.strip()
                            + "'; pass it to Subagents.load(dir, models). Known models: " + models.keySet());
                }
            }
            List<String> tools = frontmatter.list("tools");
            return new Subagent(name.strip(), description.strip(), frontmatter.body(), tools, model);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }
}
