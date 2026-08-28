package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.presenter.TurnPresenter;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnPresenter} 注册/派发守卫单测：注册者收到回调、null 注册者 no-op、
 * 非当前实例会话键不派发（{@code --session foo} headless 边界的机制实现）；
 * Phase 2 的 busy 快拒/注入 ack 通知仅 {@code fromIpc} 路径触发（本地路径面板自发渲染）。
 */
class AgentLoopPresenterTest {

    @TempDir
    Path tempDir;

    AgentLoop loop;
    GatedAiService service;
    RecordingPresenter presenter;

    @BeforeEach
    void setUp() {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        service = new GatedAiService();
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), service);
        presenter = new RecordingPresenter();
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    @Test
    void registeredPresenterReceivesNotifications() {
        loop.setTurnPresenter(presenter);
        String current = InstanceContext.currentSessionKey();

        loop.notifyTurnStarted(current, "[from cli] hello");
        loop.notifyTurnCompleted(current, AgentResponse.success("done"));
        loop.notifyTurnCancelled(current, "timeout");

        assertEquals(1, presenter.started.size());
        assertEquals("[from cli] hello", presenter.started.get(0));
        assertEquals(1, presenter.completed.size());
        assertEquals("done", presenter.completed.get(0).getContent());
        assertEquals(1, presenter.cancelled.size());
        assertEquals("timeout", presenter.cancelled.get(0));
    }

    @Test
    void nullPresenterIsNoOp() {
        loop.setTurnPresenter(null);
        String current = InstanceContext.currentSessionKey();
        // 不抛异常即为通过（无注册者 = headless）
        loop.notifyTurnStarted(current, "x");
        loop.notifyTurnCompleted(current, AgentResponse.success("y"));
        loop.notifyTurnCancelled(current, "timeout");
    }

    @Test
    void nonCurrentSessionKeyIsNotDispatched() {
        loop.setTurnPresenter(presenter);
        loop.notifyTurnStarted("some-other-session", "x");
        loop.notifyTurnCompleted("some-other-session", AgentResponse.success("y"));
        loop.notifyTurnCancelled("some-other-session", "timeout");
        assertTrue(presenter.started.isEmpty(), "headless session must not reach the presenter");
        assertTrue(presenter.completed.isEmpty());
        assertTrue(presenter.cancelled.isEmpty());
    }

    @Test
    void presenterExceptionIsSwallowed() {
        loop.setTurnPresenter(new TurnPresenter() {
            @Override public void onTurnStarted(String sessionKey, String message) {
                throw new IllegalStateException("boom");
            }
            @Override public void onProgress(String sessionKey, ProgressUpdate update) { }
            @Override public void onTurnCompleted(String sessionKey, AgentResponse response) { }
            @Override public void onTurnCancelled(String sessionKey, String reason) { }
            @Override public void onTurnRejectedBusy(String sessionKey) { }
            @Override public void onInjected(String sessionKey, String message) { }
        });
        String current = InstanceContext.currentSessionKey();
        loop.notifyTurnStarted(current, "x"); // 不得抛出
        loop.notifyTurnCancelled(current, "timeout");
    }

    @Test
    void ipcDelegatedWhileBusyNotifiesRejectedBusy() throws Exception {
        loop.setTurnPresenter(presenter);
        String current = InstanceContext.currentSessionKey();
        BusyTurn busy = startBusyTurn(current);

        CompletableFuture<AgentResponse> rejected =
                loop.processMessageFromIpc("[delegated-from A] do something", current, null, true);
        AgentResponse resp = rejected.get(5, TimeUnit.SECONDS);
        assertFalse(resp.isSuccess());
        assertTrue(resp.getErrorMessage().contains("session busy"), resp.getErrorMessage());
        assertEquals(1, presenter.rejectedBusy.size(), "panel must learn the delegation was busy-rejected");
        assertTrue(presenter.started.isEmpty(), "rejected delegation must not raise a turn-started line");

        busy.release.countDown();
        assertTrue(busy.future.get(5, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void ipcMessageWhileBusyIsInjectedAndNotified() throws Exception {
        loop.setTurnPresenter(presenter);
        String current = InstanceContext.currentSessionKey();
        BusyTurn busy = startBusyTurn(current);

        CompletableFuture<AgentResponse> ack =
                loop.processMessageFromIpc("[from cli] extra input", current, null, false);
        AgentResponse resp = ack.get(5, TimeUnit.SECONDS);
        assertTrue(resp.isSuccess(), "mid-turn injection must still ack");

        assertEquals(1, presenter.injected.size());
        assertEquals("[from cli] extra input", presenter.injected.get(0));
        assertTrue(presenter.rejectedBusy.isEmpty());

        busy.release.countDown();
        busy.future.get(5, TimeUnit.SECONDS);
    }

    @Test
    void localInjectWhileBusyDoesNotNotifyPresenter() throws Exception {
        loop.setTurnPresenter(presenter);
        String current = InstanceContext.currentSessionKey();
        BusyTurn busy = startBusyTurn(current);

        CompletableFuture<AgentResponse> ack = loop.processMessage("local extra", current, null, false);
        assertTrue(ack.get(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(presenter.injected.isEmpty(),
                "local path renders its own inject feedback; a presenter notify would double-display");

        busy.release.countDown();
        busy.future.get(5, TimeUnit.SECONDS);
    }

    /** 占住会话的第一个回合：LLM 调用挂起直至 {@link #release}，使路由槽（hasActiveRun）保持存在。 */
    private BusyTurn startBusyTurn(String sessionKey) throws Exception {
        BusyTurn busy = new BusyTurn(service);
        busy.future = loop.processMessage("local question", sessionKey);
        assertTrue(busy.llmEntered.await(5, TimeUnit.SECONDS), "turn one should reach the LLM call");
        return busy;
    }

    private static final class BusyTurn {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch llmEntered = new CountDownLatch(1);
        volatile CompletableFuture<AgentResponse> future;

        BusyTurn(GatedAiService service) {
            service.script = () -> {
                llmEntered.countDown();
                release.await();
                return LLMResponse.text("turn one done");
            };
        }
    }

    private static final class RecordingPresenter implements TurnPresenter {
        final List<String> started = new CopyOnWriteArrayList<>();
        final List<AgentResponse> completed = new CopyOnWriteArrayList<>();
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        final List<String> rejectedBusy = new CopyOnWriteArrayList<>();
        final List<String> injected = new CopyOnWriteArrayList<>();

        @Override public void onTurnStarted(String sessionKey, String message) {
            started.add(message);
        }

        @Override public void onProgress(String sessionKey, ProgressUpdate update) { }

        @Override public void onTurnCompleted(String sessionKey, AgentResponse response) {
            completed.add(response);
        }

        @Override public void onTurnCancelled(String sessionKey, String reason) {
            cancelled.add(reason);
        }

        @Override public void onTurnRejectedBusy(String sessionKey) {
            rejectedBusy.add(sessionKey);
        }

        @Override public void onInjected(String sessionKey, String message) {
            injected.add(message);
        }
    }

    /** 回合脚本可替换的假 AiService：默认立即返回文本，供 busy 场景挂起第一个 LLM 调用。 */
    private interface TurnScript {
        LLMResponse run() throws Exception;
    }

    private static final class GatedAiService implements AiService {
        volatile TurnScript script = () -> LLMResponse.text("unused");

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            try {
                return script.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LLMResponse.text("interrupted");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override public String getName() {
            return "gated-fake";
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
