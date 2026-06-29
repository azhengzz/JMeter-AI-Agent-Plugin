package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.gui.render.MarkdownParserHolder;
import org.gitee.jmeter.ai.gui.render.UiThemeUtil;
import org.junit.jupiter.api.Test;

import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyledDocument;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for the HTML render path: {@link MessageProcessor} emits HTML, and
 * {@link MarkdownParserHolder#renderToHtml(String)} produces the HTML from markdown.
 *
 * <p>When the target document is NOT an {@link javax.swing.text.html.HTMLDocument} (as in these
 * headless tests), {@code MessageProcessor} falls back to inserting the raw HTML string, which
 * lets us assert on the produced HTML text without a real HTML view.
 */
class MessageProcessorApiTest {

    @Test
    void renderToHtmlProducesBold() {
        assertTrue(MarkdownParserHolder.renderToHtml("**hi**").contains("<strong>hi</strong>"),
                "bold should become <strong>");
    }

    @Test
    void renderToHtmlProducesHeading() {
        assertTrue(MarkdownParserHolder.renderToHtml("# Title").toLowerCase().contains("<h1"),
                "H1 should become <h1>");
    }

    @Test
    void renderToHtmlProducesTable() {
        String html = MarkdownParserHolder.renderToHtml("| a | b |\n|---|---|\n| 1 | 2 |");
        assertTrue(html.contains("<table"), "table present: " + html);
        assertTrue(html.contains("<th"), "header cells present: " + html);
    }

    @Test
    void appendMarkdownAppendsHtmlFragment() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument(); // non-HTMLDocument → fallback raw insert
        mp.appendMarkdown(doc, "**hi**", null);
        assertTrue(doc.getText(0, doc.getLength()).contains("<strong>hi</strong>"),
                "markdown HTML appended: " + doc.getText(0, doc.getLength()));
    }

    @Test
    void appendStyledEscapesHtml() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();
        mp.appendStyled(doc, "<b>not bold</b> & more", null);
        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("&lt;b&gt;"), "angle brackets escaped: " + text);
        assertFalse(text.contains("<b>not bold</b>"), "no raw markup: " + text);
    }

    @Test
    void appendStyledAppliesBoldStyle() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();
        mp.appendStyled(doc, "x", null, Font.BOLD);
        assertTrue(doc.getText(0, doc.getLength()).contains("font-weight:bold"),
                "bold style in CSS: " + doc.getText(0, doc.getLength()));
    }

    @Test
    void codeBlockBackgroundAvailableFromThemeUtil() {
        assertNotNull(UiThemeUtil.getCodeBlockBackground());
    }

    @Test
    void softLineBreakRendersAsBr() {
        // Single newlines (e.g. /status output) must become <br>, not collapse to a space.
        String html = MarkdownParserHolder.renderToHtml("line1\nline2");
        assertTrue(html.contains("<br"), "soft break should render as <br>: " + html);
    }

    @Test
    void autoScrollRunnerFiresOnlyWhenPinnedToBottom() throws Exception {
        // Smart-scroll: the runner (scroll-to-bottom) must fire once per appended block when the
        // probe reports the viewport pinned to the bottom, and not at all when it does not.
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument(); // non-HTMLDocument → fallback insert path
        int[] scrollCount = {0};

        mp.setAutoScroll(() -> true, () -> scrollCount[0]++);
        mp.appendHtml(doc, "<div>a</div>");
        mp.appendHtml(doc, "<div>b</div>");
        assertEquals(2, scrollCount[0], "runner fires once per append while pinned to bottom");

        // User scrolled away from the bottom → probe reports false → runner must not fire.
        mp.setAutoScroll(() -> false, () -> scrollCount[0]++);
        mp.appendHtml(doc, "<div>c</div>");
        assertEquals(2, scrollCount[0], "runner does not fire when not pinned to bottom");

        // No collaborators wired → no scrolling at all (headless default).
        MessageProcessor bare = new MessageProcessor();
        bare.appendHtml(doc, "<div>d</div>"); // must not throw
    }
}
