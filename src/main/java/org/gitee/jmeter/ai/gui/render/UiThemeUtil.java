package org.gitee.jmeter.ai.gui.render;

import java.awt.Font;

/**
 * CJK font fallback helper for the chat render layer (legacy HTML-path theme
 * helpers were removed along with the JEditorPane text/html route).
 */
public final class UiThemeUtil {

    private UiThemeUtil() {
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
}
