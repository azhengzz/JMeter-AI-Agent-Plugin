package org.gitee.jmeter.ai.gui.render;

import javax.swing.*;
import java.awt.*;

/**
 * Shared UI theme helpers for the chat render layer.
 *
 * <p>Extracted here so both {@code MessageProcessor} and the render-package
 * components ({@code MarkdownRenderer}, {@code CodeBlockPanel}, ...) use a single
 * source of truth for theme-adaptive colors and CJK font fallback.
 */
public final class UiThemeUtil {

    private UiThemeUtil() {
    }

    /**
     * Gets a code block background color that is slightly different from the
     * panel background, providing visual distinction in both light and dark themes.
     */
    public static Color getCodeBlockBackground() {
        Color panelBg = UIManager.getColor("Panel.background");
        if (panelBg == null) {
            return new Color(240, 240, 240);
        }
        int r = panelBg.getRed();
        int g = panelBg.getGreen();
        int b = panelBg.getBlue();
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        if (luminance < 0.5) {
            // Dark theme: lighten slightly
            return new Color(Math.min(r + 20, 255), Math.min(g + 20, 255), Math.min(b + 20, 255));
        }
        // Light theme: darken slightly
        return new Color(Math.max(r - 15, 0), Math.max(g - 15, 0), Math.max(b - 15, 0));
    }

    /**
     * Returns a font that can render CJK glyphs, falling back through common
     * CJK families if the given font cannot display the probe character.
     */
    public static Font ensureCjkSupport(Font font) {
        if (font == null) {
            return null;
        }
        if (font.canDisplay('中')) {
            return font;
        }
        String[] cjkFonts = {"Microsoft YaHei", "SimHei", "SimSun", "PingFang SC", "Dialog"};
        for (String name : cjkFonts) {
            Font candidate = new Font(name, font.getStyle(), font.getSize());
            if (candidate.canDisplay('中')) {
                return candidate;
            }
        }
        return font;
    }

    /** Convert a Color to a CSS hex string (e.g. "#1a2b3c"). Null defaults to black. */
    public static String toHex(Color c) {
        if (c == null) {
            return "#000000";
        }
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
