package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.context.ContextWindowManager;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.model.*;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
    // 每次注入检查点最多从队列中取出的用户消息数
    private static final int MAX_INJECTIONS_PER_TURN = 3;
    // 单次 Agent run 最多经历的注入周期数；超出部分留在队列，由 finally 块重新提交为独立 processMessage
    private static final int MAX_INJECTION_CYCLES = 5;

    private final ToolRegistry toolRegistry;
    private final MemoryConsolidator memoryConsolidator;
    private final ContextBuilder contextBuilder;
    private final ContextWindowManager contextWindowManager;
    private final SessionManager sessionManager;
    private final AiService aiService;
    private final int defaultMaxIterations;
    private final long toolTimeoutMs;

    // Track the thread running the agent loop so Stop button can interrupt it
    private volatile Thread runningThread;

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
        this.contextWindowManager = new ContextWindowManager(contextTokens);
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

                // Check memory consolidation (Nanobot: maybe_consolidate_by_tokens [sync])
                memoryConsolidator.maybeConsolidate(session).join();

                // Create hook context
                AgentHookContext context = new AgentHookContext(runId, session, spec.getUserMessage());

                // Build initial messages (getHistory now returns only unconsolidated messages)
                List<Message> messages;
                if (spec.getInitialMessages() != null && !spec.getInitialMessages().isEmpty()) {
                    messages = new ArrayList<>(spec.getInitialMessages());
                } else {
                    messages = contextBuilder.buildMessages(
                        session.getHistory(0),
                        spec.getUserMessage(),
                        toolRegistry.getToolDefinitions()
                    );
                }

                // Run agent loop
                AgentRunResult result = runAgentLoop(messages, session, spec, context, startTime);

                // Skip session persistence if task was cancelled (Nanobot: CancelledError skips session.save)
                if (!isAborted(spec)) {
                    int skipCount = Math.max(0, messages.size() - 1);
                    saveMessagesToSession(session, result.getCurrentMessages(), skipCount);
                    memoryConsolidator.maybeConsolidate(session);
                }

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
     * Attempt to drain injected messages and append them to the message list.
     * Ported from Nanobot's _try_drain_injections.
     *
     * @return InjectionResult with shouldContinue, updated injectionCycle, hadInjections
     */
    private InjectionResult tryDrainInjections(
            List<Message> currentMessages,
            AgentRunSpec spec,
            int injectionCycle) {

        if (injectionCycle >= MAX_INJECTION_CYCLES) {
            return InjectionResult.noContinue(injectionCycle);
        }

        Function<Integer, List<String>> callback = spec.getInjectionCallback();
        if (callback == null) {
            return InjectionResult.noContinue(injectionCycle);
        }

        List<String> rawMessages = callback.apply(MAX_INJECTIONS_PER_TURN);
        if (rawMessages == null || rawMessages.isEmpty()) {
            return InjectionResult.noContinue(injectionCycle);
        }

        injectionCycle++;
        appendInjectedMessages(currentMessages, rawMessages);

        log.info("Injected {} messages at cycle {}/{}",
            rawMessages.size(), injectionCycle, MAX_INJECTION_CYCLES);

        return new InjectionResult(true, injectionCycle, true);
    }

    /**
     * Append injected user messages while preserving role alternation.
     * Ported from Nanobot's _append_injected_messages.
     * Consecutive user messages are merged with "\n\n" separator.
     */
    private void appendInjectedMessages(List<Message> currentMessages, List<String> injections) {
        for (String text : injections) {
            if (!currentMessages.isEmpty()
                    && currentMessages.get(currentMessages.size() - 1).getRole() == Message.Role.USER) {
                Message last = currentMessages.get(currentMessages.size() - 1);
                String merged = last.getContent() + "\n\n" + text;
                currentMessages.set(currentMessages.size() - 1, Message.user(merged));
            } else {
                currentMessages.add(Message.user(text));
            }
        }
    }

    private static class InjectionResult {
        final boolean shouldContinue;
        final int injectionCycle;
        final boolean hadInjections;

        InjectionResult(boolean shouldContinue, int injectionCycle, boolean hadInjections) {
            this.shouldContinue = shouldContinue;
            this.injectionCycle = injectionCycle;
            this.hadInjections = hadInjections;
        }

        static InjectionResult noContinue(int cycle) {
            return new InjectionResult(false, cycle, false);
        }
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

        // Track the running thread so cancellation can interrupt it
        runningThread = Thread.currentThread();
        try {
        List<Message> currentMessages = new ArrayList<>(messages);
        List<String> toolsUsed = new ArrayList<>();
        String finalContent = null;
        int maxIterations = spec.getMaxIterations() > 0 ? spec.getMaxIterations() : defaultMaxIterations;
        int iteration = 0;
        int injectionCycles = 0;
        boolean hadInjections = false;
        AgentHook hook = spec.getHook();

        // Build per-run LLM options from spec overrides
        LlmCallOptions llmOptions = LlmCallOptions.builder()
            .model(spec.getModel())
            .temperature(spec.getTemperature())
            .maxTokens(spec.getMaxTokens())
            .reasoningEffort(spec.getReasoningEffort())
            .build();

        while (iteration < maxIterations) {
            iteration++;
            context.setCurrentIteration(iteration);

            // Check abort flag (set by cancellation) and thread interrupt
            if (isAborted(spec)) {
                log.info("Agent loop aborted at iteration {} for session {}", iteration, spec.getSessionKey());
                break;
            }

            if (hook != null) hook.beforeIteration(context);

            // Check for iteration limit
            if (iteration > 1) {
                log.debug("Iteration {}", iteration);
            }

            // Check abort before making LLM call (avoid wasting tokens if already stopped)
            if (isAborted(spec)) {
                log.info("Agent loop aborted before LLM call at iteration {} for session {}", iteration, spec.getSessionKey());
                break;
            }

            // Call LLM
            LLMResponse response = callLLM(currentMessages, llmOptions);
            context.setLastLlmResponse(response);

            // Check abort after LLM call returns
            if (isAborted(spec)) {
                log.info("Agent loop aborted after LLM call at iteration {}", iteration);
                break;
            }

            // Capture usage from LLM response (last iteration wins, matching Nanobot)
            Map<String, Integer> respUsage = response.getUsage();
            if (respUsage != null && !respUsage.isEmpty()) {
                context.setUsage(respUsage);
            }

            if (response.isError()) {
                if ("Interrupted".equals(response.getErrorMessage())) {
                    log.info("Agent loop aborted during LLM call at iteration {}", iteration);
                    break;
                }
                log.error("LLM returned error: {}", response.getErrorMessage());
                finalContent = "I encountered an error: " + response.getErrorMessage();
                if (hook != null) hook.onError(new RuntimeException(response.getErrorMessage()), context);

                // Injection check 4: after LLM error
                InjectionResult inj4 = tryDrainInjections(currentMessages, spec, injectionCycles);
                injectionCycles = inj4.injectionCycle;
                hadInjections |= inj4.hadInjections;
                if (inj4.shouldContinue) {
                        if (hook != null) hook.afterIteration(context);
                        continue;
                    }
                break;
            }

            // Check for tool calls
            if (response.hasToolCalls()) {
                // Add assistant message with tool calls
                currentMessages = contextBuilder.addAssistantMessage(
                    currentMessages,
                    response.getContent(),
                    response.getToolCalls(),
                    response.getReasoningContent()
                );

                if (hook != null) hook.beforeExecuteTools(response.getToolCalls(), context);

                // Check abort before executing tools
                if (isAborted(spec)) {
                    log.info("Agent loop aborted before tool execution at iteration {}", iteration);
                    break;
                }

                // Execute tools (concurrent or serial)
                ToolExecutionResult executionResult = executeToolCalls(
                    response.getToolCalls(),
                    spec.isConcurrentTools()
                );
                List<ToolResult> toolResults = executionResult.results;
                List<org.gitee.jmeter.ai.agent.model.ToolEvent> toolEvents = executionResult.events;

                context.setLastToolResults(toolResults);
                // Add tool events to context
                for (var event : toolEvents) {
                    context.addToolEvent(event);
                }

                // Check for tool errors if failOnToolError is enabled
                if (spec.isFailOnToolError()) {
                    List<org.gitee.jmeter.ai.agent.model.ToolEvent> failedEvents = toolEvents.stream()
                            .filter(org.gitee.jmeter.ai.agent.model.ToolEvent::isError)
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

                        // Injection check 3: after tool fatal error
                        InjectionResult inj3 = tryDrainInjections(currentMessages, spec, injectionCycles);
                        injectionCycles = inj3.injectionCycle;
                        hadInjections |= inj3.hadInjections;
                        if (inj3.shouldContinue) continue;
                        break;
                    }
                }

                if (hook != null) hook.afterExecuteTools(response.getToolCalls(), context);

                // Check abort after tool execution
                if (isAborted(spec)) {
                    log.info("Agent loop aborted after tool execution at iteration {}", iteration);
                    break;
                }

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

                // Injection check 1: after tool execution, before next LLM call
                InjectionResult inj1 = tryDrainInjections(currentMessages, spec, injectionCycles);
                injectionCycles = inj1.injectionCycle;
                hadInjections |= inj1.hadInjections;
                if (inj1.shouldContinue) {
                    if (hook != null) hook.afterIteration(context);
                    continue;
                }

            } else {
                // No tool calls, this is the final response
                finalContent = response.getContent();

                // Injection check 5: empty response
                if (finalContent == null || finalContent.isEmpty()) {
                    InjectionResult inj5 = tryDrainInjections(currentMessages, spec, injectionCycles);
                    injectionCycles = inj5.injectionCycle;
                    hadInjections |= inj5.hadInjections;
                    if (inj5.shouldContinue) {
                        if (hook != null) hook.afterIteration(context);
                        continue;
                    }
                    // No injections and empty → append placeholder and break
                }

                // Append assistant message before checking for injections,
                // so role alternation is preserved: assistant → user(injected).
                currentMessages = contextBuilder.addAssistantMessage(
                    currentMessages, finalContent, null, response.getReasoningContent());

                // Injection check 2: after final response
                InjectionResult inj2 = tryDrainInjections(currentMessages, spec, injectionCycles);
                injectionCycles = inj2.injectionCycle;
                hadInjections |= inj2.hadInjections;
                if (inj2.shouldContinue) {
                    if (hook != null) {
                        hook.onIntermediateResponse(finalContent, context);
                    }
                    finalContent = null;
                    if (hook != null) hook.afterIteration(context);
                    continue;
                }

                break;
            }

            if (hook != null) hook.afterIteration(context);
        }

        // Check max iterations
        if (finalContent == null && iteration >= maxIterations) {
            log.warn("Max iterations reached: {}", maxIterations);

            // Injection drain 6: after max iterations (drain only, don't continue loop)
            if (spec.getInjectionCallback() != null) {
                List<String> remaining = spec.getInjectionCallback().apply(MAX_INJECTIONS_PER_TURN);
                if (remaining != null && !remaining.isEmpty()) {
                    hadInjections = true;
                    appendInjectedMessages(currentMessages, remaining);
                    log.info("Drained {} remaining injected messages after max iterations", remaining.size());
                }
            }

            finalContent = "I reached the maximum number of tool call iterations. Please try breaking the task into smaller steps.";
        }

        // Finalize content through hook
        if (hook != null) {
            finalContent = hook.finalizeContent(finalContent, context);
        }

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
            .currentMessages(currentMessages)
            .metadata(resultMetadata)
            .hadInjections(hadInjections)
            .build();
        } finally {
            runningThread = null;
        }
    }

    /**
     * Call the LLM with the current messages.
     * Uses tool calling if supported by the AI service.
     */
    private LLMResponse callLLM(List<Message> messages, LlmCallOptions options) {
        try {
            // Check if the service supports tool calling
            if (aiService.supportsToolCalling()) {
                log.debug("Using tool calling enabled LLM service");

                // Get tool definitions from the tool registry
                List<org.gitee.jmeter.ai.agent.model.ToolDefinition> tools =
                    toolRegistry.getToolDefinitionObjects();

                log.debug("Calling LLM with {} messages and {} tools", messages.size(), tools.size());

                // Call the service with full messages and tools
                return aiService.generateResponseWithTools(messages, tools, options);
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
        List<org.gitee.jmeter.ai.agent.model.ToolEvent> events = new ArrayList<>();

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
        final List<org.gitee.jmeter.ai.agent.model.ToolEvent> events;

        ToolExecutionResult(List<ToolResult> results, List<org.gitee.jmeter.ai.agent.model.ToolEvent> events) {
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
                    .reasoningContent(msg.getReasoningContent())
                    .metadata(msg.getMetadata())
                    .build();
                session.addMessage(optimizedMsg);
            }
        }
        sessionManager.saveSession(session);
    }

    private boolean isAborted(AgentRunSpec spec) {
        return (spec.getAbortFlag() != null && spec.getAbortFlag().get())
                || Thread.currentThread().isInterrupted();
    }

    /**
     * Interrupt the thread running the agent loop, called by Stop button.
     */
    public void interrupt() {
        Thread t = runningThread;
        if (t != null && t.isAlive()) {
            log.info("Interrupting agent loop thread: {}", t.getName());
            t.interrupt();
        }
    }
}
