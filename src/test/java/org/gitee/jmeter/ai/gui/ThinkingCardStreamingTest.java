package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考卡流式扩展：整段替换原地重写、自动/手动折叠两态（双向保护——
 * 替换可重新展开自动折叠的卡，用户手动选择不被覆盖）。
 */
class ThinkingCardStreamingTest {

    @Test
    void finishAutoCollapsesUntouchedCard() {
        ThinkingCard card = new ThinkingCard();
        card.appendText("推理片段");
        card.finish();
        assertTrue(card.isCollapsed(), "untouched card auto-collapses on finish");
        assertFalse(card.isSpinnerRunning());
    }

    @Test
    void runningCardStartsCollapsedWithThinkingHeader() {
        // 需求 1：默认收起——运行中 spinner/"Thinking…" 状态正常，正文闭合
        ThinkingCard card = new ThinkingCard();
        card.appendText("流式思考片段");
        assertTrue(card.isCollapsed(), "card starts collapsed while running");
        assertTrue(card.isSpinnerRunning(), "spinner keeps running while collapsed");
        assertTrue(card.getHeaderText().contains("Thinking"), "header shows running state");
        assertEquals("流式思考片段", card.getText(), "body still accumulates while hidden");

        // 用户手动展开不受影响
        card.setCollapsed(false);
        assertFalse(card.isCollapsed());
        card.setCollapsed(true);
    }

    @Test
    void replaceKeepsCollapsedDefault() {
        ThinkingCard card = new ThinkingCard();
        card.appendText("第一次尝试的思考");
        card.finish();
        assertTrue(card.isCollapsed());

        card.replaceText("重试后的完整思考");
        assertEquals("重试后的完整思考", card.getText(), "body rewritten in place");
        assertTrue(card.isCollapsed(), "replacement keeps the collapsed default");
        assertFalse(card.isSpinnerRunning(), "replacement is terminal for the segment attempt");
    }

    @Test
    void replaceKeepsUserManualCollapse() {
        ThinkingCard card = new ThinkingCard();
        card.appendText("思考");
        card.setCollapsed(true);   // user gesture
        card.finish();
        card.replaceText("重试后的思考");
        assertTrue(card.isCollapsed(), "user's manual collapse survives replacement");
        assertEquals("重试后的思考", card.getText());
    }

    @Test
    void finishKeepsUserManualExpand() {
        ThinkingCard card = new ThinkingCard();
        card.setCollapsed(false);  // user explicitly expanded while running
        card.appendText("还在流式");
        card.finish();
        assertFalse(card.isCollapsed(), "manual expand survives auto-finish (was force-collapsed before)");
        assertFalse(card.isSpinnerRunning());
    }

    @Test
    void clearResetsBodyAndCollapses() {
        ThinkingCard card = new ThinkingCard();
        card.appendText("旧内容");
        card.clear();
        assertEquals("", card.getText());
        assertTrue(card.isCollapsed());
        assertFalse(card.isSpinnerRunning());
    }
}
