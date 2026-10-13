package org.gitee.jmeter.ai.agent.snapshot;

import org.apache.jmeter.gui.GuiPackage;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照服务与命名的纯逻辑测试（无 GUI 路径）。EDT 克隆/序列化链路的 GUI 侧
 * 行为由 SnapshotTreeCloneTest 与手动冒烟覆盖。
 */
class TurnPlanSnapshotServiceTest {

    /** UTC 15:30 == 东八区 23:30：命名用系统时区墙钟（与日志一致）。 */
    private static final Instant T0 = Instant.parse("2026-10-12T15:30:00.123Z");

    // --- 命名：格式 / 排序 / 隔离 ---

    @Test
    void turnSnapshotFileNameEncodesFivePartIdentity() {
        String name = SnapshotNames.turnSnapshotFileName(
                T0, "12345-678912345", 42L, TurnOrigin.LOCAL_PANEL, "demo.jmx");
        assertEquals("20261012233000123-12345-678912345-42-local-demo.jmx", name,
                "五段格式：紧凑定宽时间戳-instanceId-turnId-origin-计划文件名（基名去扩展）");
    }

    @Test
    void lexicographicOrderFollowsTimeOrder() {
        String earlier = SnapshotNames.turnSnapshotFileName(T0, "i", 1L, TurnOrigin.LOCAL_PANEL, "p.jmx");
        String later = SnapshotNames.turnSnapshotFileName(T0.plusMillis(1), "i", 1L, TurnOrigin.LOCAL_PANEL, "p.jmx");
        assertTrue(earlier.compareTo(later) < 0, "字典序 = 时间序（紧凑定宽时间戳在前）");
        String muchLater = SnapshotNames.turnSnapshotFileName(T0.plusSeconds(60), "i", 1L, TurnOrigin.LOCAL_PANEL, "p.jmx");
        assertTrue(later.compareTo(muchLater) < 0);
    }

    @Test
    void dualInstancesSameMillisecondDoNotCollide() {
        String a = SnapshotNames.turnSnapshotFileName(T0, "111-1", 7L, TurnOrigin.IPC_CLI, "p.jmx");
        String b = SnapshotNames.turnSnapshotFileName(T0, "222-2", 7L, TurnOrigin.IPC_CLI, "p.jmx");
        assertFalse(a.equals(b), "同 workspace 双实例同毫秒快照互不覆盖");
    }

    @Test
    void absentIdentityFallsBackToTokens() {
        String name = SnapshotNames.turnSnapshotFileName(T0, null, null, null, null);
        assertTrue(name.endsWith("-unknown-instance-noturn-unknown-unsaved.jmx"),
                "缺省段降级占位: " + name);
    }

    @Test
    void planNameTokenStripsPathAndExtension() {
        assertEquals("demo", SnapshotNames.planNameToken("demo.jmx"));
        assertEquals("demo", SnapshotNames.planNameToken("C:\\plans\\demo.JMX"), "扩展名大小写不敏感");
        assertEquals("demo", SnapshotNames.planNameToken("/opt/plans/demo.jmx"), "正斜杠路径剥目录");
        assertEquals("v1.2-plan", SnapshotNames.planNameToken("v1.2-plan.jmx"), "仅剥结尾 .jmx");
        assertEquals("nosuffix", SnapshotNames.planNameToken("nosuffix"), "无扩展名原样保留");
        assertEquals("unsaved", SnapshotNames.planNameToken(null), "未保存计划占位");
        assertEquals("unsaved", SnapshotNames.planNameToken("   "), "空白占位");
    }

    @Test
    void runAutosaveFileNameKeepsPrefixAndAddsInstance() {
        assertEquals("agent-run-12345-678912345-1780000000000.jmx",
                SnapshotNames.runAutosaveFileName("agent-run-", "12345-678912345", 1780000000000L));
        assertEquals("goal-smoke-999-1-1780000000999.jmx",
                SnapshotNames.runAutosaveFileName("goal-smoke-", "999-1", 1780000000999L));
    }

    // --- no-op 降级 ---

    @Test
    void beginTurnWithoutTurnIdentityIsNoOp() {
        Assumptions.assumeTrue(GuiPackage.getInstance() == null, "no-GUI environment");
        assertNull(new TurnPlanSnapshotService(testExecutor()).beginTurn(null, TurnOrigin.LOCAL_PANEL),
                "turnId 缺席（子代理/直构测试运行）不参与快照");
    }

    @Test
    void beginTurnWithoutGuiIsNoOp() {
        Assumptions.assumeTrue(GuiPackage.getInstance() == null, "no-GUI environment");
        assertNull(new TurnPlanSnapshotService(testExecutor()).beginTurn(1L, TurnOrigin.LOCAL_PANEL),
                "无 GuiPackage（无 GUI 环境）不参与快照");
    }

    @Test
    void completeTurnWithNullScopeIsSafe() {
        TurnPlanSnapshotService service = new TurnPlanSnapshotService(testExecutor());
        // 不抛即通过：null scope（no-op 分支）是常态路径
        service.completeTurn(null);
        service.flushPending(0);
    }

    // --- 中断位保持克隆（迟到中断 TOCTOU 防护的机制钉） ---

    @Test
    void interruptPreservingCapture_restoresBitAndDegradesHeadless() {
        Thread.currentThread().interrupt();
        try {
            assertNull(SnapshotTreeCapture.captureDetachedPlanClonePreservingInterrupt(null),
                    "无 GUI 环境克隆安全降级 null");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "克隆后中断位原样回置（isAborted = flag ‖ isInterrupted 的语义不变）");
        } finally {
            Thread.interrupted(); // 清扫，避免污染同线程后续测试
        }
        // 无中断位时保持清零（不凭空造位）
        assertNull(SnapshotTreeCapture.captureDetachedPlanClonePreservingInterrupt(null));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    // --- 关闭冲刷：有界等待 ---

    @Test
    void flushPendingDrainsQueuedTaskWithinBound() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-flush-test");
            t.setDaemon(true);
            return t;
        });
        try {
            TurnPlanSnapshotService service = new TurnPlanSnapshotService(pool);
            AtomicBoolean done = new AtomicBoolean(false);
            pool.execute(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                done.set(true);
            });
            service.flushPending(2_000);
            assertTrue(done.get(), "界内任务被排空完成");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void flushPendingAbandonsTaskBeyondBound() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-flush-test-slow");
            t.setDaemon(true);
            return t;
        });
        try {
            TurnPlanSnapshotService service = new TurnPlanSnapshotService(pool);
            AtomicBoolean done = new AtomicBoolean(false);
            pool.execute(() -> {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return; // 被打断即放弃，与超窗放弃同义
                }
                done.set(true);
            });
            long start = System.nanoTime();
            service.flushPending(100);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 1_500, "超窗即返回不阻断退出，实际 " + elapsedMs + " ms");
            assertFalse(done.get(), "超窗任务放弃");
        } finally {
            pool.shutdownNow();
        }
    }

    private static ExecutorService testExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snapshot-test-pool");
            t.setDaemon(true);
            return t;
        });
    }
}
