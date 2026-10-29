package org.gitee.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.event.MouseWheelEvent;

import java.awt.Font;
import javax.swing.JFrame;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

/**
 * The pinned-to-bottom math and the capture-before-append protocol. A
 * JScrollPane's real scrollbar model is externally managed by its viewport
 * wiring (writes get clobbered), so the model-math scenarios run against a
 * standalone scrollbar handed out via an override - deterministic in headless
 * tests while exercising the same code path.
 */
class ChatScrollerTest {

    @Test
    void nullScrollPaneCountsAsPinned() {
        assertTrue(ChatScroller.isPinnedToBottom(null));
        // no-op, must not throw
        ChatScroller.scrollToBottomIfPinned(null, true);
    }

    @Test
    void onlyExactBottomCountsAsPinned() {
        JScrollBar bar = new JScrollBar();
        bar.setUnitIncrement(16);
        JScrollPane scrollPane = paneReporting(bar);

        bar.getModel().setRangeProperties(785, 200, 0, 1000, false);
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane),
                "near-bottom is still a user reading position");

        bar.getModel().setRangeProperties(799, 200, 0, 1000, false);
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane),
                "even one pixel above the bottom must stop following");

        bar.getModel().setRangeProperties(800, 200, 0, 1000, false);
        assertTrue(ChatScroller.isPinnedToBottom(scrollPane), "exactly at bottom: pinned");

        bar.setValueIsAdjusting(true);
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane),
                "holding the scrollbar thumb pauses follow even before the thumb moves");
    }

    @Test
    void zeroUnitIncrementDoesNotChangeExactBottomRule() {
        JScrollBar bar = new JScrollBar();
        bar.setUnitIncrement(0);
        JScrollPane scrollPane = paneReporting(bar);

        bar.getModel().setRangeProperties(799, 200, 0, 1000, false);
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane));
        bar.getModel().setRangeProperties(800, 200, 0, 1000, false);
        assertTrue(ChatScroller.isPinnedToBottom(scrollPane));
    }

    @Test
    void scrollPaneOfFindsAncestor() {
        JPanel view = new JPanel();
        JScrollPane scrollPane = new JScrollPane(view);
        assertSame(scrollPane, ChatScroller.scrollPaneOf(view));
    }

    @Test
    void scrollToBottomIfPinnedClampsToTrueBottom() throws Exception {
        JScrollBar bar = new JScrollBar();
        bar.getModel().setRangeProperties(0, 200, 0, 1000, false);
        JScrollPane scrollPane = paneReporting(bar);

        ChatScroller.scrollToBottomIfPinned(scrollPane, true);
        // the scroll is posted via invokeLater; barrier on the EDT before reading
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals(800, bar.getValue(), "setValue(max) clamps to max - extent");

        bar.setValue(0);
        ChatScroller.scrollToBottomIfPinned(scrollPane, false);
        EventQueue.invokeAndWait(() -> {
        });
        assertEquals(0, bar.getValue(), "wasPinned=false must not scroll");
        assertNotNull(scrollPane);
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void userScrollingCancelsPendingFollowAndRestoresOnlyAtExactBottom() throws Exception {
        JScrollPane[] holder = new JScrollPane[1];
        EventQueue.invokeAndWait(() -> {
            JPanel content = new JPanel();
            content.setPreferredSize(new Dimension(400, 4000));
            JScrollPane pane = new JScrollPane(content);
            pane.setSize(400, 200);
            pane.doLayout();
            pane.getViewport().doLayout();
            holder[0] = pane;
            // This adjustment is caused by content/layout, not user input: it
            // must not cancel a legitimate pending follow.
            pane.getVerticalScrollBar().setValue(0);
            ChatScroller.scrollToBottomIfPinned(pane, true);
        });
        EventQueue.invokeAndWait(() -> {
        });
        EventQueue.invokeAndWait(() -> {
            JScrollBar bar = holder[0].getVerticalScrollBar();
            assertTrue(ChatScroller.isPinnedToBottom(holder[0]),
                    "content-induced adjustments must not cancel pending follow");
            assertEquals(bar.getMaximum() - bar.getVisibleAmount(), bar.getValue());
        });
        EventQueue.invokeAndWait(() -> {
            JScrollBar bar = holder[0].getVerticalScrollBar();
            int parked = bar.getValue() - 16;
            ChatScroller.scrollToBottomIfPinned(holder[0], true);
            bar.setValue(parked);
            holder[0].dispatchEvent(new MouseWheelEvent(holder[0],
                    MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1));
        });
        EventQueue.invokeAndWait(() -> {
        });
        EventQueue.invokeAndWait(() -> {
            JScrollBar bar = holder[0].getVerticalScrollBar();
            assertEquals(bar.getMaximum() - bar.getVisibleAmount() - 15, bar.getValue(),
                    "wheel input must cancel the already-queued jump");
            assertFalse(ChatScroller.isPinnedToBottom(holder[0]),
                    "one wheel step above the tail is a user reading position, not pinned");
            bar.setValue(bar.getMaximum() - bar.getVisibleAmount() - 1);
            assertFalse(ChatScroller.isPinnedToBottom(holder[0]),
                    "even one pixel above the tail must not follow new content");
            bar.setValue(bar.getMaximum());
            assertTrue(ChatScroller.isPinnedToBottom(holder[0]),
                    "returning to the exact bottom resumes following");
        });
    }

    @Test
    void activeThumbDragVetoesPendingFollowWithoutChangingValue() throws Exception {
        JScrollBar bar = new JScrollBar();
        bar.getModel().setRangeProperties(800, 200, 0, 1000, false);
        JScrollPane scrollPane = paneReporting(bar);

        assertTrue(ChatScroller.isPinnedToBottom(scrollPane));
        bar.setValueIsAdjusting(true);
        ChatScroller.scrollToBottomIfPinned(scrollPane, true);
        bar.getModel().setRangeProperties(700, 200, 0, 1200, true);
        EventQueue.invokeAndWait(() -> {
        });

        assertEquals(700, bar.getValue(), "holding the thumb must veto a pending jump");
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane));
    }

    @Test
    void scrollModelReplacementDoesNotFollowUsingStaleCapture() throws Exception {
        JScrollBar oldBar = new JScrollBar();
        oldBar.getModel().setRangeProperties(800, 200, 0, 1000, false);
        JScrollPane scrollPane = paneReporting(oldBar);
        assertTrue(ChatScroller.isPinnedToBottom(scrollPane));
        ChatScroller.scrollToBottomIfPinned(scrollPane, true);

        JScrollBar replacement = new JScrollBar();
        replacement.getModel().setRangeProperties(100, 200, 0, 1200, false);
        scrollPane.setVerticalScrollBar(replacement);
        EventQueue.invokeAndWait(() -> {
        });

        assertEquals(100, replacement.getValue(),
                "a stale follow must not jump a newly installed scrollbar model");
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void hiddenPaneDoesNotOverrideExistingReadingPositionOnReopen() throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            EventQueue.invokeAndWait(() -> {
                JPanel content = new JPanel();
                content.setPreferredSize(new Dimension(400, 4000));
                frame[0] = new JFrame("chat-scroll-test");
                frame[0].add(new JScrollPane(content));
                frame[0].setSize(420, 240);
                frame[0].setVisible(true);
            });
            EventQueue.invokeAndWait(() -> {
                JScrollPane pane = (JScrollPane) frame[0].getContentPane().getComponent(0);
                pane.getVerticalScrollBar().setValue(120);
                frame[0].setVisible(false);
                ChatScroller.scrollToBottomIfPinned(pane,
                        ChatScroller.isPinnedToBottom(pane));
            });
            EventQueue.invokeAndWait(() -> {
                frame[0].setVisible(true);
            });
            EventQueue.invokeAndWait(() -> {
            });
            EventQueue.invokeAndWait(() -> {
                JScrollPane pane = (JScrollPane) frame[0].getContentPane().getComponent(0);
                assertEquals(120, pane.getVerticalScrollBar().getValue(),
                        "reopening a previously realized pane must preserve the reading position");
            });
        } finally {
            if (frame[0] != null) {
                EventQueue.invokeAndWait(frame[0]::dispose);
            }
        }
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void zeroWidthPaneRetainsParkedStateUntilRestored() throws Exception {
        JFrame[] frame = new JFrame[1];
        JScrollPane[] paneHolder = new JScrollPane[1];
        try {
            EventQueue.invokeAndWait(() -> {
                TranscriptView view = new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 14));
                JScrollPane pane = new JScrollPane(view);
                paneHolder[0] = pane;
                for (int i = 0; i < 12; i++) {
                    view.addAssistantMarkdown("history " + i + "\n\n" + "paragraph\n".repeat(6));
                }
                // Null layout keeps programmatic setSize in effect, so the pane
                // can genuinely collapse to zero width like a fully dragged
                // splitter (a layout-managed parent would immediately re-stretch it).
                JPanel host = new JPanel(null);
                host.add(pane);
                pane.setBounds(10, 10, 400, 200);
                frame[0] = new JFrame("collapsed-chat-scroll-test");
                frame[0].add(host);
                frame[0].setSize(420, 240);
                frame[0].setVisible(true);
            });
            flushEdt();
            EventQueue.invokeAndWait(() -> {
                JScrollPane pane = paneHolder[0];
                pane.getVerticalScrollBar().setValue(0);
                pane.setSize(0, pane.getHeight());
            });
            EventQueue.invokeAndWait(() -> {
                TranscriptView view = (TranscriptView) paneHolder[0].getViewport().getView();
                view.addSystemMessage("message while collapsed", null);
                paneHolder[0].setSize(400, 200);
            });
            flushEdt();
            EventQueue.invokeAndWait(() -> {
                JScrollPane pane = paneHolder[0];
                assertEquals(0, pane.getVerticalScrollBar().getValue(),
                        "a squeezed-hidden pane is not a brand-new transcript that should follow");
                assertFalse(ChatScroller.isPinnedToBottom(pane));
            });
        } finally {
            if (frame[0] != null) {
                EventQueue.invokeAndWait(frame[0]::dispose);
            }
        }
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void firstShowStillRevealsLatestContent() throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            JScrollPane[] holder = new JScrollPane[1];
            EventQueue.invokeAndWait(() -> {
                TranscriptView view = new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 14));
                holder[0] = new JScrollPane(view);
                for (int i = 0; i < 20; i++) {
                    view.addAssistantMarkdown("initial hidden history " + i);
                }
                frame[0] = new JFrame("first-show-scroll-test");
                frame[0].add(holder[0]);
                frame[0].setSize(420, 240);
                frame[0].setVisible(true);
            });
            flushEdt();
            EventQueue.invokeAndWait(() -> assertBottom(holder[0],
                    "a genuinely new transcript still opens at its latest message"));
        } finally {
            if (frame[0] != null) {
                EventQueue.invokeAndWait(frame[0]::dispose);
            }
        }
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void realTranscriptKeepsReadingPositionDuringToolAndReasoningGrowth() throws Exception {
        withRealTranscript((pane, view) -> {
            pane.getVerticalScrollBar().setValue(0);
            view.addSystemMessage("note while parked", null);
            view.addAssistantMarkdown("assistant while parked\n\n" + "paragraph\n".repeat(4));
            view.addUserMessage("user while parked");
            view.addToolActivity("read-only tool activity");
            view.addToolActivity("another line");
            view.appendReasoningToken("streamed reasoning");
            view.refreshTheme();
        }, (pane, view) -> assertEquals(0, pane.getVerticalScrollBar().getValue(),
                "neither the smart scroller nor any DefaultCaret may drag a parked viewport"));
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void realTranscriptKeepsFollowingTallAnswersUntilLayoutSettles() throws Exception {
        withRealTranscript((pane, view) -> {
            for (int i = 0; i < 4; i++) {
                view.addAssistantMarkdown(("## Answer " + i + "\n\n"
                        + "A paragraph with enough text to wrap and require more than one layout pass. ")
                        .repeat(8));
            }
        }, (pane, view) -> assertBottom(pane,
                "a tall answer must not leave a residual gap that disables all later follows"));
    }

    @DisabledIf(value = "java.awt.GraphicsEnvironment#isHeadless", disabledReason = "Requires a display")
    @Test
    void collapsingActivityDoesNotLookLikeAUserGesture() throws Exception {
        withRealTranscript((pane, view) -> {
            view.addToolActivity("long activity\n" + "tool detail\n".repeat(30));
            view.addAssistantMarkdown("final answer\n\n" + "answer paragraph\n".repeat(20));
        }, (pane, view) -> assertBottom(pane,
                "collapsing a tool group is content layout, not a user veto"));
    }

    private interface TranscriptAction {
        void run(JScrollPane pane, TranscriptView view);
    }

    private static void withRealTranscript(
            TranscriptAction action, TranscriptAction assertion) throws Exception {
        JFrame[] frame = new JFrame[1];
        try {
            EventQueue.invokeAndWait(() -> {
                TranscriptView view = new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 14));
                JScrollPane pane = new JScrollPane(view);
                frame[0] = new JFrame("real-transcript-scroll-test");
                frame[0].add(pane);
                frame[0].setSize(420, 240);
                for (int i = 0; i < 12; i++) {
                    view.addAssistantMarkdown("history " + i + "\n\n" + "history paragraph\n".repeat(6));
                }
                frame[0].setVisible(true);
            });
            flushEdt();
            EventQueue.invokeAndWait(() -> {
                JScrollPane pane = (JScrollPane) frame[0].getContentPane().getComponent(0);
                TranscriptView view = (TranscriptView) pane.getViewport().getView();
                action.run(pane, view);
            });
            flushEdt();
            EventQueue.invokeAndWait(() -> {
                JScrollPane pane = (JScrollPane) frame[0].getContentPane().getComponent(0);
                TranscriptView view = (TranscriptView) pane.getViewport().getView();
                assertion.run(pane, view);
            });
        } finally {
            if (frame[0] != null) {
                EventQueue.invokeAndWait(frame[0]::dispose);
            }
        }
    }

    private static void flushEdt() throws Exception {
        for (int i = 0; i < 20; i++) {
            EventQueue.invokeAndWait(() -> {
            });
        }
    }

    private static void assertBottom(JScrollPane pane, String message) {
        JScrollBar bar = pane.getVerticalScrollBar();
        assertEquals(bar.getMaximum() - bar.getVisibleAmount(), bar.getValue(), message);
    }

    private static JScrollPane paneReporting(JScrollBar bar) {
        // The override must not answer during the super constructor (the
        // scrollpane layout consults getVerticalScrollBar while building), so
        // it stays dormant until construction completes.
        class SpyScrollPane extends JScrollPane {
            private boolean spyActive;

            @Override
            public JScrollBar getVerticalScrollBar() {
                return spyActive ? bar : super.getVerticalScrollBar();
            }
        }
        SpyScrollPane spy = new SpyScrollPane();
        spy.spyActive = true;
        // Size the pane so it reads as realized: isPinnedToBottom treats a
        // never-laid-out pane (BoundedRangeModel defaults) as pinned, which
        // would bypass the model math these scenarios pin down.
        spy.setSize(400, 300);
        return spy;
    }
}
