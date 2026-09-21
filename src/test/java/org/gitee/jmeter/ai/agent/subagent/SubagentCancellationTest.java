package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancellation must actually stop a running subagent — otherwise it keeps burning
 * tokens after Stop, and the main agent stays parked on its drain wait.
 */
class SubagentCancellationTest {

    /** Blocks inside the LLM call until released or interrupted, like a real slow call. */
    private static class BlockingAiService implements AiService {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean wasInterrupted = new AtomicBoolean();

        @Override public String getName() { return "blocking"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            entered.countDown();
            try {
                Thread.sleep(60_000);
                return LLMResponse.text("never");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                wasInterrupted.set(true);
                return LLMResponse.error("Interrupted");
            }
        }
    }

    @Test
    void cancelBySessionStopsARunningSubagent() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        SubagentManager manager = new SubagentManager(
            ai, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);
        try {
            manager.spawn("long task", "slow", "chat:main", null);

            assertTrue(ai.entered.await(10, TimeUnit.SECONDS),
                "subagent should have reached the LLM call");
            assertEquals(1, manager.getRunningCountBySession("chat:main"));

            long start = System.currentTimeMillis();
            int cancelled = manager.cancelBySession("chat:main");
            assertEquals(1, cancelled, "the running subagent should be cancelled");

            // It must wind down promptly, not after the 60s sleep.
            long deadline = System.currentTimeMillis() + 10_000;
            while (manager.getRunningCountBySession("chat:main") > 0
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            long elapsed = System.currentTimeMillis() - start;
            assertEquals(0, manager.getRunningCountBySession("chat:main"),
                "cancelled subagent must stop being counted as running");
            assertTrue(elapsed < 10_000,
                "cancellation should take effect promptly, took " + elapsed + "ms");
            assertTrue(ai.wasInterrupted.get(),
                "the in-flight LLM call must actually be interrupted, not left running");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void cancelBySessionOnUnknownSessionIsANoop() {
        SubagentManager manager = new SubagentManager(
            new BlockingAiService(), SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);
        try {
            assertEquals(0, manager.cancelBySession("chat:nobody"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void shutdownStopsInFlightSubagents() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        SubagentManager manager = new SubagentManager(
            ai, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);

        manager.spawn("long task", "slow", "chat:main", null);
        assertTrue(ai.entered.await(10, TimeUnit.SECONDS));

        manager.shutdown();

        // The pool must not keep a live thread parked in the 60s sleep.
        long deadline = System.currentTimeMillis() + 10_000;
        while (!ai.wasInterrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(ai.wasInterrupted.get(),
            "shutdown must interrupt in-flight subagents rather than leaking them");
    }

    /**
     * The concurrency limit must hold under a burst — the check and the registration
     * have to be atomic, or two concurrent spawns both pass a bare count check.
     */
    @Test
    void concurrencyLimitHoldsUnderConcurrentSpawns() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        SubagentManager manager = new SubagentManager(
            ai, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);
        try {
            int limit = manager.getMaxConcurrent();
            int attempts = 8;
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(attempts);
            java.util.List<String> receipts = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

            for (int i = 0; i < attempts; i++) {
                final int n = i;
                new Thread(() -> {
                    try {
                        go.await();
                        receipts.add(manager.spawn("task " + n, "t" + n, "chat:main", null));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            go.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS), "spawn attempts should all return");

            long started = receipts.stream().filter(r -> r.contains("started")).count();
            long rejected = receipts.stream().filter(r -> r.contains("concurrency limit")).count();

            assertEquals(attempts, started + rejected, "every attempt returns start or limit: " + receipts);
            assertTrue(started <= limit,
                "at most " + limit + " subagent(s) may start, but " + started + " did");
            assertTrue(manager.getRunningCountBySession("chat:main") <= limit,
                "running count must never exceed the configured limit");
        } finally {
            manager.shutdown();
        }
    }
}
