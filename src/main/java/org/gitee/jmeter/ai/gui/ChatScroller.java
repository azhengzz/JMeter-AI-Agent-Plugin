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
     * <p>A pane that was never laid out counts as pinned too: its scrollbar
     * model still holds the BoundedRangeModel defaults (max 100 / extent 10),
     * which would read as "not pinned" and strand the first messages
     * off-screen in a panel that starts hidden.
     *
     * <p>The tolerance is the scrollbar's unit increment (about one text line)
     * so it adapts to font size / DPI instead of a brittle fixed pixel count.
     */
    public static boolean isPinnedToBottom(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return true;
        }
        if (scrollPane.getWidth() <= 0 || scrollPane.getHeight() <= 0) {
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
     *
     * <p>When the pane was never laid out (content arrived while the panel was
     * hidden, e.g. an IPC turn rendering off-screen), a follow is deferred to
     * the pane's first show/resize instead - otherwise the accumulated
     * transcript would open scrolled to the top.
     */
    public static void scrollToBottomIfPinned(JScrollPane scrollPane, boolean wasPinned) {
        if (scrollPane == null || !wasPinned) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (scrollPane.getWidth() > 0) {
                jumpToBottom(scrollPane);
            } else {
                followOnFirstShow(scrollPane);
            }
        });
    }

    private static void jumpToBottom(JScrollPane scrollPane) {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar != null) {
            bar.setValue(bar.getMaximum());
        }
    }

    /** Installs (at most one) one-shot follower that jumps to bottom on first realization. */
    private static void followOnFirstShow(JScrollPane scrollPane) {
        for (java.awt.event.ComponentListener listener : scrollPane.getComponentListeners()) {
            if (listener instanceof FirstShowFollower) {
                return;
            }
        }
        scrollPane.addComponentListener(new FirstShowFollower());
    }

    /**
     * Jumps to the bottom once the pane gets its first real size - fired by
     * the show/resize that realize it - then removes itself. componentResized
     * is the reliable trigger for a never-laid-out pane (layout has run by the
     * time width is non-zero); componentShown covers an already-realized pane
     * being re-shown (width non-zero immediately).
     */
    private static final class FirstShowFollower
            implements java.awt.event.ComponentListener {

        @Override
        public void componentResized(java.awt.event.ComponentEvent e) {
            maybeFollow(e);
        }

        @Override
        public void componentShown(java.awt.event.ComponentEvent e) {
            maybeFollow(e);
        }

        private void maybeFollow(java.awt.event.ComponentEvent e) {
            JScrollPane pane = (JScrollPane) e.getComponent();
            if (pane.getWidth() <= 0) {
                return;
            }
            pane.removeComponentListener(this);
            SwingUtilities.invokeLater(() -> jumpToBottom(pane));
        }

        @Override
        public void componentMoved(java.awt.event.ComponentEvent e) {
        }

        @Override
        public void componentHidden(java.awt.event.ComponentEvent e) {
        }
    }
}
