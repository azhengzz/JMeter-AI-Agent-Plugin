package org.gitee.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

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
    void pinnedDetectionUsesUnitIncrementTolerance() {
        JScrollBar bar = new JScrollBar();
        bar.setUnitIncrement(16);
        JScrollPane scrollPane = paneReporting(bar);

        // value + visibleAmount (extent) vs maximum - tolerance
        bar.getModel().setRangeProperties(785, 200, 0, 1000, false);
        assertTrue(ChatScroller.isPinnedToBottom(scrollPane), "985 >= 1000-16: pinned");

        bar.getModel().setRangeProperties(780, 200, 0, 1000, false);
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane), "980 < 984: not pinned");

        bar.getModel().setRangeProperties(800, 200, 0, 1000, false);
        assertTrue(ChatScroller.isPinnedToBottom(scrollPane), "exactly at bottom: pinned");
    }

    @Test
    void zeroUnitIncrementFallsBackTo16() {
        JScrollBar bar = new JScrollBar();
        bar.setUnitIncrement(0);
        JScrollPane scrollPane = paneReporting(bar);

        bar.getModel().setRangeProperties(984, 200, 0, 1000, false);
        // 1184 >= 1000 - 16 = 984: pinned with fallback tolerance
        assertTrue(ChatScroller.isPinnedToBottom(scrollPane));
        bar.getModel().setRangeProperties(783, 200, 0, 1000, false);
        // 983 < 984: just outside the fallback tolerance
        assertFalse(ChatScroller.isPinnedToBottom(scrollPane));
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
