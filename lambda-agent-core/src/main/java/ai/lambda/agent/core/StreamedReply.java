package ai.lambda.agent.core;

import java.util.function.Consumer;

/**
 * Passes one model call's streamed text to listeners across retries, without showing any of it
 * twice. A retry usually starts the same answer again: while it repeats what listeners already
 * have, nothing is passed on, and only the new text after that is. If the retry says something
 * else, listeners are told to drop what they have ({@code restart}) and get the new text from
 * its start.
 */
final class StreamedReply {

    private final Consumer<String> deliver;
    private final Runnable restart;
    private final StringBuilder delivered = new StringBuilder();
    private StringBuilder attempt = new StringBuilder();
    private boolean diverged;

    StreamedReply(Consumer<String> deliver, Runnable restart) {
        this.deliver = deliver;
        this.restart = restart;
    }

    /** Call before each attempt, including the first. */
    void startAttempt() {
        attempt = new StringBuilder();
        diverged = false;
    }

    /** A piece of the current attempt's text. */
    void accept(String delta) {
        int position = attempt.length();
        attempt.append(delta);
        if (diverged) {
            send(delta);
            return;
        }
        // The part of this piece that listeners may already have.
        int overlap = Math.min(delta.length(), Math.max(0, delivered.length() - position));
        for (int i = 0; i < overlap; i++) {
            if (delivered.charAt(position + i) != delta.charAt(i)) {
                startOver();
                return;
            }
        }
        if (overlap < delta.length()) send(delta.substring(overlap));
    }

    /** Call when an attempt succeeded. */
    void finish() {
        // The final answer is shorter than what listeners were shown (a retry that ended early).
        if (!diverged && attempt.length() < delivered.length()) startOver();
    }

    private void startOver() {
        diverged = true;
        restart.run();
        delivered.setLength(0);
        if (!attempt.isEmpty()) send(attempt.toString());
    }

    private void send(String text) {
        delivered.append(text);
        deliver.accept(text);
    }
}
