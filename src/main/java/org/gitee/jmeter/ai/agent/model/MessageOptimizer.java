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
