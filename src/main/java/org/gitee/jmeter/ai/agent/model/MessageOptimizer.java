package org.gitee.jmeter.ai.agent.model;

import org.gitee.jmeter.ai.utils.AiConfig;

/**
 * Utility for optimizing messages before persistence.
 * Based on Nanobot's session persistence optimizations.
 *
 * <p>NOTE: runtime-context stripping is deliberately NOT done here. The only correct
 * strip is {@code ContextBuilder.stripRuntimeContext} (tag-based, handles the
 * user-text-first layout), applied by AgentRunner right after this method for USER
 * messages. The old prefix-layout stripper that lived here once ate the real user
 * text whenever the runtime block contained a blank line (e.g. the @-instance
 * mention section) — persistence then kept only the block tail.
 */
public class MessageOptimizer {

    /**
     * Optimize a message content for persistence.
     * Returns null if the message should be skipped entirely.
     */
    public static String optimizeContent(Message.Role role, String content, boolean hasToolCalls) {
        if (content == null) {
            // 带 tool_calls 的 assistant 消息 content 可为 null（OpenAI 工具调用响应恒
            // null）：落盘为空串而非整条丢弃——丢弃会让其后的 tool 结果成孤儿，下次加载
            // 被 findLegalStart 截肢整段回合尾（与 shouldSkip 不跳过这类消息的口径对齐）。
            if (role == Message.Role.ASSISTANT && hasToolCalls) {
                return "";
            }
            return null;
        }

        // Skip empty assistant messages without tool calls
        if (role == Message.Role.ASSISTANT && !hasToolCalls) {
            if (content.isEmpty()) {
                return null;
            }
        }

        int maxChars = AiConfig.getToolResultMaxChars();

        // Handle tool result messages - truncate large results
        if (role == Message.Role.TOOL && content.length() > maxChars) {
            content = content.substring(0, maxChars) + "\n...(truncated)";
        }

        // Handle assistant messages - truncate if needed
        if (role == Message.Role.ASSISTANT && content.length() > maxChars) {
            content = content.substring(0, maxChars) + "\n...(truncated)";
        }

        return content;
    }

    /**
     * Check if a message should be skipped during persistence.
     */
    public static boolean shouldSkip(Message msg) {
        // Skip empty assistant messages without tool calls or reasoning content
        if (msg.getRole() == Message.Role.ASSISTANT && !msg.hasToolCalls() && !msg.hasReasoningContent()) {
            if (msg.getContent() == null || msg.getContent().isEmpty()) {
                return true;
            }
        }

        // Skip system messages
        if (msg.getRole() == Message.Role.SYSTEM) {
            return true;
        }

        return false;
    }
}
