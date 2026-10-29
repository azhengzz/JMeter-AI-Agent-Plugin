package org.gitee.jmeter.ai.gui.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * Self-drawn toggle switch icon (pill track + round knob) replacing the
 * native check glyph on the ToAI toggle: reads its colors from
 * {@link ThemeColors} at paint time — a quiet separator-gray track when off,
 * the accent tone when on — so it follows the active look-and-feel without
 * re-creation. Dark themes take a sturdier pairing: the off track deepens
 * below the canvas and the knob lifts to white, because up there the
 * separator, card and elevated tones all converge (on Darcula they sit
 * within a few RGB units of each other) and the quiet-gray pill would melt
 * into the input card. The knob keeps a same-tone hairline crisping its
 * anti-aliased edge against the track.
 */
public final class ToggleSwitchIcon implements Icon {

    private static final int WIDTH = 34;
    private static final int HEIGHT = 18;
    private static final int KNOB = 14;
    private static final int INSET = 2;
    // How far the dark-theme off track sinks below the canvas — the input
    // card fills canvas+10, so this keeps the pill a fixed 35 levels away
    // from the surface it sits on.
    private static final int DARK_TRACK_SHIFT = 25;

    private final boolean on;

    private ToggleSwitchIcon(boolean on) {
        this.on = on;
    }

    public static ToggleSwitchIcon off() {
        return new ToggleSwitchIcon(false);
    }

    public static ToggleSwitchIcon on() {
        return new ToggleSwitchIcon(true);
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            boolean dark = ThemeColors.isDark();
            g2.setColor(on ? ThemeColors.accent()
                    : dark ? ThemeColors.shift(ThemeColors.canvas(), -DARK_TRACK_SHIFT)
                           : ThemeColors.separator());
            g2.fillRoundRect(x, y, WIDTH, HEIGHT, HEIGHT, HEIGHT);

            int knobX = on ? x + WIDTH - KNOB - INSET : x + INSET;
            int knobY = y + (HEIGHT - KNOB) / 2;
            g2.setColor(dark ? Color.WHITE : ThemeColors.elevatedSurface());
            g2.fillOval(knobX, knobY, KNOB, KNOB);
            g2.setStroke(new BasicStroke(1f));
            g2.drawOval(knobX, knobY, KNOB, KNOB);
        } finally {
            g2.dispose();
        }
    }
}
