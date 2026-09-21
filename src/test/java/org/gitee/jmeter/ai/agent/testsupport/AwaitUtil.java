package org.gitee.jmeter.ai.agent.testsupport;

import java.util.function.BooleanSupplier;

/** 测试等待工具：轮询条件成立（孤儿回合无外部 future 可等待，以事件流为完成信号）。 */
public final class AwaitUtil {

    private static final long TIMEOUT_SECONDS = 10;

    private AwaitUtil() { }

    /** 10s 上限、20ms 轮询；超时抛 AssertionError。 */
    public static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }
}
