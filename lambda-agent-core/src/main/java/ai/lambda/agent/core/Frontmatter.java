package ai.lambda.agent.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The YAML frontmatter at the top of a Markdown file (between two {@code ---} lines), plus the
 * Markdown body after it. Supports the small YAML subset that skill and agent files use:
 * <ul>
 *   <li>{@code key: value}, with optional single or double quotes;</li>
 *   <li>values continued on indented lines, including {@code |} and {@code >} blocks;</li>
 *   <li>lists, inline ({@code tools: [a, b]}) or as indented {@code - item} lines.</li>
 * </ul>
 * Nested maps are not supported; their lines are kept as text on the parent key.
 */
final class Frontmatter {

    private final Map<String, String> scalars;
    private final Map<String, List<String>> lists;
    private final String body;

    private Frontmatter(Map<String, String> scalars, Map<String, List<String>> lists, String body) {
        this.scalars = scalars;
        this.lists = lists;
        this.body = body;
    }

    /** A text value, or null if the key is missing or holds a list. */
    String get(String key) {
        return scalars.get(key);
    }

    /** A list value, or null if the key is missing. A single text value counts as a one-item list. */
    List<String> list(String key) {
        if (lists.containsKey(key)) return lists.get(key);
        String value = scalars.get(key);
        return value == null || value.isBlank() ? null : List.of(value.strip());
    }

    String body() {
        return body;
    }

    static Frontmatter parse(String content) {
        List<String> lines = content.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            throw new IllegalArgumentException("must start with '---' frontmatter");
        }
        int end = 1;
        while (end < lines.size() && !lines.get(end).strip().equals("---")) end++;
        if (end == lines.size()) {
            throw new IllegalArgumentException("frontmatter is missing its closing '---'");
        }

        Map<String, String> scalars = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        String key = null;
        boolean literal = false;
        for (String line : lines.subList(1, end)) {
            if (line.isBlank()) continue;
            String stripped = line.strip();

            if (Character.isWhitespace(line.charAt(0)) && key != null) {
                String current = scalars.get(key);
                boolean listItem = stripped.equals("-") || stripped.startsWith("- ");
                if (listItem && (lists.containsKey(key) || (current != null && current.isEmpty()))) {
                    scalars.remove(key);
                    lists.computeIfAbsent(key, k -> new ArrayList<>()).add(unquote(stripped.substring(1).strip()));
                } else if (current != null) {
                    String joiner = current.isEmpty() ? "" : literal ? "\n" : " ";
                    scalars.put(key, current + joiner + stripped);
                }
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("invalid frontmatter line: " + stripped);
            }
            key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            literal = value.startsWith("|");
            lists.remove(key);
            if (value.startsWith("[") && value.endsWith("]")) {
                scalars.remove(key);
                lists.put(key, splitInline(value.substring(1, value.length() - 1)));
            } else {
                if (value.equals("|") || value.equals("|-") || value.equals(">") || value.equals(">-")) {
                    value = "";
                }
                scalars.put(key, unquote(value));
            }
        }

        String body = String.join("\n", lines.subList(end + 1, lines.size())).strip();
        return new Frontmatter(scalars, lists, body);
    }

    private static List<String> splitInline(String inner) {
        if (inner.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.stream(inner.split(","))
                .map(String::strip)
                .map(Frontmatter::unquote)
                .filter(s -> !s.isEmpty())
                .toList());
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == '"' && last == '"') {
                return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            if (first == '\'' && last == '\'') {
                return value.substring(1, value.length() - 1).replace("''", "'");
            }
        }
        return value;
    }
}
