package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.gui.render.MarkdownParserHolder;
import org.gitee.jmeter.ai.gui.render.UiThemeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyledDocument;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.util.function.BooleanSupplier;

/**
 * Processes and formats messages for display in the chat interface (HTML render path).
 *
 * <p>The chat area runs with content type {@code "text/html"}. Markdown is converted to
 * HTML via Flexmark's {@link com.vladsch.flexmark.html.HtmlRenderer} (see
 * {@link MarkdownParserHolder#renderToHtml(String)}) and appended to the document body.
 * Plain text is HTML-escaped and wrapped in a styled {@code <div>}.
 *
 * <p>Base font/colors come from the {@link javax.swing.text.html.StyleSheet} configured by
 * {@code AiChatPanel}; {@link #setBaseFont(Font)} is retained for API compatibility but is
 * not used by the HTML path.
 */
public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    /**
     * Smart-scroll collaborators, wired by {@code AiChatPanel} once the chat scroll pane exists.
     * {@code atBottomProbe} reports whether the viewport is currently pinned to the bottom
     * (captured <em>before</em> each append); {@code scrollToBottom} is then invoked after the
     * append so the latest content is revealed. Both are null in headless tests → no scrolling.
     */
    private BooleanSupplier atBottomProbe;
    private Runnable scrollToBottom;

    /**
     * Enable smart auto-scroll: when a probe reports the viewport is pinned to the bottom at the
     * moment content is appended, the runner is called to reveal the new content. Passing
     * {@code null}s disables the behavior (default).
     */
    public void setAutoScroll(BooleanSupplier atBottomProbe, Runnable scrollToBottom) {
        this.atBottomProbe = atBottomProbe;
        this.scrollToBottom = scrollToBottom;
    }

    /**
     * No-op on the HTML render path (colors/fonts come from the StyleSheet configured by
     * AiChatPanel). Retained for API compatibility with existing call sites.
     */
    public void setBaseFont(Font font) {
        // intentionally empty
    }

    /**
     * Append a message. When {@code parseMarkdown} is true the text is rendered as markdown
     * (Flexmark → HTML); otherwise it is appended as HTML-escaped styled text.
     */
    public void appendMessage(StyledDocument doc, String message, Color color, boolean parseMarkdown) throws BadLocationException {
        if (parseMarkdown) {
            appendMarkdown(doc, message, color);
        } else {
            appendStyled(doc, message, color);
        }
    }

    /**
     * Render markdown to HTML and append it to the document body.
     *
     * <p>{@code fg} is retained for API compatibility but intentionally unused: ordinary
     * message text inherits the body color from the StyleSheet so it follows the JMeter
     * theme (light/dark). Callers needing a fixed semantic color should use
     * {@link #appendStyled(StyledDocument, String, Color)} instead.
     */
    public void appendMarkdown(StyledDocument doc, String markdown, Color fg) throws BadLocationException {
        String html = MarkdownParserHolder.renderToHtml(markdown);
        appendHtml(doc, "<div>" + html + "</div>");
    }

    /**
     * Append plain styled text (HTML-escaped; newlines become {@code <br>}).
     *
     * <p>Pass {@code fg == null} for ordinary text so it inherits the body color and follows
     * the JMeter theme; pass a non-null color for fixed semantic colors (errors, status, etc.).
     */
    public void appendStyled(StyledDocument doc, String text, Color fg) throws BadLocationException {
        String styleAttr = fg != null ? " style=\"color:" + UiThemeUtil.toHex(fg) + "\"" : "";
        appendHtml(doc, "<div" + styleAttr + ">" + escapeHtml(text).replace("\n", "<br>") + "</div>");
    }

    /**
     * Append plain styled text with a Swing font style bit ({@link Font#BOLD}/{@link Font#ITALIC}).
     *
     * <p>As with {@link #appendStyled(StyledDocument, String, Color)}, {@code fg == null} means
     * "inherit the themed body color"; a non-null color is rendered inline (semantic colors).
     */
    public void appendStyled(StyledDocument doc, String text, Color fg, int fontStyle) throws BadLocationException {
        StringBuilder style = new StringBuilder();
        if (fg != null) {
            style.append("color:").append(UiThemeUtil.toHex(fg)).append(";");
        }
        if ((fontStyle & Font.BOLD) != 0) {
            style.append("font-weight:bold;");
        }
        if ((fontStyle & Font.ITALIC) != 0) {
            style.append("font-style:italic;");
        }
        String styleAttr = style.length() > 0 ? " style=\"" + style + "\"" : "";
        appendHtml(doc, "<div" + styleAttr + ">" + escapeHtml(text).replace("\n", "<br>") + "</div>");
    }

    /** Append an HTML fragment to the end of the document body. */
    public void appendHtml(StyledDocument doc, String htmlFragment) throws BadLocationException {
        // Capture "pinned to bottom" BEFORE mutating the document: the viewport's current
        // position tells us whether the user is following the tail (→ reveal new content)
        // or reading history (→ leave their position untouched). Reading it post-append
        // would be useless since the new content itself pushes the viewport off the bottom.
        boolean pinToBottom = atBottomProbe != null && atBottomProbe.getAsBoolean();
        if (!(doc instanceof HTMLDocument)) {
            // Fallback if the document is not HTML-backed: insert as plain text.
            doc.insertString(doc.getLength(), htmlFragment, null);
        } else {
            HTMLDocument htmlDoc = (HTMLDocument) doc;
            Element body = findBody(htmlDoc.getDefaultRootElement());
            if (body == null) {
                body = htmlDoc.getDefaultRootElement();
            }
            try {
                htmlDoc.insertBeforeEnd(body, htmlFragment);
                // HTMLDocument is born with (and re-creates after a clear) an implied empty
                // paragraph — a lone '\n' at the very start of body. Since content is appended
                // after it, the first message would otherwise render with a blank line above it.
                // Strip it; safe on every call because it only removes an empty paragraph that
                // precedes all real content (present only right after a reset).
                stripLeadingEmptyParagraph(htmlDoc, body);
            } catch (java.io.IOException e) {
                log.warn("Failed to append HTML fragment to chat document", e);
                pinToBottom = false; // nothing was appended → nothing to scroll to
            }
        }
        if (pinToBottom && scrollToBottom != null) {
            scrollToBottom.run();
        }
    }

    private static void stripLeadingEmptyParagraph(HTMLDocument doc, Element body) {
        if (body == null || body.getElementCount() == 0) {
            return;
        }
        Element first = body.getElement(0);
        if (first == null) {
            return;
        }

        // The leading body child is a strippable placeholder when its text content is all
        // whitespace — regardless of element name. After setText("") the body retains an
        // empty <div> (leftover from the prior message) or an implied <p>, each holding a
        // lone '\n'; without stripping it, the first message renders with a blank line
        // above it. Only the leading element is considered, and only when empty, so real
        // first-message content (always non-empty) is never touched.
        int start = first.getStartOffset();
        int len = first.getEndOffset() - start;
        if (len <= 0) {
            return;
        }
        try {
            if (doc.getText(start, len).trim().isEmpty()) {
                doc.remove(start, len);
            }
        } catch (BadLocationException e) {
            log.debug("Skipping leading empty paragraph strip: {}", e.getMessage());
        }
    }

    private static Element findBody(Element e) {
        if (e == null) {
            return null;
        }
        if ("body".equalsIgnoreCase(e.getName())) {
            return e;
        }
        for (int i = 0; i < e.getElementCount(); i++) {
            Element found = findBody(e.getElement(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Append the "AI is thinking..." loading indicator with a stable id for later removal. */
    public void appendLoadingIndicator(StyledDocument doc, Color fg) throws BadLocationException {
        appendHtml(doc, "<div id=\"ai-loading\" style=\"color:" + UiThemeUtil.toHex(fg)
                + ";font-style:italic;margin:0\">AI is thinking...</div>");
    }

    /**
     * Remove the loading indicator by its id. Uses {@link HTMLDocument#getElement(String)} +
     * clearing inner HTML, which is reliable regardless of the element's position in the document
     * (unlike text-search + remove, which is fragile near the document end on HTMLDocument).
     */
    public void removeLoadingIndicator(StyledDocument doc) throws BadLocationException {
        // Locate the literal indicator text and remove that range. This mirrors the original
        // StyledDocument behaviour and works on HTMLDocument too (which extends
        // DefaultStyledDocument). setInnerHTML(elem,"") turned out to be a no-op visually under
        // HTMLEditorKit, so plain remove is used instead. Length is clamped to document bounds
        // to avoid HTMLDocument's trailing-implied-char boundary error.
        String full = doc.getText(0, doc.getLength());
        int idx = full.lastIndexOf("AI is thinking...");
        if (idx < 0) {
            log.info("Loading indicator text not found in document");
            return;
        }
        int end = idx + "AI is thinking...".length();
        // Also remove trailing newline(s) left by the indicator block so no blank gap remains.
        while (end < full.length() && (full.charAt(end) == '\n' || full.charAt(end) == '\r')) {
            end++;
        }
        int len = Math.min(end - idx, doc.getLength() - idx);
        if (len <= 0) {
            return;
        }
        try {
            doc.remove(idx, len);
            log.info("Loading indicator removed at offset {} len {}", idx, len);
        } catch (BadLocationException ex) {
            log.warn("Loading indicator remove failed at {} len {}: {}", idx, len, ex.getMessage());
        }
    }
}
