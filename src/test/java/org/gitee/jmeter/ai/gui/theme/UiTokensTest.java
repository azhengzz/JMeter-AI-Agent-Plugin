package org.gitee.jmeter.ai.gui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;

import org.junit.jupiter.api.Test;

/** Token values and the type ramp (relative sizes, clamped at 10pt). */
class UiTokensTest {

    @Test
    void spacingScaleMatchesSpec() {
        assertEquals(4, UiTokens.SPACE_1);
        assertEquals(8, UiTokens.SPACE_2);
        assertEquals(12, UiTokens.SPACE_3);
        assertEquals(16, UiTokens.SPACE_4);
        assertEquals(20, UiTokens.SPACE_5);
        assertEquals(24, UiTokens.SPACE_6);
        assertEquals(32, UiTokens.SPACE_8);
    }

    @Test
    void radiiMatchSpec() {
        assertEquals(8, UiTokens.RADIUS_SMALL);
        assertEquals(12, UiTokens.RADIUS_MEDIUM);
        assertEquals(18, UiTokens.RADIUS_LARGE);
    }

    @Test
    void typeRampIsMonotonicRelativeToBase() {
        Font base = new Font(Font.DIALOG, Font.PLAIN, 14);
        float title = UiTokens.title(base).getSize2D();
        float heading = UiTokens.heading(base).getSize2D();
        float body = UiTokens.body(base).getSize2D();
        float label = UiTokens.label(base).getSize2D();
        float caption = UiTokens.caption(base).getSize2D();

        assertTrue(title > heading, "title must exceed heading");
        assertTrue(heading > body, "heading must exceed body");
        assertTrue(body > label, "body must exceed label");
        assertTrue(label > caption, "label must exceed caption");
        assertEquals(17f, title);
        assertEquals(12f, caption);
    }

    @Test
    void typeRampClampsAtMinimumSize() {
        Font tiny = new Font(Font.DIALOG, Font.PLAIN, 9);
        assertTrue(UiTokens.caption(tiny).getSize2D() >= 10f,
                "caption must never drop below the 10pt floor");
    }

    @Test
    void rampPreservesBoldnessIntents() {
        Font base = new Font(Font.DIALOG, Font.PLAIN, 14);
        assertTrue(UiTokens.title(base).isBold());
        assertTrue(UiTokens.heading(base).isBold());
        assertTrue(UiTokens.label(base).isBold());
        assertTrue(!UiTokens.body(base).isBold());
    }
}
