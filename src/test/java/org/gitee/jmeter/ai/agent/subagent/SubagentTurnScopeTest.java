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
 * Two failure modes that stall the user for minutes, both invisible in a happy-path
 * test: waiting on a subagent whose result can no longer be delivered, and a
 * cancelled-while-queued subagent that never frees its slot.
 */
class SubagentTurnScopeTest {

    /** Parks in the LLM call so the subagent stays "running" for the whole test. */
    private static class BlockingAiService implements AiService {
        final CountDownLatch entered = new CountDownLatch(1);

        @Override public String getName() { return "blocking"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            entered.countDown();
            try {
                Thread.sleep(60_000);
                return LLMResponse.text("never");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.error("Interrupted");
            }
        }
    }

    /**
     * A subagent left over from a finished turn must not make the NEXT turn wait.
     * Its result is discarded on arrival, so waiting could only ever burn the full
     * drain timeout — a multi-minute hang on the user's next message.
     */
    @Test
    void aSubagentFromAnEndedTurnIsNotWaitedOn() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        SubagentManager manager = new SubagentManager(
            ai, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);
        try {
            AtomicBoolean turnActive = new AtomicBoolean(true);
            manager.spawn("long audit", "audit", "chat:main", turnActive::get);

            assertTrue(ai.entered.await(10, TimeUnit.SECONDS), "subagent should be running");
            assertEquals(1, manager.getWaitableCountBySession("chat:main"),
                "while its turn is active, the main agent should wait for it");

            // The turn ends while the subagent is still running.
            turnActive.set(false);

            assertEquals(0, manager.getWaitableCountBySession("chat:main"),
                "a subagent whose turn has ended must NOT be waited on by the next turn");
            assertEquals(1, manager.getRunningCountBySession("chat:main"),
                "it is still genuinely running, just not waitable");
        } finally {
            manager.shutdown();
        }
    }

    /**
     * Cancelling a subagent that is still queued must free its slot. The task body
     * never runs, so the cleanup in its finally block never runs either — without
     * an explicit release the session looks busy forever: every later turn blocks
     * on the drain wait and every later spawn is refused.
     */
    @Test
    void cancellingAQueuedSubagentReleasesItsSlot() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        SubagentManager manager = new SubagentManager(
            ai, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);
        try {
            // First subagent occupies the single pool thread.
            manager.spawn("first", "first", "chat:a", null);
            assertTrue(ai.entered.await(10, TimeUnit.SECONDS));

            // A different session queues behind it — never starts.
            String receipt = manager.spawn("queued", "queued", "chat:b", null);
            assertTrue(receipt.contains("started"),
                "a different session is not blocked by the per-session limit: " + receipt);
            assertEquals(1, manager.getRunningCountBySession("chat:b"));

            manager.cancelBySession("chat:b");

            assertEquals(0, manager.getRunningCountBySession("chat:b"),
                "a subagent cancelled before it started must free its slot, "
                + "or the session stays permanently 'busy'");
            assertEquals(0, manager.getWaitableCountBySession("chat:b"),
                "and must not keep the next turn blocking on the drain wait");

            // The slot is genuinely reusable.
            String again = manager.spawn("after cancel", "again", "chat:b", null);
            assertTrue(again.contains("started"),
                "spawning must work again after a queued cancel: " + again);
        } finally {
            manager.shutdown();
        }
    }

    /** A null turn token means "always deliverable" — used by callers with no turn context. */
    @Test
    void aSubagentWithNoTurnTokenIsAlwaysWaitable() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        SubagentManager manager = new SubagentManager(
            ai, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(), (key, tok, msg) -> true);
        try {
            manager.spawn("no token", "plain", "chat:main", null);
            assertTrue(ai.entered.await(10, TimeUnit.SECONDS));

            assertEquals(1, manager.getWaitableCountBySession("chat:main"));
        } finally {
            manager.shutdown();
        }
    }
}
