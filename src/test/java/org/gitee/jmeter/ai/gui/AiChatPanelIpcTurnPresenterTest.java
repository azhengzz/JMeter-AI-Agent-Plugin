package org.gitee.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.provider.AiServiceFactory;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiChatPanel} 的 TurnPresenter 实现单测：IPC 回合（委派/CLI）领养呈现——
 * "You:" 行与按钮状态（onTurnStarted）、进度/终结接入既有渲染链、生命周期系统
 * 提示行（onTurnCancelled/onTurnRejectedBusy/onInjected）、呈现代数窗口
 * （武装前丢弃、/new 后迟到丢弃）。
 *
 * <p>通知由测试线程直发 {@code loop.notifyXxx}（生产经 IpcServer 在 ipc-worker 上
 * 调同一批方法；面板实现只依赖"回调在非 EDT 线程"这一契约）。
 */
class AiChatPanelIpcTurnPresenterTest {

    @TempDir
    Path tempDir;

    AgentLoop loop;
    AiChatPanel panel;
    String sessionKey;
    String previousJMeterHome;

    @BeforeEach
    void setUp() throws Exception {
        sessionKey = InstanceContext.currentSessionKey();

        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), new IdleAiService());

        // 面板构造会经 AgentLoopFactory 创建真实 loop（SessionManager 落在
        // JMeter home 下）——钉到 tempDir 防止单测污染仓库目录
        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());

        panel = new AiChatPanel();
        JComboBox<?> selector = field(panel, "modelSelector");
        awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed (model item selected)");
        SwingUtilities.invokeAndWait(() -> { }); // 排空 EDT，确保 done() 完整跑完
        setField(panel, "agentLoop", loop);
        invoke(panel, "registerRepublishListener");
        loop.setTurnPresenter(panel); // 生产对等：IPC 回合呈现绑定注入的 loop
    }

    @AfterEach
    void tearDown() {
        loop.setTurnPresenter(null);
        loop.shutdown();
        AgentLoopFactory.reset(); // 面板构造可能经工厂缓存了真实 loop，退役之
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    @Test
    void turnStartRendersYouLineAndStopsMode_completionResets() throws Exception {
        loop.notifyTurnStarted(sessionKey, "[from cli] hello");
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");
        JButton sendButton = field(panel, "sendButton");
        assertTrue(chatArea.getText().contains("You: [from cli] hello"),
                "来源消息（带前缀）必须以 You: 行渲染");
        assertTrue(stopButton.isVisible(), "回合运行中 Stop 按钮必须可见");
        assertNotNull(sendButton.getToolTipText(), "Send 必须切入注入模式");

        loop.notifyProgress(sessionKey, ProgressUpdate.thinking("THINKING-TRACE"));
        loop.notifyTurnCompleted(sessionKey, AgentResponse.success("FINAL-ANSWER"));
        // THINKING 的渲染比 FINAL 多一跳 EDT（runInIpcTurn 的 EDT-B 入队 handleProgress
        // 的内层 EDT-B2，B2 恒排在其入队前已在队列里的所有事件——含本测试的 drain
        // sentinel——之后）：invokeAndWait 排不干净，须轮询内容而非排空事件
        awaitUntil(() -> {
            String t = chatTextOnEdt(chatArea);
            return t.contains("THINKING-TRACE") && t.contains("FINAL-ANSWER");
        }, "progress and completion rendered");
        awaitEdtDrained();

        // 注：chatArea.getText() 是 HTML 源码，非 ASCII 会被转成数字字符实体——断言用 ASCII
        // 失败消息带代数与聊天区全文：诊断全量回归下偶发的渲染丢失（代数被外部翻转 vs 渲染被清除）
        String html = chatTextOnEdt(chatArea);
        String diag = "convGen=" + field(panel, "conversationGeneration")
                + " ipcGen=" + field(panel, "ipcTurnGeneration") + " chat=" + html;
        assertTrue(html.contains("THINKING-TRACE"), "进度事件必须接入既有渲染链 " + diag);
        assertTrue(html.contains("FINAL-ANSWER"), "终结必须走 appendBotResponse 路径 " + diag);
        assertFalse(stopButton.isVisible(), "回合结束（无后续回合）Stop 按钮必须隐藏");
        assertNull(sendButton.getToolTipText(), "Send 必须退出注入模式");
    }

    @Test
    void progressBeforeTurnStartIsDropped() throws Exception {
        // 未武装（ipcTurnGeneration == -1）：早于 onTurnStarted EDT 运行的投递丢弃
        loop.notifyProgress(sessionKey, ProgressUpdate.thinking("EARLY-EVENT"));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertFalse(chatArea.getText().contains("EARLY-EVENT"),
                "武装前到达的进度不得渲染（IpcServer 提交回合与首事件间的毫秒窗口）");
    }

    @Test
    void deliveryAfterSlashNewIsDroppedByGeneration() throws Exception {
        loop.notifyTurnStarted(sessionKey, "[from cli] hello");
        awaitEdtDrained();

        // /new：代数 +1 并清空聊天区（真实路径：handleNewCommand 同步清空+代数翻转，
        // cmdNew 作为 Phase 3 异步回合执行）
        JTextArea messageField = field(panel, "messageField");
        SwingUtilities.invokeAndWait(() -> {
            messageField.setText("/new");
            invoke(panel, "sendMessage");
        });

        loop.notifyProgress(sessionKey, ProgressUpdate.thinking("LATE-PROGRESS"));
        loop.notifyTurnCompleted(sessionKey, AgentResponse.success("LATE-FINAL"));
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertTrue(chatArea.getText().contains("You: /new"), "/new 自身的回显不受影响");
        assertFalse(chatArea.getText().contains("LATE-PROGRESS"),
                "重置后迟到的进度必须按代数丢弃");
        assertFalse(chatArea.getText().contains("LATE-FINAL"),
                "重置后迟到的结论必须按代数丢弃，不得渲染进新会话");
    }

    @Test
    void cancellationRendersReceiptLineAndResets_userStop() throws Exception {
        loop.notifyTurnStarted(sessionKey, "[from cli] hello");
        awaitEdtDrained();

        loop.notifyTurnCancelled(sessionKey, IpcResponse.CANCEL_REASON_USER_STOP);
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        JButton stopButton = field(panel, "stopButton");
        assertTrue(chatArea.getText().contains("Task cancelled"),
                "人工终止必须渲染系统提示行");
        assertTrue(chatArea.getText().contains("Partial results"),
                "人工终止文案必须含『部分结果已回传』回执");
        assertFalse(stopButton.isVisible(), "取消后（无后续回合）Stop 按钮必须隐藏");
    }

    @Test
    void cancellationRendersTimeoutLine() throws Exception {
        loop.notifyTurnStarted(sessionKey, "[delegated-from A] task");
        awaitEdtDrained();

        loop.notifyTurnCancelled(sessionKey, IpcResponse.CANCEL_REASON_TIMEOUT);
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertTrue(chatArea.getText().contains("timed out"),
                "超时取消必须渲染超时文案");
    }

    @Test
    void busyRejectAndInjectedRenderNoticeLines() throws Exception {
        // 直调面板的 presenter 实现（AgentLoop 侧的 fromIpc 门控派发已由
        // AgentLoopPresenterTest 覆盖；此处测面板渲染）
        panel.onTurnRejectedBusy(sessionKey);
        panel.onInjected(sessionKey, "[from cli] extra input");
        awaitEdtDrained();

        JTextPane chatArea = field(panel, "chatArea");
        assertTrue(chatArea.getText().contains("Delegation rejected"),
                "busy 快拒必须渲染系统提示行");
        assertTrue(chatArea.getText().contains("[Injected] You: [from cli] extra input"),
                "注入消息必须以注入回显样式渲染（含来源前缀）");
    }

    @Test
    void turnStartQueuedBehindSlashNewDoesNotRearmWindow() throws Exception {
        // F4 复现（对抗审查）：/new 的 EDT 事件先入队、回合启动事件排其后——
        // onTurnStarted 的代数必须于<b>通知时</b>快照；若在 EDT 执行时才读，会按
        // /new 之后的新代数武装窗口，幽灵 "You:" 行与取消回执渗入刚清空的新会话
        JTextPane chatArea = field(panel, "chatArea");
        java.util.concurrent.CountDownLatch edtHold = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                edtHold.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        JTextArea messageField = field(panel, "messageField");
        SwingUtilities.invokeLater(() -> {
            messageField.setText("/new");
            invoke(panel, "sendMessage");
        });
        // EDT 仍被钳制：/new 必然后于本通知执行 → 快照必然读到旧代数
        loop.notifyTurnStarted(sessionKey, "[from cli] race");
        edtHold.countDown();

        awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: /new"), "/new echo rendered");
        awaitEdtDrained();
        String html = chatTextOnEdt(chatArea);
        assertFalse(html.contains("[from cli] race"),
                "旧会话的回合启动不得按 EDT 执行时的新代数武装并渲染进 /new 后的新会话");
        int ipcGen = field(panel, "ipcTurnGeneration");
        assertEquals(-1, ipcGen, "呈现窗口不得被旧会话的启动通知重新武装");

        // 窗口未武装：迟到的取消回执同样丢弃
        loop.notifyTurnCancelled(sessionKey, IpcResponse.CANCEL_REASON_USER_STOP);
        awaitEdtDrained();
        assertFalse(chatTextOnEdt(chatArea).contains("Task cancelled"),
                "未武装窗口的迟到取消回执不得渲染");
    }

    @Test
    void lateIpcTerminalAfterLocalTurnStartIsDropped() throws Exception {
        // F3 复现（对抗审查）：垂死 IPC 回合（超时分支 cancelActiveTask 阻塞至多 5s
        // 后才发终结通知）与用户同时开启本地回合——本地回合一开即硬边界
        JTextPane chatArea = field(panel, "chatArea");
        loop.notifyTurnStarted(sessionKey, "[from cli] hello");
        awaitEdtDrained();

        JTextArea messageField = field(panel, "messageField");
        SwingUtilities.invokeAndWait(() -> {
            messageField.setText("local question");
            invoke(panel, "sendMessage");
        });
        awaitUntil(() -> chatTextOnEdt(chatArea).contains("You: local question"),
                "local turn echo rendered");

        loop.notifyTurnCancelled(sessionKey, IpcResponse.CANCEL_REASON_TIMEOUT);
        awaitEdtDrained();
        String html = chatTextOnEdt(chatArea);
        assertTrue(html.contains("You: [from cli] hello"),
                "同代数内先前的 IPC 回显不受影响（sanity）");
        assertFalse(html.contains("timed out"),
                "本地回合一开 IPC 呈现窗口即关闭，迟到的超时取消不得渲染");
        assertFalse(html.contains("Task cancelled"), "取消回执整段丢弃");
    }

    @Test
    void adoptsRunningIpcTurnWhenPanelJoinsMidTurn() throws Exception {
        // F2b 复现（对抗审查）：面板懒创建——委派/CLI 回合先于面板存在，其
        // onTurnStarted 发在旧 presenter（null）上丢失。构造完成时本实例会话上
        // 仍有活跃回合 → 领养：提示行 + Stop 模式 + 武装窗口（后续事件照常渲染，
        // 错过的中途进度不补放——Q12 无缓冲决策）
        JTextPane chatArea = field(panel, "chatArea");
        java.util.concurrent.CountDownLatch llmEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLlm = new java.util.concurrent.CountDownLatch(1);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        AgentLoop runningLoop = new AgentLoop(new ToolRegistry(Runnable::run),
                memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey),
                new BlockingAiService(llmEntered, releaseLlm));
        CompletableFuture<org.gitee.jmeter.ai.agent.model.AgentResponse> running = null;
        try {
            // 回合先开跑（presenter 尚未注册——生产上面板此刻还不存在）
            running = runningLoop.processMessageFromIpc("[from cli] long delegated task",
                    sessionKey, null, true);
            assertTrue(llmEntered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "delegated turn should reach its LLM call");

            // 面板此刻构造：绑定 loop（生产：构造器的 initializeAgentLoop）+ 领养
            setField(panel, "agentLoop", runningLoop);
            runningLoop.setTurnPresenter(panel);
            invoke(panel, "adoptRunningIpcTurnIfNeeded");

            JButton stopButton = field(panel, "stopButton");
            awaitUntil(stopButton::isVisible, "adoption switches to Stop mode");
            String html = chatTextOnEdt(chatArea);
            assertTrue(html.contains("An IPC turn"),
                    "领养必须渲染提示行（面板晚到，回合已在跑）");
            assertFalse(html.contains("You: [from cli] long delegated task"),
                    "错过的回合启动不补放 You: 行（Q12 无缓冲决策）");
            Integer ipcGen = field(panel, "ipcTurnGeneration");
            Integer convGen = field(panel, "conversationGeneration");
            assertEquals(convGen, ipcGen, "领养必须武装呈现窗口");

            // 后续事件照常渲染
            runningLoop.notifyProgress(sessionKey, ProgressUpdate.thinking("ADOPTED-PROGRESS"));
            awaitUntil(() -> chatTextOnEdt(chatArea).contains("ADOPTED-PROGRESS"),
                    "post-adoption progress renders");
        } finally {
            runningLoop.setTurnPresenter(null);
            releaseLlm.countDown();
            if (running != null) {
                try {
                    running.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 收尾形态无关紧要：确保回合结束即可
                }
            }
            runningLoop.shutdown();
            setField(panel, "agentLoop", loop); // 还原，tearDown 关的是原 loop
        }
    }

    @Test
    void panelConstructionSharesFactoryCachedLoopWithIpcWarmup() throws Exception {
        // F2a 复现（对抗审查）：IpcServer 预热与面板构造必须命中同一
        // AiServiceFactory 缓存实例 → 同一 AgentLoop 单例；面板懒创建中途打开
        // 不得 shutdown+recreate 换掉 IPC 在跑回合所在的 loop
        AiService warmed = null;
        try {
            warmed = AiServiceFactory.createService(AiConfig.getDefaultModel());
        } catch (Exception e) {
            // 测试环境未注册 provider：无共享可言，跳过
        }
        Assumptions.assumeTrue(warmed != null, "默认 provider 不可用时跳过（无共享可言）");
        AgentLoop shared = AgentLoopFactory.getAgentLoop(warmed);
        Assumptions.assumeTrue(shared != null, "agent 禁用时跳过");
        try {
            AiChatPanel second = new AiChatPanel();
            assertSame(shared, AgentLoopFactory.getAgentLoop(),
                    "面板构造不得换掉 IPC 预热的 AgentLoop 单例");

            // 模型加载完成（选择器落地默认模型）后也不得再换：选择器条目剥前缀后
            // 命中同一 cache key → switchAiService 判 newService == currentAiService
            JComboBox<?> selector2 = field(second, "modelSelector");
            awaitUntil(() -> selector2.getSelectedItem() != null, "second panel models loaded");
            SwingUtilities.invokeAndWait(() -> { });
            assertSame(shared, AgentLoopFactory.getAgentLoop(),
                    "模型加载完成后的 switchAiService 不得重建单例 loop");
        } finally {
            AgentLoopFactory.reset();
        }
    }

    // ------------------------------------------------------------------
    // 测试基础设施（对齐 AiChatPanelNewConversationTest 的面板脚手架）
    // ------------------------------------------------------------------

    /** 排空 EDT：invokeAndWait 的任务入队并执行完毕后，之前入队的事件必然已执行。 */
    private static void awaitEdtDrained() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /** 在 EDT 上读聊天区全文（HTML 源码）：Swing 组件读取须在 EDT，且轮询需同步取值。 */
    private static String chatTextOnEdt(JTextPane chatArea) {
        try {
            java.util.concurrent.atomic.AtomicReference<String> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> ref.set(chatArea.getText()));
            return ref.get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read chatArea on EDT", e);
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field f = reachableField(target.getClass(), name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = reachableField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set field " + name, e);
        }
    }

    private static Field reachableField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name) {
        try {
            Method m = null;
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod(name);
                    break;
                } catch (NoSuchMethodException ignore) {
                    // walk up
                }
            }
            if (m == null) {
                throw new NoSuchMethodException(name);
            }
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot invoke " + name, e);
        }
    }

    /** 立即应答的假 AiService：本测试不跑真实回合，仅满足 AgentLoop 构造。 */
    private static final class IdleAiService implements AiService {
        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            return LLMResponse.text("unused");
        }

        @Override public String getName() {
            return "idle-fake";
        }

        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override public void setGenerationSettings(GenerationSettings settings) { }

        @Override public boolean supportsToolCalling() {
            return true;
        }
    }

    /** 进入即报号、等待外部放行的假 AiService：模拟一次长时间阻塞的 LLM 调用（领养测试）。 */
    private static final class BlockingAiService implements AiService {
        private final java.util.concurrent.CountDownLatch entered;
        private final java.util.concurrent.CountDownLatch release;

        BlockingAiService(java.util.concurrent.CountDownLatch entered,
                java.util.concurrent.CountDownLatch release) {
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
                throw new IllegalStateException("LLM call interrupted", e);
            }
            return LLMResponse.text("blocked-done");
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
