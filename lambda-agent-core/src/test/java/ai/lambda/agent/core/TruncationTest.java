package ai.lambda.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TruncationTest {

    private static final String NOTE = "\\s*\\[lambda-agent-core] Result truncated: \\d+ characters omitted\\.\\s*";

    @Test
    void shortTextIsUnchanged() {
        assertEquals("hello", Truncation.keepHeadAndTail("hello", 5));
    }

    @Test
    void longTextKeepsStartAndEndAndSaysHowMuchWasCut() {
        String text = "H".repeat(100) + "M".repeat(800) + "T".repeat(100);

        String out = Truncation.keepHeadAndTail(text, 150);

        assertTrue(out.startsWith("H".repeat(100)), out);
        assertTrue(out.endsWith("T".repeat(50)), out);
        assertTrue(out.contains("Result truncated: 850 characters omitted."), out);
        assertEquals(150, out.replaceAll(NOTE, "").length());
    }

    @Test
    void zeroLimitLeavesOnlyTheNote() {
        assertEquals("\n\n[lambda-agent-core] Result truncated: 3 characters omitted.\n\n",
                Truncation.keepHeadAndTail("abc", 0));
    }

    @Test
    void doesNotSplitEmoji() {
        String text = "ab😀cdefgh"; // the emoji is two chars at index 2 and 3

        String out = Truncation.keepHeadAndTail(text, 4);

        assertFalse(out.contains("\uD83D") && !out.contains("😀"), "half an emoji: " + out);
    }
}
