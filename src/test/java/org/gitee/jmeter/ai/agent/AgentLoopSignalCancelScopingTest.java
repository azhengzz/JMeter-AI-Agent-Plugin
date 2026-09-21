package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * signalCancel 的 interrupt 范围回归（对抗审查 F1）：AgentLoop 的 executor 是
 * 单线程串行的，取消一个<b>还在排队</b>的会话回合时，不得无差别 interrupt executor
 * 线程——那会连坐杀死另一会话正在运行的回合（生产路径：CLI 以 {@code --session}
 * 外部键发起回合、其 {@code /agent} 等待超时后 IpcServer 对该键 cancelActiveTask，
 * 而 executor 上正跑着本地默认会话的回合）。排队回合由 future.cancel(true) +
 * 任务头取消预检作废即可。
 */
class AgentLoopSignalCancelScopingTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    CountDownLatch releaseLlm;
    CountDownLatch llmEntered;

    @BeforeEach
    void setUp() {
        releaseLlm = new CountDownLatch(1);
        llmEntered = new CountDownLatch(1);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"),
                new BlockingAiService(llmEntered, releaseLlm));
    }

    @AfterEach
    void tearDown() {
        releaseLlm.countDown();
        loop.shutdown();
    }

    @Test
    void cancellingQueuedSessionDoesNotInterruptOtherSessionsRunningTurn() throws Exception {
        // 会话 A 的回合先提交并占住单线程 executor（阻塞在 LLM 调用上）
        CompletableFuture<AgentResponse> running = loop.processMessage("a-task", "session-a");
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "session-a turn should reach its LLM call");

        // 会话 B 的回合排在 A 之后（executor 单线程，必然尚未 pickup）
        CompletableFuture<AgentResponse> queued = loop.processMessage("b-task", "session-b");
        // B 侧超时自取消（生产：IpcServer 对 --session 键的超时分支）
        assertTrue(loop.signalCancel("session-b"), "signalCancel should report an effect");

        // 修复前：无差别 interrupt 会打断 A 阻塞中的 LLM 调用（A 异常收尾）；
        // 修复后：A 必须毫发无伤地正常完成
        releaseLlm.countDown();
        AgentResponse outcome = running.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(outcome, "session-a turn must complete normally");
        assertTrue(queued.isCancelled(), "session-b queued turn must be voided as cancelled");
    }

    @Test
    void cancellingRunningSessionStillInterruptsItsOwnTurn() throws Exception {
        // 反向兜底：矫枉过正防护——取消的会话恰是运行回合时，interrupt 必须照发
        CompletableFuture<AgentResponse> running = loop.processMessage("a-task", "session-a");
        assertTrue(llmEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "session-a turn should reach its LLM call");

        loop.signalCancel("session-a");

        // 阻塞中的 LLM 调用被 interrupt 打断 → 回合迅速以异常/取消收尾（而非等到 release）
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!running.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(running.isDone(),
                "interrupting the running session's turn must terminate it promptly");
        assertTrue(running.isCompletedExceptionally() || running.isCancelled(),
                "interrupted turn must not report success");
    }

    /** 进入即报号、等待外部放行的假 AiService：模拟一次长时间阻塞的 LLM 调用。 */
    private static final class BlockingAiService implements AiService {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingAiService(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 对齐真实 SDK：阻塞中的 HTTP 调用被 interrupt 即抛出
                throw new IllegalStateException("LLM call interrupted", e);
            }
            return LLMResponse.text("blocked-done-text");
        }

        @Override public String getName() {
            return "blocking-fake";
        }

        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override public void setGenerationSettings(GenerationSettings settings) { }

        @Override public boolean supportsToolCalling() {
            return true;
        }
    }
}
