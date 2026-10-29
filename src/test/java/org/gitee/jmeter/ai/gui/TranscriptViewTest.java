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
