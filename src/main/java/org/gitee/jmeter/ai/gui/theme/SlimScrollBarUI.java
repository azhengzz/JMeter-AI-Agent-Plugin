package org.gitee.jmeter.ai.gui.theme;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * Overlay-style scrollbar (slim rounded thumb, no arrow buttons, transparent
 * track) used by the transcript's embedded scrollers (tables, code blocks)
 * and the message input's own scroll pane. Thumb color resolves from
 * {@link ThemeColors} at paint time, so the bar follows the active
 * look-and-feel (including a live light/dark switch on re-render) without
 * reinstalling the UI.
 */
public final class SlimScrollBarUI extends BasicScrollBarUI {

    /** Track thickness in px; the thumb paints inset by {@link #THUMB_INSET}. */
    private static final int THICKNESS = 10;
    private static final int THUMB_INSET = 2;
    private static final int THUMB_ARC = 8;

    /**
     * A scroll bar born with the slim UI that re-pins it after every reset:
     * the host look-and-feel/zoom refresh sweeps every component with
     * {@code updateComponentTreeUI}, which swaps each bar back to the native
     * delegate, so the override reinstalls the slim UI inside that sweep.
     */
    public static JScrollBar bar(int orientation) {
        JScrollBar bar = new JScrollBar(orientation) {
            @Override
            public void updateUI() {
                super.updateUI();
                pin(this);
            }
        };
        pin(bar);
        return bar;
    }

    /**
     * A scroll pane whose bars keep the slim UI across look-and-feel
     * switches (see {@link #bar}). The pane's wheel gesture stays intact;
     * call {@link #install} on panes embedded in the transcript, where the
     * wheel must bubble to the chat's own scroll bar instead.
     */
    public static JScrollPane scroller(Component view, int vsbPolicy, int hsbPolicy) {
        return new JScrollPane(view, vsbPolicy, hsbPolicy) {
            @Override
            public JScrollBar createVerticalScrollBar() {
                return bar(JScrollBar.VERTICAL);
            }

            @Override
            public JScrollBar createHorizontalScrollBar() {
                return bar(JScrollBar.HORIZONTAL);
            }
        };
    }

    /**
     * Installs the slim UI on both bars of the given scroll pane and strips the
     * pane's mouse-wheel handler: the scroller embeds inside the chat
     * transcript with no vertical bar of its own, so a wheel event it consumed
     * would be swallowed for nothing (and could silently shift the viewport).
     * With the handler gone the wheel bubbles up to the enclosing transcript
     * scroll pane and moves the chat, even when the pointer sits on a table.
     */
    public static void install(JScrollPane pane) {
        pin(pane.getHorizontalScrollBar());
        pin(pane.getVerticalScrollBar());
        stripWheel(pane);
    }

    /** Removes the pane's own wheel handler so wheel events bubble upward. */
    public static void stripWheel(JScrollPane pane) {
        for (java.awt.event.MouseWheelListener listener : pane.getMouseWheelListeners()) {
            pane.removeMouseWheelListener(listener);
        }
    }

    private static void pin(JScrollBar bar) {
        bar.setUI(new SlimScrollBarUI());
        bar.setOpaque(false);
        bar.setFocusable(false);
        bar.setUnitIncrement(16);
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        // transparent: the card background shows through
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        g.setColor(ThemeColors.separator());
        g.fillRoundRect(
                thumbBounds.x + THUMB_INSET,
                thumbBounds.y + THUMB_INSET,
                thumbBounds.width - THUMB_INSET * 2,
                thumbBounds.height - THUMB_INSET * 2,
                THUMB_ARC, THUMB_ARC);
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return hiddenButton();
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return hiddenButton();
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        boolean vertical = scrollbar != null
                && scrollbar.getOrientation() == JScrollBar.VERTICAL;
        return vertical ? new Dimension(THICKNESS, 40) : new Dimension(40, THICKNESS);
    }

    private static JButton hiddenButton() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(0, 0));
        button.setMinimumSize(new Dimension(0, 0));
        button.setMaximumSize(new Dimension(0, 0));
        button.setBorder(null);
        button.setOpaque(false);
        button.setFocusable(false);
        return button;
    }
}
