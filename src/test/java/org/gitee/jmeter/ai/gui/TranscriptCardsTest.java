package org.gitee.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;

import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.junit.jupiter.api.Test;

/** Style and lifecycle behavior of the individual card components. */
class TranscriptCardsTest {

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

    // --- MessageCard ---------------------------------------------------------

    @Test
    void userCardPaintsBubbleBackgroundAssistantStaysFlat() {
        MessageCard user = new MessageCard(MessageCard.Role.USER, new Font(Font.DIALOG, Font.PLAIN, 14));
        MessageCard assistant = new MessageCard(MessageCard.Role.ASSISTANT, new Font(Font.DIALOG, Font.PLAIN, 14));
        assertEquals(ThemeColors.userBubbleBackground(), user.getBackground());
        assertNotEquals(ThemeColors.userBubbleBackground(), assistant.getBackground());
    }

    @Test
    void primaryButtonUsesAccentTextOnSoftTint() {
        QuietButton send = new QuietButton("Send", QuietButton.Kind.PRIMARY);
        assertEquals(ThemeColors.accent(), send.getForeground(),
                "主按钮文字用主题色配合浅色调底（不再深色实底白字）");
        assertFalse(ThemeColors.accentSoft().equals(ThemeColors.accent()),
                "主按钮底色为浅色调，非实心 accent");
    }

