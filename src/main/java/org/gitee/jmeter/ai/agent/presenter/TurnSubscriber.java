package org.gitee.jmeter.ai.agent.presenter;

/**
 * 回合事件订阅者：单方法 = 单一顺序点，新订阅者零改动接入（实现本接口 +
 * {@code AgentLoopFactory.addTurnSubscriber} 一行注册；面板/GUI 之外亦可订阅
 * ——回合审计、用量统计、无头事件记录器等）。
 *
 * <p><b>线程契约：</b>回调线程不保证（EDT / ipc-worker / agent-loop / 池化线程
 * 四类载体均可能）。订阅者必须自行编组到自己的线程（Swing 实现恰一次
 * {@code invokeLater}）并做会话代数过滤（通知线程快照、投递时比对）。允许实现
 * "已在目标线程则直接投递"的零跳分支——此时投递路径必须<b>可重入安全</b>
 * （EDT 零跳使订阅者的渲染代码在发射线程内联执行，与后续事件的可能交叠要求
 * 投递入口不依赖"不在自己线程上"的隐含假设）。
 *
 * <p><b>锁上下文契约：</b>部分发射点在 AgentLoop 内部锁（{@code resetFenceLock}）
 * 持有期内执行——会话重置触发的取消事件、垂死回合收尾 re-publish 的孤儿开始
 * 事件。订阅者回调必须 O(微秒) 返回、不得获取 AgentLoop 内部锁、不得做 IO——
 * 违者在 EDT 路径上会拖累会话重置与垂死回合收尾。
 *
 * <p><b>异常契约：</b>抛出的 Throwable（含 Error——类路径错位等 LinkageError 场景）
 * 被 AgentLoop 吞掉记日志，不影响回合执行与其他订阅者（逐订阅者隔离）。
 *
 * <p><b>顺序契约：</b>同一回合 STARTED→PROGRESS*→恰好一个终态；跨回合终态(N)
 * 先于 STARTED(N+1)（自然完成路径）；垂死回合终态先于其 re-publish 孤儿的 STARTED。
 * 取消路径例外：取消终态在任务槽摘除后才发射（发射须以 cancel 真实成功 + 终态
 * 认领为前提，不能提前），槽已空窗口内新回合可开跑——CANCELLED(N) 可能晚于
 * STARTED(N+1) 到达；订阅者须按回合身份（turnId）过滤，不得以「先见终态后见
 * 开始」做状态机假设。派发同步内联于发射线程，无中间队列——订阅者恰一次编组 +
 * 目标线程 FIFO 即得渲染序 = 发射序。不可见回合（IPC 命令回合）只派发终态、无
 * STARTED/PROGRESS，订阅者须按"终态可无起点"编码。
 */
public interface TurnSubscriber {

    /** 收到一枚回合事件（契约与线程/锁/顺序保证见接口 javadoc）。 */
    void onTurnEvent(TurnEvent event);
}
