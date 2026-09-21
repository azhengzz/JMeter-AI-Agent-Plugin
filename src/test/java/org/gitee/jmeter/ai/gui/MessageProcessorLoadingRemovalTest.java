package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.Color;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "injected line turns gray italic after AI output" bug.
 *
 * <p>In an {@code HTMLDocument} every block's content leaf ends with its paragraph terminator
 * ({@code '\n'}). When a user message is injected mid-turn, the green italic
 * {@code "[Injected] You: ..."} div is appended <em>after</em> the loading indicator's div, and
 * the first non-USAGE progress then removes that indicator. If the removal range extends
 * <em>forward</em> over the indicator's trailing {@code '\n'}, the paragraph terminator is
 * deleted and HTMLDocument merges the paragraphs: the following green div is absorbed into the
 * indicator's element and inherits its attributes ({@code Label.disabledForeground} gray +
 * italic + {@code margin:0}), so the injected line renders gray italic and the stale
 * {@code id="ai-loading"} div survives. {@link MessageProcessor#removeLoadingIndicator} must
 * therefore consume the <em>preceding</em> newline and keep the trailing terminator.
 *
 * <p>Uses a real {@code HTMLDocument} (headless-safe; no display needed).
 */
class MessageProcessorLoadingRemovalTest {

    /** The literal text {@code appendLoadingIndicator} writes, and the search key for removal. */
    private static final String INDICATOR = "AI is thinking...";

    /** Green used by {@code AiChatPanel} for the injected-line echo, italic like the indicator. */
    private static final Color INJECTED_GREEN = new Color(0x00, 0x80, 0x00);

    /** Gray used by {@code AiChatPanel.armActiveTurn} for the loading indicator. */
    private static final Color INDICATOR_GRAY = new Color(0x99, 0x99, 0x99);

    private static StyledDocument newHtmlDoc() {
        return (HTMLDocument) new HTMLEditorKit().createDefaultDocument();
    }

    /**
     * Reproduce the production sequence for a turn with a mid-turn injection:
     * the user line, the armed loading indicator, then the injected echo — all appended
     * before any AI output arrives.
     */
    private static void appendTurnWithInjection(MessageProcessor mp, StyledDocument doc) throws Exception {
        mp.appendMessage(doc, "You: hello", null, false);
        mp.appendLoadingIndicator(doc, INDICATOR_GRAY);
        mp.appendStyled(doc, "[Injected] You: 1--1", INJECTED_GREEN, Font.ITALIC);
    }

    @Test
    void removalKeepsInjectedLineGreenItalic() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        onEdt(() -> appendTurnWithInjection(mp, doc));
        onEdt(() -> mp.removeLoadingIndicator(doc));

        int offset = doc.getText(0, doc.getLength()).indexOf("[Injected]");
        assertTrue(offset >= 0, "injected line must survive the removal: " + escape(text(doc)));

        Color fg = effectiveForeground(doc.getCharacterElement(offset));
        assertNotNull(fg, "injected line must inherit an explicit color, not the body default");
        assertEquals(INJECTED_GREEN, fg, "injected line must stay green (not absorb the indicator gray)");
        assertTrue(isItalic(doc.getCharacterElement(offset)), "injected line stays italic");
    }

    @Test
    void removalLeavesNoStaleIndicatorElement() throws Exception {
        // The absorption bug also kept the indicator's div alive (id="ai-loading") holding the
        // injected text; a later getElement("ai-loading") would then resolve to that zombie.
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        onEdt(() -> appendTurnWithInjection(mp, doc));
        onEdt(() -> mp.removeLoadingIndicator(doc));

        assertNull(((HTMLDocument) doc).getElement("ai-loading"),
                "indicator element must be fully removed: " + escape(text(doc)));
        assertFalse(text(doc).contains(INDICATOR), "indicator text must be gone: " + escape(text(doc)));
    }

    @Test
    void removalLeavesNoBlankLine() throws Exception {
        // Consuming the PRECEDING newline (rather than the trailing one) must not leave an
        // empty paragraph rendering a blank gap where the indicator used to be.
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        onEdt(() -> appendTurnWithInjection(mp, doc));
        onEdt(() -> mp.removeLoadingIndicator(doc));

        String text = text(doc);
        assertFalse(text.contains("\n\n"), "no blank line left behind: " + escape(text));
        assertTrue(text.startsWith("You: hello\n[Injected]"), "lines stay adjacent: " + escape(text));
    }

    @Test
    void removalOnFreshDocumentWithoutIndicatorIsNoOp() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        onEdt(() -> mp.appendMessage(doc, "You: hello", null, false));
        onEdt(() -> mp.removeLoadingIndicator(doc));

        assertTrue(text(doc).contains("You: hello"), "content untouched when no indicator present");
    }

     /**
     * Contract test for the indicator-first document shape: here nothing precedes the indicator, so
     * the backward consumption is a no-op and the removal stops at the text itself, leaving the
     * indicator's now-empty div behind. That empty block still occupies vertical space (its
     * {@code div} margin) — a known, accepted artifact, because dropping the remnant is worse:
  * removing it by any means tried (element-range remove, or removing only its content) deletes a
  * paragraph terminator and re-triggers the very merge this fix exists to prevent, turning the
  * injected line gray again. So for this shape the choice is "green text + one blank line" or
  * "correct spacing + gray text", and the fix deliberately takes the former.
  *
  * <p>Note this shape is <em>not</em> reachable in production: the panel always shows its welcome
  * message first (constructor + every reset path call {@code displayWelcomeMessage}), so a real
  * transcript never starts with the indicator — including on REPUBLISH turns, which skip the
  * {@code You} echo but still follow the welcome message. This test therefore pins the contract
  * (the injected line keeps its OWN styling instead of inheriting the indicator's) for a shape
  * kept defensively, not one a user can drive.
  */
    @Test
    void removalKeepsInjectedLineStyledWhenIndicatorIsFirstBlock() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        onEdt(() -> {
            mp.appendLoadingIndicator(doc, INDICATOR_GRAY);
            mp.appendStyled(doc, "[Injected] You: 1--1", INJECTED_GREEN, Font.ITALIC);
        });
        onEdt(() -> mp.removeLoadingIndicator(doc));

        String text = text(doc);
        int offset = text.indexOf("[Injected]");
        assertTrue(offset >= 0, "injected line must survive: " + escape(text));
        assertEquals(INJECTED_GREEN, effectiveForeground(doc.getCharacterElement(offset)),
                "injected line must keep its own green, not inherit the indicator gray");
        assertTrue(isItalic(doc.getCharacterElement(offset)), "injected line stays italic");
        assertFalse(text.contains(INDICATOR), "indicator text must be gone: " + escape(text));
    }

    /** Effective foreground walking up from a leaf, honoring inline CSS + Swing style attrs. */
    private static Color effectiveForeground(Element leaf) {
        for (Element e = leaf; e != null; e = e.getParentElement()) {
            AttributeSet attrs = e.getAttributes();
            Object css = attrs.getAttribute(HTML.Attribute.COLOR);
            if (css instanceof Color c) {
                return c;
            }
            if (css instanceof String s) {
                Color parsed = parseHex(s);
                if (parsed != null) {
                    return parsed;
                }
            }
            Object fg = attrs.getAttribute(StyleConstants.Foreground);
            if (fg instanceof Color c) {
                return c;
            }
        }
        return null;
    }

    /** Whether italic is set on the leaf or any ancestor. */
    private static boolean isItalic(Element leaf) {
        for (Element e = leaf; e != null; e = e.getParentElement()) {
            AttributeSet attrs = e.getAttributes();
            Object css = attrs.getAttribute(HTML.Attribute.STYLE);
            if (css instanceof String s && s.toLowerCase().contains("italic")) {
                return true;
            }
            Object italic = attrs.getAttribute(StyleConstants.Italic);
            if (Boolean.TRUE.equals(italic)) {
                return true;
            }
        }
        return false;
    }

    private static Color parseHex(String value) {
        String v = value.trim();
        if (v.startsWith("#") && (v.length() == 7 || v.length() == 4)) {
            try {
                return Color.decode(v.length() == 4
                        ? "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3)
                        : v);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(StyledDocument doc) throws Exception {
        return doc.getText(0, doc.getLength());
    }

    /** EDT 护栏对等：MessageProcessor 文档变更入口已加 isDispatchThread 断言，测试调用须上 EDT。 */
    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n");
    }
}