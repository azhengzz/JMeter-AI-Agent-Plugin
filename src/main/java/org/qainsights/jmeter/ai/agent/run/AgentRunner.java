package org.qainsights.jmeter.ai.agent.run;

import org.qainsights.jmeter.ai.agent.context.ContextBuilder;
import org.qainsights.jmeter.ai.agent.context.ContextWindowManager;
import org.qainsights.jmeter.ai.agent.hooks.AgentHook;
import org.qainsights.jmeter.ai.agent.hooks.AgentHookContext;
import org.qainsights.jmeter.ai.agent.memory.MemoryConsolidator;
import org.qainsights.jmeter.ai.agent.model.*;
import org.qainsights.jmeter.ai.agent.session.Session;
import org.qainsights.jmeter.ai.agent.session.SessionManager;
import org.qainsights.jmeter.ai.agent.tools.ToolRegistry;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Core Agent Runner - extracted from AgentLoop.
 * Handles the main agent iteration loop with hook support.
 *
 * Responsibilities:
 * - Run agent iteration loop
 * - Execute hooks at appropriate points
 * - Support concurrent tool execution
 * - Manage agent state
 */
public class AgentRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final String DEFAULT_RUN_ID_PREFIX = "run-";

    private final ToolRegistry toolRegistry;
    private final MemoryConsolidator memoryConsolidator;
    private final ContextBuilder contextBuilder;
    private final ContextWindowManager contextWindowManager;
    private final SessionManager sessionManager;
    private final AiService aiService;
    private final int defaultMaxIterations;
    private final long toolTimeoutMs;

    /**
     * Create an AgentRunner.
     */
    public AgentRunner(
            ToolRegistry toolRegistry,
            MemoryConsolidator memoryConsolidator,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            AiService aiService,
            int maxIterations,
            int toolResultMaxChars,
            long toolTimeoutMs) {
        this.toolRegistry = toolRegistry;
        this.memoryConsolidator = memoryConsolidator;
        this.contextBuilder = contextBuilder;
        int contextTokens = Integer.parseInt(AiConfig.getProperty("jmeter.ai.context.window.tokens", "65536"));
        this.contextWindowManager = new ContextWindowManager(contextTokens, 0.10);
        this.sessionManager = sessionManager;
        this.aiService = aiService;
        this.defaultMaxIterations = maxIterations;
        this.toolTimeoutMs = toolTimeoutMs;
        // toolResultMaxChars is used in MessageOptimizer
    }

    /**
     * Get the AI service used by this runner.
     */
    public AiService getAiService() {
        return aiService;
    }

    /**
     * Run an agent with the given specification.
     */
    public CompletableFuture<AgentRunResult> run(AgentRunSpec spec) {
        return CompletableFuture.supplyAsync(() -> {
            String runId = DEFAULT_RUN_ID_PREFIX + java.util.UUID.randomUUID().toString().substring(0, 8);
            Instant startTime = Instant.now();

            try {
                log.info("Starting agent run {} for session: {}", runId, spec.getSessionKey());

                // Get or create session
                Session session = sessionManager.getOrCreate(spec.getSessionKey());

                // Check memory consolidation
                memoryConsolidator.maybeConsolidate(session).join();

                // Create hook context
                AgentHookContext context = new AgentHookContext(runId, session, spec.getUserMessage());

                // Build initial messages
                List<Message> messages = contextBuilder.buildMessages(
                    session.getHistory(0),
                    spec.getUserMessage(),
                    toolRegistry.getToolDefinitions()
                );

                // Trim to context window if needed (intelligent context management)
                messages = contextWindowManager.trimToContextWindow(messages, contextWindowManager.maxContextTokens);

                // Run agent loop
                AgentRunResult result = runAgentLoop(messages, session, spec, context, startTime);

                log.info("Agent run {} completed with success={}", runId, result.isSuccess());
                return result;

            } catch (Exception e) {
                log.error("Agent run " + runId + " failed", e);
                return AgentRunResult.builder()
                    .runId(runId)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .startTime(startTime)
                    .endTime(Instant.now())
                    .build();
            }
        });
    }

    /**
     * Run the main agent iteration loop.
     */
    private AgentRunResult runAgentLoop(
            List<Message> messages,
            Session session,
            AgentRunSpec spec,
            AgentHookContext context,
            Instant startTime) {

        List<Message> currentMessages = new ArrayList<>(messages);
        List<String> toolsUsed = new ArrayList<>();
        String finalContent = null;
        int maxIterations = spec.getMaxIterations() > 0 ? spec.getMaxIterations() : defaultMaxIterations;
        int iteration = 0;
        AgentHook hook = spec.getHook();

        while (iteration < maxIterations) {
            iteration++;
            context.setCurrentIteration(iteration);

            if (hook != null) hook.beforeIteration(context);

            // Check for iteration limit
            if (iteration > 1) {
                log.debug("Iteration {}", iteration);
            }

            // Call LLM
            LLMResponse response = callLLM(currentMessages);
            context.setLastLlmResponse(response);

            // Accumulate usage from LLM response
            Map<String, Integer> respUsage = response.getUsage();
            if (respUsage != null && !respUsage.isEmpty()) {
                for (Map.Entry<String, Integer> entry : respUsage.entrySet()) {
                    context.addUsage(entry.getKey(), entry.getValue());
                }
            }

            if (response.isError()) {
                log.error("LLM returned error: {}", response.getErrorMessage());
                finalContent = "I encountered an error: " + response.getErrorMessage();
                if (hook != null) hook.onError(new RuntimeException(response.getErrorMessage()), context);
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

                if (hook != null) hook.beforeExecuteTools(response.getToolCalls(), context);

                // Execute tools (concurrent or serial)
                ToolExecutionResult executionResult = executeToolCalls(
                    response.getToolCalls(),
                    spec.isConcurrentTools()
                );
                List<ToolResult> toolResults = executionResult.results;
                List<org.qainsights.jmeter.ai.agent.model.ToolEvent> toolEvents = executionResult.events;

                context.setLastToolResults(toolResults);
                // Add tool events to context
                for (var event : toolEvents) {
                    context.addToolEvent(event);
                }

                // Check for tool errors if failOnToolError is enabled
                if (spec.isFailOnToolError()) {
                    List<org.qainsights.jmeter.ai.agent.model.ToolEvent> failedEvents = toolEvents.stream()
                            .filter(org.qainsights.jmeter.ai.agent.model.ToolEvent::isError)
                            .toList();
                    if (!failedEvents.isEmpty()) {
                        String error = failedEvents.stream()
                                .map(e -> e.getToolName() + ": " + e.getDetail())
                                .collect(Collectors.joining("; "));
                        log.error("Tool execution failed (failOnToolError=true): {}", error);
                        context.setError("Tool execution failed: " + error);
                        context.setStopReason("tool_error");
                        if (hook != null) hook.afterIteration(context);
                        finalContent = "Error: Tool execution failed: " + error;
                        break;
                    }
                }

                if (hook != null) hook.afterExecuteTools(response.getToolCalls(), context);

                // Add tool results to messages
                for (int i = 0; i < response.getToolCalls().size(); i++) {
                    ToolCall call = response.getToolCalls().get(i);
                    if (i < toolResults.size()) {
                        currentMessages = contextBuilder.addToolResult(
                            currentMessages,
                            call.getId(),
                            call.getName(),
                            toolResults.get(i).getResult()
                        );
                    }
                }

                // Track tools used
                List<String> iterationTools = response.getToolCalls().stream()
                    .map(ToolCall::getName)
                    .collect(Collectors.toList());
                toolsUsed.addAll(iterationTools);
                for (String toolName : iterationTools) {
                    context.addToolUsed(toolName);
                }

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

            if (hook != null) hook.afterIteration(context);
        }

        // Check max iterations
        if (finalContent == null && iteration >= maxIterations) {
            log.warn("Max iterations reached: {}", maxIterations);
            finalContent = "I reached the maximum number of tool call iterations. Please try breaking the task into smaller steps.";
        }

        // Finalize content through hook
        if (hook != null) {
            finalContent = hook.finalizeContent(finalContent, context);
        }

        // Save messages to session
        // Include user message (last of initial messages) and all new messages
        int skipCount = Math.max(0, messages.size() - 1);
        saveMessagesToSession(session, currentMessages, skipCount);

        // Trigger background memory consolidation
        memoryConsolidator.maybeConsolidate(session);

        // Build result
        java.util.Map<String, Object> resultMetadata = new java.util.HashMap<>();
        resultMetadata.put("usage", context.getUsage());

        return AgentRunResult.builder()
            .runId(context.getRunId())
            .content(finalContent)
            .toolsUsed(toolsUsed)
            .iterationCount(iteration)
            .success(true)
            .startTime(startTime)
            .endTime(Instant.now())
            .session(session)
            .toolEvents(context.getToolEvents())
            .metadata(resultMetadata)
            .build();
    }

    /**
     * Call the LLM with the current messages.
     * Uses tool calling if supported by the AI service.
     */
    private LLMResponse callLLM(List<Message> messages) {
        try {
            // Check if the service supports tool calling
            if (aiService.supportsToolCalling()) {
                log.debug("Using tool calling enabled LLM service");

                // Get tool definitions from the tool registry
                List<org.qainsights.jmeter.ai.agent.model.ToolDefinition> tools =
                    toolRegistry.getToolDefinitionObjects();

                log.debug("Calling LLM with {} messages and {} tools", messages.size(), tools.size());

                // Call the service with full messages and tools
                return aiService.generateResponseWithTools(messages, tools);
            } else {
                // Fall back to simple text-based response
                log.debug("Using simple text-based LLM service (no tool calling support)");

                // Convert messages to format expected by AI service
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

                // Check if the response is an error message from the AI service
                // OpenAiService returns errors as "Error: ..." strings
                if (response != null && response.startsWith("Error:")) {
                    log.warn("AI service returned an error response: {}", response);
                    return LLMResponse.error(response.substring(7)); // Remove "Error: " prefix
                }

                return LLMResponse.text(response);
            }

        } catch (Exception e) {
            log.error("Error calling LLM", e);
            return LLMResponse.error(e.getMessage());
        }
    }

    /**
     * Execute tool calls (concurrent or serial).
     * Returns both tool results and tool events.
     */
    private ToolExecutionResult executeToolCalls(List<ToolCall> toolCalls, boolean concurrent) {
        List<ToolResult> results;
        List<org.qainsights.jmeter.ai.agent.model.ToolEvent> events = new ArrayList<>();

        if (concurrent) {
            // Execute concurrently using ToolRegistry's async support with timeout
            var batchResult = toolRegistry.executeAsyncWithEvents(toolCalls, toolTimeoutMs).join();
            results = batchResult.results();
            events = new ArrayList<>(batchResult.events());
        } else {
            // Execute serially
            results = new ArrayList<>();
            for (ToolCall call : toolCalls) {
                var executionResult = toolRegistry.executeWithEvent(call.getName(), call.getArguments());
                results.add(executionResult.result());
                events.add(executionResult.event());

                if (!executionResult.result().isSuccess()) {
                    log.warn("Tool {} failed: {}", call.getName(), executionResult.result().getError());
                }
            }
        }

        return new ToolExecutionResult(results, events);
    }

    /**
     * Helper class to hold tool execution results and events
     */
    private static class ToolExecutionResult {
        final List<ToolResult> results;
        final List<org.qainsights.jmeter.ai.agent.model.ToolEvent> events;

        ToolExecutionResult(List<ToolResult> results, List<org.qainsights.jmeter.ai.agent.model.ToolEvent> events) {
            this.results = results;
            this.events = events;
        }
    }

    /**
     * Save new messages to session with optimization.
     * Based on Nanobot's session persistence optimizations.
     */
    private void saveMessagesToSession(Session session, List<Message> allMessages, int skipCount) {
        for (int i = skipCount; i < allMessages.size(); i++) {
            Message msg = allMessages.get(i);

            // Skip messages that should be skipped
            if (MessageOptimizer.shouldSkip(msg)) {
                continue;
            }

            // Optimize content for persistence
            String optimizedContent = MessageOptimizer.optimizeContent(
                msg.getRole(), msg.getContent(), msg.hasToolCalls());

            if (optimizedContent != null) {
                // Create optimized message (with same properties but modified content)
                Message optimizedMsg = Message.builder()
                    .role(msg.getRole())
                    .content(optimizedContent)
                    .toolCalls(msg.getToolCalls())
                    .toolCallId(msg.getToolCallId())
                    .metadata(msg.getMetadata())
                    .build();
                session.addMessage(optimizedMsg);
            }
        }
        sessionManager.saveSession(session);
    }
}
