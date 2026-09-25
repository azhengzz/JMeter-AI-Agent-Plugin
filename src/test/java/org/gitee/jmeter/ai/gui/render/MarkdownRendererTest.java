package org.gitee.jmeter.ai.gui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;

/**
 * Covers the ported markdown state machine: supported syntax renders with the
 * expected attributes, and the deliberately-unsupported syntax (nested lists,
 * ordered lists, blockquotes) is pinned to plain-text rendering so the
 * behavior cannot drift from the reference implementation unnoticed.
 */
class MarkdownRendererTest {

    private static final Font BASE = new Font(Font.DIALOG, Font.PLAIN, 14);

    private final StyledDocument doc = new DefaultStyledDocument();

    private String render(String markdown) throws BadLocationException {
        doc.remove(0, doc.getLength()); // multi-call tests must not inherit earlier renders
        MarkdownRenderer.process(doc, markdown, BASE);
        return doc.getText(0, doc.getLength());
    }

    private AttributeSet attrsAt(String docText, String needle) {
        int idx = docText.indexOf(needle);
        assertTrue(idx >= 0, "expected to find '" + needle + "' in rendered text");
        return doc.getCharacterElement(idx).getAttributes();
    }

    private List<Object> embeddedComponents() {
        List<Object> found = new ArrayList<>();
        for (int i = 0; i < doc.getLength(); i++) {
            Object component = StyleConstants.getComponent(doc.getCharacterElement(i).getAttributes());
            if (component != null && (found.isEmpty() || found.get(found.size() - 1) != component)) {
                found.add(component);
            }
        }
        return found;
    }

