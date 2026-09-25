package org.gitee.jmeter.ai.gui.theme;

import java.awt.Font;
import javax.swing.UIManager;

/**
 * Design tokens for the chat UI: spacing scale, corner radii, control metrics and
 * the type ramp. All chat components derive their metrics from here so the
 * visual language stays consistent and tracks the active LaF font.
 */
public final class UiTokens {

    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    /**
     * Page-level gutter between the chat panel's outer edge and its content
     * (the card stream and the input dock). Deliberately tighter than
     * {@link #SPACE_2} so the composer reads nearly edge to edge, but kept
     * here as a named token so every column derives from the same value.
     */
    public static final int PAGE_MARGIN = 6;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_5 = 20;
    public static final int SPACE_6 = 24;
    public static final int SPACE_8 = 32;

    public static final int RADIUS_SMALL = 8;
    public static final int RADIUS_MEDIUM = 12;
    public static final int RADIUS_LARGE = 18;

    public static final int CONTROL_HEIGHT = 32;
    public static final int ICON_BUTTON_SIZE = 32;
    public static final int BUTTON_COMPACT_HORIZONTAL_INSET = 6;
    public static final int BUTTON_STANDARD_HORIZONTAL_INSET = 11;
    public static final int BUTTON_STANDARD_VERTICAL_INSET = 5;

    private UiTokens() {
    }

    public static Font title(Font base) {
        return font(base, Font.BOLD, 3f);
    }

    public static Font heading(Font base) {
        return font(base, Font.BOLD, 1f);
    }

    public static Font body(Font base) {
        return font(base, Font.PLAIN, 0f);
    }

    public static Font label(Font base) {
        return font(base, Font.BOLD, -1f);
    }

    public static Font caption(Font base) {
        return font(base, Font.PLAIN, -2f);
    }

    private static Font font(Font base, int style, float delta) {
        Font resolved = base != null ? base : UIManager.getFont("Label.font");
        if (resolved == null) {
            resolved = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        // deriveFont (never new Font(family, ...)) keeps the JVM composite-font
        // fallback chain intact so CJK glyphs render instead of boxes.
        return resolved.deriveFont(style, Math.max(10f, resolved.getSize2D() + delta));
    }
}
