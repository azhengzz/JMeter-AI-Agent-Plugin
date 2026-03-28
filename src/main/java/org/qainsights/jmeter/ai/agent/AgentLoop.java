package org.qainsights.jmeter.ai.agent;

import org.qainsights.jmeter.ai.agent.context.ContextBuilder;
import org.qainsights.jmeter.ai.agent.memory.MemoryConsolidator;
import org.qainsights.jmeter.ai.agent.memory.MemoryStore;
import org.qainsights.jmeter.ai.agent.model.*;
import org.qainsights.jmeter.ai.agent.session.Session;
import org.qainsights.jmeter.ai.agent.session.SessionManager;
import org.qainsights.jmeter.ai.agent.tools.ToolRegistry;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core Agent Loop processing engine.
 * Implements the Message -> LLM -> Tools -> Response -> Message loop.
 */
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final MemoryConsolidator memoryConsolidator;
    private final ContextBuilder contextBuilder;
    private final SessionManager sessionManager;
    private final AiService aiService;
    private final int maxIterations;
    private final int toolResultMaxChars;
    private final ExecutorService executorService;

    private ProgressCallback progressCallback;

    public AgentLoop(
            ToolRegistry toolRegistry,
            MemoryStore memoryStore,
            MemoryConsolidator memoryConsolidator,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            AiService aiService) {
        this.toolRegistry = toolRegistry;
        this.memoryStore = memoryStore;
        this.memoryConsolidator = memoryConsolidator;
        this.contextBuilder = contextBuilder;
        this.sessionManager = sessionManager;
        this.aiService = aiService;
        this.maxIterations = Integer.parseInt(AiConfig.getProperty("agent.max.iterations", "40"));
        this.toolResultMaxChars = Integer.parseInt(AiConfig.getProperty("agent.tool.result.max.chars", "16000"));
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agent-loop");
            thread.setDaemon(true);
            return thread;
        });

        log.info("AgentLoop initialized with maxIterations={}, tools={}", maxIterations, toolRegistry.size());
    }

    /**
     * Set progress callback for this processing run
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Process a message through the agent loop
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey) {
        return processMessage(message, sessionKey, null);
    }

    /**
     * Process a message through the agent loop with progress callback
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey,
            ProgressCallback callback) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                this.progressCallback = callback;

                log.info("Processing message for session {}: '{}'", sessionKey, truncate(message, 100));

                // Get or create session
                Session session = sessionManager.getOrCreate(sessionKey);

                // Check memory consolidation
                memoryConsolidator.maybeConsolidate(session).join();

                // Build messages
                List<Message> messages = contextBuilder.buildMessages(
                        session.getHistory(0),
                        message,
                        toolRegistry.getToolDefinitions()
                );

                // Run agent loop
                return runAgentLoop(messages, session);

            } catch (Exception e) {
                log.error("Error processing message", e);
                return AgentResponse.error("Processing failed: " + e.getMessage());
            }
        }, executorService);
    }

    /**
     * Run the main agent iteration loop
     */
    private AgentResponse runAgentLoop(List<Message> messages, Session session) {
        List<Message> currentMessages = new ArrayList<>(messages);
        List<String> toolsUsed = new ArrayList<>();
        String finalContent = null;
        int iteration = 0;

        while (iteration < maxIterations) {
            iteration++;

            // Check for iteration limit
            if (iteration > 1) {
                publishProgress("Iteration " + iteration + "...");
            }

            // Call LLM
            LLMResponse response = callLLM(currentMessages);
            if (response.isError()) {
                log.error("LLM returned error: {}", response.getErrorMessage());
                finalContent = "I encountered an error: " + response.getErrorMessage();
                break;
            }

            // Check for tool calls
            if (response.hasToolCalls()) {
                // Add assistant message with tool calls
                currentMessages = contextBuilder.addAssistantMessage(
                        currentMessages,
                        response.getContent(),
                        response.getToolCalls()
                );

                // Execute tools
                ToolExecutionResult toolResult = executeToolCalls(response.getToolCalls());

                // Add tool results to messages
                for (int i = 0; i < response.getToolCalls().size(); i++) {
                    ToolCall call = response.getToolCalls().get(i);
                    if (i < toolResult.results.size()) {
                        currentMessages = contextBuilder.addToolResult(
                                currentMessages,
                                call.getId(),
                                call.getName(),
                                toolResult.results.get(i)
                        );
                    }
                }

                toolsUsed.addAll(toolResult.toolsUsed);

                // Publish progress
                if (response.getContent() != null && !response.getContent().isEmpty()) {
                    publishProgress(stripThinkBlocks(response.getContent()));
                }
                publishProgress("Used tools: " + String.join(", ", toolResult.toolsUsed));

            } else {
                // No tool calls, this is the final response
                currentMessages = contextBuilder.addAssistantMessage(
                        currentMessages,
                        response.getContent(),
                        null
                );
                finalContent = response.getContent();
                break;
            }
        }

        // Check max iterations
        if (finalContent == null && iteration >= maxIterations) {
            log.warn("Max iterations reached: {}", maxIterations);
            finalContent = "I reached the maximum number of tool call iterations. Please try breaking the task into smaller steps.";
        }

        // Save messages to session (only new ones)
        saveMessagesToSession(session, currentMessages, messages.size());

        // Trigger background memory consolidation
        memoryConsolidator.maybeConsolidate(session);

        return AgentResponse.success(finalContent, toolsUsed, iteration);
    }

    /**
     * Call the LLM with the current messages
     */
    private LLMResponse callLLM(List<Message> messages) {
        try {
            // Convert messages to format expected by AI service
            // For now, use the existing simple interface
            List<String> conversation = new ArrayList<>();
            for (Message msg : messages) {
                if (msg.getRole() == Message.Role.SYSTEM) {
                    continue; // System prompt is handled by the service
                }
                if (msg.getRole() == Message.Role.TOOL) {
                    continue; // Skip tool results for now
                }
                if (msg.getContent() != null) {
                    conversation.add(msg.getContent());
                }
            }

            String response = aiService.generateResponse(conversation);
            return LLMResponse.text(response);

        } catch (Exception e) {
            log.error("Error calling LLM", e);
            return LLMResponse.error(e.getMessage());
        }
    }

    /**
     * Execute tool calls
     */
    private ToolExecutionResult executeToolCalls(List<ToolCall> toolCalls) {
        ToolExecutionResult result = new ToolExecutionResult();
        result.results = new ArrayList<>();
        result.toolsUsed = new ArrayList<>();

        for (ToolCall call : toolCalls) {
            publishProgress("Executing tool: " + call.getName());

            var toolResult = toolRegistry.execute(call.getName(), call.getArguments());

            result.toolsUsed.add(call.getName());
            result.results.add(toolResult.getResult());

            if (!toolResult.isSuccess()) {
                log.warn("Tool {} failed: {}", call.getName(), toolResult.getError());
            }
        }

        return result;
    }

    /**
     * Save new messages to session
     */
    private void saveMessagesToSession(Session session, List<Message> allMessages, int skipCount) {
        for (int i = skipCount; i < allMessages.size(); i++) {
            Message msg = allMessages.get(i);
            // Skip empty assistant messages
            if (msg.getRole() == Message.Role.ASSISTANT && !msg.hasToolCalls()) {
                if (msg.getContent() == null || msg.getContent().isEmpty()) {
                    continue;
                }
            }
            // Skip system messages
            if (msg.getRole() == Message.Role.SYSTEM) {
                continue;
            }
            session.addMessage(msg);
        }
        sessionManager.saveSession(session);
    }

    /**
     * Publish progress update
     */
    private void publishProgress(String message) {
        if (progressCallback != null) {
            try {
                progressCallback.onProgress(message);
            } catch (Exception e) {
                log.warn("Error publishing progress", e);
            }
        }
    }

    /**
     * Strip thinking blocks from content
     */
    private String stripThinkBlocks(String content) {
        if (content == null) {
            return null;
        }
        return content.replaceAll("<think>.*?</think>", "").trim();
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }

    /**
     * Get the tool registry
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Get the session manager
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Shutdown the agent loop
     */
    public void shutdown() {
        executorService.shutdown();
        sessionManager.shutdown();
        log.info("AgentLoop shutdown complete");
    }

    /**
     * Progress callback interface
     */
    public interface ProgressCallback {
        void onProgress(String message);
    }

    /**
     * Internal class for tool execution results
     */
    private static class ToolExecutionResult {
        List<String> results;
        List<String> toolsUsed;
    }
}
