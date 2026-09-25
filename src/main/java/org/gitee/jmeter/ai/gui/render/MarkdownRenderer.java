package org.gitee.jmeter.ai.gui.render;

import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders markdown text into a {@link StyledDocument} for the chat transcript.
 *
 * <p>Supported formatting: fenced code blocks (rendered as embedded panels with
 * a Copy button via {@link CodeBlockRenderer}), tables (embedded grid via
 * {@link TableBlockRenderer}), headings ({@code #}..{@code ###}), bold, italic,
 * inline code, unordered list bullets ({@code - }/{@code * }, top-level only),
 * horizontal rules ({@code ---}), and links ({@code [label](url)}) shown as
 * accented underlined labels.
 *
 * <p>Deliberately out of scope (rendered as plain text, matching the reference
 * implementation): nested list levels, ordered lists ({@code 1. }) and
 * blockquotes ({@code &gt;}). Ordinary text deliberately sets no font family so
 * it inherits the hosting component's composite font (CJK-safe); heading sizes
 * are relative to {@code baseFont} so they follow the chat font zoom.
 */
public final class MarkdownRenderer {

    private static final Logger log = LoggerFactory.getLogger(MarkdownRenderer.class);

    // The optional newline groups fold the fence's immediately-adjacent line
    // breaks into the match; the replacement spends exactly those two breaks
    // placing the placeholder on its own lines, so the blank-line count around
    // an embedded block always mirrors the source text instead of growing by
    // one on each side.
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
        "(\\n?)```([\\w+#-]*)\\s*([\\s\\S]*?)```(\\n?)"
    );
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[-*]\\s+.*");
    private static final Pattern HR_PATTERN = Pattern.compile(
        "^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$"
    );
    private static final Pattern LINK_PATTERN = Pattern.compile(
        "\\[([^\\]]+)\\]\\(([^)]+)\\)"
    );

    private MarkdownRenderer() {
    }

    /**
     * Processes a markdown message and applies formatting to the document.
     *
     * @param doc      the document to render into
     * @param message  the markdown message to process
     * @param baseFont the component's base font (heading sizes scale from it;
     *                 {@code null} falls back to the Label.font default)
     * @throws BadLocationException if a document location is invalid
     */
    public static void process(StyledDocument doc, String message, Font baseFont)
        throws BadLocationException {
        log.debug("Processing markdown message");

        // Windows/old-Mac line endings become \n: the line-based phase splits
        // on \n only, so a bare \r would leak into the document as a glyph.
        message = message.replace("\r\n", "\n").replace('\r', '\n');

        // Phase 1: extract fenced code blocks, replace with placeholder lines.
        // Manual assembly instead of appendReplacement: the match absorbs the
        // newline directly before and after the fence, and the placeholder
        // spends one newline on each side to stand on its own lines - a
        // like-for-like swap, so the blank lines the author wrote around the
        // block survive verbatim instead of stacking with placeholder breaks.
        // A leading break is only re-spent when the match actually absorbed
        // one, or when the fence sat mid-line and the placeholder still needs
        // pushing onto a line of its own.
        Map<String, String> codeSnippets = new HashMap<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(message);
        StringBuilder sb = new StringBuilder();
        int codeBlockCount = 0;
        int copiedUpTo = 0;
        while (matcher.find()) {
            codeBlockCount++;
            String language = matcher.group(2).trim();
            String snippetKey = "snippet_" + codeBlockCount;
            codeSnippets.put(snippetKey, matcher.group(3));

            sb.append(message, copiedUpTo, matcher.start());
            boolean absorbedLeadingBreak = matcher.group(1).length() > 0;
            boolean midLine = sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n';
            if (absorbedLeadingBreak || midLine) {
                sb.append('\n');
            }
            sb.append("[CODE_BLOCK:").append(snippetKey).append(':')
                .append(language).append(']').append('\n');
            copiedUpTo = matcher.end();
        }
        sb.append(message, copiedUpTo, message.length());

        // Phase 2: line-based formatting + inline state machine
        processBasicMarkdown(doc, sb.toString(), baseFont, codeSnippets);
    }

    private static void processBasicMarkdown(
        StyledDocument doc,
        String text,
        Font baseFont,
        Map<String, String> codeSnippets
    ) throws BadLocationException {
        // HTML breaks emitted by some models render as newlines instead of leaking
        text = replaceHtmlBreaksOutsideCode(text);
        String[] lines = text.split("\n");

        int baseSize = baseFont != null ? Math.round(baseFont.getSize2D()) : 12;

        SimpleAttributeSet normal = new SimpleAttributeSet();

        SimpleAttributeSet bold = new SimpleAttributeSet(normal);
        StyleConstants.setBold(bold, true);

        SimpleAttributeSet italic = new SimpleAttributeSet(normal);
        StyleConstants.setItalic(italic, true);

        SimpleAttributeSet heading1 = headingOf(bold, baseSize + 6);
        SimpleAttributeSet heading2 = headingOf(bold, baseSize + 4);
        SimpleAttributeSet heading3 = headingOf(bold, baseSize + 2);

        SimpleAttributeSet codeStyle = new SimpleAttributeSet();
        StyleConstants.setFontFamily(codeStyle, Font.MONOSPACED);
        StyleConstants.setBackground(codeStyle, ThemeColors.codeBackground());

        SimpleAttributeSet linkStyle = new SimpleAttributeSet(normal);
        StyleConstants.setForeground(linkStyle, ThemeColors.accent());
        StyleConstants.setUnderline(linkStyle, true);

        InlineStyles styles = new InlineStyles(normal, bold, italic, codeStyle, linkStyle);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            // A placeholder-shaped line only renders a block when it references
            // a real, unconsumed snippet key; otherwise it falls through to the
            // text path - literal "[CODE_BLOCK:...]" from a model must stay
            // visible, and a spoofed key must not re-render a real block.
            if (trimmed.startsWith("[CODE_BLOCK:") && trimmed.endsWith("]")
                    && renderPlaceholderCodeBlock(doc, trimmed, codeSnippets)) {
                continue;
            }

            // Markdown table: header line, then a separator row with a MATCHING
            // cell count (GFM), then body rows - without the count check a
            // pipe-bearing sentence followed by a horizontal rule would be
            // eaten into a grid
            if (TableBlockRenderer.isTableLine(line) && i + 1 < lines.length
                    && TableBlockRenderer.isTableSeparator(lines[i + 1])
                    && TableBlockRenderer.splitRow(line).size()
                            == TableBlockRenderer.splitRow(lines[i + 1]).size()) {
                java.util.List<String> header = TableBlockRenderer.splitRow(line);
                java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
                i += 2;
                while (i < lines.length && TableBlockRenderer.isTableLine(lines[i])) {
                    rows.add(TableBlockRenderer.splitRow(lines[i]));
                    i++;
                }
                i--; // step back: the for-loop increments past the last row
                TableBlockRenderer.render(doc, header, rows);
                continue;
            }

            if (HR_PATTERN.matcher(line).matches()) {
                renderHorizontalRule(doc);
                continue;
            }

            if (line.startsWith("# ")) {
                doc.insertString(doc.getLength(), line.substring(2) + "\n", heading1);
            } else if (line.startsWith("## ")) {
                doc.insertString(doc.getLength(), line.substring(3) + "\n", heading2);
            } else if (line.startsWith("### ")) {
                doc.insertString(doc.getLength(), line.substring(4) + "\n", heading3);
            } else if (BULLET_PATTERN.matcher(line).matches()) {
                // Top-level unordered list item: normalized bullet + inline formatting
                doc.insertString(doc.getLength(), "• ", bold);
                processInline(doc, line.substring(bulletContentStart(line)), styles, false);
            } else {
                processInline(doc, line, styles, true);
            }
        }
    }

    /** Index just past a bullet marker's whitespace run ("-\\s+" per BULLET_PATTERN). */
    private static int bulletContentStart(String line) {
        int idx = 1;
        while (idx < line.length() && Character.isWhitespace(line.charAt(idx))) {
            idx++;
        }
        return idx;
    }

    /**
     * Replaces {@code <br>} variants with newlines, but only outside inline
     * code spans - a literal {@code `<br>`} example in backticks must survive.
     */
    static String replaceHtmlBreaksOutsideCode(String text) {
        Pattern br = Pattern.compile("(?i)<br\\s*/?>");
        java.util.List<int[]> codeSpans = new java.util.ArrayList<>();
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                if (open < 0) {
                    open = i;
                } else {
                    codeSpans.add(new int[]{open, i + 1});
                    open = -1;
                }
            }
        }
        Matcher matcher = br.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            boolean inCode = false;
            for (int[] span : codeSpans) {
                if (matcher.start() >= span[0] && matcher.start() < span[1]) {
                    inCode = true;
                    break;
                }
            }
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement(inCode ? matcher.group() : "\n"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static SimpleAttributeSet headingOf(SimpleAttributeSet base, int size) {
        SimpleAttributeSet heading = new SimpleAttributeSet(base);
        StyleConstants.setFontSize(heading, size);
        return heading;
    }

    /**
     * True when the emphasis marker at {@code i} ({@code width} chars) can
     * plausibly open or close a span (CommonMark flanking rules): a marker
     * surrounded by whitespace on both sides - e.g. the multiplication sign
     * in "3 * 4" - is literal instead of an unmatched toggle that would
     * silently drop the character and flip style for the rest of the line.
     */
    private static boolean emphasisMarkerAt(String line, int i, int width) {
        boolean canOpen = i + width < line.length()
                && !Character.isWhitespace(line.charAt(i + width));
        boolean canClose = i > 0 && !Character.isWhitespace(line.charAt(i - 1));
        return canOpen || canClose;
    }

    /**
     * Renders a stored code block referenced by a placeholder line.
     * Single-use: the snippet key is consumed on render so a duplicate
     * placeholder-shaped line cannot render the same block twice.
     *
     * @return true when the line referenced a real, unconsumed placeholder
     */
    private static boolean renderPlaceholderCodeBlock(
        StyledDocument doc,
        String trimmedLine,
        Map<String, String> codeSnippets
    ) throws BadLocationException {
        String[] parts = trimmedLine
            .substring(12, trimmedLine.length() - 1)
            .split(":");
        String snippetKey = parts[0];
        String language = parts.length > 1 ? parts[1] : "";

        String code = codeSnippets.remove(snippetKey);
        if (code == null) {
            return false;
        }
        // The placeholder already occupies exactly its own lines - the
        // spacing above and below mirrors the source text's blank lines,
        // same as tables and rules. Adding breaks here would widen the
        // gap beyond what the author wrote.
        CodeBlockRenderer.render(doc, code, language);
        return true;
    }

    /** Renders a horizontal rule as a thin embedded divider component. */
    private static void renderHorizontalRule(StyledDocument doc)
        throws BadLocationException {
        javax.swing.JPanel rule = new javax.swing.JPanel();
        rule.setBorder(javax.swing.BorderFactory.createMatteBorder(
                1, 0, 0, 0, ThemeColors.border()));
        rule.setPreferredSize(new java.awt.Dimension(1, 6));
        rule.setOpaque(false);

        SimpleAttributeSet componentStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(componentStyle, rule);
        doc.insertString(doc.getLength(), " ", componentStyle);
        doc.insertString(doc.getLength(), "\n", null);
    }

    /**
     * Writes one line with inline formatting (bold, italic, inline code,
     * links) applied.
     *
     * @param appendNewline true to terminate the line with a newline
     */
    private static void processInline(
        StyledDocument doc,
        String line,
        InlineStyles styles,
        boolean appendNewline
    ) throws BadLocationException {
        StringBuilder currentText = new StringBuilder();
        SimpleAttributeSet currentStyle = styles.normal;

        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);

            // Links: [label](url) - label shown accented/underlined, url dropped
            if (c == '[') {
                Matcher m = LINK_PATTERN.matcher(line.substring(i));
                if (m.lookingAt() && m.start() == 0) {
                    doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                    currentText.setLength(0);
                    doc.insertString(doc.getLength(), m.group(1), styles.link);
                    i += m.end();
                    continue;
                }
            }

            if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                if (emphasisMarkerAt(line, i, 2)) {
                    doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                    currentText.setLength(0);
                    currentStyle = currentStyle == styles.bold ? styles.normal : styles.bold;
                } else {
                    currentText.append("**");
                }
                i += 2;
            } else if (c == '*') {
                if (emphasisMarkerAt(line, i, 1)) {
                    doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                    currentText.setLength(0);
                    currentStyle = currentStyle == styles.italic ? styles.normal : styles.italic;
                } else {
                    currentText.append('*');
                }
                i++;
            } else if (c == '`') {
                doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                currentText.setLength(0);
                currentStyle = currentStyle == styles.code ? styles.normal : styles.code;
                i++;
            } else {
                currentText.append(c);
                i++;
            }
        }

        doc.insertString(
            doc.getLength(),
            currentText.toString() + (appendNewline ? "\n" : ""),
            currentStyle
        );
        if (!appendNewline) {
            doc.insertString(doc.getLength(), "\n", styles.normal);
        }
    }

    /** Named bundle of the inline styles used while scanning a line. */
    private static final class InlineStyles {
        final SimpleAttributeSet normal;
        final SimpleAttributeSet bold;
        final SimpleAttributeSet italic;
        final SimpleAttributeSet code;
        final SimpleAttributeSet link;

        InlineStyles(
            SimpleAttributeSet normal,
            SimpleAttributeSet bold,
            SimpleAttributeSet italic,
            SimpleAttributeSet code,
            SimpleAttributeSet link
        ) {
            this.normal = normal;
            this.bold = bold;
            this.italic = italic;
            this.code = code;
            this.link = link;
        }
    }
}
