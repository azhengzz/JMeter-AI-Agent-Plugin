package org.gitee.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/** Small animated "AI is thinking" row shown while a turn is running. */
class ThinkingRow extends JPanel {
    private final JLabel label;
    private final Timer timer;
    private int dots;

    ThinkingRow() {
        super(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        label = new JLabel("AI is thinking");
        label.setFont(UiTokens.caption(label.getFont()).deriveFont(Font.ITALIC));
        label.setForeground(ThemeColors.accent());
        label.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_2, UiTokens.SPACE_3));
        add(label);
        timer = new Timer(400, e -> advance());
        timer.start();
    }

    void applyTheme() {
        label.setForeground(ThemeColors.accent());
        repaint();
    }

    /** True while the dots timer is running (for tests / dispose checks). */
    boolean isSpinnerRunning() {
        return timer.isRunning();
    }

    void dispose() {
        timer.stop();
    }

    private void advance() {
        dots = (dots + 1) % 4;
        label.setText("AI is thinking" + ".".repeat(dots));
    }
}
