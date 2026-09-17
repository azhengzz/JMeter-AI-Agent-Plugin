package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.context.ContextWindowManager;
import org.gitee.jmeter.ai.agent.turn.InjectionItem;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.model.*;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.gitee.jmeter.ai.instance.DelegationGuard;
import org.gitee.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    // 中止落盘合成消息文案（对齐 Nanobot session/recovery.py：合成行专属，真实消息不带）
    private static final String INTERRUPTED_NO_RESPONSE =
        "Error: Task interrupted before a response was generated.";
    private static final String INTERRUPTED_TOOL_RESULT =
        "Error: Task interrupted before this tool finished.";
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
        int contextTokens = AiConfig.getContextWindowTokens();
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
     * Run an agent with the given specification. Synchronous: executes inline on
     * the calling thread (main link = single-threaded agent-loop executor thread,
     * subagent = subagent pool thread) and returns the result directly — the LLM
     * loop and both consolidations share that one thread, which is exactly what
     * the callers' interrupt targets ({@code Turn.runnerThread} on the main link,
     * {@code RunningSubagent.runThread} for subagents).
     */
    public AgentRunResult run(AgentRunSpec spec) {
        String runId = DEFAULT_RUN_ID_PREFIX + java.util.UUID.randomUUID().toString().substring(0, 8);
        Instant startTime = Instant.now();

        // 执行线程是跨回合复用的:主链路的 agent-loop 专用线程串行跑所有回合,子代理线程
        // 来自固定大小的池。上一回合被 Stop 取消时,signalCancel 的 runnerThread 中断
        // 可能晚于上一回合 finally 的 Thread.interrupted() 清扫才送达(读→interrupt 的
        // TOCTOU 窗口),在复用线程上留下残留——入口处清一次,避免这一轮一进 while
        // 迭代 1 就因 isInterrupted() 直接 break 返回空回复。取消语义不受影响:
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
            // flag (spec's flag IS the one the cancelActiveTask map holds). Declared
            // once, reused by the post-loop call below. Interrupt-inclusive on purpose:
            // every evaluator (here, the post-loop call, saveMessagesToSession's
            // rechecks, lockLongTermMemory 的 abort 感知等锁轮询) runs on this run
            // thread where the bit is meaningful, and all interrupt sources set the
            // flag first — the bit only ever adds conservatism for residual interrupts.
            BooleanSupplier abortSignal = () -> isAborted(spec);
            if (spec.isPersistSession()) {
                memoryConsolidator.maybeConsolidate(session, abortSignal);
            }

            // 会话重置代数快照：epoch 翻转 ⟺ 会话被重置（markConversationReset
            // 在栅栏锁下先翻代数再清空）。中止落盘/悬空尾懒收尾以「代数未翻转」为 RESET
            // 判别——CancelCause 会被 signalCancel 的 abortVisible 守卫吞掉（Stop-后-//new
            // 序列下 RESET 永远写不进 cause），epoch 不可被取消时序欺骗。无供应商
            // （子代理/直构测试）= 不判重置，两条新写路径均不执行。
            final long epochAtStart = spec.getResetEpochSupplier() != null
                ? spec.getResetEpochSupplier().get() : Long.MIN_VALUE;

            // Create hook context
            AgentHookContext context = new AgentHookContext(runId, session, spec.getUserMessage());

            // Build initial messages (getHistory now returns only unconsolidated messages)
            List<Message> messages;
            // 触发消息是否已提前落盘（persist-early）：终局保存据此去重（skipCount 前移），
            // 中止落盘据此判定「回合确实开跑过」（早落盘被跳过 = 回合起点前即被取消）。
            boolean inputPersistedEarly = false;
            if (spec.getInitialMessages() != null && !spec.getInitialMessages().isEmpty()) {
                messages = new ArrayList<>(spec.getInitialMessages());
            } else {
                // 懒收尾先于历史快照：上一回合遗留的悬空 user 尾（进程强杀、Error
                // 逃逸、LLM 错误回合、迭代上限收尾抽干——「合法 USER 尾」不存在）以合成
                // assistant 收尾闭合，且收尾进入本回合 LLM 上下文——否则请求中出现连续
                // 两条 user（Anthropic 400）。仅持久化会话（子代理无会话文件）。
                if (spec.isPersistSession()) {
                    closeDanglingUserTail(session, spec, epochAtStart, abortSignal);
                }
                // 历史快照必须先于早落盘：刚落盘的触发消息不得经 getHistory 再入
                // 上下文（否则 LLM 上下文中触发消息出现两次）
                List<Message> history = session.getHistory(AiConfig.getMaxHistorySize());
                messages = contextBuilder.buildMessages(
                    history,
                    spec.getUserMessage(),
                    toolRegistry.getToolDefinitions(),
                    spec.getInstanceMentions()
                );
                // 早落盘：回合开始即把触发 user 消息（含 Runtime Context 块 +
                // 标记，与终局同一变换）写入 jsonl——Stop/取消/崩溃后刚发的消息不再丢失
                if (spec.isPersistSession()) {
                    // messages 末位 = 触发 user 消息（buildMessages 拼在最后）；true = 已落盘
                    inputPersistedEarly = persistUserMessageEarly(
                        session, messages.get(messages.size() - 1), abortSignal, spec, epochAtStart);
                }
            }

            // Run agent loop
            AgentRunResult result = runAgentLoop(messages, session, spec, context, startTime);

            // Skip session persistence if task was cancelled (Nanobot: CancelledError skips session.save)
            // and always skip it for ephemeral subagent runs.
            if (spec.isPersistSession() && !isAborted(spec) && result.isSuccess()) {
                int skipCount = Math.max(0, messages.size() - (inputPersistedEarly ? 0 : 1));
                saveMessagesToSession(session, result.getCurrentMessages(), skipCount, abortSignal);
                // 后置整合必须同步内联、跑在 run 执行线程上,不能丢到后台线程。前提:
                // 回合 future 一旦 complete,AgentLoop.whenComplete 会立即把本回合的 abort
                // flag 从 map 移除,cancelActiveTask 靠查这个 map 才能取消一个回合。
                //
                // 时序保证:run() 同步直调,回合 future 在 run() 返回后才 complete →
                // 整合(在 run() 内)必然先于 future complete 跑完,whenComplete 移除 flag
                // 必然发生在整合之后 → 关闭期间 cancelActiveTask 一直能找到这个 flag,
                // 整合可被正常取消。若丢到后台线程,flag 可能先被移除,整合就成了取消不到的
                // "僵尸回合",关闭时照常写盘,与关闭对话框的深度提炼抢写 HISTORY/MEMORY
                // (重复条目、后写覆盖)。
                //
                // 取消兜底:整合等锁/写盘前都查 abortSignal(flag+中断,均在 run 执行线程求值),
                // 被取消则不落盘,与前置整合一致。
                memoryConsolidator.maybeConsolidate(session, abortSignal);
            } else if (spec.isPersistSession() && inputPersistedEarly
                    && resetEpochUnchanged(spec, epochAtStart)) {
                // 中止落盘：回合没跑完（Stop 或异常中止）时把半成品写盘——已完成的
                // 真实消息照写，半截的 tool_call/对话尾用合成中断标记补全（防下次
                // 请求 400）；被 /new 重置的回合不做中止落盘（resetEpochUnchanged 已拦）。
                // skipCount = messages.size()：触发消息已早落盘，只追加回合内新增
                // 消息；此路径不做后置整合。
                materializeInterruptedTurn(session, result.getCurrentMessages(),
                    messages.size(), spec, epochAtStart);
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
            // The run thread is reused (agent-loop dedicated thread across turns /
            // pooled subagent thread): clear the guard so a later run on this
            // thread is not wrongly blocked from delegating.
            DelegationGuard.end();
            // Same thread-reuse reasoning: a stale context would misroute a
            // later subagent result into the wrong session.
            AgentRunContext.clear();
            // Consume an interrupt raised to abort this run, so the reused thread
            // does not hand it to the next run (which would bail at iteration 1
            // and answer with nothing). ORDERING INVARIANT: this sweep must stay
            // AFTER the persistence chain has read the interrupt bit — the guard
            // `spec.isPersistSession() && !isAborted(spec) && result.isSuccess()`、其下的
            // abortSignal rechecks（saveMessagesToSession、lockLongTermMemory 等锁
            // 轮询）与中止落盘分支（materializeInterruptedTurn 的代数复查）都在本
            // 线程上读 isAborted/epoch。Sweeping earlier would
            // wash an interrupted run into "completed" and let a half-finished
            // turn be written to the session.
            Thread.interrupted();
        }
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

        Function<Integer, List<InjectionItem>> callback = spec.getInjectionCallback();
        if (callback == null) {
            return InjectionResult.noContinue(injectionCycle);
        }

        List<InjectionItem> items = callback.apply(MAX_INJECTIONS_PER_TURN);
        if (items == null || items.isEmpty()) {
            return InjectionResult.noContinue(injectionCycle);
        }

        injectionCycle++;
        appendInjectedMessages(currentMessages, items);

        log.info("Injected {} messages at cycle {}/{}",
            items.size(), injectionCycle, MAX_INJECTION_CYCLES);

        return new InjectionResult(true, injectionCycle, true);
    }

    /**
     * Append injected user messages while preserving role alternation.
     * Ported from Nanobot's _append_injected_messages.
     * Consecutive user messages are merged with "\n\n" separator.
     *
     * <p>整批注入合并为一条,消息<b>末尾</b>附一个 runtime context(对齐 Nanobot
     * {@code _to_user_message}:排空的 pending 消息逐条解析并 append 块)。注入条目携带的
     * @-实例引用合并去重后渲染进注入块的实例小节(busy 注入不降级);工具引导行按实际
     * 注册裁剪。块必须尾随:stripRuntimeContext 按尾随块精确剥离,块若卡在正文中间会
     * 吞掉其后文本——故合并进已带块的既有 user 消息(drain6 的现实场景)时先剥旧块、
     * 拼正文、再挂新块。
     * Package-private for testability.
     */
    void appendInjectedMessages(List<Message> currentMessages, List<InjectionItem> items) {
        List<String> texts = new ArrayList<>(items.size());
        Map<String, InstanceInfo> mentions = new LinkedHashMap<>();
        for (InjectionItem item : items) {
            texts.add(item.getText());
            for (InstanceInfo info : item.getMentions()) {
                mentions.putIfAbsent(info.getInstanceId(), info);
            }
        }
        String block = contextBuilder.buildInjectionRuntimeContext(
                List.copyOf(mentions.values()), toolRegistry.getToolDefinitions());
        String joined = String.join("\n\n", texts);
        if (currentMessages.isEmpty()
                || currentMessages.get(currentMessages.size() - 1).getRole() != Message.Role.USER) {
            currentMessages.add(Message.user(joined + "\n\n" + block));
            return;
        }
        Message last = currentMessages.get(currentMessages.size() - 1);
        String base = ContextBuilder.stripRuntimeContext(last.getContent());
        currentMessages.set(currentMessages.size() - 1, Message.user(
                base + "\n\n" + joined + "\n\n" + block));
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
     * runAgentLoop 单次运行的共享可变状态：while 体、两大分支方法与注入
     * 检查点都读写这里的字段，替代原先散落在巨方法体内的局部变量。可变字段仅在 run
     * 执行线程上触碰（run 同步直调，无跨线程发布）。
     */
    private static final class LoopState {
        List<Message> currentMessages;
        String finalContent;
        int iteration;
        int injectionCycles;
        boolean hadInjections;
        final List<String> toolsUsed = new ArrayList<>();
        final AgentHook hook;
        final LlmCallOptions llmOptions;
        final int maxIterations;

        LoopState(List<Message> messages, AgentRunSpec spec, int defaultMaxIterations) {
            this.currentMessages = new ArrayList<>(messages);
            this.hook = spec.getHook();
            // Build per-run LLM options from spec overrides
            this.llmOptions = LlmCallOptions.builder()
                .model(spec.getModel())
                .temperature(spec.getTemperature())
                .maxTokens(spec.getMaxTokens())
                .reasoningEffort(spec.getReasoningEffort())
                .build();
            this.maxIterations = spec.getMaxIterations() > 0 ? spec.getMaxIterations() : defaultMaxIterations;
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

        LoopState state = new LoopState(messages, spec, defaultMaxIterations);
        try {

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
                .toolsUsed(state.toolsUsed)
                .iterationCount(state.iteration)
                .success(true)
                .startTime(startTime)
                .endTime(Instant.now())
                .session(session)
                .toolEvents(context.getToolEvents())
                .currentMessages(state.currentMessages)
                .metadata(errMeta)
                .stopReason(context.getStopReason())
                .hadInjections(state.hadInjections)
                .build();
        }

        while (state.iteration < state.maxIterations) {
            state.iteration++;
            context.setCurrentIteration(state.iteration);

            // Check abort flag (set by cancellation) and thread interrupt
            if (isAborted(spec)) {
                log.info("Agent loop aborted at iteration {} for session {}", state.iteration, spec.getSessionKey());
                break;
            }

            if (state.hook != null) state.hook.beforeIteration(context);

            // Check for iteration limit
            if (state.iteration > 1) {
                log.info("Iteration {}", state.iteration);
            }

            // Check abort before making LLM call (avoid wasting tokens if already stopped)
            if (isAborted(spec)) {
                log.info("Agent loop aborted before LLM call at iteration {} for session {}", state.iteration, spec.getSessionKey());
                break;
            }

            // Call LLM — govern context first: trim a per-iteration copy if over budget.
            // currentMessages (the persisted conversation) is never mutated by govern.
            List<Message> messagesForModel = contextWindowManager.govern(state.currentMessages, spec.getMaxTokens());
            LLMResponse response = callLLM(messagesForModel, state.llmOptions);
            context.setLastLlmResponse(response);

            // Check abort after LLM call returns
            if (isAborted(spec)) {
                log.info("Agent loop aborted after LLM call at iteration {}", state.iteration);
                break;
            }

            // Capture usage from LLM response (last iteration wins, matching Nanobot)
            Map<String, Integer> respUsage = response.getUsage();
            if (respUsage != null && !respUsage.isEmpty()) {
                context.setUsage(respUsage);
                // Per-iteration usage notification (context-usage indicator): fires
                // immediately after each LLM call, not only at turn end.
                if (state.hook != null) {
                    state.hook.onUsage(respUsage, context);
                }
            }

            if (response.isError()) {
                if ("Interrupted".equals(response.getErrorMessage())) {
                    log.info("Agent loop aborted during LLM call at iteration {}", state.iteration);
                    break;
                }
                log.error("LLM returned error: {}", response.getErrorMessage());
                state.finalContent = "I encountered an error: " + response.getErrorMessage();
                if (state.hook != null) {
                    state.hook.onError(new RuntimeException(response.getErrorMessage()), context);
                }

                // Injection check 4: after LLM error
                if (checkpoint(state, spec)) {
                    if (state.hook != null) state.hook.afterIteration(context);
                    continue;
                }
                break;
            }

            // Check for tool calls
            if (response.hasToolCalls()) {
                if (handleToolCallsBranch(response, state, spec, context)) continue;
                break;
            }

            if (handleFinalResponseBranch(response, state, spec, context)) continue;
            break;
        }

        // Check max iterations
        if (state.finalContent == null && state.iteration >= state.maxIterations) {
            log.warn("Max iterations reached: {}", state.maxIterations);

            // Injection drain 6: after max iterations (drain only, don't continue loop).
            // 手写保留、不走 checkpoint:此处绕过 MAX_INJECTION_CYCLES 上限、
            // 直接 append、永不 continue——已用满 5 周期后打到 maxIterations 的场景仍须抽干。
            if (spec.getInjectionCallback() != null) {
                List<InjectionItem> remaining = spec.getInjectionCallback().apply(MAX_INJECTIONS_PER_TURN);
                if (remaining != null && !remaining.isEmpty()) {
                    state.hadInjections = true;
                    appendInjectedMessages(state.currentMessages, remaining);
                    log.info("Drained {} remaining injected messages after max iterations", remaining.size());
                }
            }

            state.finalContent = "I reached the maximum number of tool call iterations. Please try breaking the task into smaller steps.";
        }

        // Finalize content through hook
        if (state.hook != null) {
            state.finalContent = state.hook.finalizeContent(state.finalContent, context);
        }

        // Build result
        java.util.Map<String, Object> resultMetadata = new java.util.HashMap<>();
        resultMetadata.put("usage", context.getUsage());

        return AgentRunResult.builder()
            .runId(context.getRunId())
            .content(state.finalContent)
            .toolsUsed(state.toolsUsed)
            .iterationCount(state.iteration)
            .success(true)
            .startTime(startTime)
            .endTime(Instant.now())
            .session(session)
            .toolEvents(context.getToolEvents())
            .currentMessages(state.currentMessages)
            .metadata(resultMetadata)
            .stopReason(context.getStopReason())
            .hadInjections(state.hadInjections)
            .build();
        } catch (Exception e) {
            // 异常安全结果：携带部分转录（state.currentMessages）供 run() 的
            // 中止落盘分支使用。hook 零新增发射（外部行为与现状一致）；errorMessage 与 usage
            // metadata 与 run() 既有 catch 结果同形，保证 toAgentResponse 终态文本不变；
            // 新增字段仅 currentMessages 与 stopReason="exception"。Error 仍逃逸
            // catch(Exception)，与 kill -9 同形，由悬空尾懒收尾兜底。
            log.error("Agent loop failed mid-run for session {}", spec.getSessionKey(), e);
            java.util.Map<String, Object> errMeta = new java.util.HashMap<>();
            errMeta.put("usage", context.getUsage());
            return AgentRunResult.builder()
                .runId(context.getRunId())
                .errorMessage(e.getMessage())
                .success(false)
                .startTime(startTime)
                .endTime(Instant.now())
                .session(session)
                .currentMessages(state.currentMessages)
                .metadata(errMeta)
                .stopReason("exception")
                .build();
        }
    }

    /**
     * while 体的工具调用分支（含 inj1/inj3 两个注入检查点）。返回 shouldContinue：
     * true = 回到循环条件继续迭代——含 inj1 无注入时的自然落穿路径（此时本方法已发射
     * 原循环体尾部的 afterIteration，那个位置只有工具分支可达）；false = break 出循环
     * （执行前/后中止、致命工具错误后无注入）。
     */
    private boolean handleToolCallsBranch(
            LLMResponse response, LoopState state, AgentRunSpec spec, AgentHookContext context) {

        // Add assistant message with tool calls
        state.currentMessages = contextBuilder.addAssistantMessage(
            state.currentMessages,
            response.getContent(),
            response.getToolCalls(),
            response.getReasoningContent()
        );

        if (state.hook != null) state.hook.beforeExecuteTools(response.getToolCalls(), context);

        // Check abort before executing tools
        if (isAborted(spec)) {
            log.info("Agent loop aborted before tool execution at iteration {}", state.iteration);
            return false;
        }

        // Execute tools (concurrency-safe batches run in parallel; unsafe calls inline)
        ToolExecutionResult executionResult = executeToolCalls(
            response.getToolCalls()
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
                // 块内 hook 变体：afterIteration 在检查点之前发射——inj3 的 continue 路径
                // 因此不再补发射（与 inj1/4/5 不同）
                if (state.hook != null) state.hook.afterIteration(context);
                state.finalContent = "Error: Tool execution failed: " + error;

                // Injection check 3: after tool fatal error
                return checkpoint(state, spec);
            }
        }

        if (state.hook != null) state.hook.afterExecuteTools(response.getToolCalls(), context);

        // Check abort after tool execution
        if (isAborted(spec)) {
            log.info("Agent loop aborted after tool execution at iteration {}", state.iteration);
            return false;
        }

        // Add tool results to messages
        for (int i = 0; i < response.getToolCalls().size(); i++) {
            ToolCall call = response.getToolCalls().get(i);
            if (i < toolResults.size()) {
                state.currentMessages = contextBuilder.addToolResult(
                    state.currentMessages,
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
        state.toolsUsed.addAll(iterationTools);
        for (String toolName : iterationTools) {
            context.addToolUsed(toolName);
        }

        // Injection check 1: after tool execution, before next LLM call
        if (checkpoint(state, spec)) {
            if (state.hook != null) state.hook.afterIteration(context);
            return true;
        }

        // inj1 无注入的自然落穿：原循环体尾部的 afterIteration 只有工具分支可达，
        // 在此发射后交回 while 条件决定是否还有下一迭代
        if (state.hook != null) state.hook.afterIteration(context);
        return true;
    }

    /**
     * while 体的终答分支（无工具调用；含 inj5/inj2 两个注入检查点）。返回
     * shouldContinue：true = 注入到达、回到循环条件继续迭代；false = 终答落定、
     * break 出循环。
     */
    private boolean handleFinalResponseBranch(
            LLMResponse response, LoopState state, AgentRunSpec spec, AgentHookContext context) {

        // No tool calls, this is the final response
        state.finalContent = response.getContent();

        // Injection check 5: empty response
        if (state.finalContent == null || state.finalContent.isEmpty()) {
            if (checkpoint(state, spec)) {
                if (state.hook != null) state.hook.afterIteration(context);
                return true;
            }
            // No injections and empty → append placeholder and break
        }

        // Append assistant message before checking for injections,
        // so role alternation is preserved: assistant → user(injected).
        state.currentMessages = contextBuilder.addAssistantMessage(
            state.currentMessages, state.finalContent, null, response.getReasoningContent());

        // Injection check 2: after final response
        if (checkpoint(state, spec)) {
            // 先广播中间答、再清 finalContent——只有本检查点的 continue 路径清除
            // （inj4 的错误串就保留，由下一迭代的回复覆盖）
            if (state.hook != null) {
                state.hook.onIntermediateResponse(state.finalContent, context);
            }
            state.finalContent = null;
            if (state.hook != null) state.hook.afterIteration(context);
            return true;
        }

        return false;
    }

    /**
     * 注入检查点仪式（inj1–inj5 五处同构块的去重）：抽干注入队列并把结果并回
     * {@code state}（injectionCycles/hadInjections），返回是否应继续下一迭代。
     *
     * <p>只收敛同构部分——各调用侧 continue 路径的 hook 差异（inj1/4/5 带
     * afterIteration、inj2 另有 onIntermediateResponse + finalContent 清空、inj3 无）保留
     * 在调用侧。第 6 处（maxIterations 后收尾抽干 drain6）不走这里：它绕过
     * MAX_INJECTION_CYCLES 上限、直接 append、永不 continue（见 runAgentLoop 尾部）。
     */
    private boolean checkpoint(LoopState state, AgentRunSpec spec) {
        InjectionResult inj = tryDrainInjections(state.currentMessages, spec, state.injectionCycles);
        state.injectionCycles = inj.injectionCycle;
        state.hadInjections |= inj.hadInjections;
        return inj.shouldContinue;
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
     * Execute tool calls with Nanobot-style concurrency-safe batching
     * ({@code _partition_tool_batches}): consecutive {@link Tool#isConcurrencySafe()}
     * calls form one parallel batch (via {@code executeAsyncWithEvents}, per-tool
     * timeout, results restored to call order); every unsafe call is its own
     * singleton batch executed inline on the run thread — ThreadLocal run
     * context ({@code AgentRunContext}/{@code DelegationGuard}) stays visible
     * exactly as in the all-serial era. Batches run in call order; results and
     * events are returned in original call order. {@code failOnToolError} keeps
     * its post-hoc semantics (caller inspects events after all batches complete).
     */
    private ToolExecutionResult executeToolCalls(List<ToolCall> toolCalls) {
        List<ToolResult> results = new ArrayList<>();
        List<org.gitee.jmeter.ai.agent.model.ToolEvent> events = new ArrayList<>();

        for (List<ToolCall> batch : partitionByConcurrencySafety(toolCalls)) {
            if (batch.size() > 1) {
                var batchResult = toolRegistry.executeAsyncWithEvents(batch, toolTimeoutMs).join();
                results.addAll(batchResult.results());
                events.addAll(batchResult.events());
            } else {
                ToolCall call = batch.get(0);
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
     * Split calls into batches preserving call order: a run of consecutive
     * concurrency-safe calls becomes one batch (parallel); each unsafe call is a
     * singleton batch (inline serial). Unknown tool names are treated as unsafe.
     */
    private List<List<ToolCall>> partitionByConcurrencySafety(List<ToolCall> toolCalls) {
        List<List<ToolCall>> batches = new ArrayList<>();
        List<ToolCall> current = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            Tool tool = toolRegistry.get(call.getName());
            boolean safe = tool != null && tool.isConcurrencySafe();
            if (safe) {
                current.add(call);
                continue;
            }
            if (!current.isEmpty()) {
                batches.add(current);
                current = new ArrayList<>();
            }
            batches.add(new ArrayList<>(List.of(call)));
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
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
    private void saveMessagesToSession(Session session, List<Message> allMessages, int skipCount,
            BooleanSupplier abortSignal) {
        // 二道复查：调用点守卫读到 false 之后、真正落盘之前，
        // 会话可能已被 /new / "+" 重置——此时落盘会把旧会话内容写进刚清空的新会话
        // 文件。重置经 signalCancel 必先置共享 abort flag，此处复查即收窄该窗口。
        if (abortSignal.getAsBoolean()) {
            log.info("Skipping session persistence: abort signalled during final persistence window");
            return;
        }
        for (int i = skipCount; i < allMessages.size(); i++) {
            Message persistable = toPersistableMessage(allMessages.get(i));
            if (persistable != null) {
                session.addMessage(persistable);
            }
        }
        // 落盘前的最后一道复查：伤害发生在写文件——last-writer-wins
        // 会覆盖重置线程刚写的空文件。入口复查后若重置恰好落地（载体被调度出去的
        // 窗口），此处再拦一次，把窗口收窄到检查与写之间的指令级
        if (abortSignal.getAsBoolean()) {
            log.info("Skipping session file write: abort signalled during persistence");
            return;
        }
        sessionManager.saveSession(session);
    }

    private boolean isAborted(AgentRunSpec spec) {
        return (spec.getAbortFlag() != null && spec.getAbortFlag().get())
                || Thread.currentThread().isInterrupted();
    }

    /**
     * 会话自回合开始是否未被 /new 重置（无供应商时无法判别，按已重置处理）。
     *
     * @return true = 会话自回合开始未被重置，允许落盘；false = 已被重置（或无法判别），
     *         禁止写盘——旧回合内容会复活进刚清空的新会话
     */
    private static boolean resetEpochUnchanged(AgentRunSpec spec, long epochAtStart) {
        return epochAtStart != Long.MIN_VALUE
                && spec.getResetEpochSupplier() != null
                && spec.getResetEpochSupplier().get() == epochAtStart;
    }

    /**
     * 单消息持久化变换（终局保存/早落盘/中止落盘三路共用）：
     * {@code shouldSkip} → null；{@code optimizeContent} 为 null → null（整条跳过）；
     * USER 消息保留尾随 runtime-context 块并挂 {@code _runtime_context} 精确剥离标记
     * （对齐 Nanobot：LLM 回放能看到历史消息当时的时间/脚本/选区/实例引用，公共视图
     * 按标记剥离）；timestamp/reasoningContent/metadata 原样重建。
     */
    private Message toPersistableMessage(Message msg) {
        if (MessageOptimizer.shouldSkip(msg)) {
            return null;
        }
        String optimizedContent = MessageOptimizer.optimizeContent(
            msg.getRole(), msg.getContent(), msg.hasToolCalls());
        if (optimizedContent == null) {
            return null;
        }
        Map<String, Object> persistMetadata = msg.getMetadata();
        if (msg.getRole() == Message.Role.USER) {
            Map<String, Object> marker = ContextBuilder.runtimeContextMarker(optimizedContent);
            if (marker != null) {
                persistMetadata = new java.util.LinkedHashMap<>();
                if (msg.getMetadata() != null) {
                    persistMetadata.putAll(msg.getMetadata());
                }
                persistMetadata.put(ContextBuilder.RUNTIME_CONTEXT_META_KEY, marker);
            }
        }
        return Message.builder()
            .role(msg.getRole())
            .content(optimizedContent)
            .toolCalls(msg.getToolCalls())
            .toolCallId(msg.getToolCallId())
            .reasoningContent(msg.getReasoningContent())
            .metadata(persistMetadata)
            .timestamp(msg.getTimestamp())
            .build();
    }

    /**
     * 早落盘（对齐 Nanobot {@code _persist_user_message_early}）：回合开始
     * 即把触发 user 消息写入 jsonl——Stop/取消/崩溃后刚发的消息不再丢失。守卫与姊妹
     * 写路径同纪律（对抗测试修订）：入口查中止信号（重置经 signalCancel 先置 flag 再
     * clear，先到则不写），addMessage 后、saveSession 前复查重置代数——重置若在
     * [入口检查 → 落盘] 跨度内完整落地（载体线程被调度出去的窗口，含消息变换与全文件
     * 写），此处复查即收窄，防旧会话触发消息复活进刚清空的新会话（last-writer-wins
     * 覆盖重置线程刚写的空文件）；复查失败时跳过文件写，内存追加残留随重置 invalidate
     * 失效（旧 Session 对象不再被 flush）。落盘失败仅 log（尽力而为，不影响回合）。
     */
    private boolean persistUserMessageEarly(Session session, Message trigger,
            BooleanSupplier abortSignal, AgentRunSpec spec, long epochAtStart) {
        try {
            if (abortSignal.getAsBoolean()) {
                return false;
            }
            Message persistable = toPersistableMessage(trigger);
            if (persistable == null) {
                return false;
            }
            session.addMessage(persistable);
            if (!resetEpochUnchanged(spec, epochAtStart)) {
                return false;
            }
            sessionManager.saveSession(session);
            log.info("Early-persisted triggering user message for session {}", session.getKey());
            return true;
        } catch (Exception e) {
            log.warn("Early user-message persist failed for session {}", session.getKey(), e);
            return false;
        }
    }

    /**
     * 悬空 user 尾懒收尾（对齐 Nanobot {@code restore_pending_interruption}
     * 的效果）：会话末条为 USER（进程强杀、Error 逃逸、LLM 错误回合、迭代上限收尾抽干
     * 均可遗留——「合法 USER 尾」不存在，任何 user 尾都会使下回合请求出现连续两条
     * user，Anthropic 对连续同角色消息返回 400）时追加合成 assistant 收尾。守卫与早
     * 落盘同纪律：追加前查中止信号、提交前复查代数（重置进行中则放弃文件写——清空后
     * 末条非 USER，无需收尾；内存追加残留随重置 invalidate 失效，不再被 flush）。
     */
    private void closeDanglingUserTail(Session session, AgentRunSpec spec, long epochAtStart,
            BooleanSupplier abortSignal) {
        try {
            if (spec.getResetEpochSupplier() == null || abortSignal.getAsBoolean()) {
                return;
            }
            List<Message> existing = session.getMessages();
            if (existing.isEmpty()
                    || existing.get(existing.size() - 1).getRole() != Message.Role.USER) {
                return;
            }
            session.addMessage(syntheticInterruptedCloser());
            if (!resetEpochUnchanged(spec, epochAtStart)) {
                return;
            }
            sessionManager.saveSession(session);
            log.info("Closed dangling user tail for session {}", session.getKey());
        } catch (Exception e) {
            log.warn("Failed to close dangling user tail for session {}", session.getKey(), e);
        }
    }

    /**
     * 中止落盘（对齐 Nanobot {@code session/recovery.py} 的中断收尾语义，即
     * materialize）：取消/异常中止的回合不整回合丢弃——compose-then-commit，局部
     * 构造完整列表（真实 partial + 全部合成消息）后一次性提交，任一并发 flush 只见
     * 中止落盘前/后的完整状态。真实保留全部已完成迭代（同步 runner 手握全量消息，
     * 优于 Nanobot checkpoint 只保末迭代）；每个悬空 tool_call 补合成 tool 结果
     * （不带则下回合请求违反 provider 约束：assistant.tool_calls 必须有配对结果）；
     * 无 assistant 产出或尾为 USER 补合成 assistant 收尾（杜绝连续 user）。入口与
     * 提交前复查重置代数；方法整体 catch——中止落盘是尽力而为的持久化增强，自身
     * 异常绝不影响回合结果与 GUI 终态。
     */
    private void materializeInterruptedTurn(Session session, List<Message> currentMessages,
            int skipCount, AgentRunSpec spec, long epochAtStart) {
        try {
            if (!resetEpochUnchanged(spec, epochAtStart)) {
                return;
            }
            List<Message> committed = new ArrayList<>();
            boolean sawAssistant = false;
            for (int i = Math.max(0, skipCount); i < currentMessages.size(); i++) {
                Message persistable = toPersistableMessage(currentMessages.get(i));
                if (persistable != null) {
                    committed.add(persistable);
                    sawAssistant |= persistable.getRole() == Message.Role.ASSISTANT;
                }
            }
            // 悬空 tool_call：本次落盘范围内带 tool_calls 的 assistant 无后续配对结果者逐个
            // 补合成结果（abort 落在工具执行前/后的窗口；更早迭代的调用已被各自结果
            // 从 dangling 集合移除）
            Map<String, ToolCall> dangling = new LinkedHashMap<>();
            for (Message msg : committed) {
                if (msg.getRole() == Message.Role.ASSISTANT) {
                    for (ToolCall call : msg.getToolCalls()) {
                        if (call.getId() != null) {
                            dangling.put(call.getId(), call);
                        }
                    }
                } else if (msg.getRole() == Message.Role.TOOL && msg.getToolCallId() != null) {
                    dangling.remove(msg.getToolCallId());
                }
            }
            for (ToolCall call : dangling.values()) {
                committed.add(syntheticInterruptedToolResult(call));
            }
            // 无 assistant 产出（中止早于任何回复）或尾为 USER（触发/注入未被回应）：
            // 补合成收尾——无真实消息、只有收尾 = Nanobot「user-only 回合以错误收尾」同形
            boolean tailUser = !committed.isEmpty()
                    && committed.get(committed.size() - 1).getRole() == Message.Role.USER;
            if (!sawAssistant || tailUser) {
                committed.add(syntheticInterruptedCloser());
            }
            if (committed.isEmpty()) {
                return;
            }
            if (!resetEpochUnchanged(spec, epochAtStart)) {
                // 提交前复查（compose-then-commit：尚未 addMessage，无内存残留）
                return;
            }
            session.addMessages(committed);
            sessionManager.saveSession(session);
            log.info("Materialized {} message(s) for interrupted session {}",
                committed.size(), session.getKey());
        } catch (Exception e) {
            log.warn("Interrupted-turn materialization failed for session {}", session.getKey(), e);
        }
    }

    /** 合成 assistant 中断收尾（文案对齐 Nanobot recovery.py；标记经 jsonl 顶层 _recovery_interrupted round-trip）。 */
    private static Message syntheticInterruptedCloser() {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY, Boolean.TRUE);
        return Message.builder()
            .role(Message.Role.ASSISTANT)
            .content(INTERRUPTED_NO_RESPONSE)
            .metadata(metadata)
            .build();
    }

    /** 合成 tool 中断结果（悬空 tool_call 的配对；metadata 须新建可变 Map——Message 的元数据 Map 不可原地改）。 */
    private static Message syntheticInterruptedToolResult(ToolCall call) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("toolName", call.getName());
        metadata.put(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY, Boolean.TRUE);
        return Message.builder()
            .role(Message.Role.TOOL)
            .content(INTERRUPTED_TOOL_RESULT)
            .toolCallId(call.getId())
            .metadata(metadata)
            .build();
    }
}
