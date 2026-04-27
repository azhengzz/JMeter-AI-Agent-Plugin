package org.qainsights.jmeter.ai.agent.memory;

import org.qainsights.jmeter.ai.agent.model.*;
import org.qainsights.jmeter.ai.agent.session.Session;
import org.qainsights.jmeter.ai.agent.session.SessionManager;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-powered memory consolidation aligned with Nanobot's MemoryConsolidator.
 * Uses forced tool calling (save_memory) for structured output,
 * with text-based fallback and raw-archive degradation.
 */
public class MemoryConsolidator {
    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONSOLIDATION_ROUNDS = 5;
    private static final int SAFETY_BUFFER = 1024;

    private static final ToolDefinition SAVE_MEMORY_TOOL_DEF = ToolDefinition.builder()
            .name("save_memory")
            .description("Save the memory consolidation result to persistent storage.")
            .parameters(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "history_entry", Map.of(
                                    "type", "string",
                                    "description", "A paragraph summarizing key events/decisions/topics. Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search."
                            ),
                            "memory_update", Map.of(
                                    "type", "string",
                                    "description", "Full updated long-term memory as markdown. Include all existing facts plus new ones. Return unchanged if nothing new."
                            )
                    ),
                    "required", List.of("history_entry", "memory_update")
            ))
            .build();

    private final MemoryStore memoryStore;
    private final AiService aiService;
    private final SessionManager sessionManager;
    private final int contextWindowTokens;
    private final int maxCompletionTokens;
    private final double consolidationThreshold;
    private int consecutiveFailures = 0;

    public MemoryConsolidator(MemoryStore memoryStore, AiService aiService, SessionManager sessionManager) {
        this.memoryStore = memoryStore;
        this.aiService = aiService;
        this.sessionManager = sessionManager;
        this.contextWindowTokens = Integer.parseInt(AiConfig.getProperty("jmeter.ai.context.window.tokens", "65536"));
        this.maxCompletionTokens = Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tokens", "4096"));
        this.consolidationThreshold = Double.parseDouble(AiConfig.getProperty("agent.memory.consolidation.threshold", "0.5"));
    }

    /**
     * Check if consolidation is needed for the given session.
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
     * Consolidate a session when needed — multi-round support (Nanobot alignment).
     * Archives old messages in rounds until token count drops to target.
     */
    public CompletableFuture<Boolean> maybeConsolidate(Session session) {
        if (!needsConsolidation(session)) {
            return CompletableFuture.completedFuture(true);
        }

        int budget = contextWindowTokens - maxCompletionTokens - SAFETY_BUFFER;
        int target = budget / 2;

        return CompletableFuture.supplyAsync(() -> {
            for (int round = 0; round < MAX_CONSOLIDATION_ROUNDS; round++) {
                int estimated = estimateSessionTokens(session);
                if (estimated <= target) {
                    log.debug("Consolidation target reached: {} <= {} tokens", estimated, target);
                    break;
                }

                int boundary = pickConsolidationBoundary(session, Math.max(1, estimated - target));
                if (boundary <= session.getLastConsolidatedIndex()) {
                    log.debug("No safe consolidation boundary found (round {})", round);
                    break;
                }

                List<Message> chunk = session.getMessagesInRange(
                        session.getLastConsolidatedIndex(), boundary);
                if (chunk.isEmpty()) {
                    break;
                }

                log.info("Consolidation round {} for session {}: estimated={}/{} tokens, chunk={} msgs",
                        round, session.getKey(), estimated, contextWindowTokens, chunk.size());

                if (!consolidateWithAi(chunk)) {
                    log.warn("Consolidation round {} failed, stopping", round);
                    break;
                }

                session.setLastConsolidatedIndex(boundary);
                if (sessionManager != null) {
                    sessionManager.saveSession(session);
                }
            }
            return true;
        });
    }

    /**
     * Archive messages with guaranteed persistence (Nanobot's archive_messages).
     * Retries AI consolidation up to MAX_RETRIES times, then falls back to raw archive.
     */
    public CompletableFuture<Boolean> archiveMessagesAsync(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            log.info("Archiving {} messages with AI consolidation", messages.size());

            for (int i = 0; i < MAX_RETRIES; i++) {
                if (consolidateWithAi(messages)) {
                    consecutiveFailures = 0;
                    return true;
                }
                log.warn("AI consolidation attempt {}/{} failed", i + 1, MAX_RETRIES);
            }

            // All retries failed — raw archive as last resort
            log.warn("All AI consolidation attempts failed, falling back to raw archive");
            rawArchive(messages);
            return true;
        });
    }

    /**
     * Core AI consolidation logic — tries forced tool calling, falls back to text-based.
     */
    private boolean consolidateWithAi(List<Message> messages) {
        try {
            String currentMemory = memoryStore.readLongTermMemory();
            String messagesText = formatMessages(messages);

            if (aiService.supportsForcedToolChoice()) {
                return consolidateViaToolCall(currentMemory, messagesText, messages);
            } else {
                return consolidateViaText(currentMemory, messagesText, messages);
            }
        } catch (Exception e) {
            log.warn("AI consolidation attempt failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Nanobot-mode: use forced tool calling to get structured save_memory output.
     * Aligned with Nanobot: only retries with auto if forced returns error about unsupported tool_choice.
     * If LLM ignores tool_choice (finishReason=stop, no tool calls), returns failure for outer retry loop.
     */
    private boolean consolidateViaToolCall(String currentMemory, String messagesText, List<Message> originalMessages) {
        List<Message> chatMessages = List.of(
                Message.system("You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."),
                Message.user(buildConsolidationPrompt(currentMemory, messagesText))
        );

        try {
            // Nanobot line 139-145: try forced tool_choice first
            LLMResponse response = aiService.generateResponseWithForcedTool(
                    chatMessages, List.of(SAVE_MEMORY_TOOL_DEF), "save_memory");

            // Nanobot line 147-156: if error about unsupported tool_choice, retry with auto
            if (response.isError() && isToolChoiceUnsupported(response.getErrorMessage())) {
                log.warn("Forced tool_choice unsupported, retrying with auto");
                response = aiService.generateResponseWithTools(chatMessages, List.of(SAVE_MEMORY_TOOL_DEF));
            }

            // Nanobot line 158-166: if no tool calls, fail
            if (!response.hasToolCalls()) {
                log.warn("LLM did not call save_memory tool (finishReason={}, content_len={}, content_preview={})",
                        response.getFinishReason(),
                        response.getContent() != null ? response.getContent().length() : 0,
                        response.getContent() != null ? response.getContent().substring(0, Math.min(200, response.getContent().length())) : "");
                return handleConsolidationFailure(originalMessages);
            }

            // Nanobot line 168-196: validate args and save
            return extractAndSaveToolCallResult(response, currentMemory, originalMessages);

        } catch (Exception e) {
            // Nanobot line 197-199: exception → fail_or_raw_archive
            log.error("Memory consolidation failed", e);
            return handleConsolidationFailure(originalMessages);
        }
    }

    /**
     * Extract save_memory tool call result and persist.
     */
    private boolean extractAndSaveToolCallResult(LLMResponse response, String currentMemory, List<Message> originalMessages) {
        ToolCall saveCall = response.getToolCalls().stream()
                .filter(tc -> "save_memory".equals(tc.getName()))
                .findFirst().orElse(null);

        if (saveCall == null) {
            log.warn("No save_memory tool call in response");
            return handleConsolidationFailure(originalMessages);
        }

        Map<String, Object> args = saveCall.getArguments();
        String historyEntry = normalizeToString(args.get("history_entry"));
        String memoryUpdate = normalizeToString(args.get("memory_update"));

        if (historyEntry.isEmpty()) {
            log.warn("history_entry is empty after normalization");
            return handleConsolidationFailure(originalMessages);
        }

        memoryStore.appendHistory(historyEntry);
        if (!memoryUpdate.equals(currentMemory)) {
            memoryStore.writeLongTermMemory(memoryUpdate);
        }

        log.info("Memory consolidation done (tool-call mode): history={} chars, memory_changed={}",
                historyEntry.length(), !memoryUpdate.equals(currentMemory));
        return true;
    }

    private boolean isToolChoiceUnsupported(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("tool_choice") || lower.contains("does not support")
                || lower.contains("should be [\"none\", \"auto\"]");
    }

    /**
     * Text-mode fallback: parse HISTORY_ENTRY / MEMORY_UPDATE markers from response.
     */
    private boolean consolidateViaText(String currentMemory, String messagesText, List<Message> originalMessages) {
        String prompt = buildConsolidationPrompt(currentMemory, messagesText);
        String response = aiService.generateResponse(List.of(prompt));
        boolean success = parseAndSaveConsolidation(response);

        if (!success) {
            return handleConsolidationFailure(originalMessages);
        }

        log.info("Memory consolidation done (text mode)");
        return true;
    }

    /**
     * Handle consolidation failure — track consecutive failures, raw-archive if threshold reached.
     */
    private boolean handleConsolidationFailure(List<Message> messages) {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_RETRIES) {
            rawArchive(messages);
            consecutiveFailures = 0;
        }
        return false;
    }

    /**
     * Pick a user-turn boundary that removes enough old prompt tokens (Nanobot alignment).
     */
    private int pickConsolidationBoundary(Session session, int tokensToRemove) {
        List<Message> all = session.getHistory(0);
        int start = session.getLastConsolidatedIndex();
        if (start >= all.size() || tokensToRemove <= 0) {
            return start;
        }

        int removedTokens = 0;
        int lastBoundary = start;

        for (int i = start; i < all.size(); i++) {
            Message msg = all.get(i);
            removedTokens += (msg.getContent() != null ? msg.getContent().length() / 4 : 0);
            if (i > start && msg.getRole() == Message.Role.USER) {
                lastBoundary = i;
                if (removedTokens >= tokensToRemove) {
                    return lastBoundary;
                }
            }
        }

        return lastBoundary > start ? lastBoundary : all.size();
    }

    // --- Prompt building and parsing ---

    private String buildConsolidationPrompt(String currentMemory, String messagesText) {
        return String.format("""
                Process this conversation and call the save_memory tool with your consolidation.

                ## Current Long-term Memory
                %s

                ## Conversation to Process
                %s
                """,
                currentMemory.isEmpty() ? "(empty)" : currentMemory,
                messagesText);
    }

    private boolean parseAndSaveConsolidation(String consolidation) {
        try {
            String historyEntry = extractSection(consolidation, "HISTORY_ENTRY");
            String memoryUpdate = extractSection(consolidation, "MEMORY_UPDATE");

            if (historyEntry != null && !historyEntry.trim().isEmpty()) {
                memoryStore.appendHistory(historyEntry.trim());
            }

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

    // --- Formatting utilities ---

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

    private String normalizeToString(Object value) {
        if (value == null) return "";
        if (value instanceof String) return ((String) value).trim();
        String str = value.toString();
        if (str.startsWith("\"") && str.endsWith("\"")) {
            return str.substring(1, str.length() - 1).trim();
        }
        return str.trim();
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

    /**
     * Estimate token count for a session (~4 chars per token).
     * Public so commands like /status can use it.
     */
    public int estimateSessionTokens(Session session) {
        List<Message> messages = session.getHistory(0);
        int totalChars = 0;
        for (Message message : messages) {
            if (message.getContent() != null) {
                totalChars += message.getContent().length();
            }
        }
        return totalChars / 4;
    }
}
