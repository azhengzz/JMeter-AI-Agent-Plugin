package org.gitee.jmeter.ai.agent.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-run registry of close handles for in-flight provider streams. A provider
 * registers its underlying stream's close (idempotent) right after opening it —
 * preferably via try-with-resources on the returned registration so the handle
 * is dropped on normal completion; the cancellation path
 * ({@code AgentLoop.signalCancel}) closes everything still registered so Stop
 * interrupts even a stalled stream whose blocking read never observes the
 * thread interrupt.
 *
 * <p><b>取消代际</b>：{@link #closeAll()} 置取消位——此后 {@link #register} 的
 * 句柄（provider 与取消交错的晚注册）在注册点立即关闭，不再等待下一次
 * closeAll。Close/clear/register 均幂等。
 *
 * <p><b>线性化</b>：register 的「检查取消位 → 加入列表」与 closeAll 的
 * 「置取消位 → 快照清空」在同一锁内互斥——否则 register 已通过取消检查、
 * 尚未入列的窗口里 closeAll 会漏掉该句柄（Stop 后停滞流永不关闭）。
 */
public final class StreamCloseRegistry {

    private final List<Runnable> closeHandles;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object lock = new Object();

    /** Handle unregister token (removes the handle; never closes the stream). */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public StreamCloseRegistry() {
        this(new CopyOnWriteArrayList<>());
    }

    /** 测试注入-backing-list 构造（确定性并发交错探针用）。 */
    StreamCloseRegistry(List<Runnable> backingList) {
        this.closeHandles = backingList;
    }

    /**
     * Register a close handle; the handle itself must be safe to call more than
     * once. Returns a registration that removes the handle on close (it never
     * closes the stream) — use it in try-with-resources around stream drain.
     * 注册时若取消位已置（Stop 先于 provider 开流的交错），句柄立即关闭。
     */
    public Registration register(Runnable close) {
        if (close == null) {
            return () -> { };
        }
        boolean runNow;
        synchronized (lock) {
            runNow = cancelled.get();
            if (!runNow) {
                closeHandles.add(close);
            }
        }
        if (runNow) {
            runIsolated(close);
            return () -> { };
        }
        return () -> closeHandles.remove(close);
    }

    /** Close every registered handle (each failure isolated), then drop them. */
    public void closeAll() {
        List<Runnable> snapshot;
        synchronized (lock) {
            cancelled.set(true);
            snapshot = List.copyOf(closeHandles);
            closeHandles.clear();
        }
        for (Runnable close : snapshot) {
            runIsolated(close);
        }
    }

    /** Drop all handles without closing (normal end of run). */
    public void clear() {
        synchronized (lock) {
            closeHandles.clear();
        }
    }

    /** Whether any handle is currently registered. */
    public boolean isEmpty() {
        return closeHandles.isEmpty();
    }

    private static void runIsolated(Runnable close) {
        try {
            close.run();
        } catch (RuntimeException e) {
            // A dying stream may throw on close; cancellation must proceed.
        }
    }
}
