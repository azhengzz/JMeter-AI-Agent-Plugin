package org.gitee.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;

/**
 * Vector icons drawn with Graphics2D instead of emoji or font glyphs, so they
 * render identically on any look-and-feel and any font coverage. Color follows
 * the hosting component's foreground (disabled components get the secondary
 * tone), so the icons re-theme without re-creation.
 */
public final class ActionIcons {

    private ActionIcons() {
    }

    /** Copy-to-clipboard icon: two overlapping rounded sheets. */
    public static Icon copy(int size) {
        return icon(size, (g2, x, y, s) -> {
            int inset = Math.max(2, Math.round(s * 0.16f));
            int offset = Math.max(2, Math.round(s * 0.18f));
            int width = s - inset * 2 - offset;
            int height = s - inset * 2 - offset;
            int arc = Math.max(2, Math.round(s * 0.16f));
            g2.drawRoundRect(x + inset + offset, y + inset, width, height, arc, arc);
            g2.drawRoundRect(x + inset, y + inset + offset, width, height, arc, arc);
        });
    }

    /** Send icon: an upward chevron-tipped shaft. */
    public static Icon send(int size) {
        return icon(size, (g2, x, y, s) -> {
            int center = s / 2;
            int top = Math.max(2, Math.round(s * 0.18f));
            int side = Math.max(3, Math.round(s * 0.28f));
            int bottom = s - Math.max(2, Math.round(s * 0.18f));
            g2.drawLine(x + center, y + top, x + center, y + bottom);
            g2.drawLine(x + center, y + top, x + side, y + Math.round(s * 0.45f));
            g2.drawLine(x + center, y + top, x + s - side, y + Math.round(s * 0.45f));
        });
    }

    private static Icon icon(int size, Painter painter) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    Color foreground = component != null && component.isEnabled()
                            ? component.getForeground() : ThemeColors.secondaryText();
                    g2.setColor(foreground != null ? foreground : ThemeColors.foreground());
                    g2.setStroke(new BasicStroke(Math.max(1.4f, size / 9f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    painter.paint(g2, x, y, size);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    private interface Painter {
        void paint(Graphics2D graphics, int x, int y, int size);
    }
}
