package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/**
 * Drops the oldest messages until the history fits a budget, without producing a history
 * that model APIs reject:
 * <ul>
 *   <li>A TOOL result is never kept without the ASSISTANT message that requested it,
 *       and a request is never kept without its results. They are kept or dropped together.</li>
 *   <li>After the system prompt, the kept history starts with a USER message.</li>
 *   <li>The latest USER message is always kept, even if it alone is over budget. If the
 *       current turn is too long, its oldest tool steps are dropped instead.</li>
 *   <li>The newest tool step is always kept. If it is too big, its tool results are
 *       shortened in the returned list (the session itself is not changed).</li>
 *   <li>When earlier messages are dropped, provider state (such as Claude's signed thinking
 *       blocks) is removed from the kept messages: it is bound to the full conversation it came
 *       from, and providers reject it once earlier turns are gone.</li>
 * </ul>
 */
final class HistoryTrimmer {

    private HistoryTrimmer() {
    }

    /**
     * @param history the full conversation
     * @param cost    size of one message (1 for message counting, or its token count)
     * @param budget  max total cost, including the system prompt
     */
    static List<Message> trim(List<Message> history, ToIntFunction<Message> cost, int budget) {
        List<Message> kept = trimBlocks(history, cost, budget);
        java.util.Set<Message> keptSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        keptSet.addAll(kept);
        boolean dropped = history.stream().anyMatch(m -> !keptSet.contains(m));
        if (!dropped) return kept;
        List<Message> stripped = new ArrayList<>(kept.size());
        for (Message m : kept) stripped.add(m.getProviderState() == null ? m : m.withProviderState(null));
        return stripped;
    }

    private static List<Message> trimBlocks(List<Message> history, ToIntFunction<Message> cost, int budget) {
        if (history.isEmpty()) return history;

        Message system = history.get(0).getRole() == Role.SYSTEM ? history.get(0) : null;
        List<List<Message>> blocks = toBlocks(history, system != null ? 1 : 0);

        // Cache costs: counting tokens can mean a network call per message.
        int[] blockCosts = new int[blocks.size()];
        Arrays.fill(blockCosts, -1);
        IntUnaryOperator costOf = b -> {
            if (blockCosts[b] < 0) blockCosts[b] = blocks.get(b).stream().mapToInt(cost).sum();
            return blockCosts[b];
        };

        int remaining = budget - (system != null ? cost.applyAsInt(system) : 0);

        // 1. Keep the newest whole blocks that fit.
        int firstKept = blocks.size();
        int used = 0;
        for (int b = blocks.size() - 1; b >= 0; b--) {
            int c = costOf.applyAsInt(b);
            if (used + c > remaining) break;
            used += c;
            firstKept = b;
        }

        List<Message> result = new ArrayList<>();
        if (system != null) result.add(system);

        // 2. Start at a USER message if one is among the kept blocks.
        for (int b = firstKept; b < blocks.size(); b++) {
            if (isUser(blocks.get(b))) {
                addBlocks(result, blocks, b);
                return result;
            }
        }

        // 3. Everything that fits belongs to the current turn: keep the user's message
        //    that started it, and drop that turn's oldest steps until it fits.
        int anchor = -1;
        for (int b = firstKept - 1; b >= 0; b--) {
            if (isUser(blocks.get(b))) {
                anchor = b;
                break;
            }
        }
        if (anchor < 0) {
            // No user message at all; just avoid leading orphan tool results.
            int b = firstKept;
            while (b < blocks.size() && blocks.get(b).get(0).getRole() == Role.TOOL) b++;
            addBlocks(result, blocks, b);
            return result;
        }

        result.addAll(blocks.get(anchor));
        int last = blocks.size() - 1;
        if (anchor == last) return result;

        // Drop the turn's oldest steps, but never the newest one: it holds the tool results
        // the model is waiting for, and without them it would just call the same tool again.
        int from = Math.min(firstKept, last);
        used = costOf.applyAsInt(anchor);
        for (int b = from; b <= last; b++) used += costOf.applyAsInt(b);
        while (used > remaining && from < last) {
            used -= costOf.applyAsInt(from);
            from++;
        }
        for (int b = from; b < last; b++) result.addAll(blocks.get(b));

        List<Message> newest = blocks.get(last);
        if (used > remaining) {
            int available = remaining - (used - costOf.applyAsInt(last));
            newest = shrinkToolResults(newest, available, cost);
        }
        result.addAll(newest);
        return result;
    }

    /**
     * Shortens the TOOL results in a block until the whole block fits {@code available}.
     * Always cuts from the original text, so truncation notes never pile up. Best effort:
     * if even empty results don't fit, the block is returned with results cut to the note.
     */
    private static List<Message> shrinkToolResults(List<Message> block, int available, ToIntFunction<Message> cost) {
        int fixed = block.stream().filter(m -> m.getRole() != Role.TOOL).mapToInt(cost).sum();
        int target = available - fixed;
        double factor = 1.0;
        List<Message> current = block;

        for (int attempt = 0; attempt < 8; attempt++) {
            int toolCost = current.stream().filter(m -> m.getRole() == Role.TOOL).mapToInt(cost).sum();
            if (toolCost <= target || toolCost == 0) return current;
            // Aim a little under the target: the truncation note adds some size of its own.
            factor = target <= 0 ? 0 : factor * target / toolCost * 0.9;
            current = shrink(block, factor);
            if (factor == 0) return current;
        }
        return shrink(block, 0);
    }

    private static List<Message> shrink(List<Message> block, double factor) {
        List<Message> out = new ArrayList<>(block.size());
        for (Message m : block) {
            if (m.getRole() != Role.TOOL) {
                out.add(m);
                continue;
            }
            int maxChars = (int) (m.getContent().length() * factor);
            out.add(new Message(Role.TOOL, Truncation.keepHeadAndTail(m.getContent(), maxChars),
                    m.getToolCallId(), m.getToolCallName(), null));
        }
        return out;
    }

    /**
     * Groups messages into units that must stay together: an ASSISTANT message with the
     * TOOL results that follow it, or any other single message. TOOL results with no
     * ASSISTANT before them form their own block.
     */
    private static List<List<Message>> toBlocks(List<Message> history, int start) {
        List<List<Message>> blocks = new ArrayList<>();
        int i = start;
        while (i < history.size()) {
            Role role = history.get(i).getRole();
            int j = i + 1;
            if (role == Role.ASSISTANT || role == Role.TOOL) {
                while (j < history.size() && history.get(j).getRole() == Role.TOOL) j++;
            }
            blocks.add(history.subList(i, j));
            i = j;
        }
        return blocks;
    }

    private static boolean isUser(List<Message> block) {
        return block.get(0).getRole() == Role.USER;
    }

    private static void addBlocks(List<Message> result, List<List<Message>> blocks, int from) {
        for (int b = from; b < blocks.size(); b++) result.addAll(blocks.get(b));
    }
}
