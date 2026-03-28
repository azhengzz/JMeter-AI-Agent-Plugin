package org.qainsights.jmeter.ai.agent.memory;

import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.session.Session;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consolidates conversation history into memory.
 * When context window exceeds threshold, archives old messages to MEMORY.md and HISTORY.md.
 */
public class MemoryConsolidator {
    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MemoryStore memoryStore;
    private final AiService aiService;
    private final int contextWindowTokens;
    private final double consolidationThreshold;

    public MemoryConsolidator(MemoryStore memoryStore, AiService aiService) {
        this.memoryStore = memoryStore;
        this.aiService = aiService;
        this.contextWindowTokens = Integer.parseInt(AiConfig.getProperty("agent.context.window.tokens", "60000"));
        this.consolidationThreshold = Double.parseDouble(AiConfig.getProperty("agent.memory.consolidation.threshold", "0.5"));
    }

    /**
     * Check if consolidation is needed for the given session
     */
    public boolean needsConsolidation(Session session) {
        if (!memoryStore.isEnabled()) {
            return false;
        }

        int estimatedTokens = estimateSessionTokens(session);
        boolean needs = estimatedTokens > (contextWindowTokens * consolidationThreshold);

        if (needs) {
            log.info("Memory consolidation needed: {} estimated tokens, threshold: {}",
                    estimatedTokens, (int) (contextWindowTokens * consolidationThreshold));
        }

        return needs;
    }

    /**
     * Consolidate messages from a session into memory
     */
    public CompletableFuture<Boolean> consolidateMessages(List<Message> messages, Session session) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!memoryStore.isEnabled() || messages.isEmpty()) {
                    return true;
                }

                log.info("Starting memory consolidation for {} messages", messages.size());

                String currentMemory = memoryStore.readLongTermMemory();
                String messagesText = formatMessages(messages);

                String prompt = buildConsolidationPrompt(currentMemory, messagesText);

                // Call AI to consolidate
                String consolidation = aiService.generateResponse(List.of(prompt));

                // Parse and save
                boolean success = parseAndSaveConsolidation(consolidation);

                if (success) {
                    log.info("Memory consolidation completed successfully");
                }

                return success;

            } catch (Exception e) {
                log.error("Error during memory consolidation", e);
                // Fallback: raw archive
                return rawArchive(messages);
            }
        });
    }

    /**
     * Consolidate a session when needed
     */
    public CompletableFuture<Boolean> maybeConsolidate(Session session) {
        if (!needsConsolidation(session)) {
            return CompletableFuture.completedFuture(true);
        }

        int messagesToArchive = selectMessagesToArchive(session);
        if (messagesToArchive <= 0) {
            return CompletableFuture.completedFuture(true);
        }

        List<Message> messages = session.getHistory(messagesToArchive);
        return consolidateMessages(messages, session).thenApply(success -> {
            if (success) {
                session.setLastConsolidatedIndex(session.getHistory(0).size());
            }
            return success;
        });
    }

    private String buildConsolidationPrompt(String currentMemory, String messagesText) {
        return String.format("""
                You are a memory consolidation agent. Process the following conversation and provide a summary.

                ## Current Long-term Memory
                %s

                ## Conversation to Process
                %s

                Please provide:
                1. A history entry summarizing key events (start with [YYYY-MM-DD HH:MM], include details useful for search)
                2. Updated long-term memory (include all existing facts plus new ones, return unchanged if nothing new)

                Format your response as:
                HISTORY_ENTRY:
                [Your history entry here]

                MEMORY_UPDATE:
                [Your updated long-term memory here]
                """,
                currentMemory.isEmpty() ? "(empty)" : currentMemory,
                messagesText);
    }

    private boolean parseAndSaveConsolidation(String consolidation) {
        try {
            // Parse the response
            String historyEntry = extractSection(consolidation, "HISTORY_ENTRY");
            String memoryUpdate = extractSection(consolidation, "MEMORY_UPDATE");

            // Save history entry
            if (historyEntry != null && !historyEntry.trim().isEmpty()) {
                memoryStore.appendHistory(historyEntry.trim());
            }

            // Save memory update
            if (memoryUpdate != null && !memoryUpdate.trim().isEmpty()) {
                String currentMemory = memoryStore.readLongTermMemory();
                if (!memoryUpdate.equals(currentMemory)) {
                    memoryStore.writeLongTermMemory(memoryUpdate);
                }
            }

            return true;

        } catch (Exception e) {
            log.error("Error parsing consolidation result", e);
            return false;
        }
    }

    private String extractSection(String text, String sectionName) {
        Pattern pattern = Pattern.compile(sectionName + ":\\s*([\\s\\S]*?)(?=\\n[A-Z_]+:|$)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            String timestamp = message.getTimestamp().format(TIMESTAMP_FORMAT);
            String role = message.getRole().toString().toUpperCase();
            String content = message.getContent();
            if (content == null) continue;

            String toolsInfo = message.hasToolCalls() ?
                    " [tools: " + message.getToolCalls().size() + "]" : "";

            sb.append("[").append(timestamp).append("] ")
                    .append(role).append(toolsInfo).append(": ")
                    .append(truncate(content, 200)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }

    private boolean rawArchive(List<Message> messages) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String entry = "[" + timestamp + "] [RAW] " + messages.size() + " messages\n" +
                    formatMessages(messages);
            memoryStore.appendHistory(entry);
            log.warn("Performed raw archive (AI consolidation failed)");
            return true;
        } catch (Exception e) {
            log.error("Raw archive also failed", e);
            return false;
        }
    }

    private int estimateSessionTokens(Session session) {
        // Rough estimation: ~4 characters per token
        List<Message> messages = session.getHistory(0);
        int totalChars = 0;
        for (Message message : messages) {
            if (message.getContent() != null) {
                totalChars += message.getContent().length();
            }
        }
        return totalChars / 4;
    }

    private int selectMessagesToArchive(Session session) {
        // Select messages from last consolidated index to a reasonable boundary
        int lastIndex = session.getLastConsolidatedIndex();
        int totalMessages = session.getHistory(0).size();
        return totalMessages - lastIndex;
    }
}
