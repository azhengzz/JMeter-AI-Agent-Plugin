package org.gitee.jmeter.ai.agent.presenter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回合身份句柄：进程唯一 id + 来源 + 显示域元数据 + 终态去重位。
 *
 * <p>id 取进程级递增序列，跨 AgentLoop 重建不回绕——陈旧 loop 的迟到事件
 * 不会撞上新回合 id，订阅者按 id 过滤即可。
 *
 * <p>{@link #tryClaimTerminal()} 保证终态恰好发射一次：取消路径与回合收尾
 * 路径竞态时，先认领者发射、后到者放弃，订阅者无需自行去重。
 */
public final class TurnHandle {
    /** 进程级序列：每个回合拿到一个进程内唯一且单调递增的 id。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private final long id;
    private final String sessionKey;
    private final TurnOrigin origin;
    /** 触发本回合的消息文本：面板据此画 You 回显行；
     * IPC 源已带 "[from cli] "/"[delegated-from …]" 来源前缀；REPUBLISH 为 null
     * （You 回显已由 INJECTED 事件给过）。 */
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
