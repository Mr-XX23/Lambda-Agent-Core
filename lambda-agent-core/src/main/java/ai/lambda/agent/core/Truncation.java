package ai.lambda.agent.core;

/**
 * Shortens long text by cutting out the middle. The start and end of tool output
 * (headers, final lines, error summaries) are usually the most useful parts.
 */
final class Truncation {

    private Truncation() {
    }

    /**
     * Keeps about the first two thirds and last third of {@code maxChars} characters of
     * {@code text}, with a note in between saying how much was cut, so the model knows the
     * output is incomplete. The note itself is not counted in {@code maxChars}.
     */
    static String keepHeadAndTail(String text, int maxChars) {
        if (text.length() <= maxChars) return text;

        int keep = Math.max(0, maxChars);
        int head = keep * 2 / 3;
        int tail = keep - head;
        // Don't split a surrogate pair (e.g. an emoji) in half.
        if (head > 0 && Character.isHighSurrogate(text.charAt(head - 1))) head--;
        if (tail > 0 && Character.isLowSurrogate(text.charAt(text.length() - tail))) tail--;

        int omitted = text.length() - head - tail;
        return text.substring(0, head)
                + "\n\n[... " + omitted + " characters truncated ...]\n\n"
                + text.substring(text.length() - tail);
    }
}
