package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.text.StyledDocument;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式实时渲染：流式 tick 的累计 Markdown 全量重渲与终态渲染同路径——
 * 已闭合的围栏代码块与成形的表格即时渲染为内嵌组件（复制按钮/网格随流
 * 出现，流段结束无升级跳变）；尚未闭合的尾部围栏按纯文本呈现直至闭合。
 * 流式中途的主题/字号触发重渲保持组件形态（无降级模式可回退）。
 */
class MessageCardStreamingTest {

    private static final String MARKDOWN = "# Title\n\n```java\nint x = 1;\n```\n\n"
            + "| A | B |\n|---|---|\n| 1 | 2 |\n";

    private static int embeddedScrollerCount(StyledDocument doc) {
        int count = 0;
        for (int i = 0; i < doc.getLength(); i++) {
            Component c = javax.swing.text.StyleConstants.getComponent(
                    doc.getCharacterElement(i).getAttributes());
            if (c instanceof JScrollPane) {
                count++;
            }
        }
        return count;
    }

    @Test
    void closedBlocksRenderComponentsDuringStreaming() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, null);

        // 流式尾段：围栏未闭合——仍按纯文本呈现（尚无组件）
        card.setMarkdownContent("# Title\n\n```java\nint ");
        assertEquals(0, embeddedScrollerCount(card.getBodyDocument()),
                "unclosed tail fence stays plain text (no component yet)");

        // 流式增长到围栏闭合 + 表格成形——组件即时出现，不等流段结束
        card.setMarkdownContent(MARKDOWN);
        assertTrue(embeddedScrollerCount(card.getBodyDocument()) >= 2,
                "closed fence + formed table render embedded components during streaming");
        assertEquals(MARKDOWN, card.getText(), "raw markdown accumulates verbatim");

        // 流段终态重渲：同一路径，视觉无跳变，组件保留
        card.setMarkdownContent(MARKDOWN);
        assertTrue(embeddedScrollerCount(card.getBodyDocument()) >= 2,
                "final render keeps the same components (no upgrade jump)");
    }

    @Test
    void themeRerenderDuringStreamingKeepsComponents() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, null);
        card.setMarkdownContent(MARKDOWN);
        card.applyTheme();   // LAF 扫描重渲（流式中也可能发生）
        assertTrue(embeddedScrollerCount(card.getBodyDocument()) >= 2,
                "theme re-render keeps component rendering");
    }

    @Test
    void fontRerenderDuringStreamingKeepsComponents() {
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, null);
        card.setMarkdownContent(MARKDOWN);
        card.applyFont(null);
        assertTrue(embeddedScrollerCount(card.getBodyDocument()) >= 2,
                "font re-render keeps component rendering");
    }
}
