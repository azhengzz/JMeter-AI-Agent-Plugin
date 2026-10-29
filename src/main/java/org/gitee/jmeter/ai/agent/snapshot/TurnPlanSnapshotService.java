package org.gitee.jmeter.ai.agent.snapshot;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jorphan.collections.HashTree;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 回合级测试计划快照服务：回合起点在内存持有树基线，回合终态比对，树变化才把
 * <b>起点基线</b>落盘一份——为「准确回退到某一次回合之前」提供磁盘落点。
 *
 * <p><b>线程模型</b>：调用方（agent 回合的 runner 线程）承担回合两端的同步 EDT 树
 * 克隆（毫秒级；起点克隆必须先于本回合任何工具对树的修改，终点克隆在收尾路径上
 * 采样终态时刻）——两次克隆均以「清位执行、结束回置」保护中断位，Stop 中止的回合
 * 与上一回合迟到中断都不会击穿克隆，且 {@code isAborted} 语义不变。序列化、比对与
 * 写盘全部异步跑在专用单线程执行器上，任务体<b>不依赖 EDT</b>——这是关闭冲刷可达
 * 的前提：标准退出路径（EDT 上 System.exit）期间 EDT 不再泵事件，任何依赖 EDT 的
 * 排队任务都永无完成机会，冲刷只能烧满时限后放弃。单线程 FIFO 保证起点序列化先于
 * 终点比对完成——终点任务内 {@code join()} 基线 future 必然已就绪。
 *
 * <p><b>参与门</b>：{@code turnId == null}（子代理/直构测试运行）或无 GuiPackage
 * （无 GUI 环境）时整体 no-op。纯命令回合不进 runner，天然不参与。
 *
 * <p><b>失败语义</b>：全链路 best-effort——任何环节失败仅记日志，绝不影响 agent
 * 回合执行；不修改计划的文件关联与 IPC 注册信息（直写序列化字节，不经 Save 动作）。
 *
 * <p><b>关闭冲刷</b>：{@link #flushPending(long)} 有界排空未落盘任务（挂 JVM
 * shutdown hook），焊死「回合刚结束就关 JMeter」丢最后一份基线的窗口；超窗放弃。
 *
 * <p>非 final 以供终态验收测试子类化记录调用（生产代码不应继承）。
 */
public class TurnPlanSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(TurnPlanSnapshotService.class);

    private static volatile TurnPlanSnapshotService instance = new TurnPlanSnapshotService();

    /** 进程级默认实例（AgentRunner 经此接线；测试经 setForTest 替换观测）。 */
    public static TurnPlanSnapshotService get() {
        return instance;
    }

    /** 测试缝：替换默认实例。生产代码不调用。 */
    static void setForTest(TurnPlanSnapshotService custom) {
        instance = custom;
    }

    private final ExecutorService executor;

    TurnPlanSnapshotService(ExecutorService executor) {
        this.executor = executor;
    }

    private TurnPlanSnapshotService() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "turn-plan-snapshot");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 单回合快照作用域：携带起点基线字节的 future 与起点计划关联文件名，终态任务消费。 */
    public static final class TurnSnapshotScope {
        final Long turnId;
        final TurnOrigin origin;
        final String planFileName;
        final CompletableFuture<byte[]> baseline = new CompletableFuture<>();

        TurnSnapshotScope(Long turnId, TurnOrigin origin, String planFileName) {
            this.turnId = turnId;
            this.origin = origin;
            this.planFileName = planFileName;
        }
    }

    /**
     * 回合起点：同步克隆计划树（EDT，中断位保持）+ 提交异步序列化，返回作用域。
     * 不参与快照（turnId 缺席 / 无 GUI / 克隆失败）返回 null，
     * {@link #completeTurn} 对 null 安全 no-op——调用方无需判空分支语义。
     */
    public TurnSnapshotScope beginTurn(Long turnId, TurnOrigin origin) {
        if (turnId == null || origin == null) {
            return null; // 子代理 / 直构测试运行：无回合身份，不参与
        }
        try {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return null; // 无 GUI 环境（纯单测等）：无可快照之树
            }
            HashTree beginClone =
                    SnapshotTreeCapture.captureDetachedPlanClonePreservingInterrupt(gui);
            if (beginClone == null) {
                return null;
            }
            // 计划文件名随基线一并捕获于起点（基线属于起点状态；读取方式与
            // RunTestTool 在池线程读 getTestPlanFile 的既有先例一致）
            TurnSnapshotScope scope =
                    new TurnSnapshotScope(turnId, origin, gui.getTestPlanFile());
            executor.execute(() -> {
                try {
                    scope.baseline.complete(SnapshotTreeCapture.serializeDetached(beginClone));
                } catch (Throwable t) {
                    log.warn("Snapshot: baseline serialization failed (best-effort): {}", t.toString());
                    scope.baseline.completeExceptionally(t);
                }
            });
            log.debug("Snapshot: baseline captured for turn {} ({})", turnId, origin);
            return scope;
        } catch (Throwable t) {
            log.warn("Snapshot: beginTurn failed (best-effort): {}", t.toString());
            return null;
        }
    }

    /**
     * 回合终态：调用线程（runner 收尾路径）同步采样终点克隆（EDT，中断位保持——
     * Stop 中止的回合 finally 里仍带中断位，清位克隆、结束回置，不触碰 Thread.interrupted()
     * 清扫的次序不变量），随后提交 <b>EDT-free</b> 异步任务做序列化/比对/条件落盘。
     * 字节一致不落盘；不一致把起点基线字节原子写入 workspace autosave 目录。
     */
    public void completeTurn(TurnSnapshotScope scope) {
        if (scope == null) {
            return;
        }
        try {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return; // GUI 已卸（关闭中）：无终态可比，放弃本份
            }
            HashTree endClone =
                    SnapshotTreeCapture.captureDetachedPlanClonePreservingInterrupt(gui);
            if (endClone == null) {
                return;
            }
            executor.execute(() -> {
                try {
                    writeIfChanged(scope, endClone,
                            WorkspacePaths.resolveWorkspace().resolve(SnapshotNames.AUTOSAVE_DIR));
                } catch (Throwable t) {
                    log.warn("Snapshot: turn-end compare/write failed (best-effort): {}", t.toString());
                }
            });
        } catch (Throwable t) {
            log.warn("Snapshot: completeTurn failed (best-effort): {}", t.toString());
        }
    }

    /**
     * 终点比对与条件落盘（EDT-free，可独立测试）：字节一致（含改了又改回的净零
     * 回合）不落盘；不一致把<b>起点基线</b>字节写入 {@code dir} 下四元命名文件。
     * Package-private overload 供测试注入目录；生产走 workspace autosave 目录。
     */
    void writeIfChanged(TurnSnapshotScope scope, HashTree endClone, Path dir) throws IOException {
        byte[] endBytes = SnapshotTreeCapture.serializeDetached(endClone);
        // FIFO：起点序列化任务先于本任务在同一执行器线程上完成
        byte[] beginBytes = scope.baseline.join();
        if (Arrays.equals(beginBytes, endBytes)) {
            return; // 树未变化：不落盘
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(SnapshotNames.turnSnapshotFileName(
                Instant.now(), InstanceContext.instanceId(), scope.turnId, scope.origin,
                scope.planFileName));
        atomicWrite(file, beginBytes);
        log.info("Snapshot: plan changed during turn {} ({}), baseline written to {}",
                scope.turnId, scope.origin, file);
    }

    /**
     * 临时文件 + 原子改名落盘：进程死于写中途时不留截断的半份基线（损坏基线比
     * 缺一份更糟——恢复流程按名字挑文件，无有效性校验能把它筛掉）。
     */
    private static void atomicWrite(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 关闭冲刷：拒绝新任务并<b>有界</b>等待已排队任务完成，超窗放弃（不阻断退出）。
     * 挂 JVM shutdown hook 调用；幂等（重复调用只等待）。
     */
    public void flushPending(long timeoutMs) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("Snapshot: shutdown flush timed out after {} ms, pending snapshots abandoned",
                        timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Snapshot: shutdown flush interrupted, pending snapshots abandoned");
        }
    }
}
