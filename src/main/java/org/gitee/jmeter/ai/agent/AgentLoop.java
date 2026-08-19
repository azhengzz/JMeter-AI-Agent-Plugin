package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.command.BuiltinCommands;
import org.gitee.jmeter.ai.agent.command.CommandContext;
import org.gitee.jmeter.ai.agent.command.CommandRouter;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.hooks.ProgressCallbackHookAdapter;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.*;
import org.gitee.jmeter.ai.agent.run.AgentRunResult;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.agent.run.InjectionManager;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.subagent.SubagentManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Facade for Agent Loop operations.
 * Delegates to AgentRunner for actual execution.
 * Maintains backward compatibility with existing code.
 */
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentRunner agentRunner;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryConsolidator memoryConsolidator;
    private final ExecutorService executorService;
    private final int defaultMaxIterations;
    private final GenerationSettings generationSettings;
    private final CommandRouter commandRouter;
    private final ConcurrentHashMap<String, CompletableFuture<AgentResponse>> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> abortFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> completionLatches = new ConcurrentHashMap<>();
    private final InjectionManager injectionManager = new InjectionManager();

    // Subagent support (null when agent.subagent.enabled=false)
    private volatile SubagentManager subagentManager;
    private final long subagentDrainTimeoutMs;
    // Identifies the currently active turn per session, so a subagent result that
    // arrives after its turn ended is not injected into an unrelated later turn.
    private final ConcurrentHashMap<String, Object> activeTurnTokens = new ConcurrentHashMap<>();
    // Sessions whose subagent wait already timed out this turn — do not block again.
    private final ConcurrentHashMap<String, Boolean> drainTimedOut = new ConcurrentHashMap<>();
    // Session whose turn this thread is currently executing, so a command running
    // inside that turn (e.g. /new) does not cancel the turn it is running in.
    private final ThreadLocal<String> turnOwnedByThisThread = new ThreadLocal<>();

    // Runtime state for /status command (matching Nanobot's loop._last_usage / _start_time)
    private final Instant startTime = Instant.now();
    private volatile Map<String, Integer> lastUsage = Map.of();
    private volatile ProgressCallback progressCallback;

    public AgentLoop(
            ToolRegistry toolRegistry,
            MemoryStore memoryStore,
            MemoryConsolidator memoryConsolidator,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            AiService aiService) {

        int maxIterations = Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tool.iterations", "50"));
        int toolResultMaxChars = Integer.parseInt(AiConfig.getProperty("agent.tool.result.max.chars", "16000"));
        long toolTimeoutMs = Long.parseLong(AiConfig.getProperty("agent.tools.timeout.ms", "30000"));

        // Cap at 300s: past that a subagent is presumed hung and the turn moves on.
        long drainTimeoutSec = Long.parseLong(
            AiConfig.getProperty("agent.subagent.drain.timeout.seconds", "120"));
        this.subagentDrainTimeoutMs = Math.min(drainTimeoutSec, 300L) * 1000L;

        this.agentRunner = new AgentRunner(
            toolRegistry,
            memoryConsolidator,
            contextBuilder,
            sessionManager,
            aiService,
            maxIterations,
            toolResultMaxChars,
            toolTimeoutMs
        );

        this.toolRegistry = toolRegistry;
        this.memoryConsolidator = memoryConsolidator;
        this.sessionManager = sessionManager;
        this.defaultMaxIterations = maxIterations;
        this.generationSettings = aiService.getGenerationSettings();
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agent-loop");
            thread.setDaemon(true);
            return thread;
        });

        log.info("AgentLoop initialized with maxIterations={}, tools={}", maxIterations, toolRegistry.size());

        // Initialize command router
        this.commandRouter = new CommandRouter();
        BuiltinCommands.registerBuiltinCommands(commandRouter);
    }

    /**
     * Set progress callback for this processing run (legacy API).
     * Note: This is now handled per-request via hooks.
     */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Process a message through the agent loop (legacy API).
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey) {
        return processMessage(message, sessionKey, progressCallback, false);
    }

    /**
     * Process a message through the agent loop, optionally marking the turn as
     * cross-instance delegated ({@link DelegationGuard}: tools in that turn refuse
     * to delegate again). Used by IPC {@code /agent} for peer delegation requests.
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey,
            boolean delegated) {
        return processMessage(message, sessionKey, progressCallback, delegated);
    }

    /**
     * Process a message through the agent loop with progress callback (legacy API).
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey,
            ProgressCallback callback) {
        return processMessage(message, sessionKey, callback, false);
    }

    /**
     * Process a message through the agent loop with progress callback.
     * Supports slash command routing before agent execution.
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey,
            ProgressCallback callback,
            boolean delegated) {

        String raw = message.trim();

        // Phase 1: Priority command dispatch (immediate, no executor needed)
        if (commandRouter.isPriority(raw)) {
            CommandContext ctx = new CommandContext(raw, "", null, sessionKey, this);
            String result = commandRouter.dispatchPriority(ctx);
            if (result != null) {
                return CompletableFuture.completedFuture(AgentResponse.success(result));
            }
        }

        // Phase 2: Mid-turn injection routing
        // activeTasks 在方法结束前提交时,覆盖 [提交→完成] 全程;injectionQueues 仅在执行器
        // pickup 后置位,留有 [提交→pickup] 窗口,突发并发请求会绕过注入短路各自进
        // Phase 3 排队,挤满 ipc-worker 且队尾纯排队耗光 120s 超时。补 activeTasks 闭合该窗口。
        if (activeTasks.containsKey(sessionKey) || injectionManager.hasActiveRun(sessionKey)) {
            // 委派回合绝不并入注入队列:委派方阻塞等待的是任务结果,不是"已注入"回执;
            // 且注入队列只存 String,并入会静默丢失 delegated 标记 → 深度守卫被绕过
            // (队列消息要么被并入正在跑的本地用户回合、要么以 delegated=false 重发布)。
            if (delegated) {
                return CompletableFuture.completedFuture(AgentResponse.error(
                    "session busy: this instance has a turn in flight for session " + sessionKey
                        + "; retry the delegation later"));
            }
            // Non-priority commands must not be queued for injection.
            // dispatch them directly (same pattern as priority commands).
            if (commandRouter.isDispatchable(raw)) {
                Session session = sessionManager.getOrCreate(sessionKey);
                CommandContext ctx = new CommandContext(raw, "", session, sessionKey, this);
                String cmdResult = commandRouter.dispatch(ctx);
                if (cmdResult != null) {
                    return CompletableFuture.completedFuture(AgentResponse.success(cmdResult));
                }
            }

            // Route to pending queue for mid-turn injection
            if (injectionManager.offer(sessionKey, message)) {
                log.info("Message enqueued for mid-turn injection in session {}", sessionKey);
                return CompletableFuture.completedFuture(
                    AgentResponse.success("Message injected into current conversation."));
            }
        }

        // Phase 3: Normal processing (via executor)
        final AtomicBoolean abortFlag = new AtomicBoolean(false);
        final CountDownLatch completionLatch = new CountDownLatch(1);
        abortFlags.put(sessionKey, abortFlag);
        completionLatches.put(sessionKey, completionLatch);

        // Marks this turn as the active one; a subagent spawned here compares
        // against it before announcing so late results cannot derail a later turn.
        final Object turnToken = new Object();

        CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
            injectionManager.register(sessionKey);
            activeTurnTokens.put(sessionKey, turnToken);
            turnOwnedByThisThread.set(sessionKey);
            try {
                // Check regular commands first (inside executor)
                Session session = sessionManager.getOrCreate(sessionKey);
                CommandContext ctx = new CommandContext(raw, "", session, sessionKey, this);
                String cmdResult = commandRouter.dispatch(ctx);
                if (cmdResult != null) {
                    return AgentResponse.success(cmdResult);
                }

                // Build run spec with generation defaults
                AgentRunSpec spec = AgentRunSpec.builder()
                    .userMessage(message)
                    .sessionKey(sessionKey)
                    .hook(callback != null ? new ProgressCallbackHookAdapter(callback) : null)
                    .maxIterations(defaultMaxIterations)
                    .model(AiConfig.getDefaultModel())
                    .temperature(generationSettings.getTemperature())
                    .maxTokens(generationSettings.getMaxTokens())
                    .reasoningEffort(generationSettings.getReasoningEffort())
                    .abortFlag(abortFlag)
                    .injectionCallback(limit -> drainInjected(sessionKey, limit))
                    .delegated(delegated)
                    .build();

                // Run agent
                AgentRunResult result = agentRunner.run(spec).join();

                // Capture usage stats for /status command
                try {
                    Map<String, Object> meta = result.getMetadata();
                    if (meta != null && meta.containsKey("usage")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Integer> usage = (Map<String, Integer>) meta.get("usage");
                        setLastUsage(usage);
                    }
                } catch (Exception e) {
                    log.debug("Could not capture usage stats", e);
                }

                // Convert to legacy response format
                return result.toAgentResponse();
            } finally {
                // This turn is over: stop accepting subagent announcements for it,
                // and reset the timeout latch so the next turn may block again.
                // Under the same lock as offerInjection, so a result being delivered
                // right now either lands before the turn closes or is refused — never
                // enqueued into a queue that is about to be drained and re-published.
                synchronized (turnTeardownLock) {
                    activeTurnTokens.remove(sessionKey, turnToken);
                }
                drainTimedOut.remove(sessionKey);
                // The agent-loop thread is reused by the next turn.
                turnOwnedByThisThread.remove();

                // Cleanup: re-publish remaining messages as new processMessage calls
                // so they are fully processed by the agent (not just saved to history).
                // Mirrors Nanobot's finally block at loop.py:817-835.
                List<String> remaining = injectionManager.cleanup(sessionKey);
                if (!remaining.isEmpty()) {
                    log.info("Re-publishing {} leftover message(s) for session {}",
                        remaining.size(), sessionKey);
                    for (String msg : remaining) {
                        // 注入队列里只可能是用户消息:委派请求在 Phase 2 的 delegated 分支即被
                        // 拒绝返回(见上文"委派回合绝不并入注入队列"),从不 offer 入队。故此处
                        // 写死 delegated=false 语义上必然正确、没有委派标记可丢;若委派消息被
                        // 并入队列又以此 false 重发布,DelegationGuard(深度守卫)会被绕过,被
                        // 委派回合可再委派出去(跨实例 ping-pong)。
                        processMessage(msg, sessionKey, callback, false);
                    }
                }
            }
        }, executorService);

        // Track active task for cancellation support
        activeTasks.put(sessionKey, future);
        future.whenComplete((r, e) -> {
            activeTasks.remove(sessionKey);
            abortFlags.remove(sessionKey);
            completionLatches.remove(sessionKey);
            completionLatch.countDown();
        });

        return future;
    }

    /**
     * Get the tool registry.
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Get the session manager.
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Get the AI service used by this agent loop.
     */
    public AiService getAiService() {
        return agentRunner.getAiService();
    }

    /**
     * Get the command router.
     */
    public CommandRouter getCommandRouter() {
        return commandRouter;
    }

    /**
     * Get the memory consolidator.
     */
    public MemoryConsolidator getMemoryConsolidator() {
        return memoryConsolidator;
    }

    /**
     * Get the agent loop start time.
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Get last LLM call usage (prompt_tokens, completion_tokens).
     */
    public Map<String, Integer> getLastUsage() {
        return lastUsage;
    }

    /**
     * Update last usage stats after an LLM call.
     */
    public void setLastUsage(Map<String, Integer> usage) {
        this.lastUsage = usage != null ? Map.copyOf(usage) : Map.of();
    }

    /**
     * Cancel the active task for a session.
     * @return true if a task was cancelled
     */
    public boolean cancelActiveTask(String sessionKey) {
        boolean cancelled = signalCancel(sessionKey);

        // 4. Wait for actual completion
        // TODO 优化点: 此处 latch.await(5s) 在 EDT 上同步阻塞(Stop 按钮经此路径,见 AiChatPanel.stopActiveTask)。
        //  最坏占用 EDT 5 秒(GUI 短暂无响应);后续可改为后台线程 await + SwingUtilities.invokeLater 回 EDT 做 UI 收尾。
        CountDownLatch latch = completionLatches.get(sessionKey);
        if (latch != null) {
            try {
                boolean completed = latch.await(5, TimeUnit.SECONDS);
                if (!completed) {
                    log.warn("Timed out waiting for task cleanup in session {}", sessionKey);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return cancelled;
    }

    /**
     * Signal an active run (and its subagents) to stop, without waiting for it.
     *
     * <p>Callers that must not block use this: {@code /new} is dispatched on the
     * caller's thread — the Swing EDT when typed during an active run, or the
     * agent-loop thread inside the very future it would otherwise await. Only the
     * Stop button needs the completion wait, so only {@link #cancelActiveTask}
     * does it.
     *
     * @return true if there was something to cancel
     */
    public boolean signalCancel(String sessionKey) {
        // 0. Cancel subagents FIRST. The loop thread may be parked waiting for one
        //    of their results; killing them unblocks that wait immediately. Doing
        //    this later would leave Stop unresponsive until the drain timeout.
        var manager = subagentManager;
        if (manager != null) {
            manager.cancelBySession(sessionKey);
        }

        // A command like /new runs inside a turn of its own when nothing else is
        // active. Cancelling from there would kill the caller mid-command and the
        // user would get a CancellationException instead of the confirmation.
        // Subagents (step 0) are still cancelled — only the caller's turn is spared.
        if (sessionKey.equals(turnOwnedByThisThread.get())) {
            log.debug("signalCancel from within session {}'s own turn; not cancelling the caller", sessionKey);
            return false;
        }

        // 1. Set abort flag first (signals agent loop to stop)
        AtomicBoolean abort = abortFlags.get(sessionKey);
        if (abort != null) {
            abort.set(true);
        }

        // 2. Interrupt the actual agent loop thread (stops in-progress LLM calls)
        agentRunner.interrupt();

        // 3. Cancel the future
        CompletableFuture<AgentResponse> future = activeTasks.remove(sessionKey);
        boolean cancelled = false;
        if (future != null && !future.isDone()) {
            cancelled = future.cancel(true);
            log.info("Cancelled active task for session {}: {}", sessionKey, cancelled);
        }

        return cancelled || (abort != null);
    }

    /**
     * Shutdown the agent loop.
     */
    public void shutdown() {
        var manager = subagentManager;
        if (manager != null) {
            manager.shutdown();
        }
        executorService.shutdown();
        sessionManager.shutdown();
        log.info("AgentLoop shutdown complete");
    }

    /**
     * Inject a follow-up message into an active agent run.
     * Called from the UI when user sends a message during agent processing.
     *
     * @return true if the message was queued successfully
     */
    public boolean injectMessage(String sessionKey, String message) {
        return injectionManager.offer(sessionKey, message);
    }

    /**
     * Seam handed to {@link SubagentManager} as a
     * {@code ResultSink}, so the manager needs no reference back to this loop.
     */
    public boolean offerInjection(String sessionKey, Object turnToken, String message) {
        // Validate the turn and enqueue under the same lock the turn teardown takes.
        // If these were separate steps the turn could end in between, and the queued
        // announcement would be re-published as a bogus new user turn.
        synchronized (turnTeardownLock) {
            if (turnToken != null && turnToken != activeTurnTokens.get(sessionKey)) {
                return false;
            }
            return injectionManager.offer(sessionKey, message);
        }
    }

    /** Serialises turn teardown against subagent result delivery. */
    private final Object turnTeardownLock = new Object();

    /**
     * Attach the subagent manager. Wired by {@code AgentLoopFactory} after
     * construction, since the manager needs {@link #offerInjection} as its sink.
     */
    public void setSubagentManager(SubagentManager manager) {
        this.subagentManager = manager;
    }

    public SubagentManager getSubagentManager() {
        return subagentManager;
    }

    /**
     * A token identifying the turn currently active for a session. A subagent
     * captures it at spawn time and checks it before announcing, so a late result
     * cannot land in an unrelated later turn.
     */
    public SubagentManager.TurnToken currentTurnToken(String sessionKey) {
        Object token = activeTurnTokens.get(sessionKey);
        if (token == null) {
            return null;
        }
        return new SubagentManager.TurnToken() {
            @Override public boolean isActive() {
                return token == activeTurnTokens.get(sessionKey);
            }
            @Override public Object identity() {
                return token;
            }
        };
    }

    /**
     * Drain injected messages, blocking for a subagent result when one is still
     * running and nothing is ready yet — this is what folds a subagent's output
     * back into the same turn instead of a competing new one.
     *
     * <p>Ready messages (e.g. the user typing) always win: the blocking wait only
     * happens when the queue is empty.
     */
    private List<String> drainInjected(String sessionKey, int limit) {
        var manager = subagentManager;
        // Only wait on subagents spawned by the turn that is still running: a
        // leftover from an earlier turn has its result discarded on arrival, so
        // blocking on it would burn the whole timeout for nothing.
        boolean mayBlock = manager != null
            && !drainTimedOut.containsKey(sessionKey)
            && manager.getWaitableCountBySession(sessionKey) > 0;

        if (!mayBlock) {
            return injectionManager.drain(sessionKey, limit);
        }

        List<String> items = injectionManager.drainBlocking(sessionKey, limit, subagentDrainTimeoutMs);
        if (items.isEmpty()) {
            // Timed out (or interrupted): the subagent is presumed hung. Stop
            // blocking for the rest of this turn — the injection cycle counter only
            // advances when messages actually arrive, so without this latch every
            // remaining checkpoint would wait the full timeout again. A late result
            // still reaches the user via the announce fallback / status query.
            drainTimedOut.put(sessionKey, Boolean.TRUE);
            log.warn("Subagent drain timed out for session {}; not blocking again this turn", sessionKey);
        }
        return items;
    }

    /**
     * Check if a session has an active agent run (for UI routing).
     */
    public boolean hasActiveRun(String sessionKey) {
        return injectionManager.hasActiveRun(sessionKey);
    }

    /**
     * Progress callback interface for receiving typed updates during agent execution.
     */
    public interface ProgressCallback {
        void onProgress(ProgressUpdate update);
    }
}
