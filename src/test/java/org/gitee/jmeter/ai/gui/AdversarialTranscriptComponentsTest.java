package org.gitee.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for the transcript components (ThinkingCard previews,
 * TranscriptView ordering across interleaved events, ChatScroller safety
 * nets). Assertions describe expected behavior; failures are findings.
 */
class AdversarialTranscriptComponentsTest {

    private static void onEdt(Runnable body) throws Exception {
        EventQueue.invokeAndWait(body::run);
    }

    private TranscriptView newView() throws Exception {
        AtomicReference<TranscriptView> ref = new AtomicReference<>();
        onEdt(() -> ref.set(new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 14))));
        return ref.get();
    }

    // --- ThinkingCard header preview --------------------------------------------

    /**
     * The collapsed header preview cuts at a fixed char count; the cut must
     * never split a surrogate pair (emoji in reasoning text), which produces
     * mojibake in the header.
     */
    @Test
    void collapsedPreviewDoesNotSplitSurrogatePairs() throws Exception {
        AtomicReference<ThinkingCard> ref = new AtomicReference<>();
        AtomicInteger violation = new AtomicInteger(-1);
        onEdt(() -> {
            ThinkingCard card = new ThinkingCard();
            // 59 BMP chars, then a 2-char emoji (surrogate pair), then a tail:
            // the 60-char cut lands exactly between the high and low surrogate.
            String text = "x".repeat(59) + "😀" + "tail";
            card.appendText(text);
            card.finish();
            ref.set(card);
            String header = card.getHeaderText();
            for (int i = 0; i < header.length(); i++) {
                char c = header.charAt(i);
                if (Character.isHighSurrogate(c)
                        && (i + 1 >= header.length()
                            || !Character.isLowSurrogate(header.charAt(i + 1)))) {
                    violation.set(i);
                }
            }
        });
        assertEquals(-1, violation.get(),
                "header preview split a surrogate pair at the cut point (mojibake)");
    }

    // --- TranscriptView interleavings --------------------------------------------

    /** A second reasoning burst after an answer gets its own card, in order. */
    @Test
    void reasoningAfterAnswerAppendsSeparateCardInOrder() throws Exception {
        TranscriptView view = newView();
        onEdt(() -> {
            view.appendReasoningToken("first-thoughts");
            view.addAssistantMarkdown("answer");
            view.appendReasoningToken("second-thoughts");
        });
        String visible = view.visibleTextForTests();
        int first = visible.indexOf("first-thoughts");
        int answer = visible.indexOf("answer");
        int second = visible.indexOf("second-thoughts");
        assertTrue(first >= 0 && answer > first && second > answer,
                "component order must follow event order: " + visible);
        assertTrue(view.getThinkingCard().isRunning(),
                "the latest reasoning card streams (spinner on)");
        long stillRunning = java.util.Arrays.stream(view.getComponents())
                .filter(c -> c instanceof ThinkingCard)
                .filter(c -> ((ThinkingCard) c).isRunning())
                .count();
        assertEquals(1, stillRunning, "older thinking cards must be finished");
    }

    /** Tool activity after a finished group lands in a NEW collapsed group. */
    @Test
    void newActivityAfterFinishedGroupDoesNotReviveOldGroup() throws Exception {
        TranscriptView view = newView();
        onEdt(() -> {
            view.addToolActivity("call-1");
            view.addUserMessage("interrupting message");
            view.addToolActivity("call-2");
        });
        long groups = java.util.Arrays.stream(view.getComponents())
                .filter(c -> c instanceof ToolActivityGroup).count();
        assertEquals(2, groups, "a finished group must not absorb later activity");
        String visible = view.visibleTextForTests();
        assertTrue(visible.indexOf("call-1") < visible.indexOf("You: interrupting message"));
        assertTrue(visible.indexOf("You: interrupting message") < visible.indexOf("call-2"));
    }

    /** System notes never become message cards and keep insertion order. */
    @Test
    void systemNotesStayNotesInOrder() throws Exception {
        TranscriptView view = newView();
        onEdt(() -> {
            view.addSystemMessage("note-1", null);
            view.addUserMessage("hi");
            view.addSystemMessage("note-2", null);
        });
        assertEquals(1, view.getCardCount());
        String visible = view.visibleTextForTests();
        assertTrue(visible.indexOf("note-1") < visible.indexOf("You: hi"));
        assertTrue(visible.indexOf("You: hi") < visible.indexOf("note-2"));
    }

    /** clearTranscript fully resets interleaved transient components. */
    @Test
    void clearResumesAcceptingEverythingAfterwards() throws Exception {
        TranscriptView view = newView();
        onEdt(() -> {
            view.addUserMessage("u");
            view.addToolActivity("t");
            view.appendReasoningToken("r");
            view.showThinking();
            view.clearTranscript();
            view.addUserMessage("fresh");
        });
        assertEquals(1, view.getCardCount());
        assertEquals("fresh", view.getCard(0).getText());
        assertNull(view.getActivityGroup());
        assertNull(view.getThinkingCard());
        assertFalse(view.isThinkingShowing());
    }

    // --- ChatScroller safety nets --------------------------------------------------

    @Test
    void scrollerTreatsMissingPaneAsPinned() {
        assertTrue(ChatScroller.isPinnedToBottom(null));
        ChatScroller.scrollToBottomIfPinned(null, true); // must not throw
    }

    @Test
    void scrollerTreatsUnrealizedPaneAsPinned() {
        // an empty, never-laid-out transcript pane: its scrollbar model still
        // holds the BoundedRangeModel defaults, which must not read as "not
        // pinned" — the very first message has to auto-scroll into view
        javax.swing.JScrollPane pane = new javax.swing.JScrollPane(new javax.swing.JPanel());
        assertTrue(ChatScroller.isPinnedToBottom(pane),
                "a fresh empty pane must count as pinned");
    }

    // --- MessageCard robustness ------------------------------------------------------

    /** Null markdown must not corrupt the card or throw on the EDT. */
    @Test
    void messageCardToleratesNullMarkdown() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        onEdt(() -> {
            try {
                MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, null);
                card.setMarkdownContent(null);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        assertNull(failure.get(), "null content must not throw (guards exist at callers, "
                + "but the card should not turn into an EDT exception if one slips through)");
    }

    /** Empty markdown renders an empty body without artifacts. */
    @Test
    void messageCardRendersEmptyMarkdown() throws Exception {
        AtomicReference<MessageCard> ref = new AtomicReference<>();
        onEdt(() -> {
            MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, null);
            card.setMarkdownContent("");
            ref.set(card);
        });
        assertNotNull(ref.get());
        assertEquals("", ref.get().getText());
    }
}
