package org.gitee.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/**
 * Rounded visual container in the chat card design language: a rounded fill
 * with a subtle separator outline that brightens to the accent focus ring
 * while the wrapped input component holds focus. Colors are read from
 * {@link ThemeColors} at paint time so the panel re-themes without re-creation.
 *
 * <p>Painting contract: the fill goes down in {@link #paintComponent} (under
 * every child) while the outline goes down last in {@link #paint} (over the
 * children). The transcript shell's content reaches the card edge by design,
 * and children painting after the outline would erase its inner half along
 * their spans — so the outline overlays them. The composer's rows are padded
 * well clear of the edge and never overlap either way, so the overlay is
 * invisible there. Children are also clipped to the rounded shape (see
 * {@link #paintChildren}) so opaque rectangular content cannot square off the
 * corner cutouts.
 */
class RoundedBorderPanel extends JPanel {

    private static final int ARC = UiTokens.RADIUS_LARGE * 2;
    private static final float FOCUS_STROKE = 1.6f;

    private boolean focused;
    /** Canvas-tinted shell (transcript area) vs the elevated composer tint. */
    private final boolean canvasShell;

    RoundedBorderPanel(JComponent content) {
        this(content, false);
    }

    /**
     * Canvas-tinted variant for the transcript display area: it carries the
     * same canvas token the scroll viewport painted before the shell existed,
     * so the cards inside keep exactly the contrast they had.
     */
    RoundedBorderPanel(JComponent content, boolean canvasShell) {
        super(new BorderLayout());
        this.canvasShell = canvasShell;
        setOpaque(false);
        add(content, BorderLayout.CENTER);
    }

    /** Focus ring state, driven by the wrapped input's FocusListener. */
    void setFocused(boolean focused) {
        if (this.focused == focused) {
            return;
        }
        this.focused = focused;
        repaint();
    }

    boolean isFocused() {
        return focused;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = 1;
            int width = getWidth() - inset * 2 - 1;
            int height = getHeight() - inset * 2 - 1;
            if (width <= 0 || height <= 0) {
                return;
            }
            g2.setColor(canvasShell ? ThemeColors.canvas() : ThemeColors.elevatedSurface());
            g2.fillRoundRect(inset, inset, width, height, ARC, ARC);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    @Override
    protected void paintChildren(Graphics g) {
        // Children are clipped to the rounded fill shape: the transcript's
        // opaque system notes (and the scrollbar thumb) are full-width
        // rectangles whose square corners would otherwise paint over the
        // corner cutouts — the same artifact the non-opaque viewport
        // prevents one layer up. Intersect (never replace) so damage-region
        // clipping from the repaint machinery survives. The scroll pane and
        // everything under this shell is non-opaque, so child-anchored
        // damage always walks up to the opaque root and comes back down
        // through this method — the clip cannot be bypassed.
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            int width = getWidth() - 2 - 1;
            int height = getHeight() - 2 - 1;
            if (width > 0 && height > 0) {
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(
                        1, 1, width, height, ARC, ARC));
            }
            super.paintChildren(g2);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        // Outline last (see the class javadoc for the ordering contract).
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = 1;
            int width = getWidth() - inset * 2 - 1;
            int height = getHeight() - inset * 2 - 1;
            if (width <= 0 || height <= 0) {
                return;
            }
            g2.setColor(focused ? ThemeColors.focusRing() : ThemeColors.separator());
            g2.setStroke(new java.awt.BasicStroke(focused ? FOCUS_STROKE : 1f));
            g2.drawRoundRect(inset, inset, width, height, ARC, ARC);
        } finally {
            g2.dispose();
        }
    }
}
