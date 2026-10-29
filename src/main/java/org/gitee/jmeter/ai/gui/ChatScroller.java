package org.gitee.jmeter.ai.gui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyAdapter;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Chat-style following: exact bottom follows; any explicit reading position
 * stays put until the user deliberately returns to the bottom.
 *
 * <p>Protocol (capture-before-append): read the pinned state <em>before</em>
 * mutating the transcript, then call {@link #scrollToBottomIfPinned} after the
 * mutation. Reading post-append would mistake the newly grown content for a
 * user who scrolled away.
 */
public final class ChatScroller {

    private static final String GESTURE_KEY = "chat.userGesture";
    /**
     * Cards settle over several EDT/layout passes, not one invokeLater. The
     * chase keeps going while the scrollbar maximum still moves (layout
     * settling) and stops once the bottom is stable; the cap is only a
     * runaway backstop.
     */
    private static final int MAX_LAYOUT_FOLLOWS = 12;
    /** Idle passes at the bottom that confirm layout has finished settling. */
    private static final int STABLE_CONFIRM_PASSES = 2;

    private ChatScroller() {
    }

    public static JScrollPane scrollPaneOf(JComponent component) {
        return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
    }

    /**
     * Only the exact bottom counts as following. A held scrollbar thumb pauses
     * following even before it moves; a zero-sized pane retains its last state.
     */
    public static boolean isPinnedToBottom(JScrollPane scrollPane) {
        if (scrollPane == null) {
            return true;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar == null) {
            return true;
        }
        GestureState gestures = gesturesFor(scrollPane, bar);
        if (scrollPane.getWidth() <= 0 || scrollPane.getHeight() <= 0) {
            return !bar.getValueIsAdjusting() && gestures.lastPinned;
        }
        gestures.lastPinned = !bar.getValueIsAdjusting()
                && bar.getValue() + bar.getVisibleAmount() == bar.getMaximum();
        return gestures.lastPinned;
    }

    /**
     * Follow only when the pinned capture and user-gesture generation still
     * match. Unlike comparing pixel values, layout growth/collapse cannot be
     * mistaken for user input. A small bounded chase accommodates card layout
     * settling; any real scroll gesture aborts the entire chase.
     */
    public static void scrollToBottomIfPinned(JScrollPane scrollPane, boolean wasPinned) {
        if (scrollPane == null) {
            return;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        GestureState gestures = bar == null ? null : gesturesFor(scrollPane, bar);
        if (!wasPinned) {
            cancelDeferredFollow(scrollPane);
            return;
        }
        long generation = gestures == null ? 0 : gestures.generation;
        SwingUtilities.invokeLater(
                () -> followAfterLayout(scrollPane, bar, generation, 0, -1, 0));
    }

    private static void followAfterLayout(
            JScrollPane pane, JScrollBar capturedBar, long generation,
            int pass, int lastMax, int idlePasses) {
        JScrollBar bar = pane.getVerticalScrollBar();
        if (bar == null || bar != capturedBar || bar.getValueIsAdjusting()) {
            return;
        }
        GestureState gestures = (GestureState) pane.getClientProperty(GESTURE_KEY);
        if (gestures == null || gestures.generation != generation) {
            return;
        }
        if (pane.getWidth() <= 0 || pane.getHeight() <= 0) {
            if (isPinnedToBottom(pane)) {
                followOnFirstShow(pane, generation);
            } else {
                cancelDeferredFollow(pane);
            }
            return;
        }
        int max = bar.getMaximum();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount() == max;
        if (max == lastMax && !atBottom) {
            // No layout change since our own last pass, yet the view left the
            // bottom: something repositioned it programmatically — respect it
            // instead of dragging it back (settle lag always comes with a
            // growing maximum, this path does not).
            return;
        }
        jumpToBottom(pane);
        // The final layout increment can land one EDT cycle after the chase
        // first reaches the bottom, so stability needs consecutive idle
        // confirmations, not a single unchanged observation.
        int newIdle = max == lastMax ? idlePasses + 1 : 0;
        if (pass + 1 < MAX_LAYOUT_FOLLOWS && (!atBottom || newIdle < STABLE_CONFIRM_PASSES)) {
            int observedMax = max;
            int observedIdle = newIdle;
            SwingUtilities.invokeLater(() -> followAfterLayout(
                    pane, capturedBar, generation, pass + 1, observedMax, observedIdle));
        }
    }

    private static void jumpToBottom(JScrollPane scrollPane) {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        if (bar != null) {
            bar.setValue(bar.getMaximum());
        }
    }

    /** Records genuine gestures, never scrollbar-model/layout adjustments. */
    private static GestureState gesturesFor(JScrollPane pane, JScrollBar bar) {
        GestureState state = (GestureState) pane.getClientProperty(GESTURE_KEY);
        if (state == null) {
            state = new GestureState(pane, bar);
            pane.putClientProperty(GESTURE_KEY, state);
        }
        return state;
    }

    private static final class GestureState {
        private final JScrollPane pane;
        private long generation;
        private boolean lastPinned = true;
        private AWTEventListener toolkitInput;

        GestureState(JScrollPane pane, JScrollBar bar) {
            this.pane = pane;
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    generation++;
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    generation++;
                }
            };
            bar.addMouseListener(mouse);
            bar.addMouseMotionListener(mouse);
            // Geometry bookkeeping only (never a user veto): keep lastPinned in
            // step with the bar so the zero-size branch reports the real
            // pre-collapse state even when the move bypassed isPinnedToBottom.
            bar.addAdjustmentListener(event -> {
                if (pane.getWidth() > 0 && pane.getHeight() > 0) {
                    JScrollBar source = (JScrollBar) event.getSource();
                    lastPinned = !source.getValueIsAdjusting()
                            && source.getValue() + source.getVisibleAmount()
                                    == source.getMaximum();
                }
            });
            bar.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    generation++;
                }
            });
            // One pane-local wheel listener survives LaF sweeps that replace
            // JScrollPane's own wheel UI listener.
            pane.addMouseWheelListener(event -> generation++);
            // A wheel event may be delivered after an EDT listener's posted
            // follow; seeing it at the AWT entry point closes that gap. This
            // single listener per chat pane is not armed per message.
            toolkitInput = event -> {
                if (event.getID() == MouseEvent.MOUSE_WHEEL
                        && isWithinPane(((MouseEvent) event).getComponent())) {
                    generation++;
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    toolkitInput, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
            pane.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                        && !pane.isDisplayable()) {
                    dispose();
                }
            });
        }

        private void dispose() {
            Toolkit.getDefaultToolkit().removeAWTEventListener(toolkitInput);
            cancelDeferredFollow(pane);
            generation++;
        }

        private boolean isWithinPane(Component component) {
            return component != null && SwingUtilities.isDescendingFrom(component, pane);
        }
    }

    private static void cancelDeferredFollow(JScrollPane scrollPane) {
        for (java.awt.event.ComponentListener listener : scrollPane.getComponentListeners()) {
            if (listener instanceof FirstShowFollower) {
                scrollPane.removeComponentListener(listener);
            }
        }
    }

    /** Installs (at most one) first-show follower; user input invalidates it. */
    private static void followOnFirstShow(JScrollPane scrollPane, long generation) {
        for (java.awt.event.ComponentListener listener : scrollPane.getComponentListeners()) {
            if (listener instanceof FirstShowFollower) {
                return;
            }
        }
        scrollPane.addComponentListener(new FirstShowFollower(generation));
    }

    private static final class FirstShowFollower
            implements java.awt.event.ComponentListener {
        private final long generation;

        FirstShowFollower(long generation) {
            this.generation = generation;
        }

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
            SwingUtilities.invokeLater(() -> {
                if (isPinnedToBottom(pane)) {
                    followAfterLayout(pane, pane.getVerticalScrollBar(), generation, 0, -1, 0);
                }
            });
        }

        @Override
        public void componentMoved(java.awt.event.ComponentEvent e) {
        }

        @Override
        public void componentHidden(java.awt.event.ComponentEvent e) {
        }
    }
}
