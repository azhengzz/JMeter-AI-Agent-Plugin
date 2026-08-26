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
import org.gitee.jmeter.ai.instance.DelegationGuard;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
    // 以下会话级状态以 sessionId 为键，被 loop 线程 / commonPool 工具线程 / SwingWorker / 子代理线程
    // 并发读写，故用 ConcurrentHashMap（读无锁，适合高频读、低频写、单条原子操作）；
    // 跨条目的复合原子操作（check-then-act）不靠它保证，另行用显式锁（如 resetFenceLock）。
    private final ConcurrentHashMap<String, CompletableFuture<AgentResponse>> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> abortFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> completionLatches = new ConcurrentHashMap<>();
    private final InjectionManager injectionManager = new InjectionManager();

    // 会话重置代数（/new、"+" 开新会话时递增）：垂死回合收尾 re-publish 残留前比对，
    // 代数已变 = 残留属于被放弃的旧会话，丢弃。Stop 不递增——「ack 过的
    // 消息不悬挂」恢复契约（注入队列归回合私有）仅在会话未被重置时成立。
    private final ConcurrentHashMap<String, Long> sessionEpochs = new ConcurrentHashMap<>();

    // Subagent support (null when agent.subagent.enabled=false)
    private volatile SubagentManager subagentManager;
    private final long subagentDrainTimeoutMs;
    // 标识每个会话当前「活跃回合」的令牌：用于比对，避免回合已结束才迟迟返回的子代理
    // 结果被注入到不相干的后续回合。
    private final ConcurrentHashMap<String, Object> activeTurnTokens = new ConcurrentHashMap<>();
    // 本回合子代理等待已超时的会话——之后不再阻塞等待。
    private final ConcurrentHashMap<String, Boolean> drainTimedOut = new ConcurrentHashMap<>();
    // 本线程当前正在执行的回合所属会话：使回合内运行的命令（如 /new）不会取消自己
    // 正身处其中的那个回合。
    private final ThreadLocal<String> turnOwnedByThisThread = new ThreadLocal<>();
    // 当前线程正在执行的回合自身的中止句柄与 future：回合内命令（/new、关闭整合清空）
    // 触发 signalCancel 时按<b>身份</b>豁免调用者自身——取消自身会在命令返回前杀死
    // 自己（用户看到 CancellationException 而非确认），但同会话的其他回合（Stop→/new
    // 序列里垂死回合在 /new 排队期间 re-publish 的旧会话孤儿）仍必须随重置消亡。
    private final ThreadLocal<TurnSelfRef> currentTurnSelf = new ThreadLocal<>();

    /** 回合自身句柄对（见 {@link #currentTurnSelf}）。 */
    private record TurnSelfRef(AtomicBoolean abortFlag, CompletableFuture<AgentResponse> future) {}

    // 本线程刚执行的重置所翻转到的会话代数（resetConversation 在栅栏内写入；命令回合
    // 的 lambda 在派发成功后读取并清除）。回合收尾对注入残留的分类只采纳「本回合
    // 自身命令造成的翻转」，不重读 currentEpoch——并发的外来重置（"+" 点击、关闭整合
    // 清空）落在派发窗口内时，重读会把被其放弃的旧会话残留洗白进最新会话。
    // 仅 loop 线程读取；EDT 路径（Phase 2 内联 cmdNew）写入后无人读，残留无害。
    private final ThreadLocal<Long> ownResetEpoch = new ThreadLocal<>();

    /**
     * 重置栅栏锁：{@link #resetConversation} 的「取消 + 代数翻转」与
     * {@link #republishLeftovers} 的「代数检查 + 重发布」在此互斥——两者都是
     * check-then-act，不加锁时重置与垂死回合收尾的重发布可互相穿插：旧代数
     * 孤儿漏网重发布、或重发布恰好横跨代数翻转的缝隙。
     */
    private final Object resetFenceLock = new Object();

    // Runtime state for /status command (matching Nanobot's loop._last_usage / _start_time)
    private final Instant startTime = Instant.now();
    private volatile Map<String, Integer> lastUsage = Map.of();
    private volatile ProgressCallback progressCallback;

    /**
     * re-publish 回合监听器：回合收尾把队列残留重新发布成新回合时，该回合没有
     * 调用方持有其 future（原 SwingWorker/IPC 调用方早已终止），最终 AgentResponse
     * 若无消费者将被静默丢弃（GUI 不渲染）。注册方（AiChatPanel）通过本监听器接管
     * 孤儿回合的 future，渲染最终回复并接管 UI 状态。
     */
    private volatile java.util.function.Consumer<CompletableFuture<AgentResponse>> republishListener;

    /**
     * 注册 re-publish 孤儿回合监听器。传 null 清除。
     */
    public void setRepublishListener(java.util.function.Consumer<CompletableFuture<AgentResponse>> listener) {
        this.republishListener = listener;
    }

    public AgentLoop(
            ToolRegistry toolRegistry,
            MemoryStore memoryStore,
            MemoryConsolidator memoryConsolidator,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            AiService aiService) {

        int maxIterations = AiConfig.getMaxToolIterations();
        int toolResultMaxChars = AiConfig.getToolResultMaxChars();
        long toolTimeoutMs = AiConfig.getToolTimeoutMs();

        // Cap at 300s: past that a subagent is presumed hung and the turn moves on.
        long drainTimeoutSec = AiConfig.getSubagentDrainTimeoutSeconds();
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
        // 会话正有回合在跑（路由槽存在，即 hasActiveRun = containsKey）时，把消息塞进该
        // 回合的注入队列，由回合内检查点中途注入；否则落到 Phase 3 开新回合。
        // 槽是注入路由的唯一事实来源。术语：提交=execute 把回合任务交给线程池；pickup=
        // 工作线程取出任务真正开跑；ack=offer 入队成功、调用方收到"已注入"回执。
        // startTurn 在提交之前就注册槽，故 [提交→pickup] 窗口内的并发 offer 仍能命中队列
        // 并拿到 ack（DelegationGuardTest 确定性的前提——窗口消息不会被拆成独立回合排到
        // 忙碌回合之后）；signalCancel 摘槽后，垂死会话读 hasActiveRun 为 false，新消息
        // 自然只能走 Phase 3 开新回合。窗口内已 ack 的消息由其载体回合在 pickup 时守护
        // 作废，保证 ack 过即必有交代、不悬挂。
        if (injectionManager.hasActiveRun(sessionKey)) {
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
        return startTurn(raw, message, sessionKey, callback, delegated);
    }

    /**
     * Phase 3：直接启动一个回合（经单线程 executor），不经 Phase 1 命令路由与
     * Phase 2 注入短路。除 processMessage 路由完毕后调用外，回合收尾的 leftover
     * re-publish 也走这里——re-publish 的语义是「起独立回合」，经 Phase 2 会被
     * 先提交回合的注入队列吸收成多余 ack。注入队列里只可能是纯用户消息
     * （priority/dispatchable 命令在 Phase 1/2 已拦截，公告在 re-publish 前已过滤），
     * 跳过命令路由安全。
     *
     * <p>注入队列归回合私有（对齐 Nanobot loop.py 的 per-turn pending queue + 身份条件摘除）：
     * <ul>
     *   <li>队列归回合私有：提交前 register 占路由槽并拿句柄，lambda 按<b>句柄</b>
     *       抽干/清理——垂死回合偷不到后继回合的消息，cleanup 条件摘槽不误摘后继；</li>
     *   <li>手工 future + executor.execute（而非 supplyAsync）：supplyAsync 对已取消
     *       （result 已置）的任务会整体跳过 lambda（AsyncSupply.run 判 d.result==null），
     *       pre-pickup 被取消回合的队列将无人善后。手工提交使被取消的死任务被取出时
     *       仍运行 guard 分支：按句柄抽干队列残留并作废（2026-08-23 契约修订：取消
     *       语义不重发布），窗口内已 ack 的消息不悬挂。</li>
     * </ul>
     */
    private CompletableFuture<AgentResponse> startTurn(
            String raw,
            String message,
            String sessionKey,
            ProgressCallback callback,
            boolean delegated) {
        final AtomicBoolean abortFlag = new AtomicBoolean(false);
        final CountDownLatch completionLatch = new CountDownLatch(1);
        abortFlags.put(sessionKey, abortFlag);
        completionLatches.put(sessionKey, completionLatch);

        final LinkedBlockingQueue<InjectionManager.InjectionItem> queue = injectionManager.register(sessionKey);

        // Marks this turn as the active one; a subagent spawned here compares
        // against it before announcing so late results cannot derail a later turn.
        final Object turnToken = new Object();

        final CompletableFuture<AgentResponse> future = new CompletableFuture<>();
        try {
            executorService.execute(() -> {
                if (future.isCancelled()) {
                    // pre-pickup 取消的善后：本回合从未运行（对齐 supplyAsync 的跳过
                    // 语义），但 [提交→pickup] 窗口内已 ack 入队的消息不能悬挂——按句柄
                    // 抽干并作废（2026-08-23 契约修订：取消语义一律作废，重置取消与
                    // Stop 取消无需再按代数区分——队列消息必然 ack 于取消之前，
                    // cancelRouting 与 offer 在 CHM bin 锁下互斥，摘槽后无新 offer）
                    discardCancelledLeftovers(injectionManager.cleanup(sessionKey, queue), sessionKey);
                    return;
                }
                activeTurnTokens.put(sessionKey, turnToken);
                turnOwnedByThisThread.set(sessionKey);
                currentTurnSelf.set(new TurnSelfRef(abortFlag, future));
                ownResetEpoch.remove(); // 防本线程上一回合/上一命令的残留
                // 本回合归属的会话代数：/new、"+" 重置会话时代数 +1，收尾 re-publish
                // 残留前比对——代数已变则残留属于被放弃的旧会话，丢弃。在 pickup 时
                // 读取；命令派发成功后若<b>本回合自身</b>执行了重置，改采其翻转到的
                // 代数（见下方 ownResetEpoch 分支）：/new 回合自身队列里 ack 过的消息
                // 在 /new 之后输入、属于新会话，必须 re-publish 而非被旧代数误杀
                long turnEpoch = currentEpoch(sessionKey);
                AgentResponse outcome = null;
                Throwable failure = null;
                try {
                    // Check regular commands first (inside executor)
                    Session session = sessionManager.getOrCreate(sessionKey);
                    CommandContext ctx = new CommandContext(raw, "", session, sessionKey, this);
                    String cmdResult = commandRouter.dispatch(ctx);
                    if (cmdResult != null) {
                        outcome = AgentResponse.success(cmdResult);
                        // 仅采纳本回合自身命令造成的代数翻转：不重读
                        // currentEpoch——派发窗口内并发的外来重置（"+"、关闭整合清空）
                        // 不会被采纳，其放弃的残留按 pickup 代数比对，正确丢弃
                        Long own = ownResetEpoch.get();
                        if (own != null) {
                            turnEpoch = own;
                            ownResetEpoch.remove();
                        }
                    } else {
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
                            .injectionCallback(limit -> drainInjected(queue, sessionKey, limit))
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
                        outcome = result.toAgentResponse();
                    }
                } catch (Throwable t) {
                    failure = t;
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
                    currentTurnSelf.remove();

                    // Cleanup: drain this turn's own queue (by handle) and decide the
                    // leftovers' fate — cancelled turns void them (contract 2026-08-23),
                    // naturally-completed turns re-publish user messages as fresh turns.
                    // Mirrors Nanobot loop.py's finally block (identity-checked pop).
                    republishLeftovers(injectionManager.cleanup(sessionKey, queue), sessionKey,
                            turnEpoch, callback, abortFlag.get());
                }
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(outcome);
                }
            });
        } catch (RejectedExecutionException ree) {
            // executor 已退役（模型切换 AgentLoopFactory.reset / shutdown）：回合从未
            // 入队。回收路由槽；队列若已有 [提交→execute] 间隙并入的消息，已无处
            // 投递——ERROR 可见化便于找回。
            List<InjectionManager.InjectionItem> stranded = injectionManager.cleanup(sessionKey, queue);
            for (InjectionManager.InjectionItem item : stranded) {
                if (!item.isAnnouncement()) {
                    log.error("Agent loop executor retired before turn started; message not processed: '{}'",
                        item.getText());
                }
            }
            future.complete(AgentResponse.error("Agent loop is shutting down; message not processed: " + raw));
        }

        // Track active task for cancellation support
        activeTasks.put(sessionKey, future);
        future.whenComplete((r, e) -> {
            // 按值条件删除：本回合 finally 的 re-publish 已把新回合的 future/flag/latch
            // put 进同一 key，无条件 remove(key) 会当场摘掉新回合的表项——新回合将
            // 变成 Stop 不可达且 latch 错乱
            activeTasks.remove(sessionKey, future);
            abortFlags.remove(sessionKey, abortFlag);
            completionLatches.remove(sessionKey, completionLatch);
            completionLatch.countDown();
        });

        return future;
    }

    /**
     * 回合收尾（自然完成 finally / pre-pickup guard）的统一出口：决定队列残留的去向。
     * <ul>
     *   <li>取消（cancelled=true）：残留一律作废（见 {@link #discardCancelledLeftovers}）；
     *       Stop 与重置同语义——消费进上下文的消息本就随回合作废，队列残留保持同
     *       命运，消除「点得快被复活、点得慢被作废」的时序差异；</li>
     *   <li>自然完成：代数已翻（会话被 /new 或 "+" 重置）→ 残留属于被放弃的旧会话，
     *       作废；代数一致 → 把用户消息重新发布成独立回合。re-publish 回合没有调用方
     *       持有其 future，交 {@link #republishListener} 消费；监听器异常必须吞掉——
     *       这里跑在收尾路径上，异常会炸掉循环使后续残留丢失（回合本身已提交，不受
     *       影响）。subagent 公告丢弃（结果可经 subagent_status 查询，不得伪造用户回合）。</li>
     * </ul>
     *
     * @param turnEpoch 本回合启动时捕获的会话代数：自然完成收尾时与当前代数不一致
     *                  说明会话已被重置
     * @param cancelled 本回合被取消（abort flag 已置，含 Stop 与重置）：残留一律作废
     */
    private void republishLeftovers(List<InjectionManager.InjectionItem> items, String sessionKey,
            long turnEpoch, ProgressCallback callback, boolean cancelled) {
        if (items.isEmpty()) {
            return;
        }
        if (cancelled) {
            discardCancelledLeftovers(items, sessionKey);
            return;
        }
        // 代数检查与 startTurn 重发布整体在栅栏锁内：与
        // resetConversation 的「取消 + 代数翻转」互斥，杜绝检查后、重发布前
        // 重置恰好落地的 TOCTOU 缝隙
        synchronized (resetFenceLock) {
            if (currentEpoch(sessionKey) != turnEpoch) {
                // 会话重置是显式放弃：旧会话连同未消费的注入消息一起结束。重置后残留
                // 若仍被 re-publish，其回复会写入新 session 文件并渲染进刚清空的聊天区
                // 与 Stop 的差异：Stop 后用户仍留在同一会话。
                int userMessages = 0;
                for (InjectionManager.InjectionItem item : items) {
                    if (!item.isAnnouncement()) {
                        userMessages++;
                    }
                }
                log.info("Discarding leftover(s) after conversation reset for session {}: {} user message(s), "
                        + "{} announcement(s) belong to the abandoned conversation", sessionKey, userMessages,
                        items.size() - userMessages);
                return;
            }
            int droppedAnnouncements = 0;
        for (InjectionManager.InjectionItem item : items) {
            if (item.isAnnouncement()) {
                droppedAnnouncements++;
                continue;
            }
            // 注入队列里只可能是用户消息:委派请求在 Phase 2 的 delegated 分支即被
            // 拒绝返回,从不 offer 入队。故此处写死 delegated=false 语义上必然正确、
            // 没有委派标记可丢;若委派消息被并入队列又以此 false 重发布,DelegationGuard
            // (深度守卫)会被绕过,被委派回合可再委派出去(跨实例 ping-pong)。
            log.info("Re-publishing leftover message for session {}", sessionKey);
            try {
                CompletableFuture<AgentResponse> republished =
                    startTurn(item.getText(), item.getText(), sessionKey, callback, false);
                java.util.function.Consumer<CompletableFuture<AgentResponse>> listener = republishListener;
                if (listener != null) {
                    try {
                        listener.accept(republished);
                    } catch (Exception e) {
                        log.warn("Republish listener failed for session {}; the republished turn itself is unaffected",
                            sessionKey, e);
                    }
                } else {
                    log.warn("Re-published orphan turn for session {} has no republish listener; "
                        + "its final response will not be consumed by anyone", sessionKey);
                }
            } catch (RejectedExecutionException ree) {
                // executor 已退役（模型切换换血）：本回合自身的返回值不受影响，
                // 但残留消息无处投递——ERROR 可见化（消息内容进日志便于找回）
                log.error("Executor retired before leftover could be re-published for session {}; "
                    + "message lost: '{}'", sessionKey, item.getText());
            }
        }
        if (droppedAnnouncements > 0) {
            log.info("Dropped {} subagent announcement leftover(s) for session {} "
                + "(queryable via subagent_status)", droppedAnnouncements, sessionKey);
        }
        } // resetFenceLock
    }

    /**
     * 契约修订（2026-08-23，Stop=硬边界）：取消（Stop/重置）语义下未消费的注入
     * 残留一律作废，不再重发布——消费进上下文的消息本就随回合作废，队列残留保持
     * 同命运，消除「点得快被复活、点得慢被作废」的时序差异。作废仅记日志，不经
     * 回合回调渲染进聊天区（2026-08-23 拍板）；subagent 公告静默丢弃
     * （结果可经 subagent_status 查询）。
     */
    private void discardCancelledLeftovers(List<InjectionManager.InjectionItem> items, String sessionKey) {
        int userMessages = 0;
        for (InjectionManager.InjectionItem item : items) {
            if (!item.isAnnouncement()) {
                userMessages++;
            }
        }
        log.info("Discarding {} queued message(s) after cancellation for session {}: "
                + "Stop/reset voids unconsumed injections ({} user message(s))",
                items.size(), sessionKey, userMessages);
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
     * Update last usage stats.
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
     * <p><b>自身豁免按身份：</b>当调用发生在本会话回合自身的执行线程上
     * （/new 作为 Phase 3 回合运行、resetConversation 在其内部被调），只豁免调用者
     * 自身的 abort flag 与 future（取消自身 = 命令确认永远无法返回）；同会话<b>其他</b>
     * 回合照常取消——典型受害者：Stop/垂死窗口期间其他在跑或排队的旧会话回合
     * （含自然完成重发布的孤儿），它们属于被放弃的旧会话，必须随重置消亡。路由槽始终摘
     * 除：命令回合不消费注入，其后的消息走新回合排在命令之后，天然属于新会话。
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

        boolean self = sessionKey.equals(turnOwnedByThisThread.get());
        TurnSelfRef selfRef = self ? currentTurnSelf.get() : null;

        // 1. Set abort flag first (signals agent loop to stop) — 豁免调用者自身
        AtomicBoolean abort = abortFlags.get(sessionKey);
        if (abort != null && (selfRef == null || abort != selfRef.abortFlag())) {
            abort.set(true);
        }

        // 2. Interrupt the actual agent loop thread (stops in-progress LLM calls)
        //    — 仅非自身：自身正执行命令，中断会打断命令本身
        if (!self) {
            agentRunner.interrupt();
        }

        // 3. Cancel the future — 豁免调用者自身
        CompletableFuture<AgentResponse> future = activeTasks.remove(sessionKey);
        boolean cancelled = false;
        if (future != null && !future.isDone()
                && (selfRef == null || future != selfRef.future())) {
            cancelled = future.cancel(true);
            log.info("Cancelled active task for session {}: {}", sessionKey, cancelled);
        }

        // 4. 摘路由槽：垂死会话立即不可注入——新消息只能走 Phase 3 开新回合
        //    （用户原则：Stop 即会话硬边界，垂死窗口内不再产生 "[Injected]" 谎话 ack）。
        //    已 pickup 的垂死回合仍持队列句柄，其 finally 按句柄抽干残留并作废
        //    （2026-08-23 契约修订：取消语义不重发布）；pre-pickup 被取消的回合由其
        //    死任务的 guard 分支（见 startTurn）作废。
        //    顺序：先 cancel future 再摘槽——两步间隙内 offer 进垂死队列的消息同样
        //    由 finally/guard 作废（仅记日志，不渲染进聊天区），不悬挂。
        injectionManager.cancelRouting(sessionKey);

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
            // 回合令牌比对：turnToken 是子代理 spawn 时捕获的当时回合令牌，
            // activeTurnTokens.get(sessionKey) 是此刻该会话真正活跃的回合令牌。
            // 二者不等 = 原回合已结束（被取消或自然收尾、map 已被移除/替换），
            // 这条结果属于迟到的「过期公告」，直接丢弃——绝不能投递给后续回合，
            // 否则会把 A 回合的子代理结论错误地喂给 B 回合的上下文。
            if (turnToken != null && turnToken != activeTurnTokens.get(sessionKey)) {
                return false;
            }
            // 第三个参数 true = 把本条消息打标为「子代理公告」（对齐 Nanobot 的
            // injected_event 元数据），与用户消息区分开：回合自然结束或取消后，
            // re-publishLeftovers 清理队列残留时会先检查 InjectionItem.isAnnouncement()——
            // 公告直接丢弃（其结论仍可经 subagent_status 查询），只有用户消息才会被
            // re-publish 成一个新的用户回合。不区分的话，这条子代理结果公告会被误当
            // 成用户输入伪造出本不存在的回合。
            return injectionManager.offer(sessionKey, message, true);
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
     * Drain this turn's injected messages (by queue handle), blocking for a
     * subagent result when one is still running and nothing is ready yet — this
     * is what folds a subagent's output back into the same turn instead of a
     * competing new one.
     *
     * <p>Ready messages (e.g. the user typing) always win: the blocking wait only
     * happens when the queue is empty.
     */
    private List<String> drainInjected(LinkedBlockingQueue<InjectionManager.InjectionItem> queue,
            String sessionKey, int limit) {
        var manager = subagentManager;
        // Only wait on subagents spawned by the turn that is still running: a
        // leftover from an earlier turn has its result discarded on arrival, so
        // blocking on it would burn the whole timeout for nothing.
        boolean mayBlock = manager != null
            && !drainTimedOut.containsKey(sessionKey)
            && manager.getWaitableCountBySession(sessionKey) > 0;

        List<InjectionManager.InjectionItem> items = mayBlock
            ? injectionManager.drainBlocking(queue, limit, subagentDrainTimeoutMs)
            : injectionManager.drain(queue, limit);
        if (mayBlock && items.isEmpty()) {
            // Timed out (or interrupted): the subagent is presumed hung. Stop
            // blocking for the rest of this turn — the injection cycle counter only
            // advances when messages actually arrive, so without this latch every
            // remaining checkpoint would wait the full timeout again. A late result
            // still reaches the user via the announce fallback / status query.
            drainTimedOut.put(sessionKey, Boolean.TRUE);
            log.warn("Subagent drain timed out for session {}; not blocking again this turn", sessionKey);
        }
        // 抽干后无需 abort 复查回队：signalCancel 已先摘路由槽（垂死窗口的新消息
        // 进不了队列），而队列里既有的消息只在「上一次 abort 检查之后、本次抽干
        // 之前」这段同线程无阻塞的指令间隙内可能被抽走——窗口为指令级而非秒级
        // LLM 调用窗口，可忽略。
        List<String> texts = new ArrayList<>(items.size());
        for (InjectionManager.InjectionItem item : items) {
            texts.add(item.getText());
        }
        return texts;
    }

    /**
     * Check if a session has an active agent run (for UI routing).
     */
    public boolean hasActiveRun(String sessionKey) {
        // 路由槽存在 = 最新回合可注入（单一事实来源）。垂死会话的槽已被
        // signalCancel 摘除，此处天然报 false——GUI 据此在垂死窗口把新消息走
        // 正常发送而非注入，避免消息被垂死回合吞掉。
        return injectionManager.hasActiveRun(sessionKey);
    }

    /** 会话当前重置代数（从未重置为 0）。 */
    private long currentEpoch(String sessionKey) {
        return sessionEpochs.getOrDefault(sessionKey, 0L);
    }

    /**
     * 标记会话已重置（/new、"+" 开新会话）：代数 +1。此后垂死回合按旧代数
     * re-publish 的注入残留将被丢弃（见 {@link #republishLeftovers}）。Stop 不调用
     * 本方法——「ack 过的消息不悬挂」的恢复契约仅在会话未重置时成立。
     *
     * @return 翻转到的代数值（供发起线程精准采纳，免重读竞态）
     */
    public long markConversationReset(String sessionKey) {
        return sessionEpochs.compute(sessionKey, (k, v) -> (v == null ? 0L : v) + 1L);
    }

    /**
     * 会话重置核心（/new 与 "+" 按钮共用，唯一实现）：快照未整合消息 → 中止在跑
     * 回合（含子代理）→ 代数 +1 → 清空并落盘 → 失效缓存 → 异步归档快照。
     *
     * <p>中止必须先于清空（fire-and-forget，EDT 安全）：否则在跑回合会在会话清空后
     * 继续跑完，其回复经渲染回调落进刚清空的新会话，且回合持续向已清空 session
     * 回写。UI 外壳（清聊天区/欢迎语/按钮复位/渲染代数递增）留在调用方。
     *
     * @return 归档快照（空列表表示无可归档消息）
     */
    public List<Message> resetConversation(String sessionKey) {
        return resetConversation(sessionKey, true);
    }

    /**
     * @param archiveSnapshot false 供关闭期深度提炼成功后的清空复用（
     *        {@code CloseConsolidationCoordinator.clearCurrentSession}）：消息刚被
     *        提炼进 MEMORY.md，不再二次归档，但重置栅栏（取消+代数）语义完整保留
     */
    public List<Message> resetConversation(String sessionKey, boolean archiveSnapshot) {
        Session session = sessionManager.getOrCreate(sessionKey);
        List<Message> snapshot = session.getUnconsolidatedMessages();
        // 「取消 + 代数翻转」与 republishLeftovers 的「检查 + 重发布」在栅栏锁下互斥：
        // 要么重发布先入锁（旧代数放行 → 取消必然看得见它并
        // 将其消亡），要么重置先入锁（代数已翻 → 重发布见新代数即丢弃）。
        // ownResetEpoch 记录本线程翻转到的代数：若本次重置由命令回合自身发起（/new
        // 在其派发内执行），该回合的收尾分类精准采纳此值（不重读 currentEpoch，
        // 免被派发窗口内并发的外来重置污染）
        synchronized (resetFenceLock) {
            signalCancel(sessionKey);
            ownResetEpoch.set(markConversationReset(sessionKey));
        }
        session.clear();
        sessionManager.saveSession(session);
        sessionManager.invalidate(session.getKey());
        if (archiveSnapshot && !snapshot.isEmpty()) {
            memoryConsolidator.archiveMessagesAsync(snapshot);
        }
        log.info("Session reset: archived {} message(s)", snapshot.size());
        return snapshot;
    }

    /**
     * Progress callback interface for receiving typed updates during agent execution.
     */
    public interface ProgressCallback {
        void onProgress(ProgressUpdate update);
    }
}
