package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.JMeterToolRegistry;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.ClaudeService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating Agent Loop instances.
 * Provides a single point of configuration and initialization.
 */
public class AgentLoopFactory {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopFactory.class);

    private static AgentLoop instance;

    /**
     * 工厂级回合事件订阅者表：订阅关系挂在「进程级工厂」而非 AgentLoop 实例——
     * 模型切换（{@code switchAiService}）换血 loop 时订阅不丢、无需重新注册（消灭
     * 「重建后忘了再挂」一类 bug）。{@link #reset()} 与 {@link #shutdown()} 均不清空
     * 本表；createAgentLoop 每次建新 loop 后全量重挂。
     */
    private static final CopyOnWriteArrayList<TurnSubscriber> globalTurnSubscribers =
            new CopyOnWriteArrayList<>();

    /**
     * 已退役但可能仍有在跑回合的 loop（有界）：换血（模型切换/reset）时旧 loop 收进
     * 此表——其上在跑回合的迟到事件经各自订阅表照常可达，终止信号经
     * {@link #signalCancelAny} 照常可路由（面板只持有当前 loop 引用，Stop 直发会漏掉
     * 退役 loop 的回合）。条目在其会话无在跑回合时剪除（signalCancelAny 后置剪枝），
     * 上限兜底防泄漏。
     */
    private static final CopyOnWriteArrayList<AgentLoop> retiredLoops =
            new CopyOnWriteArrayList<>();

    /** 退役表上限：正常剪枝下到不了上限，兜底防极端换血频率下的累积。 */
    private static final int MAX_RETIRED_LOOPS = 4;

    /**
     * 注册回合事件订阅者：记入工厂表并立即挂到当前存活 loop（若有）。
     * 订阅者在模型切换后的新 loop 上继续生效（见 {@link #globalTurnSubscribers}）。
     */
    public static synchronized void addTurnSubscriber(TurnSubscriber subscriber) {
        if (subscriber == null) {
            return;
        }
        globalTurnSubscribers.remove(subscriber);
        globalTurnSubscribers.add(subscriber);
        if (instance != null) {
            instance.addTurnSubscriber(subscriber);
        }
    }

    /** 注销回合事件订阅者（工厂表 + 当前存活 loop 一并摘除）。 */
    public static synchronized void removeTurnSubscriber(TurnSubscriber subscriber) {
        globalTurnSubscribers.remove(subscriber);
        if (instance != null) {
            instance.removeTurnSubscriber(subscriber);
        }
    }

    /** 仅测试卫生用：清空工厂级订阅表并从存活 loop 摘除。生产代码不得调用。 */
    public static synchronized void clearTurnSubscribersForTest() {
        if (instance != null) {
            for (TurnSubscriber subscriber : globalTurnSubscribers) {
                instance.removeTurnSubscriber(subscriber);
            }
        }
        globalTurnSubscribers.clear();
    }

    /**
     * 终止信号的全实例路由（Stop 按钮入口）：先发当前单例，再对退役 loop 中该会话
     * 仍有在跑回合者补发——换血后面板只持新 loop 引用，直接 signalCancel 会漏掉旧
     * loop 上仍在跑的 IPC/委派回合（视觉上 Stop 生效、回合实际继续烧）。
     * 非阻塞（signalCancel 语义），EDT 可同步调用。排空（无在跑回合）的退役条目
     * 顺手剪除。
     *
     * @return 任一 loop 上确有可取消对象时 true
     */
    public static boolean signalCancelAny(String sessionKey) {
        return signalCancelAny(sessionKey, CancelCause.USER_STOP);
    }

    /** cause 化变体（见 {@link #signalCancelAny(String)}）。 */
    public static boolean signalCancelAny(String sessionKey, CancelCause cause) {
        return signalCancelRouted(sessionKey, cause, null);
    }

    /**
     * 路由核：先发当前单例（exclude 非空时跳过），再对退役 loop 中该会话仍有在跑
     * 回合者补发；排空条目顺手剪枝（谓词与既有 signalCancelAny 逐字节一致，enabled
     * 压测套件冻结该语义）。exclude 用于 {@link #resetConversationAny}——self 的取消
     * 由其 resetConversation 在栅栏锁内自做（保持「取消+代数」互斥不变式），路由腿
     * 只触达其余 loop。
     */
    private static boolean signalCancelRouted(String sessionKey, CancelCause cause, AgentLoop exclude) {
        boolean signalled = false;
        AgentLoop current = currentLoopSnapshot();
        if (current != null && current != exclude) {
            signalled |= current.signalCancel(sessionKey, cause);
        }
        for (AgentLoop retired : retiredLoops) {
            if (retired != exclude && retired.hasActiveRun(sessionKey)) {
                signalled |= retired.signalCancel(sessionKey, cause);
            }
        }
        retiredLoops.removeIf(l -> l != exclude && !l.hasActiveRun(sessionKey));
        return signalled;
    }

    /**
     * 会话重置的全实例路由（/new、GUI "+"、关闭整合清空的统一入口）。
     *
     * <p>分两步：<b>先取消，再重置</b>。
     *
     * <p><b>先取消</b>——{@link #signalCancelRouted} 把 RESET 信号发给「当前单例
     * loop + 退役表中仍在跑该会话回合的旧 loop」，但跳过 self：self 的取消由下面
     * {@link AgentLoop#resetConversation} 在栅栏内自做，保持「取消 + 代数翻转」的
     * 互斥不变式。其余 loop 上在跑回合的 abort 标志位（volatile 写）先于 self 腿的
     * 会话截断落盘被置位（同一线程的程序序保证）；回合收尾复查到该标志便放弃写盘，
     * 于是已截断的 jsonl 不会被退役 loop 的迟到落盘重新写回。
     *
     * <p><b>再重置</b>——在 self 腿上执行 {@link AgentLoop#resetConversation}
     * 核心（栅栏内取消 + 代数翻转 + 会话截断）。self 是发起方 loop，无论它是不是
     * 工厂当前单例（直接构造的 loop、命令回合所在 loop、换血窄窗口内已退役的
     * loop），核心都必须在它身上执行；self 腿内的 ThreadLocal 身份豁免随之原样
     * 生效（/new 命令不自杀）。
     *
     * <p>换血重建窗口（当前单例为 null）下，路由腿照常工作、self 腿照常执行。
     * 本方法不等待垂死回合收尾（fire-and-forget，对齐
     * {@link AgentLoop#resetConversation}；阻塞式收尾等待见
     * {@link #cancelActiveTaskAny}）。
     *
     * @return self 腿 resetConversation 的归档快照（空列表 = 无可归档消息）
     */
    public static List<Message> resetConversationAny(AgentLoop self, String sessionKey) {
        return resetConversationAny(self, sessionKey, true);
    }

    /** @param archiveSnapshot false 供关闭期深度提炼成功后的清空复用（不再二次归档）。 */
    public static List<Message> resetConversationAny(AgentLoop self, String sessionKey,
                                                     boolean archiveSnapshot) {
        signalCancelRouted(sessionKey, CancelCause.RESET, self);
        return self.resetConversation(sessionKey, archiveSnapshot);
    }

    /**
     * 阻塞式全实例取消（关闭整合入口）：= 逐 loop 的 signalCancel + 有界收尾等待
     * （合计 ≤5s 共享 deadline）。目标集必须在取消<b>前</b>捕获——signalCancelRouted
     * 的后置剪枝会摘除已排空条目，等待集取自剪枝后的表则垂死收尾无人等待（关闭整合
     * 依赖「先等回合真收尾再快照/提炼」）。null-safe：当前单例为空（重建窗口）不抛
     * 不漏——「工厂未初始化」不等于「必无活动回合」，退役表可能正挂着换血前 loop 的
     * 在跑回合。
     *
     * <p>本方法与 {@link #resetConversationAny} 均<b>不得</b> synchronized：要阻塞至
     * 5s，不得持有工厂监视器（否则 getAgentLoop/addTurnSubscriber 全线卡满等待）。
     *
     * @return 任一 loop 上确有可取消对象时 true
     */
    public static boolean cancelActiveTaskAny(String sessionKey, CancelCause cause) {
        List<AgentLoop> targets = new ArrayList<>();
        AgentLoop current = currentLoopSnapshot();
        if (current != null) {
            targets.add(current);
        }
        for (AgentLoop retired : retiredLoops) {
            if (retired.hasActiveRun(sessionKey)) {
                targets.add(retired);
            }
        }
        boolean signalled = false;
        for (AgentLoop target : targets) {
            signalled |= target.signalCancel(sessionKey, cause);
        }
        retiredLoops.removeIf(l -> !l.hasActiveRun(sessionKey));
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (AgentLoop target : targets) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            target.waitForCancellation(sessionKey, remaining, TimeUnit.NANOSECONDS);
        }
        return signalled;
    }

    /** 当前单例快照（短锁快取快放，不跨订阅回调持工厂监视器）。 */
    private static synchronized AgentLoop currentLoopSnapshot() {
        return instance;
    }

    /** 换血/重置时收容旧 loop：先剪枝再入表，超上限丢最旧。 */
    private static void retireLoop(AgentLoop loop) {
        retiredLoops.removeIf(l -> !l.hasActiveRun(InstanceContext.currentSessionKey()));
        retiredLoops.add(loop);
        while (retiredLoops.size() > MAX_RETIRED_LOOPS) {
            retiredLoops.remove(0);
        }
    }

    /**
     * Get or create the Agent Loop singleton instance
     */
    public static synchronized AgentLoop getAgentLoop(AiService aiService) {
        // Always recreate if a different service is requested
        if (instance == null || !instance.getAiService().equals(aiService)) {
            if (instance != null) {
                log.info("Switching AgentLoop from {} to {}", instance.getAiService().getName(), aiService.getName());
                // 先收容再关停：shutdown 不打断在跑任务（executorService.shutdown()），
                // 其上在跑回合的终止信号此后经 signalCancelAny 路由
                retireLoop(instance);
                instance.shutdown();
            }
            instance = createAgentLoop(aiService);
        }
        return instance;
    }

    /**
     * Get the Agent Loop singleton instance (must be initialized first)
     */
    public static synchronized AgentLoop getAgentLoop() {
        if (instance == null) {
            throw new IllegalStateException("Agent Loop not initialized. Call getAgentLoop(AiService) first.");
        }
        return instance;
    }

    /**
     * Create a new Agent Loop instance
     */
    private static AgentLoop createAgentLoop(AiService aiService) {
        if (!AiConfig.isAgentEnabled()) {
            log.warn("Agent Loop is disabled in configuration");
            return null;
        }

        var workspacePath = AiConfig.getWorkspacePath();
        log.info("Creating Agent Loop with AI service: {}", aiService.getName());

        // Create components
        ToolRegistry toolRegistry = new ToolRegistry();
        MemoryStore memoryStore = new MemoryStore(workspacePath);
        // 每实例会话:只加载当前 instanceId 的 jsonl,不解析历史遗留/其他实例文件
        // (currentSessionKey 受 agent.session.per-instance 门控,false 回退全局 legacy 键)。
        SessionManager sessionManager = new SessionManager(
                workspacePath, InstanceContext.currentSessionKey());

        ContextBuilder contextBuilder = new ContextBuilder(
                memoryStore,
                workspacePath
        );

        MemoryConsolidator consolidator = new MemoryConsolidator(
                memoryStore, aiService, sessionManager, contextBuilder, toolRegistry);

        // Register tools
        if (AiConfig.isJmeterToolsEnabled()) {
            JMeterToolRegistry.registerDefaultTools(toolRegistry);
        }

        // Create Agent Loop
        AgentLoop agentLoop = new AgentLoop(
                toolRegistry,
                memoryStore,
                consolidator,
                contextBuilder,
                sessionManager,
                aiService
        );

        registerSubagentTools(agentLoop, toolRegistry, contextBuilder, sessionManager, aiService);

        // 工厂级订阅表全量重挂到新 loop：模型切换换血后订阅不丢（订阅者的注册时机
        // 可以早于/晚于 loop 创建，两端解耦）
        for (TurnSubscriber subscriber : globalTurnSubscribers) {
            agentLoop.addTurnSubscriber(subscriber);
        }

        log.info("Agent Loop created successfully with {} tools", toolRegistry.size());
        return agentLoop;
    }

    /**
     * Wire up the subagent machinery when enabled.
     *
     * <p>The manager takes {@code agentLoop::offerInjection} as its result sink, so
     * a finished subagent hands its output to the loop's injection queue without
     * either side holding a reference to the other. Both tools keep the default
     * {@code core} scope, which is what keeps them out of the subagent's own
     * toolset (no recursive spawning, no self-introspection).
     */
    private static void registerSubagentTools(AgentLoop agentLoop,
                                              ToolRegistry toolRegistry,
                                              ContextBuilder contextBuilder,
                                              SessionManager sessionManager,
                                              AiService aiService) {
        boolean enabled = AiConfig.isSubagentEnabled();
        if (!enabled) {
            log.info("Subagent support is disabled");
            return;
        }

        var manager = new org.gitee.jmeter.ai.agent.subagent.SubagentManager(
                aiService,
                contextBuilder,
                sessionManager,
                toolRegistry,
                agentLoop::offerInjection);
        agentLoop.setSubagentManager(manager);

        toolRegistry.register(new org.gitee.jmeter.ai.agent.tools.subagent.SpawnTool(
                manager,
                () -> {
                    var ctx = org.gitee.jmeter.ai.agent.run.AgentRunContext.current();
                    return ctx == null ? null : agentLoop.currentTurnToken(ctx.getSessionKey());
                }));
        toolRegistry.register(new org.gitee.jmeter.ai.agent.tools.subagent.SubagentStatusTool(manager));

        log.info("Subagent support enabled (spawn + subagent_status registered)");
    }

    /**
     * Reset the Agent Loop instance (for testing or reconfiguration)
     */
    public static synchronized void reset() {
        if (instance != null) {
            retireLoop(instance);
            instance.shutdown();
            instance = null;
        }
    }

    /**
     * Shutdown the Agent Loop
     */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
}
