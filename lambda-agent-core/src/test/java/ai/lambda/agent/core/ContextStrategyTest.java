package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContextStrategyTest {

    private static Message system(String text) {
        return new Message(Role.SYSTEM, text, null);
    }

    private static Message user(String text) {
        return new Message(Role.USER, text, null);
    }

    private static Message assistant(String text) {
        return new Message(Role.ASSISTANT, text, null);
    }

    private static Message callsTools(String... ids) {
        List<ToolCall> calls = java.util.Arrays.stream(ids).map(id -> new ToolCall(id, "tool", "{}")).toList();
        return new Message(Role.ASSISTANT, "", null, null, calls);
    }

    private static Message result(String id, String text) {
        return new Message(Role.TOOL, text, id, "tool", null);
    }

    private static List<String> contents(List<Message> messages) {
        return messages.stream().map(Message::getContent).toList();
    }

    /**
     * The rules a trimmed history must follow for model APIs to accept it: it starts with a
     * user message, and every tool result comes after the request for it, and vice versa.
     */
    static void assertValidForModels(List<Message> messages) {
        int start = !messages.isEmpty() && messages.get(0).getRole() == Role.SYSTEM ? 1 : 0;
        assertTrue(messages.size() > start, "history must contain more than the system prompt");
        assertEquals(Role.USER, messages.get(start).getRole(), "first message after the system prompt must be USER");

        Set<String> requested = new HashSet<>();
        Set<String> answered = new HashSet<>();
        for (Message m : messages.subList(start, messages.size())) {
            m.getToolCalls().forEach(c -> requested.add(c.getId()));
            if (m.getRole() == Role.TOOL) {
                assertTrue(requested.contains(m.getToolCallId()), "orphan tool result " + m.getToolCallId());
                answered.add(m.getToolCallId());
            }
        }
        assertEquals(requested, answered, "every kept tool request needs its result");
    }

    @Test
    void slidingWindowKeepsSystemPromptAndNewestMessages() {
        List<Message> history = List.of(system("S"), user("1"), user("2"), user("3"), user("4"), user("5"));

        List<Message> kept = new SlidingWindowStrategy(3).optimize(history, new FakeModelClient());

        assertEquals(List.of("S", "4", "5"), contents(kept));
    }

    @Test
    void slidingWindowLeavesShortHistoryAlone() {
        List<Message> history = List.of(system("S"), user("1"));

        assertEquals(history, new SlidingWindowStrategy(5).optimize(history, new FakeModelClient()));
    }

    @Test
    void slidingWindowNeverStartsWithAnOrphanToolResult() {
        // One long turn: the user asked once, then the model called tools three times.
        List<Message> history = List.of(system("S"), user("question"),
                callsTools("c1"), result("c1", "r1"),
                callsTools("c2"), result("c2", "r2"),
                callsTools("c3"), result("c3", "r3"));

        // The old code kept the last 5 messages: r1, c2, r2, c3, r3 (r1 without its request).
        List<Message> kept = new SlidingWindowStrategy(6).optimize(history, new FakeModelClient());

        assertValidForModels(kept);
        assertEquals(List.of("S", "question", "", "r2", "", "r3"), contents(kept),
                "keeps the user's question and drops the oldest tool step");
    }

    @Test
    void slidingWindowNeverSplitsParallelToolResults() {
        List<Message> history = List.of(system("S"), user("u1"),
                callsTools("a", "b"), result("a", "ra"), result("b", "rb"),
                assistant("done"), user("u2"), assistant("hi"));

        List<Message> kept = new SlidingWindowStrategy(4).optimize(history, new FakeModelClient());

        assertValidForModels(kept);
        assertEquals(List.of("S", "u2", "hi"), contents(kept));
    }

    @Test
    void tokenLimitDropsOldestMessagesFirst() {
        // FakeModelClient counts one token per character.
        List<Message> history = List.of(system("sys"), user("aaaa"), user("bbbb"), user("cccc"));

        List<Message> kept = new TokenLimitStrategy(11).optimize(history, new FakeModelClient());

        assertEquals(List.of("sys", "bbbb", "cccc"), contents(kept));
    }

    @Test
    void tokenLimitKeepsToolResultWithItsRequest() {
        List<Message> history = List.of(system("sys"), user("question"),
                callsTools("c1"), result("c1", "result-result"), assistant("answer"));

        // The old code kept [sys, request, result, answer]: no user message at all.
        List<Message> kept = new TokenLimitStrategy(25).optimize(history, new FakeModelClient());

        assertValidForModels(kept);
        assertEquals(List.of("sys", "question", "answer"), contents(kept));
    }

    @Test
    void tokenLimitShortensAnOversizedToolResultInsteadOfDroppingIt() {
        String big = "x".repeat(50) + "y".repeat(100) + "z".repeat(50);
        List<Message> history = List.of(system("sys"), user("question"), callsTools("c1"), result("c1", big));

        // Before, the whole step was dropped, so the model would never see the result and
        // would call the tool again.
        List<Message> kept = new TokenLimitStrategy(100).optimize(history, new FakeModelClient());

        assertValidForModels(kept);
        assertEquals(4, kept.size());
        String shortened = kept.get(3).getContent();
        assertTrue(shortened.startsWith("xxx") && shortened.endsWith("zzz"), shortened);
        assertTrue(shortened.contains("Result truncated"), shortened);
        assertTrue(new FakeModelClient().countTokens(kept) <= 100, "must fit the budget");
        assertEquals(200, history.get(3).getContent().length(), "the stored history is not modified");
    }

    @Test
    void tokenLimitKeepsNewestStepEvenWhenNothingOfItFits() {
        List<Message> history = List.of(system("sys"), user("question"), callsTools("c1"), result("c1", "r".repeat(500)));

        List<Message> kept = new TokenLimitStrategy(12).optimize(history, new FakeModelClient());

        assertValidForModels(kept);
        assertEquals(4, kept.size());
        assertEquals(Truncation.keepHeadAndTail("r".repeat(500), 0), kept.get(3).getContent(),
                "only the truncation note is left");
    }

    @Test
    void droppingEarlierTurnsRemovesProviderStateFromKeptMessages() {
        var thinking = new ai.lambda.ai.core.ProviderState("anthropic", "[{\"type\":\"thinking\"}]");
        List<Message> history = List.of(system("S"), user("old"), assistant("old answer").withProviderState(thinking),
                user("new"), assistant("new answer").withProviderState(thinking));

        List<Message> trimmed = new SlidingWindowStrategy(3).optimize(history, new FakeModelClient());
        List<Message> untouched = new SlidingWindowStrategy(10).optimize(history, new FakeModelClient());

        assertEquals(List.of("S", "new", "new answer"), contents(trimmed));
        assertNull(trimmed.get(2).getProviderState(), "bound to the full conversation, so it must go");
        assertSame(history, untouched, "nothing dropped, nothing changed");
        assertEquals(thinking, history.get(4).getProviderState(), "the session itself keeps it");
    }

    @Test
    void tokenLimitAlwaysKeepsTheLatestUserMessage() {
        List<Message> history = List.of(system("sys"), user("hi"), assistant("hello"), user("x".repeat(100)));

        // The old code returned only the system prompt, so the model never saw the question.
        List<Message> kept = new TokenLimitStrategy(10).optimize(history, new FakeModelClient());

        assertValidForModels(kept);
        assertEquals(2, kept.size());
        assertEquals(100, kept.get(1).getContent().length());
    }
}
