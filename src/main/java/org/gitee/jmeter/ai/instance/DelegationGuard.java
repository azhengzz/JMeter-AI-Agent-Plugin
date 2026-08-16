package org.gitee.jmeter.ai.instance;

/**
 * 委派回合标记(深度 1 硬阻断):本实例经 IPC {@code /agent} 收到 {@code delegated=true}
 * 的请求时,{@code AgentRunner} 在该回合的 run 任务内(执行该任务的池化载体线程)置位,
 * 任务结束清除。{@code DelegateToInstanceTool} 执行前检查——被委派任务内禁止再次委派,
 * 硬阻断 A↔B 互相委派的 ping-pong(否则两侧会互卡满 {@code jmeter.ai.ipc.agent.timeout.ms} 超时)。
 *
 * <p>线程模型:置位必须发生在执行回合的线程<b>内部</b>——{@code AgentRunner.run} 用无执行器的
 * {@code supplyAsync} 提交,整回合(LLM 循环 + 串行工具调用,工具内联无线程跳转)跑在池化载体
 * 线程上,而 {@code AgentLoop} 的单线程执行器只 park 在 {@code join()} 等;在 loop 线程上置位
 * 工具看不见(与 {@code AgentRunContext} 同款"carrier threads are pooled"推理,故同位置置/清)。
 * {@code agent.tools.concurrent.enabled=true} 时工具各自跑在别的线程,标记不可见,
 * 退回既有超时兜底(单线程 loop 串行使环上等待方必然超时,不会死锁)。
 */
public final class DelegationGuard {
    private static final ThreadLocal<Boolean> DELEGATED = new ThreadLocal<>();

    private DelegationGuard() {
    }

    /** 在被委派回合的执行线程上调用(回合开始)。 */
    public static void begin() {
        DELEGATED.set(Boolean.TRUE);
    }

    /** 回合结束调用;未置位时为 no-op。 */
    public static void end() {
        DELEGATED.remove();
    }

    /** 当前线程是否正执行一个被委派的回合。 */
    public static boolean isActive() {
        return Boolean.TRUE.equals(DELEGATED.get());
    }
}
