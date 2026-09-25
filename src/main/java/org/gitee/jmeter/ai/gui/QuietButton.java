package org.gitee.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.UIManager;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/**
 * Flat, self-painted button used across the chat UI (message Copy buttons, the
 * send button, header actions). Paints its own rounded fill / outline / focus
 * ring from {@link ThemeColors} instead of the LaF's heavy chrome, so it re-themes
 * instantly with a repaint and stays visually consistent in light and dark.
 */
public class QuietButton extends JButton {

    public enum Kind {
        GHOST,
        OUTLINED,
        PRIMARY
    }

    private final Kind kind;
    private boolean compact;
    private boolean bodyFont;
    private boolean iconOnly;
    private int iconButtonSize = UiTokens.ICON_BUTTON_SIZE;

    public QuietButton(String text) {
        this(text, Kind.GHOST);
    }

    public QuietButton(String text, Kind kind) {
        super(text);
        this.kind = kind == null ? Kind.GHOST : kind;
        configure();
    }

    public QuietButton compact() {
        compact = true;
        configure();
        return this;
    }

    public QuietButton bodyFont() {
        bodyFont = true;
        configure();
        return this;
    }

    public QuietButton iconOnly() {
        return iconOnly(UiTokens.ICON_BUTTON_SIZE);
    }

    public QuietButton iconOnly(int size) {
        iconOnly = true;
        iconButtonSize = size;
        configure();
        Dimension dimension = new Dimension(size, size);
        setPreferredSize(dimension);
        setMinimumSize(dimension);
        setMaximumSize(dimension);
        return this;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (kind != null) {
            configure();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        if (iconOnly) {
            return new Dimension(iconButtonSize, iconButtonSize);
        }
        return new Dimension(preferred.width, Math.max(UiTokens.CONTROL_HEIGHT, preferred.height));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = getModel();
            Color fill = fillFor(model);
            int inset = 1;
            int width = getWidth() - inset * 2;
            int height = getHeight() - inset * 2;
            int arc = UiTokens.RADIUS_SMALL;
            if (fill != null && width > 0 && height > 0) {
                g2.setColor(fill);
                g2.fillRoundRect(inset, inset, width, height, arc, arc);
            }
            if (kind == Kind.OUTLINED && isEnabled()) {
                g2.setColor(ThemeColors.separator());
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(inset, inset, Math.max(0, width - 1), Math.max(0, height - 1), arc, arc);
            }
            if (isFocusOwner()) {
                g2.setColor(ThemeColors.focusRing());
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawRoundRect(inset, inset, Math.max(0, width - 1), Math.max(0, height - 1), arc, arc);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setForeground(foregroundFor());
    }

    private void configure() {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int horizontalInset = compact
                ? UiTokens.BUTTON_COMPACT_HORIZONTAL_INSET
                : UiTokens.BUTTON_STANDARD_HORIZONTAL_INSET;
        int verticalInset = iconOnly ? 0 : UiTokens.BUTTON_STANDARD_VERTICAL_INSET;
        horizontalInset = iconOnly ? 0 : horizontalInset;
        setMargin(new Insets(0, 0, 0, 0));
        setBorder(BorderFactory.createEmptyBorder(
                verticalInset, horizontalInset, verticalInset, horizontalInset));
        Font base = UIManager.getFont("Button.font");
        Font resolved = base != null ? base : getFont();
        setFont(bodyFont
                ? UiTokens.body(resolved)
                : compact ? UiTokens.caption(resolved) : UiTokens.label(resolved));
        setForeground(foregroundFor());
    }

    private Color fillFor(ButtonModel model) {
        Color fill;
        if (kind == Kind.PRIMARY) {
            // soft accent tint instead of a solid accent fill: a quiet, light
            // call-to-action that reads well in both themes
            fill = ThemeColors.accentSoft();
        } else if (kind == Kind.OUTLINED) {
            fill = ThemeColors.elevatedSurface();
        } else if (model.isRollover() || model.isPressed()) {
            fill = ThemeColors.hoverBackground();
        } else {
            fill = null;
        }
        if (fill == null) {
            return null;
        }
        if (!isEnabled()) {
            return ThemeColors.blend(fill, ThemeColors.canvas(), 0.42f);
        }
        if (model.isPressed()) {
            return ThemeColors.blend(ThemeColors.foreground(), fill, 0.10f);
        }
        if (model.isRollover()) {
            if (kind == Kind.PRIMARY) {
                return ThemeColors.blend(ThemeColors.foreground(), fill, 0.07f);
            }
            return ThemeColors.hoverBackground();
        }
        return fill;
    }

    private Color foregroundFor() {
        if (!isEnabled()) {
            return ThemeColors.secondaryText();
        }
        if (kind == Kind.PRIMARY) {
            // accent text on the soft tint (a white/black pick would vanish there)
            return ThemeColors.accent();
        }
        return ThemeColors.foreground();
    }
}
