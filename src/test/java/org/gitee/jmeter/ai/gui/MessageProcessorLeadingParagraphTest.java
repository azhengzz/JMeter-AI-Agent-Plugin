package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.text.StyledDocument;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "blank line above the first message" bug.
 *
 * <p>After a document clear ({@code setText("")} = {@code doc.remove(0, len)}), the body
 * retains an empty element — an implied {@code <p>} on a fresh document, or an emptied
 * {@code <div>} (leftover from the prior message) after a real clear — holding a lone
 * {@code '\n'}. Because content is appended <em>after</em> it, the first chat message would
 * otherwise render with a blank line above its first heading. {@link
 * MessageProcessor#appendHtml} must strip any leading whitespace-only element regardless of
 * its name. Uses a real {@code HTMLDocument} (headless-safe; no display needed).
 */
class MessageProcessorLeadingParagraphTest {

    private static StyledDocument newHtmlDoc() {
        return (HTMLDocument) new HTMLEditorKit().createDefaultDocument();
    }

    /** Reproduce {@code startNewConversation()}: populate, then {@code setText("")}. */
    private static StyledDocument clearedAfterPopulation() throws Exception {
        HTMLEditorKit kit = new HTMLEditorKit();
        HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
        kit.read(new StringReader("<html><body><div><p>prior message</p></div></body></html>"), doc, 0);
        doc.remove(0, doc.getLength());
        return doc;
    }

    @Test
    void firstAppendHasNoLeadingBlankLine() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        mp.appendHtml(doc, "<div><h1>Welcome</h1><p>body</p></div>");

        String text = doc.getText(0, doc.getLength());
        assertFalse(text.startsWith("\n"), "first message must not start with a blank line: " + escape(text));
        assertTrue(text.contains("Welcome"), "content present: " + escape(text));
    }

    @Test
    void firstAppendAfterClearHasNoLeadingBlankLine() throws Exception {
        // Regression for the "New Chat" button: after setText(""), the body holds an empty
        // <div> with '\n' (the prior message's wrapper). The welcome message must not render
        // a blank line above it.
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = clearedAfterPopulation();

        mp.appendHtml(doc, "<div><h1>Welcome</h1><p>body</p></div>");

        String text = doc.getText(0, doc.getLength());
        assertFalse(text.startsWith("\n"), "welcome after clear must not start with a blank line: " + escape(text));
        assertTrue(text.contains("Welcome"), "content present: " + escape(text));
    }

    @Test
    void subsequentAppendKeepsPriorContentIntact() throws Exception {
        MessageProcessor mp = new MessageProcessor();
        StyledDocument doc = newHtmlDoc();

        mp.appendHtml(doc, "<div><p>first</p></div>");
        mp.appendHtml(doc, "<div><p>second</p></div>");

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("first") && text.contains("second"), "both messages kept: " + escape(text));
        assertFalse(text.startsWith("\n"), "still no leading blank line: " + escape(text));
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n");
    }
}
