package org.gitee.jmeter.ai.agent.turn;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个回合（turn）的全部生命周期状态：身份句柄、中止位、收尾 latch、私有注入
 * 队列与执行 future 聚合为单一对象，经 {@code TurnRegistry} 按会话登记。Turn 对象
 * 自身即回合令牌——身份即令牌（{@code SubagentManager.TurnToken} 视图按 Turn
 * 身份比较）。
 *
 * <p><b>保序就位（staggered readiness）：</b>字段分三步就位而非构造时一次到位；
 * 写入时机本身承载语义，不可压扁为单次构造+注册（否则取消窗口内的行为路径与
 * 终态事件 Kind 会改变）。就位次序：
 * <ol>
 *   <li>构造 + {@code TurnRegistry.register}（startTurn 最前）：
 *       sessionKey/handle/callback/delegated/abortFlag/completionLatch 就位——
 *       signalCancel 从此刻起可置中止位，waitForCancellation 从此刻起可等收尾；</li>
 *   <li>{@link #armQueue()}：创建回合私有注入队列（容量取
 *       {@code AiConfig.getInjectionQueueSize()}）。注入路由槽 = 注册表条目 +
 *       {@code closed} 标志，队列就位前 offer/hasActiveRun 视为槽未上线；</li>
 *   <li>{@link #armFuture}（execute 之后）：signalCancel 据此 future.cancel(true)。</li>
 * </ol>
 * 两个取消窗口语义不同：[register → armFuture] 内 future 尚未就位，任务头取消预检
 * （isCancelled 恒 false）不可见——任务照常被取出、于首次迭代检查 abortFlag 中止，
 * 终态为空内容 TURN_COMPLETED（无 TURN_CANCELLED）；[armFuture → pickup] 内才走
 * future.cancel + 预检作废 + TURN_CANCELLED。
 *
 * <p><b>句柄随构造就位：</b>activeTurn 轮询读注册表条目的句柄，而条目在 register
 * 瞬间整条替换为新回合——若句柄晚于注册才就位，[注册 → 就位] 窗口会误报空闲，
 * 外部「等回合落定」的轮询将提前放行、把后继消息误判为可注入。句柄提前可见无
 * 副作用：signalCancel 对 TURN_CANCELLED 的认领以 future 已就位且未完成为前提。
 *
 * <p><b>可见性：</b>构造后写入的字段一律 volatile——写入线程（提交线程 / loop
 * 线程）与读取线程（EDT、ipc-worker 等取消方）之间不总有 happens-before 边
 * （executor 提交的 happens-before 只覆盖任务体内部的读写）。仅 loop 线程读写的
 * 字段（epoch/drainTimedOut/ownResetEpoch）volatile 为防御性加严。
 */
public final class Turn {

    private static final Logger log = LoggerFactory.getLogger(Turn.class);

    /** {@link #ownResetEpoch} 的「未写入」哨兵（代数从 0 起，-1 不与真值冲突）。 */
    public static final long OWN_RESET_EPOCH_UNSET = -1L;

    // ---- 提交时定（构造器），final：register 一完成即须可用，不依赖任何后写字段 ----

    /**
     * 回合归属的会话键：TurnRegistry 按它登记/查找在跑回合，signalCancel、
     * 注入 offer、hasActiveRun 均靠它路由到本回合。
     */
    private final String sessionKey;

    /**
     * 回合身份句柄：进程唯一 id + 来源（TurnOrigin）+ 显示域元数据 +
     * {@code tryClaimTerminal} 终态去重位。事件流订阅方靠它区分回合，
     * 终态事件「恰好一次」由它仲裁。
     */
    private final TurnHandle handle;

    /**
     * 本回合的进度回调：经 {@code buildProgressHook} 包成 AgentHook 挂进
     * runner，LLM/工具的进度事件从这里回传。
     */
    private final AgentLoop.ProgressCallback callback;

    /**
     * 是否 IPC 委派回合（delegate_to_instance 在对端实例上跑的回合）：
     * 门控显示域与注入行为（如 goal 提示不注入委派回合）。
     */
    private final boolean delegated;

    /**
     * 中止位：signalCancel 置位（回合自身命令触发的取消按回合身份自我豁免），
     * 回合内各 abort 检查点轮询读取后中止；future 未就位窗口内的取消只能靠它。
     */
    private final AtomicBoolean abortFlag;

    /**
     * 收尾信号：任务体外层 finally 先按值摘注册表条目再 countDown，
     * waitForCancellation 在后台线程等它——signalCancel 得以保持非阻塞
     * （EDT 可同步调），「等停稳」再异步等 latch。
     */
    private final CountDownLatch completionLatch;

    // ---- 保序就位的后写字段，volatile（见类 javadoc「保序就位」） ----

    private volatile LinkedBlockingQueue<InjectionItem> queue;
    private volatile CompletableFuture<AgentResponse> future;

    /**
     * 注入槽已摘除的显式表达（closed 双生命周期）：条目本身活到 latch
     * 释放点（{@code TurnRegistry} 的真正 remove），注入路由的早期死亡——signalCancel
     * 摘槽（closeRouting）/ 回尾 cleanup（身份条件置位）——以本标志表达，
     * {@code offer}/{@code hasActiveRun} 据此派生「槽已摘」。置位一律经
     * TurnRegistry 的 {@code computeIfPresent}（与 offer 同一 CHM bin 锁，原子），
     * 不在 registry 外直接写。
     */
    private volatile boolean closed;

    // ---- 回合内生命周期 ----

    /**
     * 本回合 pickup 时归属的会话代数：/new、"+" 重置会话时代数 +1，收尾 re-publish
     * 残留前比对——代数已变 = 残留属于被放弃的旧会话，丢弃。写入时机保持 pickup 时
     * （非提交时，语义与旧局部变量 {@code turnEpoch} 一致）；仅 loop 线程读写。
     */
    private volatile long epoch;

    /**
     * 本线程刚执行的重置所翻转到的会话代数（原 ThreadLocal {@code ownResetEpoch}
     * 的显式字段化）：{@code resetConversation} 在回合线程内调用时经
     * {@code currentTurn.get()} 定位本回合写入，回合收尾对注入残留的分类只采纳
     * 「本回合自身命令造成的翻转」——不重读会话当前代数，免被派发窗口内并发的
     * 外来重置（"+" 点击、关闭整合清空）污染。不可经 registry.find(sessionKey)
     * 定位：同 key 新回合可能已注册，find 会把代数写进错误回合、击穿重置代数栅栏
     * （ThreadLocal 收敛约束）。初始 {@link #OWN_RESET_EPOCH_UNSET}。
     */
    private volatile long ownResetEpoch = OWN_RESET_EPOCH_UNSET;

    /**
     * 本回合任务体的执行线程：pickup 时写入、teardown 置 null（见
     * {@link #clearRunnerThread}）。以 Phase 1 同步化为前提——pickup 线程即执行
     * 线程（LLM 调用与 drainBlocking park 所在线程），interrupt 据此精确命中。
     */
    private volatile Thread runnerThread;

    /**
     * 本回合子代理等待已超时——之后不再阻塞等待（语义本就 per-turn「本回合不再
     * 阻塞」，原按 sessionKey 存 map 靠单 executor 串行才碰巧正确）。仅 loop 线程
     * 读写，随回合对象消亡，无需显式清理。
     */
    private boolean drainTimedOut;

    public Turn(String sessionKey, TurnHandle handle, AgentLoop.ProgressCallback callback,
            boolean delegated) {
        this.sessionKey = sessionKey;
        this.handle = handle;
        this.callback = callback;
        this.delegated = delegated;
        this.abortFlag = new AtomicBoolean(false);
        this.completionLatch = new CountDownLatch(1);
    }

    public String sessionKey() {
        return sessionKey;
    }

    public AgentLoop.ProgressCallback callback() {
        return callback;
    }

    public boolean delegated() {
        return delegated;
    }

    /** 中止位：signalCancel 置位（自我豁免本回合除外），回合内各 abort 检查点读取。 */
    public AtomicBoolean abortFlag() {
        return abortFlag;
    }

    /** 收尾信号：任务体外层 finally 先按值摘表再 countDown（waitForCancellation 等待）。 */
    public CountDownLatch completionLatch() {
        return completionLatch;
    }

    /** 本回合私有的注入队列句柄（按句柄抽干/清理，垂死回合偷不到后继回合的消息）。 */
    public LinkedBlockingQueue<InjectionItem> queue() {
        return queue;
    }

    /** 回合身份句柄（进程唯一 id + 来源 + 显示域元数据 + 终态去重位 tryClaimTerminal）。 */
    public TurnHandle handle() {
        return handle;
    }

    /** 本回合的 future（发起方等待通道；signalCancel 的 cancel(true) 目标）。 */
    public CompletableFuture<AgentResponse> future() {
        return future;
    }

    public long epoch() {
        return epoch;
    }

    public long ownResetEpoch() {
        return ownResetEpoch;
    }

    public Thread runnerThread() {
        return runnerThread;
    }

    public boolean drainTimedOut() {
        return drainTimedOut;
    }

    /** 注入队列就位（时机 ②，见类 javadoc「保序就位」）：队列即本回合私有，容量取 AiConfig。 */
    public void armQueue() {
        this.queue = new LinkedBlockingQueue<>(AiConfig.getInjectionQueueSize());
    }

    /** 注入槽是否已摘（见 {@link #closed} 字段；仅经 TurnRegistry 的 bin 锁内置位）。 */
    public boolean closed() {
        return closed;
    }

    /** 置 closed（package-private：只许 TurnRegistry 的 computeIfPresent 在 bin 锁内调用）。 */
    void markClosed() {
        this.closed = true;
    }

    /**
     * 从本回合队列抽走至多 {@code limit} 条（原 {@code InjectionManager.drain} 随
     * Phase 4 迁入）。按句柄消费——回合只消费自己的队列，null 队列（未就位）返回空表。
     */
    public List<InjectionItem> drain(int limit) {
        if (queue == null) {
            return Collections.emptyList();
        }
        List<InjectionItem> items = new ArrayList<>();
        while (items.size() < limit) {
            InjectionItem item = queue.poll();
            if (item == null) break;
            items.add(item);
        }
        if (!items.isEmpty()) {
            log.debug("Drained {} messages from an injection queue", items.size());
        }
        return items;
    }

    /**
     * 同 {@link #drain(int)}，但队列空时阻塞至一条到达或超时（原
     * {@code InjectionManager.drainBlocking}，子代理汇流 park 点——主代理在此等待
     * 仍在跑的子代理，使其结果折回同一回合）。
     *
     * <p>经 {@code AgentRunSpec.injectionCallback}（{@code Function} 不能声明受检
     * 异常）调用——interrupt 在此捕获并复位标志、返回已抽到的部分，agent loop 在
     * 下一 abort 检查点退出。本方法永不抛出。
     */
    public List<InjectionItem> drainBlocking(int limit, long timeoutMs) {
        List<InjectionItem> items = drain(limit);
        if (!items.isEmpty() || queue == null) {
            return items;
        }

        try {
            InjectionItem first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (first == null) {
                log.warn("Timed out after {}ms waiting for a subagent result", timeoutMs);
                return items;
            }
            items.add(first);
            // Take whatever else already arrived, without blocking again.
            items.addAll(drain(limit - items.size()));
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Interrupted while waiting for a subagent result");
            return items;
        }
    }

    /** 抽干本回合队列的全部残留（TurnRegistry.cleanup 收尾善后用，含日志）。 */
    List<InjectionItem> drainAll() {
        List<InjectionItem> remaining = new ArrayList<>();
        if (queue != null) {
            queue.drainTo(remaining);
        }
        return remaining;
    }

    /** future 就位（时机 ③，见类 javadoc「保序就位」）。 */
    public void armFuture(CompletableFuture<AgentResponse> future) {
        this.future = future;
    }

    /** pickup：捕获回合归属的会话代数（写入时机保持 pickup，见 {@link #epoch}）。 */
    public void markPickup(long epoch) {
        this.epoch = epoch;
    }

    /** 记录本回合自身命令造成的代数翻转（见 {@link #ownResetEpoch}）。 */
    public void recordOwnReset(long epoch) {
        this.ownResetEpoch = epoch;
    }

    /** pickup：记录执行线程（见 {@link #runnerThread}）。 */
    public void setRunnerThread(Thread runner) {
        this.runnerThread = runner;
    }

    /**
     * teardown：清空执行线程引用。<b>null 是必要不变式</b>——Turn 条目在收尾最晚点
     * （latch 释放）才从注册表摘除，此前的残留线程引用会让迟到中断打向已复用/已死
     * 的线程（agent-loop 单线程 executor 跨回合复用线程）。
     */
    public void clearRunnerThread() {
        this.runnerThread = null;
    }

    /** 子代理等待超时打标（本回合后续检查点不再阻塞等待，见 {@link #drainTimedOut}）。 */
    public void markDrainTimedOut() {
        this.drainTimedOut = true;
    }
}
