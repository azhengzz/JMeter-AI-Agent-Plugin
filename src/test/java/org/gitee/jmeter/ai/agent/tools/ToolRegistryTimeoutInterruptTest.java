package org.gitee.jmeter.ai.agent.tools;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timeout in the parallel (concurrency-safe, read-only) lane must actually
 * <b>release the leaked worker</b>: the timeout branch interrupts the running
 * tool thread via the worker-reference CAS consume. This is the regression that
 * a {@code future.cancel(true)} no-op implementation would fail — cancel never
 * interrupts any thread in CompletableFuture.
 */
class ToolRegistryTimeoutInterruptTest {

    /** Blocks on a latch until interrupted; records start/interrupt/completion for assertions. */
    static final class BlockingSafeTool extends AbstractTool {
        final String name;
        final CountDownLatch gate;
        /** 进入 executeInternal 的确定性信号(「已在跑」vs「仍在排队」的区分锚)。 */
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final AtomicBoolean finishedNormally = new AtomicBoolean(false);

        BlockingSafeTool(String name, CountDownLatch gate) {
            this.name = name;
            this.gate = gate;
        }

        BlockingSafeTool(CountDownLatch gate) {
            this("blocking_safe", gate);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isConcurrencySafe() {
            return true;
        }

        @Override
        public String getDescription() {
            return "test-only blocking read";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> parameters) {
            started.countDown();
            try {
                gate.await(30, TimeUnit.SECONDS);
                finishedNormally.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(true);
            }
            return ToolResult.success("done");
        }
    }

    @Test
    @Timeout(30)
    void timeout_interruptsAndReleasesLeakedWorker() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BlockingSafeTool tool = new BlockingSafeTool(gate);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        CompletableFuture<ToolRegistry.ToolExecutionResult> f =
                registry.executeAsyncWithEvent("blocking_safe", Map.of(), 300);
        ToolRegistry.ToolExecutionResult r = f.get(10, TimeUnit.SECONDS);

        assertFalse(r.result().isSuccess(), "timed-out call must surface an error result");
        String message = r.result().getError();
        assertTrue(message != null && message.contains("timed out"), "unexpected result: " + message);

        // 假实现(cancel(true) 或无中断)在此必红:工作线程仍卡在 gate.await 上
        long deadline = System.currentTimeMillis() + 5_000;
        while (!tool.interrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(tool.interrupted.get(),
                "the leaked worker must actually be interrupted (this fails on a cancel(true) no-op)");
        gate.countDown();
    }

    @Test
    @Timeout(30)
    void fastCompletion_noTimeout_noStrayInterrupt() throws Exception {
        // 完成快于超时:无超时分支、无中断;随后同池再跑一发也未被残留中断毒化
        CountDownLatch gate = new CountDownLatch(0); // 立即通过
        BlockingSafeTool tool = new BlockingSafeTool("fast_safe", gate);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        ToolRegistry.ToolExecutionResult r =
                registry.executeAsyncWithEvent("fast_safe", Map.of(), 10_000).get(10, TimeUnit.SECONDS);
        assertTrue(r.result().isSuccess());
        assertTrue(tool.finishedNormally.get());
        assertFalse(tool.interrupted.get(), "no interrupt may reach a tool that finished in time");

        // 消费式 CAS 的守卫面:第一发超时中断后,池线程接手的下一个任务不受残留影响
        CountDownLatch slowGate = new CountDownLatch(1);
        BlockingSafeTool slow = new BlockingSafeTool("slow_safe", slowGate);
        registry.register(slow);
        registry.executeAsyncWithEvent(slow.getName(), Map.of(), 300).get(10, TimeUnit.SECONDS);
        slowGate.countDown();
        CountDownLatch fastGate2 = new CountDownLatch(0);
        BlockingSafeTool next = new BlockingSafeTool("next_safe", fastGate2);
        registry.register(next);
        ToolRegistry.ToolExecutionResult r2 =
                registry.executeAsyncWithEvent(next.getName(), Map.of(), 10_000).get(10, TimeUnit.SECONDS);
        assertTrue(r2.result().isSuccess());
        assertFalse(next.interrupted.get(), "a pooled thread's next task must not be poisoned");
    }

    @Test
    @Timeout(30)
    void timeoutBeforeWorkerStart_skipsInterrupt_queuedTaskStillRunsClean() throws Exception {
        // 「超时先于 worker 起跑」形态(单线程池被占满,supplier 仍在排队时 orTimeout 已
        // fire):workerRef 为 null → 不 interrupt 任何线程(best-effort 边界,绝不能
        // NPE 或盲 interrupt);占池者释放后排队任务仍完整执行、其线程无残留中断
        java.util.concurrent.ExecutorService single =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "single-tool-executor");
                    t.setDaemon(true);
                    return t;
                });
        try {
            CountDownLatch occupierGate = new CountDownLatch(1);
            BlockingSafeTool occupier = new BlockingSafeTool("occupier_safe", occupierGate);
            ToolRegistry registry = new ToolRegistry(single);
            registry.register(occupier);
            CompletableFuture<ToolRegistry.ToolExecutionResult> occupierFuture =
                    registry.executeAsyncWithEvent("occupier_safe", Map.of(), 30_000);
            assertTrue(occupier.started.await(5, TimeUnit.SECONDS),
                    "the occupier must hold the single pool thread before the victim is submitted");

            CountDownLatch victimGate = new CountDownLatch(0); // 立即通过:只验「迟到执行干净」
            BlockingSafeTool victim = new BlockingSafeTool("victim_safe", victimGate);
            registry.register(victim);
            ToolRegistry.ToolExecutionResult r =
                    registry.executeAsyncWithEvent("victim_safe", Map.of(), 200).get(10, TimeUnit.SECONDS);
            assertFalse(r.result().isSuccess(), "queued-then-timed-out call must surface an error result");
            assertTrue(r.result().getError() != null && r.result().getError().contains("timed out"),
                    "unexpected result: " + r.result().getError());
            assertFalse(victim.started.await(300, TimeUnit.MILLISECONDS),
                    "victim must still be queued at timeout time (workerRef == null branch)");

            // 释放占池者:排队的 victim 被 JDK AsyncSupply 的 d.result==null 守卫整体
            // 跳过(orTimeout 已完结 future,未启动的 supplier 不再执行)——无孤儿、无
            // 泄漏、更无「盲 interrupt 某线程」;workerRef==null 分支不 NPE 即本形态的全部契约
            occupierGate.countDown();
            assertTrue(occupierFuture.get(10, TimeUnit.SECONDS).result().isSuccess());
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(victim.started.getCount() == 0,
                    "a queued supplier whose future already completed must be skipped by the JDK guard");
            assertFalse(victim.interrupted.get(), "no thread may be interrupted for a not-yet-started worker");
        } finally {
            single.shutdownNow();
        }
    }
}
