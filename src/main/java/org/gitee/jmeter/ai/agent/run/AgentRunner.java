package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.context.ContextWindowManager;
import org.gitee.jmeter.ai.agent.config.AgentConfig;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.model.*;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.DelegationGuard;
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
import java.util.function.BooleanSupplier;
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
        this.contextWindowManager = new ContextWindowManager(contextTokens, memoryConsolidator);
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
        java.util.function.Supplier<AgentRunResult> task = () -> {
            String runId = DEFAULT_RUN_ID_PREFIX + java.util.UUID.randomUUID().toString().substring(0, 8);
            Instant startTime = Instant.now();

            // Track this thread from the very start of the run — NOT only inside
            // runAgentLoop — so Stop/cancel can interrupt the pre-loop consolidation
            // window too (interrupt() targets runningThread, which is null until
            // the run task starts otherwise).
            runningThread = Thread.currentThread();
            // 载体线程是池化的:上一轮被 Stop 取消的回合,其 interrupt() 可能晚于 finally 的
            // Thread.interrupted() 才送达(interrupt() 读→log→interrupt 的 TOCTOU 窗口),
            // 在复用载体上留下残留中断。入口处清一次,避免这一轮
            // 一进 while 迭代 1 就因 isInterrupted() 直接 break 返回空回复。取消语义不受影响:
            // signalCancel 先置 abort flag 再 interrupt,flag 才是取消的唯一事实来源。
            Thread.interrupted();

            // Bind the run identity so tools (e.g. spawn) can learn their session.
            AgentRunContext.set(new AgentRunContext(spec.getSessionKey(), runId));
            // isDelegated() == true 标识"当前这一个 Agent 回合是被别的实例委派过来的"，
            // DelegationGuard.begin() 在这个回合的执行线程上做一个 ThreadLocal 标记，
            // 用于禁止这个回合里再往别的实例委派（深度 1 硬阻断）。
            // 工具 DelegateToInstanceTool 在委派前会判单该标识。
            if (spec.isDelegated()) {
                DelegationGuard.begin();
            }
            try {
                log.info("Starting agent run {} for session: {}", runId, spec.getSessionKey());

                // Subagent runs stay fully ephemeral: never touch SessionManager, so
                // nothing about them can reach the main session's jsonl.
                Session session = spec.isPersistSession()
                    ? sessionManager.getOrCreate(spec.getSessionKey())
                    : new Session(spec.getSessionKey());

                // Check memory consolidation (Nanobot: maybe_consolidate_by_tokens [sync]).
                // Runs inline on this run thread; the cancel truth is the shared abort flag —
                // signalCancel sets the flag BEFORE interrupt, so an interrupt landing inside
                // the lock wait or the LLM call converges to the same no-write outcome as the
                // flag (spec's flag IS the one the cancelActiveTask map holds). The supplier
                // is flag-only and declared once, reused by the post-loop call below.
                BooleanSupplier abortSignal = () -> isAbortedFlag(spec);
                if (spec.isPersistSession()) {
                    memoryConsolidator.maybeConsolidate(session, abortSignal);
                }

                // Create hook context
                AgentHookContext context = new AgentHookContext(runId, session, spec.getUserMessage());

                // Build initial messages (getHistory now returns only unconsolidated messages)
                List<Message> messages;
                if (spec.getInitialMessages() != null && !spec.getInitialMessages().isEmpty()) {
                    messages = new ArrayList<>(spec.getInitialMessages());
                } else {
                    messages = contextBuilder.buildMessages(
                        session.getHistory(AgentConfig.getInstance().getMaxHistorySize()),
                        spec.getUserMessage(),
                        toolRegistry.getToolDefinitions()
                    );
                }

                // Run agent loop
                AgentRunResult result = runAgentLoop(messages, session, spec, context, startTime);

                // Skip session persistence if task was cancelled (Nanobot: CancelledError skips session.save)
                // and always skip it for ephemeral subagent runs.
                if (spec.isPersistSession() && !isAborted(spec)) {
                    int skipCount = Math.max(0, messages.size() - 1);
                    saveMessagesToSession(session, result.getCurrentMessages(), skipCount);
                    // 后置整合必须同步内联、跑在 run 任务线程上,不能丢到后台线程。前提:
                    // run future 一旦 complete,AgentLoop.whenComplete 会立即把本回合的 abort
                    // flag 从 map 移除,cancelActiveTask 靠查这个 map 才能取消一个回合。
                    //
                    // 时序保证:整合和回合同线程、顺序执行 → 整合必然在 future complete 之前
                    // 跑完,whenComplete 移除 flag 必然发生在整合之后 → 关闭期间 cancelActiveTask
                    // 一直能找到这个 flag,整合可被正常取消。若丢到后台线程,flag 可能先被移除,
                    // 整合就成了取消不到的"僵尸回合",关闭时照常写盘,与关闭对话框的深度提炼
                    // 抢写 HISTORY/MEMORY(重复条目、后写覆盖)。
                    //
                    // 取消兜底:整合等锁/写盘前都查 abortSignal 共享 flag,被取消则不落盘,与前置整合一致。
                    memoryConsolidator.maybeConsolidate(session, abortSignal);
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
            } finally {
                // Pooled carrier thread: clear the guard so a later run on this
                // thread is not wrongly blocked from delegating.
                DelegationGuard.end();
                // Carrier threads are pooled — a stale context would misroute a
                // later subagent result into the wrong session.
                AgentRunContext.clear();
                // runningThread cleanup moved to the run task (set at task start, cleared
                // here in the same finally) — covers the pre-loop consolidation window too.
                runningThread = null;
                // Consume an interrupt raised to abort this run, so the pooled carrier
                // does not hand it to the next run (which would bail at iteration 1 and
                // answer with nothing). Cleared only here, AFTER the persistence guard
                // above has read it — clearing it earlier would let an interrupted,
                // half-finished turn be written to the session.
                Thread.interrupted();
            }
        };

        return CompletableFuture.supplyAsync(task);
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

        // runningThread 的职责边界:它由外层 run 任务在开头设置(见 run() 内 runningThread =
        // Thread.currentThread())、在任务的 finally 里清除,runAgentLoop 对这个字段只是只读。
        // 把设置提前到 run 任务开头而非此处的原因:runAgentLoop 被调用之前还有一段"前置记忆
        // 整合"的窗口,runningThread 若此时还是 null,cancel/interrupt 就够不到正在跑前置整合
        // 的线程。runAgentLoop 本身内联跑在 run 任务那条载体线程上,不自己管这个字段——这里
        // 无论如何赋值/清空都会破坏上面的窗口覆盖,所以不要在这里动它。
        {
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

        // Fail fast: tool calling is mandatory for the agent. A service that does not
        // support tool calling must NOT silently degrade to a tool-less text loop.
        if (!aiService.supportsToolCalling()) {
            String provider = aiService.getName();
            log.error("Aborting agent run: model/provider '{}' does not support tool calling", provider);
            context.setStopReason("unsupported_model");
            String unsupportedMsg = "This model/provider (" + provider
                + ") does not support tool calling, which the agent requires. "
                + "Please select a model that supports function/tool calling.";
            java.util.Map<String, Object> errMeta = new java.util.HashMap<>();
            errMeta.put("usage", context.getUsage());
            return AgentRunResult.builder()
                .runId(context.getRunId())
                .content(unsupportedMsg)
                .toolsUsed(toolsUsed)
                .iterationCount(iteration)
                .success(true)
                .startTime(startTime)
                .endTime(Instant.now())
                .session(session)
                .toolEvents(context.getToolEvents())
                .currentMessages(currentMessages)
                .metadata(errMeta)
                .stopReason(context.getStopReason())
                .hadInjections(hadInjections)
                .build();
        }

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
                log.info("Iteration {}", iteration);
            }

            // Check abort before making LLM call (avoid wasting tokens if already stopped)
            if (isAborted(spec)) {
                log.info("Agent loop aborted before LLM call at iteration {} for session {}", iteration, spec.getSessionKey());
                break;
            }

            // Call LLM — govern context first: trim a per-iteration copy if over budget.
            // currentMessages (the persisted conversation) is never mutated by govern.
            List<Message> messagesForModel = contextWindowManager.govern(currentMessages, spec.getMaxTokens());
            LLMResponse response = callLLM(messagesForModel, llmOptions);
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
            .stopReason(context.getStopReason())
            .hadInjections(hadInjections)
            .build();
        }
    }

    /**
     * Call the LLM with the current messages.
     * Uses tool calling if supported by the AI service.
     */
    private LLMResponse callLLM(List<Message> messages, LlmCallOptions options) {
        try {
            // Tool calling is the only supported path (see mandatory-toolcalling spec);
            // runAgentLoop guards against services that do not support it.
            log.info("Using tool calling enabled LLM service");

            // Get tool definitions from the tool registry
            List<org.gitee.jmeter.ai.agent.model.ToolDefinition> tools =
                toolRegistry.getToolDefinitionObjects();

            log.info("Calling LLM with {} messages and {} tools", messages.size(), tools.size());

            // Call the service with full messages and tools
            return aiService.generateResponseWithTools(messages, tools, options);

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

            if (optimizedContent == null) {
                continue;
            }

            // Strip runtime-context block from user messages so jsonl stores only the
            // real user input. Mirrors Nanobot _save_turn: tag-based slice + skip if empty.
            if (msg.getRole() == Message.Role.USER) {
                optimizedContent = ContextBuilder.stripRuntimeContext(optimizedContent);
                if (optimizedContent.isEmpty()) {
                    continue;
                }
            }

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
        sessionManager.saveSession(session);
    }

    private boolean isAborted(AgentRunSpec spec) {
        return (spec.getAbortFlag() != null && spec.getAbortFlag().get())
                || Thread.currentThread().isInterrupted();
    }

    /**
     * Abort-flag-only check, safe to evaluate on pooled carrier threads other than the
     * run's own thread. Pre-loop/post-run consolidation runs on a ForkJoinPool carrier
     * that {@link AgentRunner#interrupt()} never targets, so the thread-interrupt half
     * of {@link #isAborted} would be meaningless there — only the shared flag reaches it.
     */
    private boolean isAbortedFlag(AgentRunSpec spec) {
        return spec.getAbortFlag() != null && spec.getAbortFlag().get();
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
