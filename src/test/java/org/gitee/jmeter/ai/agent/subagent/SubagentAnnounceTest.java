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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A finished subagent must hand its result back to the turn that spawned it — and
 * to no other. Delivering into a later, unrelated turn would derail that turn.
 */
class SubagentAnnounceTest {

    private static class InstantAiService implements AiService {
        private final String answer;
        InstantAiService(String answer) { this.answer = answer; }
        @Override public String generateResponse(List<String> conversation) { return answer; }
        @Override public String generateResponse(List<String> conversation, String model) { return answer; }
        @Override public String getName() { return "fake"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return LLMResponse.text(answer);
        }
    }

    private static SubagentStatus awaitTerminal(SubagentManager manager, String sessionKey) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<SubagentStatus> all = manager.getStatuses(sessionKey, true);
            if (!all.isEmpty() && all.get(0).isTerminal()) {
                return all.get(0);
            }
            Thread.sleep(50);
        }
        return fail("subagent did not finish in time");
    }

    @Test
    void resultIsDeliveredWhenTheSpawningTurnIsStillActive() throws Exception {
        AtomicReference<String> deliveredKey = new AtomicReference<>();
        AtomicReference<String> deliveredText = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);

        SubagentManager manager = new SubagentManager(
            new InstantAiService("found 3 samplers without assertions"),
            SubagentTestSupport.contextBuilder(), null, new ToolRegistry(),
            (key, tok, msg) -> {
                deliveredKey.set(key);
                deliveredText.set(msg);
                delivered.countDown();
                return true;
            });
        try {
            SubagentManager.TurnToken active = () -> true;
            manager.spawn("audit assertions", "audit", "chat:main", active);

            assertTrue(delivered.await(15, TimeUnit.SECONDS), "result should be delivered");
            assertEquals("chat:main", deliveredKey.get(), "must route to the spawning session");

            String text = deliveredText.get();
            assertTrue(text.contains("audit assertions"), "announcement should carry the task: " + text);
            assertTrue(text.contains("found 3 samplers without assertions"),
                "announcement should carry the result: " + text);
            assertTrue(text.contains("completed successfully"), "should report success: " + text);
            assertTrue(text.toLowerCase().contains("your own words"),
                "should instruct the main agent to relay it naturally: " + text);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void resultIsWithheldWhenTheSpawningTurnHasEnded() throws Exception {
        AtomicReference<String> delivered = new AtomicReference<>();

        // Models the real sink: it refuses a token that is no longer the active turn.
        java.util.concurrent.atomic.AtomicReference<Object> activeTurn = new java.util.concurrent.atomic.AtomicReference<>("turn-2");

        SubagentManager manager = new SubagentManager(
            new InstantAiService("late answer"),
            SubagentTestSupport.contextBuilder(), null, new ToolRegistry(),
            (key, tok, msg) -> {
                if (tok != null && tok != activeTurn.get()) {
                    return false;
                }
                delivered.set(msg);
                return true;
            });
        try {
            // This subagent belongs to turn-1, but turn-2 is the active one now.
            Object spawningTurn = "turn-1";
            SubagentManager.TurnToken stale = new SubagentManager.TurnToken() {
                @Override public boolean isActive() { return spawningTurn == activeTurn.get(); }
                @Override public Object identity() { return spawningTurn; }
            };
            manager.spawn("slow audit", "audit", "chat:main", stale);

            SubagentStatus status = awaitTerminal(manager, "chat:main");

            assertNull(delivered.get(),
                "a result from an ended turn must NOT be injected into whatever turn is current");
            assertEquals("late answer", status.getResult(),
                "the result must still be retrievable via subagent_status");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void undeliverableResultIsKeptForStatusQuery() throws Exception {
        // Sink refuses (no active run for that session).
        SubagentManager manager = new SubagentManager(
            new InstantAiService("orphan answer"),
            SubagentTestSupport.contextBuilder(), null, new ToolRegistry(),
            (key, tok, msg) -> false);
        try {
            manager.spawn("audit", "audit", "chat:main", null);

            SubagentStatus status = awaitTerminal(manager, "chat:main");

            assertEquals(SubagentStatus.Phase.DONE, status.getPhase(),
                "subagent errored instead of completing: " + status.getError());
            assertEquals("orphan answer", status.getResult(),
                "an undeliverable result must survive for the main agent to pull");
        } finally {
            manager.shutdown();
        }
    }

    /**
     * A subagent aborted by failOnToolError must be announced as a failure. The run
     * itself did not throw, so isSuccess() is true and the error text arrives as the
     * run's content — announcing that as success makes the main agent relay a tool
     * failure to the user as if it were a finding.
     */
    @Test
    void aSubagentAbortedByAToolErrorIsAnnouncedAsFailure() throws Exception {
        AtomicReference<String> deliveredText = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);

        // A tool that always fails, plus a model that always calls it.
        var registry = new ToolRegistry();
        registry.register(new org.gitee.jmeter.ai.agent.tools.AbstractTool() {
            @Override public String getName() { return "always_fails"; }
            @Override public String getDescription() { return "fails"; }
            @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            @Override public java.util.Set<String> getScopes() {
                return java.util.Set.of(org.gitee.jmeter.ai.agent.tools.Tool.SCOPE_CORE,
                    org.gitee.jmeter.ai.agent.tools.Tool.SCOPE_SUBAGENT);
            }
            @Override protected org.gitee.jmeter.ai.agent.model.ToolResult executeInternal(
                    java.util.Map<String, Object> parameters) {
                return org.gitee.jmeter.ai.agent.model.ToolResult.error("disk on fire");
            }
        });

        AiService toolCaller = new InstantAiService("unused") {
            @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
                return LLMResponse.withToolCalls(
                    List.of(new org.gitee.jmeter.ai.agent.model.ToolCall(
                        "c1", "always_fails", java.util.Map.of())),
                    null);
            }
        };

        SubagentManager manager = new SubagentManager(
            toolCaller, SubagentTestSupport.contextBuilder(), null, registry,
            (key, tok, msg) -> { deliveredText.set(msg); delivered.countDown(); return true; });
        try {
            manager.spawn("audit with a broken tool", "audit", "chat:main", null);

            assertTrue(delivered.await(15, TimeUnit.SECONDS), "a result must be announced");
            assertTrue(deliveredText.get().contains("failed"),
                "a tool-aborted subagent must be announced as failed, not successful: "
                    + deliveredText.get());
            assertFalse(deliveredText.get().contains("completed successfully"),
                "must not claim success: " + deliveredText.get());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void failedSubagentAnnouncesFailure() throws Exception {
        AtomicReference<String> deliveredText = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);

        // A service that always errors drives the run to a failed result.
        AiService failing = new InstantAiService("unused") {
            @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
                throw new IllegalStateException("provider exploded");
            }
        };

        SubagentManager manager = new SubagentManager(
            failing, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(),
            (key, tok, msg) -> { deliveredText.set(msg); delivered.countDown(); return true; });
        try {
            manager.spawn("doomed task", "doomed", "chat:main", null);

            assertTrue(delivered.await(15, TimeUnit.SECONDS),
                "a failure must still be reported back, not silently dropped");
            assertTrue(deliveredText.get().contains("doomed task"),
                "failure announcement should name the task: " + deliveredText.get());
        } finally {
            manager.shutdown();
        }
    }
}
