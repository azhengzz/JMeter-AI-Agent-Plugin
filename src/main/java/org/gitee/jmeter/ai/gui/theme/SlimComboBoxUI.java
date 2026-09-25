package org.gitee.jmeter.ai.gui.theme;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;

/**
 * Self-drawn combo box UI for the model selector, replacing the host LaF's
 * native combo (whose gradient arrow button and heavy border clash with the
 * chat card): a flat elevated field with a rounded separator outline that
 * brightens to the focus ring while the combo holds focus, and a quiet
 * chevron button instead of the native arrow. Colors resolve from
 * {@link ThemeColors} at paint time, so the field follows the active
 * look-and-feel without re-creation. Targets the non-editable combo the chat
 * panel uses; the popup's rows keep the host LaF's list styling, while the
 * popup frame gets the card's separator outline in place of
 * {@link BasicComboPopup}'s hard-coded black line border.
 */
public class SlimComboBoxUI extends BasicComboBoxUI {

    private static final int ARC = UiTokens.RADIUS_SMALL;
    private static final int BUTTON_WIDTH = 18;
    // Pinned field height: slimmer than the controls row's 32px action
    // buttons so the selector reads as a light inline control at the default
    // font. JMeter's zoom scales fonts without an upper bound, so this is a
    // floor, not a ceiling — the font metrics take over past it.
    private static final int FIELD_HEIGHT = 26;
    // Vertical breathing room around the closed value text, on top of the
    // border insets.
    private static final int TEXT_PADDING = 8;

    @Override
    public Dimension getPreferredSize(JComponent c) {
        // The native preferred height rides the renderer's row metrics and
        // lands as tall as the buttons; pin the slimmer field height instead
        // (the combo centers itself in the row — see its setBounds override).
        // Once a scaled font's line height outgrows the pin, follow it: a
        // hard 26 would clip the closed value's descenders at high zoom.
        Dimension size = super.getPreferredSize(c);
        Insets insets = c.getBorder().getBorderInsets(c);
        int textHeight = c.getFontMetrics(c.getFont()).getHeight();
        size.height = Math.max(FIELD_HEIGHT, textHeight + TEXT_PADDING + insets.top + insets.bottom);
        return size;
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        c.setOpaque(false);
        // Resolved at install time; refreshed automatically because the whole
        // UI re-installs on every LaF sweep (the combo re-pins it in
        // updateUI — see AiChatPanel's model selector).
        c.setBackground(ThemeColors.elevatedSurface());
        c.setForeground(ThemeColors.foreground());
        c.setBorder(new FieldBorder());
    }

    @Override
    protected ComboPopup createPopup() {
        BasicComboPopup popup = new BasicComboPopup(comboBox);
        // BasicComboPopup hard-codes a black line border that no host LaF can
        // re-theme (it is not a UIResource); give the frame the card's
        // separator tone instead. The border re-resolves on every theme
        // change because the whole UI re-installs on the LaF sweep.
        popup.setBorder(BorderFactory.createLineBorder(ThemeColors.separator()));
        return popup;
    }

    @Override
    public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
        // BasicComboBoxUI paints the focused closed value with the list's
        // host selection colors AFTER the renderer returns; repaint the card
        // palette (resolved at paint time) with the body-text value color —
        // matching the renderer's closed-field branch — so a focused field
        // keeps the design language instead of flashing the host selection
        // color.
        ListCellRenderer<Object> renderer = comboBox.getRenderer();
        Component component = renderer.getListCellRendererComponent(
                listBox, comboBox.getSelectedItem(), -1, false, false);
        component.setFont(comboBox.getFont());
        component.setForeground(ThemeColors.foreground());
        component.setBackground(ThemeColors.elevatedSurface());
        boolean shouldValidate = component instanceof JPanel;
        currentValuePane.paintComponent(g, component, listBox, bounds.x, bounds.y,
                bounds.width, bounds.height, shouldValidate);
    }

    @Override
    public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
        // The rounded card layers own the field's background; no native fill
        // belongs behind the value.
    }

    /** The popup installed by {@link #createPopup}; exposed for UI regression tests. */
    public ComboPopup popup() {
        return popup;
    }

    @Override
    protected JButton createArrowButton() {
        JButton button = new JButton(chevronDown(12));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setFocusable(false);
        button.setPreferredSize(new Dimension(BUTTON_WIDTH, 20));
        return button;
    }

    /** Quiet chevron pointing down, drawn in the secondary text tone. */
    private static Icon chevronDown(int size) {
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
                    g2.setColor(ThemeColors.secondaryText());
                    g2.setStroke(new BasicStroke(Math.max(1.4f, size / 9f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int left = Math.round(size * 0.28f);
                    int right = size - left;
                    int middle = size / 2;
                    int top = Math.round(size * 0.36f);
                    int bottom = Math.round(size * 0.64f);
                    g2.drawLine(x + left, y + top, x + middle, y + bottom);
                    g2.drawLine(x + middle, y + bottom, x + right, y + top);
                } finally {
                    g2.dispose();
                }
            }
        };
    }

    /**
     * Rounded field outline; brightens to the focus ring while the combo holds
     * focus. Colors read at paint time so the border re-themes for free.
     */
    private static final class FieldBorder implements Border {

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                boolean focused = c instanceof JComponent && ((JComponent) c).hasFocus();
                g2.setColor(focused ? ThemeColors.focusRing() : ThemeColors.separator());
                g2.setStroke(new BasicStroke(focused ? 1.4f : 1f));
                g2.drawRoundRect(x, y, width - 1, height - 1, ARC, ARC);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(1, 8, 1, 1);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}
