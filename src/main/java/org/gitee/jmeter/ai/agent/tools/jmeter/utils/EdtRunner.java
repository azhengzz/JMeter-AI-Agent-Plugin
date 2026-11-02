package org.gitee.jmeter.ai.agent.tools.jmeter.utils;

import org.apache.jmeter.gui.GuiPackage;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper to run Swing/JMeter GUI operations on the EDT with proper ClassLoader context.
 *
 * <p>All {@code JMeterTreeModel} operations (extends {@code DefaultTreeModel}),
 * {@code GuiPackage.refreshCurrentGui/getGui/updateCurrentGui}, and any
 * {@code gui.configure(element)} chain must run on EDT — otherwise the JTree
 * TreeState cache and RSyntaxTextArea Token cache can be corrupted, eventually
 * crashing the EDT with NPE.
 *
 * <p>This helper wraps the <b>bounded</b> EDT wait (invokeLater + latch + timed await)
 * + ClassLoader switch + exception capture pattern that is otherwise copy-pasted
 * in every JMeter element tool.
 *
 * <p>Contract for callers:
 * <ul>
 *   <li>Must be called from a worker thread, never from the EDT (guarded — returns
 *       {@link IllegalStateException} instead of deadlocking).</li>
 *   <li>Timeout/interrupt only abandons the <b>caller's wait</b>. The queued EDT
 *       action is never revoked and will still run (fully, once) when the GUI
 *       thread recovers — the same orphan semantics a Stop interrupt produced
 *       under the previous {@code invokeAndWait} implementation.</li>
 *   <li>A {@code null} return still strictly implies the action ran to completion.</li>
 * </ul>
 */
public final class EdtRunner {

    private static final Logger log = LoggerFactory.getLogger(EdtRunner.class);

    private static final String EDT_CALLER_MSG =
            "EdtRunner.run must be called from a worker thread, not the EDT "
                    + "(waiting for the EDT from the EDT itself would deadlock until timeout)";

    private static final String ABORTED_UNKNOWN_MSG =
            "Aborted while waiting for the JMeter GUI event thread (caller thread interrupted, e.g. Stop). "
                    + "Outcome UNKNOWN: the operation may still run on the EDT afterwards — "
                    + "verify with get_test_plan_tree before any further tree change.";

    private EdtRunner() {
    }

    /**
     * Action that can throw any checked exception when run on EDT.
     */
    @FunctionalInterface
    public interface EdtAction {
        void run() throws Exception;
    }

    /**
     * Run {@code action} on EDT with a bounded wait (default from
     * {@code agent.tools.edt.wait.timeout.ms}), switching the thread's context
     * ClassLoader to the JMeter GuiPackage's ClassLoader for the duration.
     *
     * <p>The caller thread waits (bounded) until the action completes or the wait
     * times out / is interrupted. Safe to call from {@code tool-executor} threads —
     * EDT never calls back into tool-executor, so no deadlock risk.
     *
     * @param guiPackage GuiPackage instance (used for ClassLoader); not null
     * @param action     Action to run on EDT; must not be null
     * @return {@code null} on success, otherwise the exception thrown by the
     *         action (Error thrown by the action is wrapped as
     *         {@code InvocationTargetException}), an {@link EdtWaitTimeoutException}
     *         when the bounded wait expired (outcome UNKNOWN — the action stays
     *         queued), or an {@code IllegalStateException} when aborted by
     *         interruption (e.g. Stop; outcome UNKNOWN likewise). The caller
     *         decides how to translate the exception into a {@code ToolResult}.
     */
    public static Exception run(GuiPackage guiPackage, EdtAction action) {
        return run(guiPackage, action, AiConfig.getEdtWaitTimeoutMs());
    }

    /**
     * Package-private overload injecting the wait bound for tests. Production
     * code always goes through {@link #run(GuiPackage, EdtAction)}.
     */
    static Exception run(GuiPackage guiPackage, EdtAction action, long timeoutMs) {
        if (EventQueue.isDispatchThread()) {
            return new IllegalStateException(EDT_CALLER_MSG);
        }
        Exception[] error = new Exception[1];
        CountDownLatch done = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(guiPackage.getClass().getClassLoader());
                action.run();
            } catch (Throwable t) {
                // 与旧 invokeAndWait 的类型行为一致:Exception 原样回传,Error 包为
                // InvocationTargetException。必须留痕——EDT 默认异常处理器不再打印它。
                log.error("EDT action failed", t);
                error[0] = (t instanceof Exception e) ? e : new InvocationTargetException(t);
            } finally {
                // countDown 必须在 finally:动作抛 Error 时若缺此步,调用方会等满
                // 超时后把「已抛错的孤儿」误判成「结局未知」。
                Thread.currentThread().setContextClassLoader(original);
                done.countDown();
            }
        });
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("EDT wait timed out after {}ms; action stays queued and will still run when the GUI thread recovers",
                        timeoutMs);
                return new EdtWaitTimeoutException(timeoutMs);
            }
        } catch (InterruptedException ie) {
            // 不恢复中断位:与旧 invokeAndWait 抛 InterruptedException 清位的行为逐位一致;
            // 取消事实来源是 turn.abortFlag 而非中断位(恢复会把粘性中断泄漏进后续工具代码)。
            return new IllegalStateException(ABORTED_UNKNOWN_MSG, ie);
        }
        return error[0];
    }

    /**
     * EDT 等待超时。{@link #getMessage()} 自含面向 LLM 的契约文案(调用点会以
     * "Failed to ...: " 前缀包装,故消息须单层自足)。
     */
    public static final class EdtWaitTimeoutException extends TimeoutException {
        private static final long serialVersionUID = 1L;

        public EdtWaitTimeoutException(long timeoutMs) {
            super(message(timeoutMs));
        }

        private static String message(long timeoutMs) {
            return "EDT wait timed out after " + timeoutMs + "ms: the JMeter GUI event thread (EDT) "
                    + "did not finish this operation in time — it is likely busy with a long GUI operation, "
                    + "or blocked (rarely, by a native modal dialog or a GUI defect). "
                    + "Outcome UNKNOWN: the operation may be queued, still running, or may have already "
                    + "completed — the wait gave up, not the operation; it will never run partially. "
                    + "Do NOT blindly retry this change (a retry could duplicate the element if the original "
                    + "applies late). First inspect the actual current state with get_test_plan_tree "
                    + "(or find_element), then act on the verified state. "
                    + "If GUI tools keep timing out, stop editing the test plan and ask the user to free "
                    + "the UI (or press Stop).";
        }
    }
}
