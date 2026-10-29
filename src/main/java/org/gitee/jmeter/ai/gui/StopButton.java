package org.gitee.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

import org.gitee.jmeter.ai.gui.theme.ThemeColors;

/**
 * The small circular stop button shown next to send while an AI turn is
 * running: a soft red disc ({@code errorSoft}, pale on light themes) with a
 * contrasting stop square, deepening on hover. Drawn rather than emoji/text so
 * it renders on any font / look-and-feel.
 */
class StopButton extends JButton {

    private static final int SIZE = 32;

    StopButton(Runnable onStop) {
        setToolTipText("Stop the current AI task");
        getAccessibleContext().setAccessibleName("Stop the current AI task");
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(SIZE, SIZE));
        addActionListener(e -> onStop.run());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Color disc = getModel().isRollover()
                    ? ThemeColors.blend(ThemeColors.error(), ThemeColors.errorSoft(), 0.35f)
                    : ThemeColors.errorSoft();
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setColor(disc);
            g2.fillOval(x, y, d, d);
            if (isFocusOwner()) {
                g2.setColor(ThemeColors.error());
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawOval(x, y, d - 1, d - 1);
            }
            // stop square, centered
            int s = Math.round(d * 0.34f);
            g2.setColor(ThemeColors.error());
            g2.fillRoundRect(x + (d - s) / 2, y + (d - s) / 2, s, s, 3, 3);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
