package org.gitee.jmeter.ai.agent.run;

/**
 * Thread-local identity of the agent run currently executing on this thread.
 *
 * <p>Tools receive only a parameter map ({@code Tool.execute(Map)}), so a tool
 * like {@code spawn} has no way to learn which session it was called from.
 * {@link AgentRunner} binds this context around the run, and
 * {@code ToolRegistry} replays it onto the tool-executor threads used for
 * concurrent tool execution, so it is visible wherever a tool runs.
 *
 * <p>Always clear in a finally block: the run threads are reused (the dedicated
 * agent-loop thread across turns, pooled subagent threads across runs), and a
 * stale session key would route a subagent result into the wrong session.
 */
public final class AgentRunContext {

    private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<>();

    private final String sessionKey;
    private final String runId;
    private final boolean goalRequested;
    private final Long deadlineAtMs;

    public AgentRunContext(String sessionKey, String runId) {
        this(sessionKey, runId, false);
    }

    public AgentRunContext(String sessionKey, String runId, boolean goalRequested) {
        this(sessionKey, runId, goalRequested, null);
    }

    /**
     * 完整构造（含回合截止期）。旧构造器委托本构造（deadline 传 null），既有调用点
     * （含测试直构）不破坏。
     */
    public AgentRunContext(String sessionKey, String runId, boolean goalRequested, Long deadlineAtMs) {
        this.sessionKey = sessionKey;
        this.runId = runId;
        this.goalRequested = goalRequested;
        this.deadlineAtMs = deadlineAtMs;
    }

    public String getSessionKey() { return sessionKey; }

    public String getRunId() { return runId; }

    /**
     * 本回合由 /goal 触发。GoalTools 的权限门（create_goal / update_goal replace）
     * 读它判定本回合是否持有 goal 变更权限。
     */
    public boolean isGoalRequested() { return goalRequested; }

    /**
     * 回合截止期（墙钟 epoch ms，可空）。非空 = IPC /agent 回合：到点未完成会被服务端
     * 超时硬取消。长等待工具（如 run_test smoke 的完成等待）据此钳制、预算不足时
     * 启动前拒绝。消费方须做双层空守卫（本 context 为 null 或本字段为 null 均不钳制）。
     */
    public Long getDeadlineAtMs() { return deadlineAtMs; }

    /** Bind the context for the current thread. */
    public static void set(AgentRunContext context) {
        CURRENT.set(context);
    }

    /** The context bound to the current thread, or null if none. */
    public static AgentRunContext current() {
        return CURRENT.get();
    }

    /** Remove the binding. Must be called in a finally block on reused threads. */
    public static void clear() {
        CURRENT.remove();
    }

    @Override
    public String toString() {
        return "AgentRunContext{sessionKey=" + sessionKey + ", runId=" + runId + '}';
    }
}