    private static List<JButton> buttonsIn(Container container) {
        List<JButton> buttons = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JButton) {
                buttons.add((JButton) child);
            } else if (child instanceof Container) {
                buttons.addAll(buttonsIn((Container) child));
            }
        }
        return buttons;
    }

    private static List<JLabel> labelsIn(Container container) {
        List<JLabel> labels = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel) {
                labels.add((JLabel) child);
            } else if (child instanceof Container) {
                labels.addAll(labelsIn((Container) child));
            }
        }
        return labels;
    }

    // --- headings -----------------------------------------------------------

    @Test
    void headingsScaleRelativeToBaseFont() throws BadLocationException {
        String text = render("# One\n## Two\n### Three\n");
        AttributeSet h1 = attrsAt(text, "One");
        assertEquals(20, StyleConstants.getFontSize(h1));
        assertTrue(StyleConstants.isBold(h1));
        assertEquals(18, StyleConstants.getFontSize(attrsAt(text, "Two")));
        assertEquals(16, StyleConstants.getFontSize(attrsAt(text, "Three")));
    }

    // --- inline formatting ----------------------------------------------------

    @Test
    void boldAndItalicToggle() throws BadLocationException {
        String text = render("a **bold** b *it* c");
        assertTrue(StyleConstants.isBold(attrsAt(text, "bold")));
        assertTrue(StyleConstants.isItalic(attrsAt(text, "it")));
        assertFalse(StyleConstants.isBold(attrsAt(text, "a")));
    }

    @Test
    void inlineCodeIsMonospacedWithBackground() throws BadLocationException {
        String text = render("see `code()` end");
        AttributeSet code = attrsAt(text, "code()");
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(code));
        assertNotNull(StyleConstants.getBackground(code));
    }

    @Test
    void linkShowsLabelOnly() throws BadLocationException {
        String text = render("go [docs](http://example.com/x) now");
        assertTrue(text.contains("docs"));
        assertFalse(text.contains("http://example.com"), "url must not leak into the text");
        assertTrue(StyleConstants.isUnderline(attrsAt(text, "docs")));
    }

    @Test
    void topLevelBulletRendersWithDotPrefix() throws BadLocationException {
        String text = render("- item one");
        assertTrue(text.contains("• item one"));
        assertTrue(StyleConstants.isBold(attrsAt(text, "•")));
    }

    // --- deliberate degradations (pinned to the reference behavior) -----------

    @Test
    void nestedBulletStaysLiteralPlainText() throws BadLocationException {
        String text = render("- top\n  - nested");
        assertTrue(text.contains("• top"), "top-level bullet gets the glyph");
        assertTrue(text.contains("- nested"), "indented sub-bullet must stay literal");
        assertFalse(text.contains("• nested"), "no bullet glyph for nested items");
    }

    @Test
    void orderedListStaysLiteralPlainText() throws BadLocationException {
        String text = render("1. first\n2. second");
        assertTrue(text.contains("1. first"));
        assertTrue(text.contains("2. second"));
    }

    @Test
    void blockquoteStaysLiteralPlainText() throws BadLocationException {
        String text = render("> quoted text");
        assertTrue(text.contains("> quoted text"));
    }

    // --- HTML safety ------------------------------------------------------------

    @Test
    void htmlTagsStayLiteral() throws BadLocationException {
        String text = render("<b>not bold</b> <img src=\"http://x\">");
        assertTrue(text.contains("<b>not bold</b>"));
        assertTrue(text.contains("<img src=\"http://x\">"));
    }

    @Test
    void brVariantsConvertOutsideCodeSpansOnly() {
        String converted = MarkdownRenderer.replaceHtmlBreaksOutsideCode(
                "a<br>b<br/>c<BR />d `keep<br>literal`");
        assertTrue(converted.contains("a\nb\nc\nd"));
        assertTrue(converted.contains("keep<br>literal"));
    }

    // --- block elements ----------------------------------------------------------

    @Test
    void horizontalRuleEmbedsComponent() throws BadLocationException {
        String text = render("above\n---\nbelow");
        assertTrue(text.contains("above"));
        assertFalse(embeddedComponents().isEmpty(), "HR should embed a divider component");
    }

    @Test
    void fencedCodeBlockEmbedsPanelWithCopyButton() throws BadLocationException {
        String text = render("intro\n```java\nfoo()\nbar()\n```\nafter");
        assertTrue(text.contains("intro"));

        List<Object> components = embeddedComponents();
        assertFalse(components.isEmpty(), "code block should embed a panel");
        JScrollPane scroller = (JScrollPane) components.get(0);
        assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                scroller.getHorizontalScrollBarPolicy(),
                "wide code must scroll inside the card, not clip");
        JPanel codePanel = (JPanel) scroller.getViewport().getView();

        List<JButton> buttons = buttonsIn(codePanel);
        assertTrue(buttons.stream().anyMatch(b -> "Copy".equals(b.getText())),
                "code block must carry a Copy button");

        List<JLabel> labels = labelsIn(codePanel);
        assertTrue(labels.stream().anyMatch(l -> "java".equals(l.getText())),
                "language label should show the fence language");
    }

    @Test
    void codeBlockSpacingMirrorsSourceBlankLines() throws BadLocationException {
        // 占位替换把 fence 紧邻的前后换行吸收进匹配，替换串恰好用这两个换行
        // 让占位行独立成行——净换行数为零，渲染空行数恒等于源文空行数，
        // 不放大也不吞并。LLM 输出的标准排版（fence 前后各一空行）因此与
        // 普通段落断行、表格、分隔线同距，不会叠出大段空白。
        // 文档序列中嵌入组件占一个空格字符。
        // 紧贴形态：源 0 空行 → 渲染 0 空行。
        assertEquals("a\n \nb\n", render("a\n```java\nX=1\n```\nb"),
                "紧贴时块与正文之间不加多余空行");
        // 标准 LLM 排版：源上下各 1 空行 → 渲染各 1 空行（与段落断行同距）。
        assertEquals("a\n\n \n\nb\n", render("a\n\n```java\nX=1\n```\n\nb"),
                "源空行原样保留，不叠成双倍");
        // 消息以 fence 开头：卡片顶部不出现多余空行（与表格开头同构）。
        assertEquals(" \ntext\n", render("```java\nX=1\n```\ntext"),
                "块前无内容时不产生空首行");
        // 相邻两块源隔 1 空行 → 块间恰 1 空行（不堆叠为 2-3）。
        assertEquals(" \n\n \n", render("```java\nA\n```\n\n```py\nB\n```"),
                "相邻块的间距同样只由源空行决定");
    }

    @Test
    void markdownTableEmbedsGridComponent() throws BadLocationException {
        render("| a | b |\n| --- | --- |\n| 1 | 2 |");
        List<Object> components = embeddedComponents();
        assertFalse(components.isEmpty(), "table should embed a grid component");
        JScrollPane scroller = (JScrollPane) components.get(0);
        assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                scroller.getHorizontalScrollBarPolicy(),
                "a wide table must scroll inside the card, not clip");
        JPanel grid = (JPanel) scroller.getViewport().getView();
        assertEquals(4, grid.getComponentCount(), "2 header + 2 body cells");

        JTextArea headerCell = (JTextArea) grid.getComponent(0);
        assertEquals("a", headerCell.getText());
        assertFalse(headerCell.isEditable(), "cells are read-only but selectable");
        assertTrue(headerCell.getFont().isBold(), "header cells must be bold");
        assertEquals("2", ((JTextArea) grid.getComponent(3)).getText());
    }

    // --- table copy -------------------------------------------------------------

    @Test
    void tableTsvJoinsCellsForPaste() {
        assertEquals("h1\th2\n1\t2",
                TableBlockRenderer.buildTsv(java.util.List.of(
                        java.util.List.of("h1", "h2"),
                        java.util.List.of("1", "2"))),
                "rows newline-joined, cells tab-joined");
        assertEquals("x | y",
                TableBlockRenderer.buildTsv(java.util.List.of(java.util.List.of("x | y"))),
                "cell text stays raw, no re-splitting");
    }

    @Test
    void copyPopupOffersCellAndTableActions() {
        JPopupMenu popup = TableBlockRenderer.buildCopyPopup(java.util.List.of(
                java.util.List.of("h"), java.util.List.of("v")));
        assertEquals("Copy Cell", ((JMenuItem) popup.getComponent(0)).getText());
        assertEquals("Copy Table", ((JMenuItem) popup.getComponent(1)).getText());
    }

    @Test
    void embeddedScrollersUseSlimScrollbars() throws BadLocationException {
        render("```java\nx()\n```");
        JScrollPane codeScroller = (JScrollPane) embeddedComponents().get(0);
        assertTrue(codeScroller.getHorizontalScrollBar().getUI()
                        instanceof org.gitee.jmeter.ai.gui.theme.SlimScrollBarUI,
                "code block scrollbar must use the slim theme-aware UI");

        render("| a | b |\n| --- | --- |\n| 1 | 2 |");
        JScrollPane tableScroller = (JScrollPane) embeddedComponents().get(0);
        assertTrue(tableScroller.getHorizontalScrollBar().getUI()
                        instanceof org.gitee.jmeter.ai.gui.theme.SlimScrollBarUI,
                "table scrollbar must use the slim theme-aware UI");
    }

    @Test
    void embeddedScrollersLetMouseWheelReachTheTranscript() throws BadLocationException {
        render("```java\nx()\n```");
        JScrollPane codeScroller = (JScrollPane) embeddedComponents().get(0);
        assertEquals(0, codeScroller.getMouseWheelListeners().length,
                "embedded scroller must not consume the wheel; the transcript scrolls instead");

        render("| a | b |\n| --- | --- |\n| 1 | 2 |");
        JScrollPane tableScroller = (JScrollPane) embeddedComponents().get(0);
        assertEquals(0, tableScroller.getMouseWheelListeners().length,
                "wheel over a table must keep scrolling the chat area");
    }

    @Test
    void tableColumnsHugTheirContentInsteadOfEqualSplit() throws BadLocationException {
        render("| k | v |\n| --- | --- |\n| id | " + "v".repeat(60) + " |");
        JScrollPane scroller = (JScrollPane) embeddedComponents().get(0);
        JPanel grid = (JPanel) scroller.getViewport().getView();
        grid.setSize(grid.getPreferredSize());
        grid.doLayout();
        JTextArea keyCell = (JTextArea) grid.getComponent(2);
        JTextArea valueCell = (JTextArea) grid.getComponent(3);
        assertTrue(keyCell.getWidth() < valueCell.getWidth(),
                "短列宽度须贴自身内容，不能被均分拉宽留出大片空白");
    }

    // --- table row splitting ---------------------------------------------------

    @Test
    void tableSeparatorDetection() {
        assertTrue(TableBlockRenderer.isTableSeparator("| --- | :-: | --- |"));
        assertTrue(TableBlockRenderer.isTableSeparator("---"));
        assertFalse(TableBlockRenderer.isTableSeparator("| a | b |"));
        assertFalse(TableBlockRenderer.isTableSeparator(""));
    }

    @Test
    void splitRowHandlesEscapedPipesAndEdgeSlots() {
        assertEquals(java.util.Arrays.asList("a", "b"), TableBlockRenderer.splitRow("| a | b |"));
        assertEquals(java.util.Arrays.asList("x | y"), TableBlockRenderer.splitRow("| x \\| y |"));
        assertEquals(java.util.Arrays.asList("only"), TableBlockRenderer.splitRow("only"));
    }
}
