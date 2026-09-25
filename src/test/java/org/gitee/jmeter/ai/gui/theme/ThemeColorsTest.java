package org.gitee.jmeter.ai.gui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ThemeColors} reads every value from the live UIManager palette at call
 * time; these tests verify the semantic tokens resolve in both light and dark
 * themes (simulated via UIManager.put) and that the dark detection and blend
 * math behave.
 */
class ThemeColorsTest {

    private Color originalPanelBackground;

    @AfterEach
    void restoreTheme() {
        if (originalPanelBackground != null) {
            UIManager.put("Panel.background", originalPanelBackground);
            originalPanelBackground = null;
        }
    }

    private void simulateTheme(Color panelBackground) {
        if (originalPanelBackground == null) {
            originalPanelBackground = UIManager.getColor("Panel.background");
        }
        UIManager.put("Panel.background", panelBackground);
    }

    @Test
    void luminanceSeparatesBlackAndWhite() {
        assertTrue(ThemeColors.luminance(Color.WHITE) > 0.5);
        assertTrue(ThemeColors.luminance(Color.BLACK) < 0.5);
    }

    @Test
    void isDarkFollowsPanelBackground() {
        simulateTheme(new Color(0x1E, 0x1E, 0x1E));
        assertTrue(ThemeColors.isDark(), "dark panel background should be detected as dark");

        simulateTheme(new Color(0xF5, 0xF5, 0xF5));
        assertFalse(ThemeColors.isDark(), "light panel background should be detected as light");
    }

    @Test
    void semanticColorsResolveInDarkTheme() {
        simulateTheme(new Color(0x1E, 0x1E, 0x1E));
        assertAllSemanticColorsNonNull();
        assertTrue(ThemeColors.isDark());
    }

    @Test
    void semanticColorsResolveInLightTheme() {
        simulateTheme(new Color(0xF5, 0xF5, 0xF5));
        assertAllSemanticColorsNonNull();
        assertFalse(ThemeColors.isDark());
    }

    @Test
    void blendIsLinearMidpoint() {
        Color mid = ThemeColors.blend(Color.WHITE, Color.BLACK, 0.5f);
        assertEquals(128, Math.round((mid.getRed() + mid.getGreen() + mid.getBlue()) / 3f), 1,
                "50% white/black blend should be mid-gray");
    }

    @Test
    void errorSoftIsAPaleRedTintDistinctFromError() {
        simulateTheme(new Color(0xF5, 0xF5, 0xF5));
        assertFalse(ThemeColors.errorSoft().equals(ThemeColors.error()),
                "soft error fill must differ from the solid error color");
        assertTrue(ThemeColors.errorSoft().getRed() > ThemeColors.errorSoft().getGreen(),
                "soft error fill must stay reddish");
        assertTrue(ThemeColors.errorSoft().getRed() > ThemeColors.error().getRed(),
                "light-theme soft error fill must be lighter than the solid error color");
    }

    private void assertAllSemanticColorsNonNull() {
        assertNotNull(ThemeColors.error());
        assertNotNull(ThemeColors.errorSoft());
        assertNotNull(ThemeColors.success());
        assertNotNull(ThemeColors.warning());
        assertNotNull(ThemeColors.info());
        assertNotNull(ThemeColors.accent());
        assertNotNull(ThemeColors.canvas());
        assertNotNull(ThemeColors.surface());
        assertNotNull(ThemeColors.elevatedSurface());
        assertNotNull(ThemeColors.subtleSurface());
        assertNotNull(ThemeColors.accentSoft());
        assertNotNull(ThemeColors.separator());
        assertNotNull(ThemeColors.shadow());
        assertNotNull(ThemeColors.secondaryText());
        assertNotNull(ThemeColors.border());
        assertNotNull(ThemeColors.foreground());
        assertNotNull(ThemeColors.codeBackground());
        assertNotNull(ThemeColors.hoverBackground());
        assertNotNull(ThemeColors.userBubbleBackground());
    }
}
