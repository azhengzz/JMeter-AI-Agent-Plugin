package org.gitee.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link TranscriptView} is EDT-only (asserted in every mutating method), so
 * each scenario runs its body on the EDT via invokeAndWait.
 */
class TranscriptViewTest {

    private static void onEdt(Runnable body) throws Exception {
        EventQueue.invokeAndWait(body::run);
    }

    private TranscriptView newView() {
        return new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 14));
    }

    @Test
    void messagesAppendInOrderAsCards() throws Exception {
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addUserMessage("hello");
            view.addAssistantMarkdown("# answer");
            view.addSystemMessage("note", null);
            ref.set(view);
        });
        TranscriptView view = ref.get();
        assertEquals(2, view.getCardCount(), "system notes are not message cards");
        assertSame(MessageCard.Role.USER, view.getCard(0).getRole());
        assertEquals("hello", view.getCard(0).getText());
        assertSame(MessageCard.Role.ASSISTANT, view.getCard(1).getRole());
        assertEquals("# answer", view.getCard(1).getText(), "raw markdown is kept for Copy");
    }

    @Test
    void toolActivityAccumulatesInOneGroupAndFinishesOnNewMessage() throws Exception {
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addToolActivity("tool a");
            view.addToolActivity("tool b");
            view.addToolActivity("tool c");
            ref.set(view);
        });
        TranscriptView view = ref.get();
        ToolActivityGroup group = view.getActivityGroup();
        assertNotNull(group);
        assertEquals(3, group.getLineCount());
        assertTrue(group.isRunning());
        assertTrue(group.getText().contains("tool a"));
        assertTrue(group.getText().contains("tool c"));

        onEdt(() -> view.addUserMessage("next"));
        assertFalse(group.isRunning(), "a new message must finish the running activity group");
        assertTrue(group.isCollapsed(), "finished group auto-collapses");
    }

    @Test
    void runningToolLinesAreReplacedInPlaceOnCompletion() throws Exception {
        // 工具开始即呈现运行态行，完成事件原位替换为终态行（不增行）；
        // 无占位的完成事件按普通行追加（迟领养/开始信号被过滤兜底）
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addRunningToolActivity(1L, "run_test", "🔧 ⋯ run_test\n  Args: {mode=smoke}");
            view.addRunningToolActivity(1L, "read_file", "🔧 ⋯ read_file");
            ref.set(view);
        });
        TranscriptView view = ref.get();
        ToolActivityGroup group = view.getActivityGroup();
        assertEquals(2, group.getLineCount());
        assertTrue(group.getText().contains("⋯ run_test"), "运行态行先行呈现");

        onEdt(() -> view.completeToolActivity(1L, "run_test", "🔧 ✓ run_test [1200ms]\n  Result: PASS"));
        assertEquals(2, group.getLineCount(), "完成原位替换不增行");
        assertTrue(group.getText().contains("✓ run_test [1200ms]"));
        assertFalse(group.getText().contains("⋯ run_test"), "占位行被终态行替换");

        onEdt(() -> view.completeToolActivity(1L, "get_tree", "🔧 ✓ get_tree [5ms]"));
        assertEquals(3, group.getLineCount(), "无占位的完成事件按普通行追加");
        assertTrue(group.getText().contains("✓ get_tree"));
    }

    @Test
    void sameNameParallelToolsFillPlaceholdersInStartOrder() throws Exception {
        // 并行同名工具：完成事件按占位行开始序 FIFO 填充（早完成先进早占位行）
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addRunningToolActivity(1L, "read_file", "🔧 ⋯ read_file #占位1");
            view.addRunningToolActivity(1L, "read_file", "🔧 ⋯ read_file #占位2");
            view.completeToolActivity(1L, "read_file", "🔧 ✓ read_file 后完成 [9ms]");
            view.completeToolActivity(1L, "read_file", "🔧 ✓ read_file 先完成 [3ms]");
            ref.set(view);
        });
        String[] lines = ref.get().getActivityGroup().getText().split("\n");
        assertTrue(lines[0].contains("后完成"), "首个完成事件填首个占位行（FIFO）: " + lines[0]);
        assertTrue(lines[1].contains("先完成"), "次个完成事件填次个占位行: " + lines[1]);
    }

    @Test
    void finishMarksUncompletedRunningLines() throws Exception {
        // Stop/回合终态时工具批仍在跑：收尾时未完成的占位行补记"未收录结果"，
        // 不留永久 pending 形态
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addRunningToolActivity(1L, "run_test", "🔧 ⋯ run_test");
            view.finishActivityIfRunning();
            ref.set(view);
        });
        String text = ref.get().getActivityGroup().getText();
        assertTrue(text.contains("not recorded"), "未完成占位行收尾补记: " + text);
    }

    @Test
    void reasoningStreamsIntoCardAndCollapsesOnAnswer() throws Exception {
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.appendReasoningToken("thinking ");
            view.appendReasoningToken("hard");
            ref.set(view);
        });
        TranscriptView view = ref.get();
        ThinkingCard card = view.getThinkingCard();
        assertNotNull(card);
        assertTrue(card.isRunning());
        assertEquals("thinking hard", card.getText());

        onEdt(() -> view.addAssistantMarkdown("the answer"));
        assertFalse(card.isRunning(), "first answer content auto-collapses the thinking card");
        assertTrue(card.isCollapsed());
    }

    @Test
    void addReasoningBlockRendersPreCollapsed() throws Exception {
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addReasoningBlock("already done");
            ref.set(view);
        });
        ThinkingCard card = ref.get().getThinkingCard();
        assertNotNull(card);
        assertFalse(card.isRunning());
        assertTrue(card.isCollapsed());
        assertTrue(card.getHeaderText().contains("Thoughts"));
    }

    @Test
    void thinkingIndicatorShowsHidesAndSurvivesRepeatCalls() throws Exception {
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.showThinking();
            view.showThinking(); // idempotent: no duplicate row
            ref.set(view);
        });
        TranscriptView view = ref.get();
        assertTrue(view.isThinkingShowing());
        long thinkingRows = java.util.Arrays.stream(view.getComponents())
                .filter(c -> c instanceof ThinkingRow).count();
        assertEquals(1, thinkingRows);

        onEdt(view::hideThinking);
        assertFalse(view.isThinkingShowing());
        onEdt(view::hideThinking); // no-op, must not throw
    }

    @Test
    void clearTranscriptDisposesAnimationsAndResetsState() throws Exception {
        AtomicReference<TranscriptView> viewRef = new AtomicReference<>();
        AtomicReference<ToolActivityGroup> groupRef = new AtomicReference<>();
        AtomicReference<ThinkingCard> cardRef = new AtomicReference<>();
        onEdt(() -> {
            TranscriptView view = newView();
            view.addUserMessage("u");
            view.addToolActivity("t");
            view.appendReasoningToken("r");
            view.showThinking();
            groupRef.set(view.getActivityGroup());
            cardRef.set(view.getThinkingCard());
            viewRef.set(view);
        });
        TranscriptView view = viewRef.get();
        assertEquals(1, view.getCardCount());

        onEdt(view::clearTranscript);
        assertEquals(0, view.getCardCount());
        assertNull(view.getActivityGroup());
        assertNull(view.getThinkingCard());
        assertFalse(view.isThinkingShowing());
        assertFalse(groupRef.get().isSpinnerRunning(), "activity spinner must be disposed");
        assertFalse(cardRef.get().isSpinnerRunning(), "thinking spinner must be disposed");
    }

    @Test
    void scrollableContractTracksWidthOnly() {
        TranscriptView view = newView();
        assertTrue(view.getScrollableTracksViewportWidth());
        assertFalse(view.getScrollableTracksViewportHeight());
        assertEquals(16, view.getScrollableUnitIncrement(null, 0, 1));
        assertEquals(184, view.getScrollableBlockIncrement(new java.awt.Rectangle(0, 0, 0, 200), 0, 1));
    }
}
