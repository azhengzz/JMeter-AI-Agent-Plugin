package org.qainsights.jmeter.ai.agent.context;

import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Context window manager — safety net for message trimming.
 * Token estimation is handled by MemoryConsolidator (single source of truth).
 * This class only trims + aligns + removes orphaned tool results.
 */
public class ContextWindowManager {
    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    private static final int SAFETY_BUFFER = 1024;

    public final int maxContextTokens;

    public ContextWindowManager(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public ContextWindowManager() {
        this(Integer.parseInt(AiConfig.getProperty("jmeter.ai.context.window.tokens", "65536")));
    }

    private int getMaxCompletionTokens() {
        return Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tokens", "4096"));
    }

    /**
     * Safety-net trim: if estimated tokens exceed maxTokens, trim to target.
     * Token estimation is provided by the caller (MemoryConsolidator).
     *
     * Three-step approach (Nanobot aligned):
     * 1. Trim by token budget (keep the most recent messages that fit)
     * 2. Align to user-turn boundary
     * 3. Skip orphaned tool results
     */
    public List<Message> trimToContextWindow(List<Message> messages, int estimatedTokens, int maxTokens) {
        if (messages.isEmpty() || estimatedTokens <= maxTokens) {
            return messages;
        }

        log.debug("Context window overflow: {} tokens, max: {}. Trimming...", estimatedTokens, maxTokens);

        // Step 1: Trim by token budget — keep the most recent messages that fit
        int targetTokens = calcTargetTokens();
        List<Message> sliced = trimByTokenBudget(messages, targetTokens);

        // Note: user-turn alignment and orphaned tool cleanup are done in Session.getHistory()
        return new ArrayList<>(sliced);
    }

    /**
     * Calculate available tokens for a new user message.
     * Caller should provide the current estimated tokens.
     */
    public int getAvailableTokens(int estimatedTokens) {
        return Math.max(0, maxContextTokens - estimatedTokens - getMaxCompletionTokens() - SAFETY_BUFFER);
    }

    /**
     * Check if context window is approaching limit.
     * Caller should provide the current estimated tokens.
     */
    public boolean isContextNearlyFull(int estimatedTokens, double threshold) {
        return estimatedTokens > (maxContextTokens * threshold);
    }

    // --- Internal ---

    private int calcTargetTokens() {
        int budget = maxContextTokens - getMaxCompletionTokens() - SAFETY_BUFFER;
        return budget / 2;
    }

    private List<Message> trimByTokenBudget(List<Message> messages, int targetTokens) {
        // Rough character-based estimate for quick budget trimming.
        // MemoryConsolidator handles precise estimation; this is a safety net.
        int currentChars = 0;
        int i = messages.size() - 1;
        // ~1 token per 4 chars + 4 token overhead per message
        int targetChars = targetTokens * 4;

        while (i >= 0) {
            Message msg = messages.get(i);
            int msgChars = msgLength(msg) + 16; // overhead
            if (currentChars + msgChars > targetChars) {
                break;
            }
            currentChars += msgChars;
            i--;
        }

        return messages.subList(i + 1, messages.size());
    }

    private int msgLength(Message msg) {
        int len = 0;
        if (msg.getContent() != null) len += msg.getContent().length();
        if (msg.getReasoningContent() != null) len += msg.getReasoningContent().length();
        if (msg.hasToolCalls()) {
            for (ToolCall tc : msg.getToolCalls()) {
                if (tc.getArguments() != null) len += tc.getArguments().toString().length();
            }
        }
        if (msg.getToolCallId() != null) len += msg.getToolCallId().length();
        return len;
    }

    private List<Message> alignToUserTurn(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == Message.Role.USER) {
                return messages.subList(i, messages.size());
            }
        }
        return messages;
    }
}
