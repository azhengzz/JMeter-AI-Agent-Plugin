package org.gitee.jmeter.ai.agent.subagent;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.agent.tools.subagent.SpawnTool;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a real {@link AgentLoop} end to end to cover the two behaviours that
 * cannot be seen from {@code SubagentManager} alone: turn-confluence (task 7.2)
 * and graceful degradation when the drain times out (task 7.6).
 */
class SubagentTurnConfluenceIT {

    @BeforeAll
    static void initJMeterProperties() throws Exception {
        // JMeterUtils.setProperty NPEs until appProperties exists, and the drain
        // timeout is read once in the AgentLoop constructor — so seed before building.
        Path props = Files.createTempFile("jmeter-it", ".properties");
        Files.writeString(props, "# test\n");
        JMeterUtils.loadJMeterProperties(props.toString());
        JMeterUtils.setProperty("agent.subagent.drain.timeout.seconds", "3");
        JMeterUtils.setProperty("jmeter.ai.max.tool.iterations", "6");
    }

    /**
     * Scripted LLM: turn 1 calls spawn, then answers. Whatever it is handed after
     * that is recorded, so the test can prove the subagent result reached the model
     * inside the same turn.
     */
    private static class ScriptedAiService implements AiService {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();
        final CountDownLatch sawSubagentResult = new CountDownLatch(1);
        volatile boolean spawnRequested;

        @Override public String generateResponse(List<String> conversation) { return "text"; }
        @Override public String generateResponse(List<String> conversation, String model) { return "text"; }
        @Override public String getName() { return "scripted"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return generateResponseWithTools(messages, tools, null);
        }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools,
                                                     LlmCallOptions options) {
            lastMessages.set(List.copyOf(messages));

            boolean carriesSubagentResult = messages.stream()
                .anyMatch(m -> m.getRole() == Message.Role.USER
                    && m.getContent() != null
                    && m.getContent().contains("SUBAGENT_FINDING"));
            if (carriesSubagentResult) {
                sawSubagentResult.countDown();
                return LLMResponse.text("Relaying: the subagent reported SUBAGENT_FINDING.");
            }

            if (calls.incrementAndGet() == 1) {
                spawnRequested = true;
                ToolCall call = new ToolCall(
                    "call-1", "spawn", Map.of("task", "audit the plan", "label", "audit"));
                return LLMResponse.withToolCalls(List.of(call), null);
            }
            return LLMResponse.text("Nothing further.");
        }
    }

    /** The subagent's own LLM: answers with the marker the main agent must relay. */
    private static class SubagentAiService implements AiService {
        private final long delayMs;
        SubagentAiService(long delayMs) { this.delayMs = delayMs; }

        @Override public String generateResponse(List<String> conversation) { return "x"; }
        @Override public String generateResponse(List<String> conversation, String model) { return "x"; }
        @Override public String getName() { return "subagent-llm"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return LLMResponse.error("Interrupted");
                }
            }
            return LLMResponse.text("SUBAGENT_FINDING: 3 samplers lack assertions");
        }
    }

    private static class Harness implements AutoCloseable {
        final AgentLoop loop;
        final SubagentManager manager;
        final ScriptedAiService mainAi;

        Harness(long subagentDelayMs) throws Exception {
            Path workspace = Files.createTempDirectory("subagent-it");
            ToolRegistry registry = new ToolRegistry();
            MemoryStore memory = new MemoryStore(workspace);
            SessionManager sessions = new SessionManager(workspace);
            ContextBuilder context = new ContextBuilder(memory, workspace);
            mainAi = new ScriptedAiService();
            MemoryConsolidator consolidator =
                new MemoryConsolidator(memory, mainAi, sessions, context, registry);

            loop = new AgentLoop(registry, memory, consolidator, context, sessions, mainAi);
            manager = new SubagentManager(
                new SubagentAiService(subagentDelayMs), context, sessions, registry,
                loop::offerInjection);
            loop.setSubagentManager(manager);
            registry.register(new SpawnTool(manager,
                () -> {
                    var ctx = org.gitee.jmeter.ai.agent.run.AgentRunContext.current();
                    return ctx == null ? null : loop.currentTurnToken(ctx.getSessionKey());
                }));
        }

        @Override public void close() {
            manager.shutdown();
            loop.shutdown();
        }
    }

    /**
     * Task 7.2 — turn confluence: the main agent spawns, blocks at an injection
     * checkpoint, absorbs the subagent's result, and relays it in the SAME turn.
     */
    @Test
    void subagentResultIsAbsorbedAndRelayedWithinTheSameTurn() throws Exception {
        try (Harness h = new Harness(300)) {
            CompletableFuture<AgentResponse> turn =
                h.loop.processMessage("audit my plan", "chat:main", null);

            AgentResponse response = turn.get(30, TimeUnit.SECONDS);

            assertTrue(h.mainAi.spawnRequested, "the scripted model should have called spawn");
            assertTrue(h.mainAi.sawSubagentResult.await(1, TimeUnit.SECONDS),
                "the subagent's result must be fed back to the model inside this turn");
            assertNotNull(response);
            assertTrue(response.isSuccess(), "turn should succeed: " + response.getContent());
            assertTrue(response.getContent().contains("SUBAGENT_FINDING"),
                "the turn's final answer must relay the subagent's finding: " + response.getContent());

            // The injected announcement reached the model as a user message.
            assertTrue(h.mainAi.lastMessages.get().stream()
                    .anyMatch(m -> m.getRole() == Message.Role.USER
                        && m.getContent() != null
                        && m.getContent().contains("SUBAGENT_FINDING")),
                "the announcement should be injected as a user message");
        }
    }

    /**
     * Task 7.6 — degradation: when the subagent outlives the drain timeout, the
     * turn must finish anyway rather than hanging, and the late result must stay
     * retrievable instead of being dropped.
     */
    @Test
    void turnFinishesWhenTheSubagentOutlivesTheDrainTimeout() throws Exception {
        // Drain timeout is 3s (seeded above); the subagent takes ~6s.
        try (Harness h = new Harness(6_000)) {
            long start = System.currentTimeMillis();
            CompletableFuture<AgentResponse> turn =
                h.loop.processMessage("audit my plan", "chat:main", null);

            AgentResponse response = turn.get(30, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertNotNull(response);
            assertTrue(response.isSuccess(), "the turn must still complete: " + response.getContent());
            assertTrue(elapsed < 25_000,
                "the turn must give up on the slow subagent, not hang; took " + elapsed + "ms");

            // The subagent keeps running and its result remains queryable.
            long deadline = System.currentTimeMillis() + 20_000;
            SubagentStatus status = null;
            while (System.currentTimeMillis() < deadline) {
                List<SubagentStatus> all = h.manager.getStatuses("chat:main", true);
                if (!all.isEmpty() && all.get(0).isTerminal()) {
                    status = all.get(0);
                    break;
                }
                Thread.sleep(100);
            }
            assertNotNull(status, "the late subagent should still reach a terminal state");
            assertTrue(status.getResult() != null || status.getError() != null,
                "a late result must remain retrievable via subagent_status, not be discarded");
        }
    }
}
