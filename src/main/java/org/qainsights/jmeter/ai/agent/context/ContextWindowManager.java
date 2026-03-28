package org.qainsights.jmeter.ai.agent.context;

import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Context window manager for intelligent message history management.
 * Based on Nanobot's context optimization strategies.
 */
public class ContextWindowManager {
    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    // Approximate token counts (can be refined with actual tokenizer)
    private static final int AVG_TOKENS_PER_CHAR = 4;
    private static final int SYSTEM_PROMPT_ESTIMATE = 2000; // ~500 tokens
    private static final int MESSAGE_OVERHEAD = 50; // Overhead per message

    // Maximum context window sizes
    public final int maxContextTokens;
    private final int reserveTokens;

    public ContextWindowManager(int maxContextTokens, double reserveRatio) {
        this.maxContextTokens = maxContextTokens;
        this.reserveTokens = (int) (maxContextTokens * reserveRatio);
    }

    /**
     * Default constructor with standard 65k context window and 10% reserve.
     */
    public ContextWindowManager() {
        this(65000, 0.10);
    }

    /**
     * Trim message history to fit within context window while preserving important messages.
     * Strategy: Keep recent messages + system message + ensure tool call context is preserved.
     */
    public List<Message> trimToContextWindow(List<Message> messages, int maxTokens) {
        if (messages.isEmpty()) {
            return messages;
        }

        // Calculate current estimated token count
        int currentTokens = estimateTokens(messages);

        if (currentTokens <= maxTokens) {
            return messages;
        }

        log.debug("Context window overflow: {} tokens, max: {}. Trimming...", currentTokens, maxTokens);

        // Find the cut point - we need to preserve tool call chains
        int cutIndex = findSafeCutPoint(messages, maxTokens);

        // Return trimmed list
        return new ArrayList<>(messages.subList(cutIndex, messages.size()));
    }

    /**
     * Calculate remaining tokens for user message.
     */
    public int getAvailableTokens(List<Message> messages) {
        int usedTokens = estimateTokens(messages);
        return Math.max(0, maxContextTokens - usedTokens - reserveTokens);
    }

    /**
     * Estimate token count for a list of messages.
     */
    private int estimateTokens(List<Message> messages) {
        int count = SYSTEM_PROMPT_ESTIMATE; // Reserve for system prompt

        for (Message msg : messages) {
            // Skip system messages (they're in SYSTEM_PROMPT_ESTIMATE)
            if (msg.getRole() == Message.Role.SYSTEM) {
                continue;
            }

            // Count content
            if (msg.getContent() != null) {
                count += msg.getContent().length() / AVG_TOKENS_PER_CHAR;
            }

            // Count tool calls
            if (msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    // Tool call overhead + arguments
                    count += 20; // Base overhead
                    if (tc.getArguments() != null) {
                        count += tc.getArguments().toString().length() / AVG_TOKENS_PER_CHAR;
                    }
                }
            }

            // Message overhead
            count += MESSAGE_OVERHEAD;
        }

        return count;
    }

    /**
     * Find a safe cut point that preserves tool call chains.
     * Tool calls form chains: user -> assistant (with tools) -> tool results -> assistant...
     * We need to preserve the integrity of these chains.
     */
    private int findSafeCutPoint(List<Message> messages, int maxTokens) {
        int targetTokens = maxTokens - reserveTokens;
        int currentTokens = 0;
        int i = messages.size() - 1;

        // Iterate backwards, tracking tool call chains
        boolean inToolChain = false;
        int toolResultCount = 0;

        while (i >= 0) {
            Message msg = messages.get(i);
            int msgTokens = estimateMessageTokens(msg);

            // Check if including this message would exceed limit
            if (currentTokens + msgTokens > targetTokens) {
                // If we're in a tool chain, try to preserve it
                if (inToolChain) {
                    // Skip back to find the start of this tool chain
                    while (i >= 0 && inToolChain) {
                        if (messages.get(i).getRole() == Message.Role.USER) {
                            break; // Start of tool chain
                        }
                        i--;
                    }
                    // Set cut point just after this user message
                    return i + 1;
                }
                return i + 1; // Cut here
            }

            currentTokens += msgTokens;

            // Track tool call chains
            if (msg.getRole() == Message.Role.USER) {
                inToolChain = false;
                toolResultCount = 0;
            } else if (msg.getRole() == Message.Role.ASSISTANT && msg.hasToolCalls()) {
                inToolChain = true;
            } else if (msg.getRole() == Message.Role.TOOL) {
                toolResultCount++;
                if (toolResultCount >= msg.getToolCalls().size()) {
                    inToolChain = false; // End of tool chain
                }
            }

            i--;
        }

        return 0; // Shouldn't reach here, but safety fallback
    }

    /**
     * Estimate tokens for a single message.
     */
    private int estimateMessageTokens(Message msg) {
        int tokens = MESSAGE_OVERHEAD;

        if (msg.getContent() != null) {
            tokens += msg.getContent().length() / AVG_TOKENS_PER_CHAR;
        }

        if (msg.hasToolCalls()) {
            for (ToolCall tc : msg.getToolCalls()) {
                tokens += 20; // Tool call overhead
                if (tc.getArguments() != null) {
                    tokens += tc.getArguments().toString().length() / AVG_TOKENS_PER_CHAR;
                }
            }
        }

        return tokens;
    }

    /**
     * Get recommended max history size based on context window.
     */
    public int getRecommendedHistorySize(int avgMessageTokens) {
        int availableTokens = maxContextTokens - SYSTEM_PROMPT_ESTIMATE - reserveTokens;
        return Math.max(0, (availableTokens * 4) / avgMessageTokens);
    }

    /**
     * Check if context window is approaching limit.
     */
    public boolean isContextNearlyFull(List<Message> messages, double threshold) {
        int currentTokens = estimateTokens(messages);
        return currentTokens > (maxContextTokens * threshold);
    }
}
