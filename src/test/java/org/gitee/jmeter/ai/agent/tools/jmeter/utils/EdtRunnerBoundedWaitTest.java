package org.gitee.jmeter.ai.agent.tools.jmeter.utils;

import org.apache.jmeter.gui.GuiPackage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Bounded-wait contract of {@link EdtRunner}: EDT actions run with TCCL switched,
 * errors propagate, timeout/interrupt only abandon the caller's wait — the queued
 * EDT action is never revoked and still runs after release (orphan semantics).
 *
 * <p>Injects small timeouts via the package-private {@code run(gui, action, timeoutMs)}
 * seam (production always reads {@code agent.tools.edt.wait.timeout.ms}). Only needs
 * an {@link java.awt.EventQueue} dispatch thread, which works headless.
 */
class EdtRunnerBoundedWaitTest {

    @BeforeAll
    static void startEdt() throws Exception {
        // 强制 EDT 启动(空操作派发);headless 下 EventQueue 照常工作
        java.awt.EventQueue.invokeAndWait(() -> { });
    }

    @Test
    @Timeout(20)
    void happyPath_returnsNull_runsOnEdt_withTcclSwitched() {
        GuiPackage gui = mock(GuiPackage.class);
        AtomicBoolean ranOnEdt = new AtomicBoolean(false);
        AtomicBoolean tcclSwitched = new AtomicBoolean(false);
        Exception err = EdtRunner.run(gui, () -> {
            ranOnEdt.set(java.awt.EventQueue.isDispatchThread());
            tcclSwitched.set(
                    Thread.currentThread().getContextClassLoader() == gui.getClass().getClassLoader());
        }, 10_000);
        assertNull(err);
        assertTrue(ranOnEdt.get(), "action must run on the EDT");
        assertTrue(tcclSwitched.get(), "TCCL must be switched to the GuiPackage loader for the action");
    }

    @Test
    @Timeout(20)
    void actionException_returnedAsIs() {
        IllegalStateException boom = new IllegalStateException("boom");
        Exception err = EdtRunner.run(mock(GuiPackage.class), () -> {
            throw boom;
        }, 10_000);
        assertSame(boom, err);
    }

    @Test
    @Timeout(20)
    void actionError_wrappedAsInvocationTargetException() {
        AssertionError edtError = new AssertionError("edt-error");
        Exception err = EdtRunner.run(mock(GuiPackage.class), () -> {
            throw edtError;
        }, 10_000);
        // 与旧 invokeAndWait 的类型行为一致:Error 以 InvocationTargetException 回传,
        // 不再以 java.lang.Error 逃逸调用方的 catch(Exception)
        assertInstanceOf(InvocationTargetException.class, err);
        assertSame(edtError, err.getCause());
    }

    @Test
    @Timeout(20)
    void timeout_returnsEdtWaitTimeoutException_orphanStillRuns() throws Exception {
        // 钉住 EDT:先排一个持闩的长事件
        CountDownLatch edtGate = new CountDownLatch(1);
        java.awt.EventQueue.invokeLater(() -> {
            try {
                edtGate.await(10, TimeUnit.SECONDS); // 安全上界:断言失败时不悬挂测试
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean orphanRan = new AtomicBoolean(false);
        Exception err = EdtRunner.run(mock(GuiPackage.class), () -> orphanRan.set(true), 200);
        assertInstanceOf(EdtRunner.EdtWaitTimeoutException.class, err);
        assertTrue(err.getMessage().contains("UNKNOWN"), "message must declare unknown outcome");
        assertTrue(err.getMessage().contains("get_test_plan_tree"), "message must point at the probe tool");
        assertFalse(orphanRan.get(), "orphan must not have run while the EDT is pinned");

        // 释放 EDT:孤儿任务稍后确实完整执行(超时不撤回已投递任务的核心不变式)
        edtGate.countDown();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!orphanRan.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(orphanRan.get(), "orphan action must still run after the EDT is released");
    }

    @Test
    @Timeout(20)
    void interrupt_returnsAbortedUnknown_clearsFlag_orphanStillRuns() throws Exception {
        CountDownLatch edtGate = new CountDownLatch(1);
        java.awt.EventQueue.invokeLater(() -> {
            try {
                edtGate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean orphanRan = new AtomicBoolean(false);
        AtomicReference<Exception> result = new AtomicReference<>();
        AtomicBoolean interruptFlagSurvived = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            result.set(EdtRunner.run(mock(GuiPackage.class), () -> orphanRan.set(true), 10_000));
            // 与 invokeAndWait 逐位一致:InterruptedException 被捕获后中断位为清
            interruptFlagSurvived.set(Thread.currentThread().isInterrupted());
        }, "edt-wait-caller");
        caller.start();
        caller.interrupt(); // 落在 await 之前或之中均收敛到同一分支
        caller.join(10_000);

        Exception err = result.get();
        assertNotNull(err);
        assertInstanceOf(IllegalStateException.class, err);
        assertTrue(err.getMessage().contains("UNKNOWN"), "interrupt path must also declare unknown outcome");
        assertInstanceOf(InterruptedException.class, err.getCause());
        assertFalse(interruptFlagSurvived.get(), "interrupt flag must be cleared (invokeAndWait parity)");
        assertFalse(orphanRan.get(), "orphan must not run while the EDT is pinned");

        edtGate.countDown();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!orphanRan.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(orphanRan.get(), "orphan action must still run after the EDT is released");
    }

    @Test
    @Timeout(20)
    void edtCallerGuard_returnsIllegalState_doesNotDeadlock() throws Exception {
        AtomicReference<Exception> fromEdt = new AtomicReference<>();
        CountDownLatch onEdt = new CountDownLatch(1);
        java.awt.EventQueue.invokeLater(() -> {
            fromEdt.set(EdtRunner.run(mock(GuiPackage.class), () -> { }, 500));
            onEdt.countDown();
        });
        assertTrue(onEdt.await(10, TimeUnit.SECONDS), "guard must return promptly instead of deadlocking");
        Exception err = fromEdt.get();
        assertInstanceOf(IllegalStateException.class, err);
        assertTrue(err.getMessage().contains("EDT"));
    }

    @Test
    void contractConcatenation_keepsLlmGuidanceReadable() {
        // 调用点以 "Failed to update element: " 前缀包装后,面向 LLM 的指引仍完整可读
        String full = "Failed to update element: "
                + new EdtRunner.EdtWaitTimeoutException(120_000).getMessage();
        assertTrue(full.contains("UNKNOWN"));
        assertTrue(full.contains("get_test_plan_tree"));
        assertTrue(full.contains("Do NOT blindly retry"));
    }

    @Test
    void abortedMessage_isSelfContained() {
        String msg = "Failed to move element: "
                + new IllegalStateException("Aborted while waiting for the JMeter GUI event thread "
                        + "(caller thread interrupted, e.g. Stop). Outcome UNKNOWN: the operation may still run "
                        + "on the EDT afterwards — verify with get_test_plan_tree before any further tree change.")
                        .getMessage();
        assertTrue(msg.contains("UNKNOWN"));
        assertTrue(msg.contains("get_test_plan_tree"));
    }
}
