package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.run.AgentRunResult;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the subagent executor topology against thread-pool starvation.
 *
 * <p>A subagent task occupies a thread of the bounded subagent pool. If that task
 * also asks {@link AgentRunner} to run ON that same pool and then blocks waiting
 * for the result, the pool self-deadlocks — with the default pool size of 1 it
 * deadlocks every single time.
 */
class SubagentExecutorDeadlockTest {

    /** Minimal AiService that answers immediately with no tool calls. */
    private static class InstantAiService implements AiService {
        @Override public String generateResponse(List<String> conversation) { return "done"; }
        @Override public String generateResponse(List<String> conversation, String model) { return "done"; }
        @Override public String getName() { return "fake"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return LLMResponse.text("done");
        }
    }

    /**
     * A run started from a thread of a size-1 pool must still complete.
     *
     * <p>This is exactly the shape of {@code SubagentManager.runSubagent}: the
     * caller already occupies the only subagent thread and joins on the run. It
     * only stays deadlock-free because {@link AgentRunner} schedules onto the
     * common pool rather than back onto the caller's pool.
     */
    @Test
    void agentRunStartedFromASingleThreadPoolCompletes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "subagent-test");
            t.setDaemon(true);
            return t;
        });
        try {
            AgentRunner runner = new AgentRunner(
                new org.gitee.jmeter.ai.agent.tools.ToolRegistry(),
                null,
                null,
                null,
                new InstantAiService(),
                3, 16000, 30000);

            AtomicReference<AgentRunResult> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var outer = pool.submit(() -> {
                try {
                    AgentRunSpec spec = AgentRunSpec.builder()
                        .sessionKey(AgentRunSpec.SUBAGENT_SESSION_PREFIX + "dead01")
                        .initialMessages(List.of(Message.user("task")))
                        .persistSession(false)
                        .maxIterations(2)
                        .build();
                    result.set(runner.run(spec).join());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            try {
                outer.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                fail("Subagent execution deadlocked: the run was scheduled onto the same "
                    + "bounded pool its caller occupies. With a pool size of 1 this hangs "
                    + "forever — AgentRunner must not schedule onto the caller's pool.");
            }

            assertNull(failure.get(), "subagent run threw: " + failure.get());
            assertNotNull(result.get(), "subagent run produced no result");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * End-to-end through SubagentManager with the shipped default concurrency of 1:
     * a spawned subagent must actually reach a terminal state.
     */
    @Test
    void spawnedSubagentReachesTerminalStateWithDefaultConcurrency() throws Exception {
        var registry = new org.gitee.jmeter.ai.agent.tools.ToolRegistry();

        SubagentManager manager = new SubagentManager(
            new InstantAiService(), SubagentTestSupport.contextBuilder(), null, registry, (key, tok, msg) -> true);
        try {
            assertEquals(1, manager.getMaxConcurrent(),
                "test assumes the shipped default of 1");

            String receipt = manager.spawn("analyse something", "probe", "chat:main", null);
            assertTrue(receipt.contains("started"), "spawn should return a start receipt: " + receipt);

            // Poll for completion rather than assuming timing.
            long deadline = System.currentTimeMillis() + 15_000;
            boolean terminal = false;
            while (System.currentTimeMillis() < deadline) {
                List<SubagentStatus> all = manager.getStatuses("chat:main", true);
                if (!all.isEmpty() && all.get(0).isTerminal()) {
                    terminal = true;
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(terminal,
                "subagent never reached a terminal state within 15s — the run is starved "
                + "on its own single-threaded pool");
            assertEquals(0, manager.getRunningCountBySession("chat:main"),
                "finished subagent must not still count as running");
        } finally {
            manager.shutdown();
        }
    }
}
