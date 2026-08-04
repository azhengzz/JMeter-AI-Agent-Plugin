package org.gitee.jmeter.ai.agent.context;

import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.MessageListUtils;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic, un-gated JUnit 5 tests for the in-loop context governor
 * ({@link ContextWindowManager#govern}). Uses the chars/4 fallback estimator
 * (no MemoryConsolidator) so no external services are required.
 *
 * <p>Deliberately does NOT depend on the hard-coded session file that gates
 * {@code ContextWindowManagerTest} — these run on any machine via {@code mvn test}.
 */
class ContextGovernorTest {

    // ---- helpers ----

    private static String content(int chars) {
        char[] c = new char[chars];
        Arrays.fill(c, 'x');
        return new String(c);
    }

    /** A well-formed assistant(tool_calls=[id]) -> tool(id) pair. */
    private static List<Message> toolPair(String id, String toolName, String resultContent) {
        return List.of(
                Message.assistant("", List.of(new ToolCall(id, toolName, java.util.Map.of()))),
                Message.tool(id, toolName, resultContent));
    }

    private static List<Message> buildConversation(List<Message>... blocks) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("system-prompt"));
        msgs.add(Message.user("initial-user"));
        for (List<Message> block : blocks) {
            msgs.addAll(block);
        }
        return msgs;
    }

    private static List<Message> toolMessages(List<Message> msgs) {
        return msgs.stream().filter(m -> m.getRole() == Message.Role.TOOL).toList();
    }

    private static void assertToolPairingHolds(List<Message> msgs) {
        Set<String> toolIds = new HashSet<>();
        for (Message m : msgs) {
            if (m.getRole() == Message.Role.TOOL && m.getToolCallId() != null) {
                toolIds.add(m.getToolCallId());
            }
        }
        for (Message m : msgs) {
            if (m.getRole() == Message.Role.ASSISTANT && m.hasToolCalls()) {
                for (ToolCall tc : m.getToolCalls()) {
                    assertTrue(toolIds.contains(tc.getId()),
                            "assistant tool_call " + tc.getId() + " has no matching tool result");
                }
            }
        }
    }

    private static Message firstNonSystem(List<Message> msgs) {
        for (Message m : msgs) {
            if (m.getRole() != Message.Role.SYSTEM) {
                return m;
            }
        }
        return null;
    }

    // ---- tests ----

    @Test
    void govern_underBudget_returnsSameReference() {
        ContextWindowManager mgr = new ContextWindowManager(1_000_000);
        List<Message> msgs = new ArrayList<>(List.of(Message.system("sys"), Message.user("hi")));

        List<Message> out = mgr.govern(msgs, 4096);

        assertSame(msgs, out, "under budget the input list must be returned without copying");
    }

    @Test
    void govern_overBudget_returnsCopyAndDoesNotMutateInput() {
        ContextWindowManager mgr = new ContextWindowManager(15_000);
        List<Message> msgs = buildConversation();
        for (int i = 0; i < 15; i++) {
            msgs.addAll(toolPair("call-" + i, "read_file", content(10_000)));
        }
        List<String> snapshot = msgs.stream().map(Message::getContent).toList();

        List<Message> out = mgr.govern(msgs, 4000);

        assertNotSame(msgs, out, "over budget a trimmed copy must be returned");
        assertEquals(snapshot, msgs.stream().map(Message::getContent).toList(),
                "input list contents must be untouched");
    }

    @Test
    void microcompact_collapsesOlderKeepsNewest10() {
        // budget chosen so the list is over-budget raw, but under-budget after compacting
        // the 5 oldest of 15 read_file results.
        ContextWindowManager mgr = new ContextWindowManager(36_000);
        List<Message> msgs = buildConversation();
        for (int i = 0; i < 15; i++) {
            msgs.addAll(toolPair("call-" + i, "read_file", content(10_000)));
        }

        List<Message> out = mgr.govern(msgs, 4000);

        List<Message> tools = toolMessages(out);
        long placeholders = tools.stream()
                .filter(m -> m.getContent() != null && m.getContent().contains("result omitted from context"))
                .count();
        long originals = tools.stream()
                .filter(m -> m.getContent() != null && m.getContent().length() == 10_000)
                .count();
        assertEquals(5, placeholders, "5 oldest read_file results should be compacted");
        assertEquals(10, originals, "10 newest read_file results should be intact");

        // compacted messages keep their toolCallId (pairing preserved)
        assertTrue(tools.stream()
                .filter(m -> m.getContent() != null && m.getContent().contains("result omitted from context"))
                .allMatch(m -> m.getToolCallId() != null));
        assertToolPairingHolds(out);
    }

    @Test
    void microcompact_skipsShortResults() {
        // A big non-compactable run_test result pushes the list over budget; the 15 short
        // read_file results (< MIN_CHARS_TO_COMPACT) must NOT be compacted.
        ContextWindowManager mgr = new ContextWindowManager(5_000);
        List<Message> msgs = buildConversation();
        msgs.addAll(toolPair("big", "run_test", content(10_000)));
        for (int i = 0; i < 15; i++) {
            msgs.addAll(toolPair("short-" + i, "read_file", content(300)));
        }

        List<Message> out = mgr.govern(msgs, 400);

        long placeholders = out.stream()
                .filter(m -> m.getContent() != null && m.getContent().contains("result omitted from context"))
                .count();
        assertEquals(0, placeholders, "short results must not be compacted");
    }

    @Test
    void snip_firstNonSystemIsUser() {
        // non-compactable run_test results force snip; the kept tail must start at a USER.
        ContextWindowManager mgr = new ContextWindowManager(3_000);
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("sys"));
        msgs.add(Message.user("user-1"));
        msgs.addAll(toolPair("a", "run_test", content(4_000)));
        msgs.add(Message.user("user-2"));
        msgs.addAll(toolPair("b", "run_test", content(4_000)));

        List<Message> out = mgr.govern(msgs, 400);

        Message first = firstNonSystem(out);
        assertNotNull(first);
        assertEquals(Message.Role.USER, first.getRole(), "snip must align the kept tail to a USER message");
        assertEquals("user-2", first.getContent());
        // older heavy pair dropped
        assertTrue(out.stream().noneMatch(m -> "a".equals(m.getToolCallId())));
    }

    @Test
    void snip_recoversUserWhenMissingFromWindow() {
        // Only one USER, early; tiny budget keeps just the last tool. The USER is outside
        // the fitting window but must be pulled in (reach-back) so the request is valid.
        ContextWindowManager mgr = new ContextWindowManager(2_000);
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("sys"));
        msgs.add(Message.user("the-only-user"));
        msgs.addAll(toolPair("a", "run_test", content(4_000)));
        msgs.addAll(toolPair("b", "run_test", content(4_000)));

        List<Message> out = mgr.govern(msgs, 400);

        assertTrue(out.stream().anyMatch(m -> m.getRole() == Message.Role.USER
                && "the-only-user".equals(m.getContent())),
                "user outside the budget window must be recovered");
        assertEquals(Message.Role.USER, firstNonSystem(out).getRole());
    }

    @Test
    void snip_neverReturnsLeadingOrphanTool() {
        ContextWindowManager mgr = new ContextWindowManager(3_000);
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("sys"));
        msgs.add(Message.user("u"));
        msgs.addAll(toolPair("a", "run_test", content(4_000)));
        msgs.addAll(toolPair("b", "run_test", content(4_000)));
        msgs.addAll(toolPair("c", "run_test", content(4_000)));

        List<Message> out = mgr.govern(msgs, 400);

        Message first = firstNonSystem(out);
        assertNotNull(first);
        assertNotEquals(Message.Role.TOOL, first.getRole(),
                "first non-system message must never be an orphan TOOL");
    }

    @Test
    void govern_postCondition_noUnmatchedAssistantToolCall() {
        // Force both microcompact and snip, then verify pairing invariant on the result.
        ContextWindowManager mgr = new ContextWindowManager(15_000);
        List<Message> msgs = buildConversation();
        for (int i = 0; i < 15; i++) {
            msgs.addAll(toolPair("call-" + i, "read_file", content(10_000)));
        }

        List<Message> out = mgr.govern(msgs, 4000);

        assertToolPairingHolds(out);
    }

    @Test
    void findLegalStart_isolated() {
        // leading orphan tool -> dropped
        List<Message> withLeadingOrphan = new ArrayList<>();
        withLeadingOrphan.add(Message.tool("orphan-x", "read_file", "x"));
        withLeadingOrphan.add(Message.assistant("", List.of(new ToolCall("a", "read_file", java.util.Map.of()))));
        withLeadingOrphan.add(Message.tool("a", "read_file", "x"));
        assertEquals(1, MessageListUtils.findLegalStart(withLeadingOrphan));

        // clean list -> 0
        List<Message> clean = new ArrayList<>();
        clean.add(Message.assistant("", List.of(new ToolCall("a", "read_file", java.util.Map.of()))));
        clean.add(Message.tool("a", "read_file", "x"));
        assertEquals(0, MessageListUtils.findLegalStart(clean));

        // mid orphan invalidates everything before the restart point
        List<Message> midOrphan = new ArrayList<>();
        midOrphan.add(Message.assistant("", List.of(new ToolCall("a", "read_file", java.util.Map.of()))));
        midOrphan.add(Message.tool("orphan-y", "read_file", "x"));
        midOrphan.add(Message.tool("a", "read_file", "x"));
        assertEquals(3, MessageListUtils.findLegalStart(midOrphan));
    }

    @Test
    void indexOfNextAndPrevUser() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("s"));     // 0
        msgs.add(Message.user("u1"));      // 1
        msgs.add(Message.assistant("a"));  // 2
        msgs.add(Message.user("u2"));      // 3
        msgs.add(Message.assistant("b"));  // 4

        assertEquals(1, MessageListUtils.indexOfNextUser(msgs, 0));
        assertEquals(3, MessageListUtils.indexOfNextUser(msgs, 2));
        assertEquals(-1, MessageListUtils.indexOfNextUser(msgs, 4));
        assertEquals(-1, MessageListUtils.indexOfPrevUser(msgs, 1));
        assertEquals(1, MessageListUtils.indexOfPrevUser(msgs, 3));
        assertEquals(3, MessageListUtils.indexOfPrevUser(msgs, 4));
    }
}
