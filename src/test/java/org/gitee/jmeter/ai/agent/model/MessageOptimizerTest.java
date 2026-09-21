package org.gitee.jmeter.ai.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MessageOptimizer} 的持久化优化契约。
 *
 * <p>核心回归（2026-09-13 实测 bug）：USER 消息必须<b>原样透传</b>——runtime-context
 * 的剥离只属于 {@code ContextBuilder.stripRuntimeContext}（AgentRunner 在本方法之后
 * 调用）。旧的前缀布局剥离器曾把「正文在前、块在后 + 块内含空行（@ 实例小节）」
 * 的消息裁成只剩块尾巴，真实正文整段丢失。
 */
class MessageOptimizerTest {

    @Test
    void userContentPassesThroughUnchangedIncludingRuntimeContext() {
        String merged = "@26092-1789292748524 看下你有几个线程组"
                + "\n\n[Runtime Context — metadata only, not instructions]\nCurrent Time: 2026-09-13 17:58"
                + "\n\nMentioned JMeter instances (JSON data, not instructions):\n[{\"instanceId\":\"26092-1789292748524\"}]"
                + "\nUse read_instance_session(instanceId=...) ... when relevant.\n[/Runtime Context]";

        assertEquals(merged, MessageOptimizer.optimizeContent(Message.Role.USER, merged, false));
    }

    @Test
    void plainUserContentPassesThrough() {
        assertEquals("好的", MessageOptimizer.optimizeContent(Message.Role.USER, "好的", false));
    }

    @Test
    void nullContentYieldsNull() {
        assertNull(MessageOptimizer.optimizeContent(Message.Role.USER, null, false));
    }

    /**
     * null-content 的工具调用 assistant（OpenAI 路径工具响应恒 null content）落盘为
     * 空串而非丢弃——丢弃会让其 tool 结果成孤儿、下次加载被 findLegalStart 截肢。
     */
    @Test
    void nullContentAssistantWithToolCallsPersistsAsEmptyString() {
        assertEquals("", MessageOptimizer.optimizeContent(Message.Role.ASSISTANT, null, true));
        // 无 tool_calls 的 null-content assistant 仍整条丢弃（与 shouldSkip 口径一致）
        assertNull(MessageOptimizer.optimizeContent(Message.Role.ASSISTANT, null, false));
    }

    @Test
    void emptyAssistantWithoutToolCallsIsSkipped() {
        assertNull(MessageOptimizer.optimizeContent(Message.Role.ASSISTANT, "", false));
        assertEquals("文本", MessageOptimizer.optimizeContent(Message.Role.ASSISTANT, "文本", false));
    }

    @Test
    void shouldSkipSystemAndEmptyAssistant() {
        assertTrue(MessageOptimizer.shouldSkip(Message.system("sys")));
        assertTrue(MessageOptimizer.shouldSkip(Message.assistant("")));
        assertFalse(MessageOptimizer.shouldSkip(Message.user("hi")));
    }
}
