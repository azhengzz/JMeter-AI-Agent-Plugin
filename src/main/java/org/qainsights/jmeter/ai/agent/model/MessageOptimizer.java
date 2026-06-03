package org.qainsights.jmeter.ai.agent.model;

/**
 * Utility for optimizing messages before persistence.
 * Based on Nanobot's session persistence optimizations.
 */
public class MessageOptimizer {
    private static final int TOOL_RESULT_MAX_CHARS = 16000;
    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context";

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

        // Handle user messages - remove runtime context prefix
        if (role == Message.Role.USER) {
            content = removeRuntimeContext(content);
        }

        // Handle tool result messages - truncate large results
        if (role == Message.Role.TOOL && content.length() > TOOL_RESULT_MAX_CHARS) {
            content = content.substring(0, TOOL_RESULT_MAX_CHARS) + "\n...(truncated)";
        }

        // Handle assistant messages - truncate if needed
        if (role == Message.Role.ASSISTANT && content.length() > TOOL_RESULT_MAX_CHARS) {
            content = content.substring(0, TOOL_RESULT_MAX_CHARS) + "\n...(truncated)";
        }

        return content;
    }

    /**
     * Remove runtime context prefix from user message.
     */
    private static String removeRuntimeContext(String content) {
        int runtimeIndex = content.indexOf(RUNTIME_CONTEXT_TAG);
        if (runtimeIndex >= 0) {
            // Find the end of the runtime context block (look for double newline)
            int endOfRuntime = content.indexOf("\n\n", runtimeIndex);
            if (endOfRuntime > 0) {
                // Keep only the user message part after runtime context
                String userPart = content.substring(endOfRuntime + 2).trim();
                if (!userPart.isEmpty()) {
                    return userPart;
                } else {
                    // If user part is empty, return null to skip this message
                    return null;
                }
            }
        }
        return content;
    }

    /**
     * Create an optimized message for persistence.
     * Returns null if the message should be skipped entirely.
     */
    public static Message createOptimized(Message msg) {
        String optimizedContent = optimizeContent(msg.getRole(), msg.getContent(), msg.hasToolCalls());

        if (optimizedContent == null) {
            return null;
        }

        // If content wasn't modified, return original message
        if (optimizedContent == msg.getContent()) {
            return msg;
        }

        // Create new message with optimized content
        // Since Message constructor is private, we need to use reflection or modify Message class
        // For now, return original message and let the optimization happen at a different layer
        return msg;
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
