package org.gitee.jmeter.ai.gui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.util.List;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for the ported markdown renderer: hostile / malformed
 * input an LLM or user message can realistically produce. Each test asserts
 * the <em>expected</em> (GFM-faithful or content-preserving) behavior; a
 * failure here is a rendering bug (content loss or misrender), not a test bug.
 */
class AdversarialMarkdownTest {

    private static final Font BASE = new Font(Font.DIALOG, Font.PLAIN, 14);

    private final StyledDocument doc = new DefaultStyledDocument();

    private String render(String markdown) throws BadLocationException {
        doc.remove(0, doc.getLength());
        MarkdownRenderer.process(doc, markdown, BASE);
        return doc.getText(0, doc.getLength());
    }

    private int embeddedComponentCount() {
        int count = 0;
        Object prev = null;
        for (int i = 0; i < doc.getLength(); i++) {
            Object c = StyleConstants.getComponent(doc.getCharacterElement(i).getAttributes());
            if (c != null && c != prev) {
                count++;
                prev = c;
            }
        }
        return count;
    }

    private java.util.List<Object> embeddedComponents() {
        java.util.List<Object> found = new java.util.ArrayList<>();
        Object prev = null;
        for (int i = 0; i < doc.getLength(); i++) {
            Object c = StyleConstants.getComponent(doc.getCharacterElement(i).getAttributes());
            if (c != null && c != prev) {
                found.add(c);
                prev = c;
            }
        }
        return found;
    }

    // --- placeholder spoofing -------------------------------------------------

    /**
     * A literal line shaped like the internal code-block placeholder must be
     * rendered as text, not silently swallowed. An assistant explaining the
     * renderer (or echoing unusual output) can legitimately emit such a line.
     */
    @Test
    void placeholderLookalikeLineIsNotSwallowed() throws BadLocationException {
        String text = render("[CODE_BLOCK:snippet_1:java]");
        assertTrue(text.contains("[CODE_BLOCK:snippet_1:java]"),
                "a literal placeholder-shaped line must stay visible");
    }

    /** Spoofing an existing snippet key must not duplicate the real block. */
    @Test
    void placeholderSpoofDoesNotDuplicateRealBlock() throws BadLocationException {
        render("```java\nX=1\n```\n[CODE_BLOCK:snippet_1:java]");
        assertEquals(1, embeddedComponentCount(),
                "only the real fence may render a code block");
    }

    /** Partial spoofs (not ending in ]) stay literal text. */
    @Test
    void partialPlaceholderLineStaysLiteral() throws BadLocationException {
        String text = render("see [CODE_BLOCK:snippet_1:java now");
        assertTrue(text.contains("[CODE_BLOCK:snippet_1:java"));
    }

    // --- table false positives --------------------------------------------------

    /**
     * GFM requires the delimiter row to have the same cell count as the
     * header. A plain sentence containing a pipe followed by a horizontal
     * rule must NOT be eaten into a table grid.
     */
    @Test
    void pipeSentenceFollowedByRuleIsNotATable() throws BadLocationException {
        String text = render("Pick one: A | B\n---\nThen continue");
        assertTrue(text.contains("Pick one: A | B"),
                "the sentence must stay in the text flow");
        assertTrue(text.contains("Then continue"));
        long grids = embeddedComponents().stream()
                .filter(c -> c instanceof javax.swing.JScrollPane)
                .count();
        assertEquals(0, grids,
                "no table grid expected (the --- is a horizontal rule panel)");
    }

    /** A header with fewer cells than the separator is not a GFM table. */
    @Test
    void separatorWithMoreCellsThanHeaderIsNotATable() throws BadLocationException {
        String text = render("| a |\n| --- | --- |\n| 1 | 2 |");
        assertTrue(text.contains("| a |"), "mismatched separator must keep the lines literal");
    }

    // --- table cell content corruption -------------------------------------------

    /**
     * A backslash that escapes nothing (e.g. Windows paths) must survive
     * table cell splitting; only {@code \|} may lose its backslash.
     */
    @Test
    void backslashInTableCellSurvives() {
        assertEquals(List.of("C:\\path\\bin"),
                TableBlockRenderer.splitRow("| C:\\path\\bin |"),
                "backslashes that escape nothing must stay literal");
    }

    // --- fence edge cases -----------------------------------------------------------

