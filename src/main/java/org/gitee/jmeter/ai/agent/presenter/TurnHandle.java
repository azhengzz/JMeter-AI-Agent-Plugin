package org.gitee.jmeter.ai.agent.presenter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回合身份句柄：进程唯一 {@code id} + 来源 + 显示域元数据 + 终态原子去重位。
 *
 * <p>{@code id} 由进程级 {@link AtomicLong} 序列分配，跨 {@code switchAiService} 的
 * AgentLoop 重建不回绕——陈旧 loop 的迟到事件不可能撞上新回合 id，订阅者按 id
 * 过滤即天然免疫模型切换期的陈旧终态。
 *
 * <p>{@link #tryClaimTerminal()} 是终态"恰好一次"的执法点：取消路径
 * （signalCancel）与回合体收尾路径（try 尾/catch）双发射点竞态时，先 claim 者
 * 发射、后到者静默放弃，订阅者无需自行去重终态。
 *
 * <p>本类是未来 turn-centric 重构（{@code refactor-agent-loop-turn-centric}）中
 * {@code Turn} 聚合体的身份字段子集（id/sessionKey/origin/echoText），
 * 前向兼容：Turn 落地时升格或由 Turn 携带本句柄，订阅者零感知。
 */
public final class TurnHandle {
    /** 进程级序列：跨 AgentLoop 实例重建持续递增（见类注释的防撞语义）。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private final long id;
    private final String sessionKey;
    private final TurnOrigin origin;
    /** 含来源前缀的消息文本；REPUBLISH 为 null（You 回显已由 INJECTED 事件给过）。 */
    private final String echoText;
    /** 命令回合（isPriority||isDispatchable，判据内建于 loop）：IPC 命令回合无显示契约。 */
    private final boolean commandTurn;
    /** 终态去重位：双发射点（signalCancel vs 回合体）恰好一次。 */
    private final AtomicBoolean terminalEmitted = new AtomicBoolean();
    // 取消原因不经句柄转存：TurnEvent.cancelled 直接携带 cause，订阅者读事件即可。
    public TurnHandle(String sessionKey, TurnOrigin origin, String echoText, boolean commandTurn) {
        this.id = SEQ.incrementAndGet();
        this.sessionKey = sessionKey;
        this.origin = origin;
        this.echoText = echoText;
        this.commandTurn = commandTurn;
    }

    public long id() {
        return id;
    }

    public String sessionKey() {
        return sessionKey;
    }

    public TurnOrigin origin() {
        return origin;
    }

    public String echoText() {
        return echoText;
    }

    public boolean commandTurn() {
        return commandTurn;
    }

    /** IPC 命令回合无显示契约：不发 STARTED/PROGRESS（订阅者仍会收到其终态）。 */
    public boolean visibleToPanel() {
        return !commandTurn || origin.isLocalPanel();
    }

    /** 原子认领终态发射权：先 claim 者发射，双发射点恰好一次。 */
    public boolean tryClaimTerminal() {
        return terminalEmitted.compareAndSet(false, true);
    }

    /** 终态是否已发射（只读，不认领）：领养方甄别「终态已发、句柄未摘」的死回合。 */
    public boolean terminalEmitted() {
        return terminalEmitted.get();
    }
}