    @Test
    void cardCarriesCopyButtonAndKeepsRawText() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, new Font(Font.DIALOG, Font.PLAIN, 14));
        card.setMarkdownContent("# Title");
        assertTrue(buttonsIn(card).stream().anyMatch(b -> "Copy".equals(b.getText())),
                "every card must carry a Copy button");
        assertEquals("# Title", card.getText(), "raw markdown source is kept for Copy");
        assertTrue(card.getBodyDocument().getLength() > 0);
    }

    @Test
    void plainContentRendersLiteral() throws Exception {
        MessageCard card = new MessageCard(MessageCard.Role.USER, new Font(Font.DIALOG, Font.PLAIN, 14));
        card.setPlainContent("You: **not bold**");
        assertEquals("You: **not bold**", card.getText());
        assertEquals("You: **not bold**",
                card.getBodyDocument().getText(0, card.getBodyDocument().getLength()));
    }

    @Test
    void assistantCardRerendersBakedColorsOnThemeChange() {
        // 主题热切换：内嵌代码块等烘焙进文档的颜色须随 applyTheme 重渲（spec
        // 「切换 LAF 历史重绘」——raw markdown 保留正是为此）
        java.awt.Color original = UIManager.getColor("Panel.background");
        try {
            UIManager.put("Panel.background", new java.awt.Color(0x1E, 0x1E, 0x1E));
            MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT,
                    new Font(Font.DIALOG, Font.PLAIN, 14));
            card.setMarkdownContent("```java\nx()\n```");
            JPanel before = embeddedCodePanel(card);
            java.awt.Color darkBg = before.getBackground();
            assertEquals(org.gitee.jmeter.ai.gui.theme.ThemeColors.codeBackground(), darkBg);

            UIManager.put("Panel.background", new java.awt.Color(0xF5, 0xF5, 0xF5));
            card.applyTheme();

            JPanel after = embeddedCodePanel(card);
            assertNotNull(after, "重渲后代码块组件仍在");
            assertNotEquals(darkBg, after.getBackground(),
                    "内嵌代码块背景须按新主题重渲（旧组件被替换、颜色重新派生）");
        } finally {
            if (original != null) {
                UIManager.put("Panel.background", original);
            }
        }
    }

    /** 在卡片文档中找内嵌滚动容器（表格/代码块），无则 null。 */
    private static JScrollPane embeddedScroller(MessageCard card) {
        javax.swing.text.StyledDocument doc = card.getBodyDocument();
        for (int i = 0; i < doc.getLength(); i++) {
            Object component = javax.swing.text.StyleConstants.getComponent(
                    doc.getCharacterElement(i).getAttributes());
            if (component instanceof JScrollPane) {
                return (JScrollPane) component;
            }
        }
        return null;
    }

    /** 在卡片文档中找内嵌代码块面板（滚动容器解包），无则 null。 */
    private static JPanel embeddedCodePanel(MessageCard card) {
        JScrollPane scroller = embeddedScroller(card);
        return scroller == null ? null : (JPanel) scroller.getViewport().getView();
    }

    /** 设置卡片尺寸并触发一次布局，使 body 获得真实宽度（headless 布局桩）。 */
    private static void layOutCardAt(MessageCard card, int width) {
        card.setSize(width, card.getPreferredSize().height);
        card.doLayout();
    }

    @Test
    void wideTableClampsToCardBodyInsteadOfClipping() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT,
                new Font(Font.DIALOG, Font.PLAIN, 14));
        card.setMarkdownContent(
                "| 编号 | 类型 | 名称 | 元素ID | 说明 |\n"
                + "| --- | --- | --- | --- | --- |\n"
                + "| 1 | ThreadGroup（普通线程组） | `Code_SceneApi` | 94845 | 默认线程组负责主流程 |\n"
                + "| 2 | SetupThreadGroup | `setUp线程组` | 41308 | 初始化与数据准备 |");
        layOutCardAt(card, 360);
        card.fitEmbeddedScrollers();

        JScrollPane scroller = embeddedScroller(card);
        assertNotNull(scroller, "表格必须嵌为可横向滚动的容器");
        JPanel grid = (JPanel) scroller.getViewport().getView();
        assertTrue(scroller.getPreferredSize().width < grid.getPreferredSize().width,
                "宽表须钳到卡宽（卡内横向滚动），而不是被右缘裁掉");
        assertTrue(scroller.getPreferredSize().width > 0);
        assertEquals(scroller.getPreferredSize(), scroller.getMaximumSize(),
                "最大尺寸钉到首选尺寸，否则文本面板会把嵌入组件拉伸吃满整行宽");
        assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                scroller.getHorizontalScrollBarPolicy());
    }

    @Test
    void narrowTableKeepsNaturalWidth() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT,
                new Font(Font.DIALOG, Font.PLAIN, 14));
        card.setMarkdownContent("| a | b |\n| --- | --- |\n| 1 | 2 |");
        layOutCardAt(card, 360);
        card.fitEmbeddedScrollers();

        JScrollPane scroller = embeddedScroller(card);
        assertNotNull(scroller);
        JPanel grid = (JPanel) scroller.getViewport().getView();
        assertEquals(grid.getPreferredSize().width, scroller.getPreferredSize().width,
                "窄表保持自然宽度，不强行钳制");
        assertEquals(scroller.getPreferredSize(), scroller.getMaximumSize(),
                "最大尺寸须钉到首选尺寸，否则文本面板会把窄表拉伸吃满整行宽、右侧留大片空白");
    }

    @Test
    void longCodeLineClampsInsteadOfClipping() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT,
                new Font(Font.DIALOG, Font.PLAIN, 14));
        card.setMarkdownContent("```text\n" + "x".repeat(240) + "\n```");
        layOutCardAt(card, 360);
        card.fitEmbeddedScrollers();

        JScrollPane scroller = embeddedScroller(card);
        assertNotNull(scroller, "代码块必须嵌为可横向滚动的容器");
        JPanel codePanel = (JPanel) scroller.getViewport().getView();
        assertTrue(scroller.getPreferredSize().width < codePanel.getPreferredSize().width,
                "超长代码行须钳到卡宽（卡内横向滚动），而不是被右缘裁掉");
    }

    // --- ToolActivityGroup -----------------------------------------------------

    @Test
    void toolActivityGroupFinishesAndAutoCollapses() {
        ToolActivityGroup group = new ToolActivityGroup();
        group.addLine("tool one");
        group.addLine("tool two");
        assertEquals(2, group.getLineCount());
        assertTrue(group.isRunning());
        assertFalse(group.isCollapsed());
        assertTrue(group.isSpinnerRunning());
        assertTrue(group.getHeaderText().contains("Agent activity"));

        group.finish();
        group.finish(); // idempotent
        assertFalse(group.isRunning());
        assertTrue(group.isCollapsed());
        assertTrue(group.getHeaderText().contains("2 tool calls"));

        group.dispose();
        assertFalse(group.isSpinnerRunning());
    }

    // --- ThinkingCard ------------------------------------------------------------

    @Test
    void thinkingCardCollapsesToTruncatedPreview() {
        ThinkingCard card = new ThinkingCard();
        card.appendText("x".repeat(100));
        assertTrue(card.isRunning());
        assertTrue(card.isSpinnerRunning());

        card.finish();
        assertTrue(card.isCollapsed());
        assertTrue(card.getHeaderText().contains("Thoughts"));
        assertTrue(card.getHeaderText().contains("…"), "long reasoning must be preview-truncated");

        card.dispose();
        assertFalse(card.isSpinnerRunning());
    }

    // --- ThinkingRow ---------------------------------------------------------------

    @Test
    void thinkingRowAnimatesAndDisposes() {
        ThinkingRow row = new ThinkingRow();
        assertTrue(row.isSpinnerRunning());
        row.dispose();
        assertFalse(row.isSpinnerRunning());
    }
}
