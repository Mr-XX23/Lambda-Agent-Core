package ai.lambda.agent.core;

import ai.lambda.ai.core.ModelClient;

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
 * The subagents an agent can delegate to, and the limits on delegation. Adding them with
 * {@link AgentConfig#withSubagents(Subagents)} gives the agent an {@code invoke_subagent} tool
 * that runs one or more tasks, in parallel, each in a fresh subagent.
 *
 * <ul>
 *   <li><b>Static subagents</b> ({@link Subagent}) are specialists with their own instructions,
 *       a subset of the main agent's tools, and optionally their own model.</li>
 *   <li><b>Self-cloning</b> ({@link #withSelfCloning(boolean)}) lets the agent start copies of
 *       itself, named {@code self}, with its instructions and tools. Use it to break a large task
 *       into independent parts.</li>
 * </ul>
 *
 * Subagents always inherit the main agent's permission policy, approval handler, retry policy,
 * context strategy and limits. Only self-clones can delegate further, up to {@link #maxDepth()}
 * levels below the main agent.
 *
 * <pre>
 * Subagents subagents = Subagents.load(Path.of("agents")).withSelfCloning(true);
 * AgentConfig config = new AgentConfig(prompt, model, tools, 10).withSubagents(subagents);
 * </pre>
 */
public final class Subagents {

    /** The name the model uses to start a copy of itself. */
    public static final String SELF = "self";

    /** Hard cap on nesting, as in the Antigravity SDK. */
    public static final int MAX_DEPTH_LIMIT = 10;

    private final Map<String, Subagent> byName;
    private final boolean selfCloning;
    private final int maxDepth;
    private final int maxParallel;
    private final int maxTasksPerCall;

    private Subagents(Map<String, Subagent> byName, boolean selfCloning, int maxDepth, int maxParallel,
                      int maxTasksPerCall) {
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH_LIMIT);
        }
        if (maxParallel < 1) throw new IllegalArgumentException("maxParallel must be at least 1");
        if (maxTasksPerCall < 1) throw new IllegalArgumentException("maxTasksPerCall must be at least 1");
        this.byName = byName;
        this.selfCloning = selfCloning;
        this.maxDepth = maxDepth;
        this.maxParallel = maxParallel;
        this.maxTasksPerCall = maxTasksPerCall;
    }

    public static Subagents of(Subagent... subagents) {
        return of(List.of(subagents));
    }

    public static Subagents of(List<Subagent> subagents) {
        Map<String, Subagent> map = new LinkedHashMap<>();
        subagents.stream().sorted(Comparator.comparing(Subagent::name)).forEach(s -> {
            if (map.putIfAbsent(s.name(), s) != null) {
                throw new IllegalArgumentException("Duplicate subagent name '" + s.name() + "'");
            }
        });
        return new Subagents(map, false, 2, 4, 8);
    }

    /** Only self-cloning, no static subagents. */
    public static Subagents selfCloning() {
        return of().withSelfCloning(true);
    }

    /** Loads definitions from {@code dir}; see {@link #load(Path, Map)}. */
    public static Subagents load(Path dir) {
        return load(dir, Map.of());
    }

    /**
     * Loads subagent definitions: every {@code *.md} file directly in {@code dir}, and every
     * {@code agent.md} in a direct subfolder. A definition's {@code model} is looked up in
     * {@code models} (for example {@code Map.of("pro", proClient, "flash", flashClient)}).
     */
    public static Subagents load(Path dir, Map<String, ModelClient> models) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Subagents directory does not exist: " + dir.toAbsolutePath());
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.sorted().forEach(child -> {
                if (Files.isRegularFile(child) && child.getFileName().toString().endsWith(".md")) {
                    files.add(child);
                } else if (Files.isRegularFile(child.resolve("agent.md"))) {
                    files.add(child.resolve("agent.md"));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list subagents in " + dir, e);
        }
        List<Subagent> subagents = new ArrayList<>();
        for (Path file : files) subagents.add(Subagent.load(file, models));
        try {
            return of(subagents);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(dir + ": " + e.getMessage(), e);
        }
    }

    /** Allows the agent to start copies of itself (named {@code self}). Off by default. */
    public Subagents withSelfCloning(boolean enabled) {
        return new Subagents(byName, enabled, maxDepth, maxParallel, maxTasksPerCall);
    }

    /** How many levels of self-clones may exist below the main agent (1 to 10, default 2). */
    public Subagents withMaxDepth(int maxDepth) {
        return new Subagents(byName, selfCloning, maxDepth, maxParallel, maxTasksPerCall);
    }

    /** How many subagents of one {@code invoke_subagent} call run at the same time (default 4). */
    public Subagents withMaxParallel(int maxParallel) {
        return new Subagents(byName, selfCloning, maxDepth, maxParallel, maxTasksPerCall);
    }

    /** How many tasks one {@code invoke_subagent} call may contain (default 8). */
    public Subagents withMaxTasksPerCall(int maxTasksPerCall) {
        return new Subagents(byName, selfCloning, maxDepth, maxParallel, maxTasksPerCall);
    }

    public List<Subagent> all() {
        return List.copyOf(byName.values());
    }

    public Optional<Subagent> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isSelfCloning() {
        return selfCloning;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxParallel() {
        return maxParallel;
    }

    public int maxTasksPerCall() {
        return maxTasksPerCall;
    }

    public boolean isEmpty() {
        return byName.isEmpty() && !selfCloning;
    }

    /** Names the model may pass to {@code invoke_subagent}. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        if (selfCloning) names.add(SELF);
        names.addAll(byName.keySet());
        return names;
    }

    /** The text added to the system prompt of an agent that can delegate. */
    String promptSection() {
        StringBuilder sb = new StringBuilder()
                .append("## Subagents\n\n")
                .append("You can hand work to subagents with `invoke_subagent`. For a large task, break it into ")
                .append("independent subtasks and pass them in one call so they run in parallel; do small or ")
                .append("tightly connected work yourself. A subagent sees only the task text you give it, not ")
                .append("this conversation, so make each task self-contained: include the goal, the relevant ")
                .append("details and file paths, and what its answer should contain. You get each subagent's ")
                .append("final answer back; check and combine them before you reply.\n\n")
                .append("Available subagents:\n");
        if (selfCloning) {
            sb.append("- ").append(SELF).append(": a copy of you, with your instructions and tools\n");
        }
        for (Subagent subagent : byName.values()) {
            sb.append("- ").append(subagent.name()).append(": ").append(subagent.description()).append('\n');
        }
        return sb.toString();
    }
}
