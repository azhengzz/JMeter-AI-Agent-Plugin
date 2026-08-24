package org.gitee.jmeter.ai.agent.memory;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 关闭期记忆整合协调器:驱动两条退出路径共享的"始终静默归档"例程,并提供深度提炼入口。
 *
 * <p>两条退出路径:
 * <ul>
 *   <li>用户主动关闭 —— {@code ExitCommand} 前置动作监听(EDT),完成后可选弹交互对话框;</li>
 *   <li>非用户退出(崩溃/{@code Restart}/{@code System.exit})—— JVM shutdown hook 兜底,
 *       仅静默归档,无对话框、无 LLM、不读 {@code GuiPackage}。</li>
 * </ul>
 *
 * <p>幂等:{@link #ARCHIVED} CAS 守卫保证静默归档只执行一次;两条路径任一先到即归档,
 * 另一路径 no-op。归档本身还经 {@link Session#getLastConsolidatedIndex()} 去重(已推进则未整合集为空)。
 *
 * <p>线程安全:全静态,无共享可变状态(仅一个静态 AtomicBoolean)。可在 EDT 与 shutdown 线程调用。
 */
public final class CloseConsolidationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(CloseConsolidationCoordinator.class);

    /** 静默归档是否已完成的幂等守卫(前置监听与 shutdown hook 共享)。 */
    private static final AtomicBoolean ARCHIVED = new AtomicBoolean(false);

    private CloseConsolidationCoordinator() {
    }

    /**
     * 始终静默地把当前实例会话的未整合消息同步归档进共享 {@code HISTORY.md}。
     * agent 未初始化(用户从未开聊)时为 no-op;{@link #ARCHIVED} CAS 保证仅执行一次。
     *
     * @return 实际归档的消息条数(供对话框文案);已归档/无可归档/agent 未初始化均返回 0
     */
    public static int archiveSilently() {
        if (!ARCHIVED.compareAndSet(false, true)) {
            return 0; // 另一路径已完成
        }
        try {
            AgentLoop loop = agentLoop();
            if (loop == null) {
                return 0;
            }
            MemoryConsolidator consolidator = loop.getMemoryConsolidator();
            SessionManager sessions = loop.getSessionManager();
            if (consolidator == null || sessions == null) {
                return 0;
            }
            Session session = sessions.get(InstanceContext.currentSessionKey());
            if (session == null) {
                return 0;
            }
            int n = consolidator.archiveSync(session);
            if (n > 0) {
                sessions.saveSession(session);
                log.info("Close-time silent archive: {} messages -> HISTORY.md", n);
            }
            return n;
        } catch (Throwable t) {
            // 关闭路径绝不能因归档失败而阻断 JVM 退出
            log.error("Close-time silent archive failed (best-effort): {}", t.toString());
            return 0;
        }
    }

    /**
     * 当前会话未整合消息的快照(副本),供关闭对话框显示条数 N 与深度提炼使用。
     * agent 未初始化返回空表。须在 {@link #archiveSilently()} 之前调用(归档会推进索引)。
     */
    public static List<Message> unconsolidatedSnapshot() {
        AgentLoop loop = agentLoop();
        if (loop == null) {
            return Collections.emptyList();
        }
        SessionManager sessions = loop.getSessionManager();
        if (sessions == null) {
            return Collections.emptyList();
        }
        Session session = sessions.get(InstanceContext.currentSessionKey());
        return session == null ? Collections.emptyList() : session.getUnconsolidatedMessages();
    }

    /**
     * 深度提炼(写 {@code MEMORY.md}),仅在用户对话框选"是"时调用。须在非 EDT 线程执行
     * (关闭对话框经 {@code SwingWorker} 调用)。有界超时由 {@code agent.memory.consolidate-on-exit.timeout.ms}。
     *
     * <p>提炼本身<b>不改动</b>会话;成功后由调用方在同一 EDT 回调里调用
     * {@link #clearCurrentSession()} 清空会话(防退出取消后二次触发提炼同一批消息)。
     *
     * @return 提炼是否成功完成(超时/失败/agent 未初始化返回 false)
     */
    public static boolean distill(List<Message> messages) {
        AgentLoop loop = agentLoop();
        if (loop == null) {
            return false;
        }
        MemoryConsolidator consolidator = loop.getMemoryConsolidator();
        if (consolidator == null) {
            return false;
        }
        long timeout = AiConfig.getConsolidateOnExitTimeoutMs();
        return consolidator.distillSync(messages, timeout);
    }

    /**
     * 深度提炼成功后清空当前实例会话（数据层）。
     *
     * <p>目的:提炼结果已写入 {@code MEMORY.md}(经系统提示持续生效),原会话消息不再有价值;
     * 清空后 {@link #unconsolidatedSnapshot()} 返回空,<b>杜绝退出被取消后二次触发提炼同一批消息</b>。
     *
     * <p>走共享重置核心 {@link AgentLoop#resetConversation(String, boolean)}(archive=false,
     * 刚提炼过不二次归档),与 {@code /new}、"+" 的栅栏语义对齐(对抗审查 C3):中止在跑回合
     * ——含关闭对话框取消上个回合后、其垂死收尾 re-publish 的孤儿——并翻转会话代数,
     * 使其后迟到的旧会话渲染/落盘被各层守卫丢弃。
     *
     * <p>须在 EDT 调用(与 GUI 清空同线程)。agent 未初始化/无会话时为 no-op。
     */
    public static void clearCurrentSession() {
        AgentLoop loop = agentLoop();
        if (loop == null) {
            return;
        }
        SessionManager sessions = loop.getSessionManager();
        if (sessions == null) {
            return;
        }
        Session session = sessions.get(InstanceContext.currentSessionKey());
        if (session == null) {
            return;
        }
        loop.resetConversation(session.getKey(), false);
    }

    private static AgentLoop agentLoop() {
        try {
            return AgentLoopFactory.getAgentLoop();
        } catch (IllegalStateException e) {
            return null; // agent 未初始化(用户从未开聊)
        }
    }
}
