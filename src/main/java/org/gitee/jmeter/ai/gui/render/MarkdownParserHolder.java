package org.gitee.jmeter.ai.gui.render;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.Arrays;

/**
 * Holds the thread-safe Flexmark {@link Parser} + {@link HtmlRenderer} singletons used by
 * the chat's HTML render path. Both are built once and reused (the built objects are safe
 * for concurrent use).
 *
 * <p>The typographic extension is intentionally NOT enabled — it converts {@code --} to
 * en-dashes and straight quotes to curly quotes, which corrupts code samples.
 */
public final class MarkdownParserHolder {

    private static final Parser PARSER;
    private static final HtmlRenderer HTML_RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create()));
        // Render soft line breaks (single '\n') as <br>. Chat content like the /status
        // output uses single newlines between fields; without this HTML collapses them to
        // spaces. Real markdown is unaffected — paragraphs use blank lines, and lists /
        // code / tables are structural rather than soft-break driven.
        options.set(HtmlRenderer.SOFT_BREAK, "<br>\n");
        PARSER = Parser.builder(options).build();
        HTML_RENDERER = HtmlRenderer.builder(options).build();
    }

    private MarkdownParserHolder() {
    }

    /** Render markdown source to an HTML fragment (for the JEditorPane text/html path). */
    public static String renderToHtml(String markdown) {
        return HTML_RENDERER.render(PARSER.parse(markdown == null ? "" : markdown));
    }
}
