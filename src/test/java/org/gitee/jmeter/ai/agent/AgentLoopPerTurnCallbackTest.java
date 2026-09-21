package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * per-turn 回调绑定回归（原 loop 级 {@code progressCallback} 残留缺陷）：
 * 回合的进度事件只发往<b>发起方传入的回调</b>；GUI 回合结束后经 IPC 到达的回合
 * （2 参/3 参 delegated 重载，无回调）不得把工具事件漏进旧 worker 的回调——
 * 这正是"只见工具事件不见消息"半状态缺陷的机制根源。
 *
 * <p>脚手架与 {@link AgentLoopRepublishTest} 相同：脚本化 fake AiService +
 * Mockito 记忆组件 + 直插 ToolRegistry 的 Noop 工具。
 */
class AgentLoopPerTurnCallbackTest {

    private static final String SESSION_KEY = "test-session";
    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    ScriptedAiService aiService;

    @BeforeEach
    void setUp() {
        aiService = new ScriptedAiService();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        MemoryConsolidator consolidator = Mockito.mock(MemoryConsolidator.class);
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        ContextBuilder contextBuilder = new ContextBuilder(memoryStore, tempDir);
        SessionManager sessionManager = new SessionManager(tempDir, SESSION_KEY);
        loop = new AgentLoop(registry, memoryStore, consolidator, contextBuilder,
                sessionManager, aiService);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    @Test
    void perTurnCallbacksDoNotCrossTalk() throws Exception {
        List<ProgressUpdate> cbA = new CopyOnWriteArrayList<>();
        List<ProgressUpdate> cbB = new CopyOnWriteArrayList<>();

        // 回合 1（cbA）：工具调用迭代产生 THINKING + TOOL_CALL，随后终稿
        aiService.script(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "A-thinking"));
        aiService.script(LLMResponse.text("A-FINAL"));
        AgentResponse r1 = loop.processMessage("M1", SESSION_KEY, cbA::add, TurnOrigin.LOCAL_PANEL)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("A-FINAL", r1.getContent());
        assertTrue(cbA.stream().anyMatch(u -> u.getType() == ProgressUpdate.Type.THINKING),
                "turn 1 must emit THINKING to its own callback");
        assertTrue(cbA.stream().anyMatch(u -> u.getType() == ProgressUpdate.Type.TOOL_CALL),
                "turn 1 must emit TOOL_CALL to its own callback");
        assertTrue(cbB.isEmpty(), "turn 2's callback must not receive turn 1 events");

        // 回合 2（cbB）：同样的事件形状，只进 cbB
        aiService.script(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "B-thinking"));
        aiService.script(LLMResponse.text("B-FINAL"));
        AgentResponse r2 = loop.processMessage("M2", SESSION_KEY, cbB::add, TurnOrigin.LOCAL_PANEL)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("B-FINAL", r2.getContent());
        int aAfterTurn1 = cbA.size();
        assertTrue(cbB.stream().anyMatch(u -> u.getType() == ProgressUpdate.Type.TOOL_CALL),
                "turn 2 must emit events to its own callback");
        assertEquals(aAfterTurn1, cbA.size(), "turn 1's callback must not receive turn 2 events");
    }

    @Test
    void ipcOverloadAfterGuiTurnLeaksNoEventsIntoStaleCallback() throws Exception {
        // GUI 回合先跑（携带回调）——旧实现会把回调残留在 loop 级字段上
        List<ProgressUpdate> guiCb = new CopyOnWriteArrayList<>();
        aiService.script(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "thinking"));
        aiService.script(LLMResponse.text("GUI-FINAL"));
        loop.processMessage("M1", SESSION_KEY, guiCb::add, TurnOrigin.LOCAL_PANEL).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(guiCb.isEmpty(), "GUI turn must receive its own events");

        // 随后 IPC 回合到达（delegated=true，无回调可用）：事件不得漏进 guiCb
        aiService.script(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", Map.of())), "ipc-thinking"));
        aiService.script(LLMResponse.text("IPC-FINAL"));
        AgentResponse ipc = loop.processMessage("M3", SESSION_KEY, null, TurnOrigin.IPC_DELEGATED)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("IPC-FINAL", ipc.getContent());
        int before = guiCb.size();
        // IPC 回合自身完整执行（工具迭代确有事件产生），但全部无人接收
        assertEquals(before, guiCb.size(),
                "IPC turn must not leak progress into the previous GUI turn's callback (half-state defect)");
    }

    /** 与 {@link AgentLoopRepublishTest} 相同的脚本化 fake（这里只需立即应答）。 */
    private static final class ScriptedAiService implements AiService {
        private final java.util.concurrent.ConcurrentLinkedQueue<LLMResponse> script =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        void script(LLMResponse response) {
            script.add(response);
        }

        @Override
        public LLMResponse generateResponseWithTools(
                List<org.gitee.jmeter.ai.agent.model.Message> messages,
                List<ToolDefinition> tools, LlmCallOptions options) {
            LLMResponse next = script.poll();
            return next != null ? next : LLMResponse.text("DEFAULT-FINAL");
        }

        @Override
        public String getName() {
            return "scripted-fake";
        }

        @Override
        public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override
        public void setGenerationSettings(GenerationSettings settings) {
            // no-op
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    /** 立即成功的空操作工具。 */
    private static final class NoopTool implements Tool {
        @Override
        public String getName() {
            return "noop_tool";
        }

        @Override
        public String getDescription() {
            return "test noop tool";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }
}
