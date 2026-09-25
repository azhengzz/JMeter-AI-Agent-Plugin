package org.gitee.jmeter.ai.gui;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Standard chat-style auto-scroll behavior for the transcript.
 *
 * <p>The view only follows new content when the user is already at (or very
 * near) the bottom. If the user has scrolled up to read earlier messages,
 * incoming content never yanks the scrollbar away from them.
 *
 * <p>Protocol (capture-before-append): read the pinned state <em>before</em>
 * mutating the transcript, then call {@link #scrollToBottomIfPinned} after the
 * mutation. Reading the state post-append is useless: the new content itself
 * pushes the viewport off the bottom, so every read would report "not pinned".
 */
public final class ChatScroller {

    private ChatScroller() {
    }

    /**
     * Finds the scroll pane containing the given component (typically the
     * chat transcript).
     */
    public static JScrollPane scrollPaneOf(JComponent component) {
        return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
    }

    /**
     * Returns true when the scroll pane's view is at (or within tolerance of)
     * the bottom - i.e. new content should be auto-scrolled into view. A
     * missing scroll pane counts as pinned so content is always revealed.
     *
     * <p>The tolerance is the scrollbar's unit increment (about one text line)
     * so it adapts to font size / DPI instead of a brittle fixed pixel count.
     */
    public static boolean isPinnedToBottom(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return true;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar == null) {
            return true;
        }
        int tolerance = bar.getUnitIncrement();
        if (tolerance <= 0) {
            tolerance = 16;
        }
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - tolerance;
    }

    /**
     * Scrolls to the bottom if {@code wasPinned} is true. Capture the pinned
     * state <em>before</em> inserting new content, then call this afterwards.
     * The scroll itself runs on the EDT via {@code invokeLater} so it executes
     * after the layout pass has updated the scrollbar's maximum for the just
     * appended content; {@code setValue(max)} is clamped by the model to
     * {@code max - extent} (the true bottom).
     */
    public static void scrollToBottomIfPinned(JScrollPane scrollPane, boolean wasPinned) {
        if (scrollPane == null || !wasPinned) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            if (bar != null) {
                bar.setValue(bar.getMaximum());
            }
        });
    }
}
