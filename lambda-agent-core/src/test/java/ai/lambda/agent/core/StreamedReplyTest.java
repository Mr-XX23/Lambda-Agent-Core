package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelCapabilities;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolSchema;
import ai.lambda.ai.core.UnsupportedMediaException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Streamed text across retries of one model call: shown once, or restarted when it changes. */
class StreamedReplyTest {

    /** What a listener saw: text pieces, and "|" where it was told to start over. */
    private final StringBuilder seen = new StringBuilder();
    private final StreamedReply reply = new StreamedReply(seen::append, () -> seen.append('|'));

    private void attempt(String... pieces) {
        reply.startAttempt();
        for (String piece : pieces) reply.accept(piece);
    }

    @Test
    void aSingleAttemptPassesEverythingThrough() {
        attempt("Hel", "lo", "!");
        reply.finish();
        assertEquals("Hello!", seen.toString());
    }

    @Test
    void aRetryThatRepeatsTheTextOnlyAddsWhatIsNew() {
        attempt("The ans", "wer is");      // fails here
        attempt("The", " answer is", " 4."); // retried
        reply.finish();
        assertEquals("The answer is 4.", seen.toString(), "no text shown twice");
    }

    @Test
    void aRetryThatSaysSomethingElseStartsOver() {
        attempt("The answer", " is");
        attempt("The result", " is 4.");
        reply.finish();
        assertEquals("The answer is|The result is 4.", seen.toString());
    }

    @Test
    void aRetryThatEndsSoonerThanWhatWasShownStartsOver() {
        attempt("Four. Also note");
        attempt("Four.");
        reply.finish();
        assertEquals("Four. Also note|Four.", seen.toString());
    }

    @Test
    void severalRetriesKeepTrackOfWhatWasShown() {
        attempt("A");
        attempt("A", "B");
        attempt("AB", "C");
        reply.finish();
        assertEquals("ABC", seen.toString());
    }

    /** A model that fails once in the middle of its answer, then answers in full. */
    private static ModelClient failsMidStream(String first, String second, AtomicInteger calls) {
        return new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                throw new UnsupportedOperationException();
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
                if (calls.incrementAndGet() == 1) {
                    onDelta.accept(first);
                    throw new IllegalStateException("connection reset");
                }
                onDelta.accept(second.substring(0, 3));
                onDelta.accept(second.substring(3));
                return new ChatResponse(new Message(Role.ASSISTANT, second, null), List.of());
            }
        };
    }

    private static List<String> run(ModelClient model, List<Integer> restarts) {
        List<String> deltas = new ArrayList<>();
        AgentConfig config = new AgentConfig("sys", model, List.of(), 3, ToolErrorStrategy.SEND_TO_MODEL,
                Duration.ofMinutes(1), 10_000, RetryPolicy.exponential(2, Duration.ofMillis(1)));
        Agent agent = new Agent(config, new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            @Override
            public void onAssistantDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onAssistantRestart() {
                restarts.add(deltas.size());
            }
        });
        agent.run("s", "hi");
        return deltas;
    }

    @Test
    void theAgentDoesNotRepeatTextWhenItRetriesAStream() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> restarts = new ArrayList<>();

        List<String> deltas = run(failsMidStream("Hello", "Hello there", calls), restarts);

        assertEquals(2, calls.get(), "retried");
        assertEquals("Hello there", String.join("", deltas));
        assertEquals(List.of(), restarts);
    }

    @Test
    void theAgentSignalsARestartWhenTheRetrySaysSomethingElse() {
        List<Integer> restarts = new ArrayList<>();

        List<String> deltas = run(failsMidStream("Hello", "Hi there", new AtomicInteger()), restarts);

        assertEquals(List.of("Hello", "Hi ", "there"), deltas);
        assertEquals(List.of(1), restarts, "told to drop 'Hello' before 'Hi there'");
    }

    @Test
    void errorsThatCannotSucceedOnASecondTryAreNotRetried() {
        for (RuntimeException error : List.of(new UnsupportedMediaException("no video"), new IllegalArgumentException("bad"))) {
            AtomicInteger calls = new AtomicInteger();
            ModelClient model = new ModelClient() {
                public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                    throw new UnsupportedOperationException();
                }

                public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
                    calls.incrementAndGet();
                    throw error;
                }
            };
            AgentConfig config = new AgentConfig("sys", model, List.of(), 3, ToolErrorStrategy.SEND_TO_MODEL,
                    Duration.ofMinutes(1), 10_000, RetryPolicy.exponential(3, Duration.ofMillis(1)));

            assertThrows(RuntimeException.class, () -> new Agent(config, new InMemorySessionStore()).run("s", "hi"));
            assertEquals(1, calls.get(), error.getClass().getSimpleName() + " is not retried");
        }
    }

    @Test
    void mediaGivesProviderCodeItsBytesWithoutCopying() {
        byte[] original = {1, 2, 3};
        Media media = Media.of(original, "image/png");
        byte[] first = media.readBytes(bytes -> bytes);
        byte[] second = media.readBytes(bytes -> bytes);

        assertSame(first, second, "the same array each time, not a new copy");
        assertArrayEquals(original, first);
        assertNotSame(original, first, "still separate from the caller's array");
        assertSame(media.dataUrl(), media.dataUrl(), "the data URL is built once");
        assertThrows(IllegalStateException.class, () -> Media.fromUrl("https://example.com/a.png").readBytes(b -> b));

        Media same = Media.of(new byte[]{1, 2, 3}, "image/png");
        assertEquals(media, same);
        assertEquals(media.hashCode(), same.hashCode());
        assertNotEquals(media, Media.of(new byte[]{1, 2, 4}, "image/png"));
        assertNotNull(ModelCapabilities.textOnly());
    }
}
