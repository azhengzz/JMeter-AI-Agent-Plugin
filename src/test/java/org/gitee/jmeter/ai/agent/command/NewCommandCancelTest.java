package org.gitee.jmeter.ai.agent.command;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /new} clears the session, and to do that safely it signals any in-flight
 * run to stop. When nothing is running, that signal must not hit the command's own
 * turn — the user still needs to see the confirmation.
 */
class NewCommandCancelTest {

    private static class QuietAiService implements AiService {
        @Override public String getName() { return "quiet"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return LLMResponse.text("ok");
        }
    }

    /**
     * A service whose tool-call entry blocks until released, so a run stays "active"
     * long enough for a concurrent {@code /new} to cancel it mid-flight. The block is
     * interruptible so signalCancel's thread interrupt unblocks it for cleanup.
     */
    private static class BlockingAiService implements AiService {
        private final CountDownLatch release;
        BlockingAiService(CountDownLatch release) { this.release = release; }
        @Override public String getName() { return "blocking"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            try {
                release.await();
                return LLMResponse.text("done");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.error("Interrupted");
            }
        }
    }

    private static AgentLoop newLoop() throws Exception {
        return newLoop(new QuietAiService());
    }

    private static AgentLoop newLoop(AiService ai) throws Exception {
        Path workspace = Files.createTempDirectory("new-cmd-test");
        ToolRegistry registry = new ToolRegistry();
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace);
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory,
            new MemoryConsolidator(memory, ai, sessions, context, registry),
            context, sessions, ai);
    }

    @Test
    void newOnAnIdleSessionReturnsItsConfirmation() throws Exception {
        AgentLoop loop = newLoop();
        try {
            AgentResponse response = loop.processMessage("/new", "chat:main")
                .get(20, TimeUnit.SECONDS);

            assertNotNull(response, "/new must produce a response");
            assertTrue(response.isSuccess(),
                "/new must not fail: " + response.getErrorMessage());
            assertTrue(response.getContent() != null && response.getContent().contains("New session"),
                "the user must see the confirmation, not a cancelled turn: " + response.getContent());
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void newStillClearsTheSessionHistory() throws Exception {
        AgentLoop loop = newLoop();
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            session.addMessage(Message.user("old question"));
            session.addMessage(Message.assistant("old answer", null));
            assertEquals(2, session.getUnconsolidatedMessages().size());

            loop.processMessage("/new", "chat:main").get(20, TimeUnit.SECONDS);

            assertTrue(loop.getSessionManager().getOrCreate("chat:main")
                    .getUnconsolidatedMessages().isEmpty(),
                "/new must still clear the conversation");
        } finally {
            loop.shutdown();
        }
    }

    /**
     * {@code /new} typed while a run is in flight must cancel that run's future, and
     * the {@code /new} command itself must still return its confirmation. The cancelled
     * run surfaces to the UI as a benign {@code TURN_CANCELLED} event (stop-style
     * line), not as an error.
     */
    @Test
    void newDuringAnActiveRunCancelsTheRun() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AgentLoop loop = newLoop(new BlockingAiService(release));
        try {
            // Start a run that blocks inside the LLM call so it stays active.
            CompletableFuture<AgentResponse> run =
                loop.processMessage("analyze the plan", "chat:main");

            // Wait until the run is actually in flight (registered as the active task).
            assertTrue(await(() -> loop.hasActiveRun("chat:main")),
                "run should become active");

            // Dispatch /new mid-run, exactly as the EDT does via injectMessage.
            CompletableFuture<AgentResponse> newCmd =
                loop.processMessage("/new", "chat:main");
            AgentResponse result = newCmd.get(20, TimeUnit.SECONDS);

            assertTrue(result.isSuccess(),
                "/new must still succeed: " + result.getErrorMessage());
            assertNotNull(result.getContent());
            assertTrue(result.getContent().contains("New session"),
                "the user must see the confirmation: " + result.getContent());

            // The in-flight run's future was cancelled — the CF throws
            // CancellationException directly to any awaiter per the Future contract
            // (the panel learns of the cancellation via TURN_CANCELLED instead).
            assertThrows(CancellationException.class,
                () -> run.get(5, TimeUnit.SECONDS),
                "the active run's future must be cancelled, not completed normally");
        } finally {
            release.countDown();
            loop.shutdown();
        }
    }

    /** Spin until condition is true, max ~5s. */
    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