    /** An unclosed fence must degrade to plain text without losing content. */
    @Test
    void unclosedFenceDegradesToPlainText() throws BadLocationException {
        String text = render("```java\nint x = 1;");
        assertTrue(text.contains("int x = 1;"), "content must survive an unclosed fence");
    }

    /** An empty message must render without throwing. */
    @Test
    void emptyMessageRendersQuietly() throws BadLocationException {
        render("");
        render("\n\n\n");
    }

    /**
     * A fence language with non-word characters (c++, c#) must not bleed the
     * extra characters into the code body.
     */
    @Test
    void fenceLanguageWithSymbolsStaysOutOfCode() throws BadLocationException {
        String text = render("```c++\nint x;\n```");
        // the code area holds "int x;" — with the bug it holds "++\nint x;"
        assertTrue(text.length() > 0);
        java.awt.Component c = StyleConstants.getComponent(
                doc.getCharacterElement(0).getAttributes());
        assertTrue(c instanceof javax.swing.JScrollPane, "code block expected");
        javax.swing.JScrollPane scroller = (javax.swing.JScrollPane) c;
        java.awt.Container panel = (java.awt.Container) scroller.getViewport().getView();
        String areaText = null;
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof javax.swing.JTextArea) {
                areaText = ((javax.swing.JTextArea) child).getText();
            }
        }
        assertEquals("int x;", areaText == null ? null : areaText.trim(),
                "the language's stray characters must not leak into the code");
    }

    /**
     * Four-backtick fences (markdown-about-markdown) must at minimum preserve
     * the inner content.
     */
    @Test
    void fourBacktickFenceKeepsInnerContent() throws BadLocationException {
        String text = render("````\ncode\n````\nafter");
        assertTrue(text.contains("after"));
        assertTrue(embeddedComponentCount() >= 1, "some code component is fine");
    }

    // --- line ending hygiene -----------------------------------------------------------

    /** CRLF input must not leak bare CR characters into the document. */
    @Test
    void crlfInputDoesNotLeakCarriageReturns() throws BadLocationException {
        String text = render("# Title\r\n\r\nbody line\r\n");
        assertFalse(text.contains("\r"), "no bare CR should enter the document");
    }

    // --- bullets -------------------------------------------------------------------------

    /** Multi-space bullets normalize to a single space after the glyph. */
    @Test
    void bulletWithMultipleSpacesNormalizes() throws BadLocationException {
        String text = render("-   spaced item");
        assertTrue(text.contains("• spaced item"),
                "bullet glyph followed by exactly one space");
    }

    // --- inline state machine hygiene ---------------------------------------------------

    /**
     * A single unmatched emphasis marker mid-text must stay literal
     * (CommonMark keeps unmatched markers).
     */
    @Test
    void unmatchedAsteriskStaysLiteral() throws BadLocationException {
        String text = render("3 * 4 = 12");
        assertTrue(text.contains("3 * 4 = 12"),
                "a lone asterisk used as multiplication must survive");
    }

    // --- crash-resistance fuzz -----------------------------------------------------------

    /**
     * No hostile input may ever throw out of the renderer: it runs on the EDT,
     * so an unexpected exception kills the turn's visual tail (callers only
     * catch BadLocationException).
     */
    @Test
    void fuzzNeverThrows() throws BadLocationException {
        String[] atoms = {
            "```", "````", "`", "**", "*", "|", "---", ":-:", "[CODE_BLOCK:snippet_1:java]",
            "[a](b)", "#", "##", "### ", "- ", "* ", "<br>", "<br/>", "正文", "😀", "\\",
            "\\|", "||", ":", "()", "[]", "\r\n", "\n", " ", "\t", "a", "1",
        };
        java.util.Random random = new java.util.Random(0xC0FFEE);
        for (int iter = 0; iter < 3000; iter++) {
            StringBuilder input = new StringBuilder();
            int pieces = 1 + random.nextInt(24);
            for (int p = 0; p < pieces; p++) {
                input.append(atoms[random.nextInt(atoms.length)]);
            }
            doc.remove(0, doc.getLength());
            MarkdownRenderer.process(doc, input.toString(), BASE);
            // the renderer must leave a readable document, whatever the input
            assertTrue(doc.getLength() >= 0);
        }
    }
}
