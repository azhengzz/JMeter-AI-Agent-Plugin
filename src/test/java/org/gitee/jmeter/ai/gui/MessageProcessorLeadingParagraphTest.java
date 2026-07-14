package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.text.StyledDocument;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "blank line above the first message" bug.
 *
 * <p>An {@link HTMLDocument} is born with (and re-creates after a clear) an implied empty
 * paragraph — a lone {@code '\n'} at the very start of {@code <body>}. Because content is
 * appended <em>after</em> it, the first chat message would otherwise render with a blank
 * line above its first heading. {@link MessageProcessor#appendHtml} must strip that leading
 * empty paragraph. Uses a real {@code HTMLDocument} (headless-safe; no display needed).
 */
class MessageProcessorLeadingParagraphTest {

    private static StyledDocument newHtmlDoc() {
        return (HTMLDocument) new HTMLEditorKit().createDefaultDocument();
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
