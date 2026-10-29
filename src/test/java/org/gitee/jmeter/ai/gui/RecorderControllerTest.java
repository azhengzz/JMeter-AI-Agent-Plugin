package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.gitee.jmeter.ai.recorder.RecorderEngine;
import org.gitee.jmeter.ai.recorder.RecorderRuntime;
import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Window;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 任务 2.2/4.4/4.1(busy 判定)：按钮三态循环与 EDT 状态同步、收官四类提示行、
 * busy 插入禁用复查。引擎走 FakeTransport 全栈（同 RecorderEngineTest 装置），
 * 经 {@link RecorderRuntime#forTest} 中继接线进控制器；两处模态弹窗由子类替换。
 */
class RecorderControllerTest {

    /** 另存路径走 SaveService 序列化：需 JMETER_HOME 定位 saveservice.properties（同 JmeterElementBridgeTest 前置）。 */
    @org.junit.jupiter.api.BeforeAll
    static void initJMeterHome() {
        String home = System.getenv("JMETER_HOME");
        assumeTrue(home != null && java.nio.file.Files.isRegularFile(
                        java.nio.file.Path.of(home, "bin", "saveservice.properties")),
                "JMETER_HOME unavailable; save-as-jmx path skipped");
        org.apache.jmeter.util.JMeterUtils.setJMeterHome(home);
        // 全量套件下 registry 可能已被其他测试以空目录占位（loaded=true 幂等跳过），
        // 从源码树技能目录强制 reload（同 JmeterElementBridgeTest 的既有处理）。
        org.gitee.jmeter.ai.utils.ElementRegistry.getInstance()
                .reload(java.nio.file.Path.of("src/main/jmeter-agent/skills"));
    }

    @TempDir
    Path workspace;

    private FakeTransport transport;
    private RecorderRuntime runtime;
    private FakeHost host;
    private TestController controller;
    private volatile RecorderResultDialog.Choice nextChoice = RecorderResultDialog.Choice.DISCARD;
    /** 弹窗「弹出」时把回合置忙（模拟弹窗后、点击前出现树编辑回合）。 */
    private volatile boolean flipTurnActiveOnDialog;
    private volatile boolean deferredNoticeShown;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        transport = new FakeTransport();
        transport.responder = msg -> {
            String method = msg.path("method").asText("");
            long id = msg.path("id").asLong();
            if ("Storage.getCookies".equals(method)) {
                return Optional.of("{\"id\":" + id
                        + ",\"result\":{\"cookies\":[{\"name\":\"sess\",\"value\":\"v1\",\"domain\":\"example.com\"}]}}");
            }
            if ("Network.getResponseBody".equals(method)) {
                return Optional.of("{\"id\":" + id
                        + ",\"result\":{\"body\":\"HELLO-BODY\",\"base64Encoded\":false}}");
            }
            return Optional.of("{\"id\":" + id + ",\"result\":{}}");
        };
        runtime = RecorderRuntime.forTest(workspace, "ctrl-test", this::newEngine);
        controller = new TestController(host, runtime);
    }

    private RecorderEngine newEngine(RecorderEngine.EngineListener listener) {
        RecorderConfig cfg = RecorderConfig.from(k -> null);
        RecorderEngine.BrowserProvider provider = (c, ws, url) -> new BrowserInstanceFinder.BrowserHandle() {
            @Override public String wsUrl() { return "ws://fake"; }
            @Override public boolean own() { return true; }
            @Override public String fallbackReason() { return null; }
            @Override public void destroy() { }
        };
        return new RecorderEngine(cfg, listener, workspace, "ctrl-test", provider, () -> transport, "// init");
    }

    // ---- 装置 ----

    private static final class FakeHost implements RecorderController.Host {
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile boolean turnActive;
        volatile int generation;

        @Override public void appendSystemMessage(String text, Color color) { messages.add(text); }
        @Override public Window window() { return null; }
        @Override public boolean turnActive() { return turnActive; }
        @Override public int generation() { return generation; }
    }

    private final class TestController extends RecorderController {
        TestController(RecorderController.Host h, RecorderRuntime rt) { super(h, rt); }

        @Override
        protected RecorderStartDialog.Result showStartDialog() {
            return new RecorderStartDialog.Result(false, null, null, null, 0);
        }

        @Override
        protected RecorderResultDialog.Result showResultDialog(boolean busy, int requests, int samplers,
                Set<String> domains, long durationMs) {
            if (flipTurnActiveOnDialog) {
                host.turnActive = true;
            }
            return new RecorderResultDialog.Result(nextChoice);
        }

        @Override
        protected void showDeferredImportNotice(java.nio.file.Path recordingDir) {
            deferredNoticeShown = true;
        }
    }

    // ---- CDP 事件注入 helpers（照 RecorderEngineTest） ----

    private void attachPage(String sessionId) {
        transport.receive("{\"method\":\"Target.attachedToTarget\",\"sessionId\":\"B\",\"params\":{"
                + "\"sessionId\":\"" + sessionId + "\",\"targetInfo\":{\"type\":\"page\",\"url\":\"about:blank\"}}}");
        transport.receive("{\"method\":\"Runtime.executionContextCreated\",\"sessionId\":\""
                + sessionId + "\",\"params\":{\"context\":{\"id\":7,\"name\":\"__jaiRecWorld\","
                + "\"auxData\":{\"frameId\":\"F1\",\"isDefault\":false}}}}");
    }

    private void injectRequestResponsePair(String rid, String url) {
        transport.receive("{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"" + rid + "\",\"type\":\"XHR\","
                + "\"request\":{\"method\":\"GET\",\"url\":\"" + url + "\",\"headers\":{\"accept\":\"*/*\"}}}}");
        transport.receive("{\"method\":\"Network.responseReceived\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"" + rid + "\",\"type\":\"XHR\",\"response\":{\"status\":200,"
                + "\"url\":\"" + url + "\",\"headers\":{\"content-type\":\"application/json\"}}}}");
    }

    // ---- 断言 helpers ----

    private RecorderEngine engine() {
        return runtime.engine();
    }

    private String buttonText() throws Exception {
        StringBuilder sb = new StringBuilder();
        SwingUtilities.invokeAndWait(() -> sb.append(controller.button().getText()));
        return sb.toString();
    }

    private void clickButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> controller.button().doClick());
    }

    private boolean anyMessageContains(String fragment) {
        return host.messages.stream().anyMatch(m -> m.contains(fragment));
    }

    /** 开一场并推进到录制态：open（ARMED）→ 按钮点击 begin（RECORDING）→ 注入一条请求/响应。 */
    private void armAndBeginRecording() throws Exception {
        engine().open(new RecorderEngine.OpenParams(null, Set.of()));
        AwaitUtil.awaitUntil(() -> "⏺ START REC".equals(safeText()), "ARMED 按钮文案");
        attachPage("S1");
        clickButton(); // ARMED 分支 → engine::begin
        AwaitUtil.awaitUntil(() -> engine().state() == RecorderEngine.State.RECORDING, "RECORDING 状态");
        AwaitUtil.awaitUntil(() -> safeText().startsWith("■ STOP"), "RECORDING 按钮文案");
        injectRequestResponsePair("r1", "http://example.com/api/a");
    }

    private String safeText() {
        try {
            return buttonText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- 2.2 按钮三态与 EDT 同步 ----

    @Test
    void buttonCyclesThreeStatesWithLiveCountAndResetsAfterDiscard() throws Exception {
        armAndBeginRecording();
        AwaitUtil.awaitUntil(() -> safeText().startsWith("■ STOP 1"), "计数上屏（■ STOP 1）");

        nextChoice = RecorderResultDialog.Choice.DISCARD;
        clickButton(); // RECORDING 分支 → engine::stop → 收官
        AwaitUtil.awaitUntil(() -> "● REC".equals(safeText()), "收官后按钮复位 ● REC");
        AwaitUtil.awaitUntil(() -> anyMessageContains("已放弃导入"), "放弃提示行");
    }

    @Test
    void cancelledInArmedAppendsMessageAndResetsButton() throws Exception {
        // 按钮在 ARMED 态点击是 begin（三态循环如此）；取消走 stop（对齐 StopRecordingTool 的 ARMED 分支）
        RecorderEngine e = engine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        AwaitUtil.awaitUntil(() -> "⏺ START REC".equals(safeText()), "ARMED 按钮文案");
        e.stop();
        AwaitUtil.awaitUntil(() -> "● REC".equals(safeText()), "取消后按钮复位");
        AwaitUtil.awaitUntil(() -> anyMessageContains("已取消录制"), "取消提示行");
    }

    // ---- 4.1 busy 判定 ----

    @Test
    void insertCancelledWhenTurnBecomesActiveAfterDialogShown() throws Exception {
        armAndBeginRecording();
        nextChoice = RecorderResultDialog.Choice.INSERT;
        flipTurnActiveOnDialog = true; // 弹窗后、点击前出现树编辑回合
        engine().stop();
        AwaitUtil.awaitUntil(() -> anyMessageContains("插入已取消"), "busy 插入取消提示行");
        AwaitUtil.awaitUntil(() -> anyMessageContains("可用 import_recording 工具或稍后重试"),
                "保留数据指引");
    }

    @Test
    void insertFailureWithoutGuiAppendsReadableMessage() throws Exception {
        armAndBeginRecording();
        nextChoice = RecorderResultDialog.Choice.INSERT;
        engine().stop();
        AwaitUtil.awaitUntil(() -> anyMessageContains("插入测试计划失败"), "插入失败提示行");
        AwaitUtil.awaitUntil(() -> anyMessageContains("录制数据仍保留在"), "保留数据指引");
    }

    // ---- 4.4 收官提示行：延后弹出与另存 ----

    @Test
    void toolDrivenFinalizeWhileTurnActiveDefersThenSavesJmx() throws Exception {
        host.turnActive = true; // 工具驱动停录时回合仍在跑
        armAndBeginRecording();
        RecorderEngine e = engine();
        e.stop(); // 工具路径直停，不经按钮
        AwaitUtil.awaitUntil(() -> anyMessageContains("回合结束后弹出产物落点窗"), "延后弹窗提示行");
        AwaitUtil.awaitUntil(() -> deferredNoticeShown, "收官即时告知窗（防回合消息冲掉）");

        host.turnActive = false;
        nextChoice = RecorderResultDialog.Choice.SAVE;
        SwingUtilities.invokeAndWait(controller::onTurnTerminal);
        // 套件负载下首次 SaveService 序列化 + schema 装配可能超 AwaitUtil 的 10s，放宽到 25s
        try {
            awaitUntil(25000, () -> anyMessageContains("录制骨架已另存"), "另存提示行");
        } catch (AssertionError err) {
            throw new AssertionError("另存提示行未到；实际消息=" + host.messages, err);
        }
        assertTrue(findRecordedJmx().isPresent(), "workspace 下应存在 recording-*.jmx");
    }

    @Test
    void lateFinalizedEventAfterGenerationFlipIsIgnored() throws Exception {
        armAndBeginRecording();
        RecorderEngine e = engine();
        host.generation = 7; // /new 之后迟到的收官消息不渗入
        e.stop();
        // 终态判定须钉住引擎引用：runtime.engine() 在终态后换新实例（IDLE），轮询新实例永不成立
        AwaitUtil.awaitUntil(() -> e.state() == RecorderEngine.State.DONE, "DONE 终态");
        nextChoice = RecorderResultDialog.Choice.SAVE;
        SwingUtilities.invokeAndWait(controller::onTurnTerminal);
        Thread.sleep(300); // 留出误触发窗口
        assertFalse(anyMessageContains("另存"), "迟到收官不得触发另存");
        assertFalse(anyMessageContains("插入"), "迟到收官不得触发插入");
        assertFalse(anyMessageContains("放弃"), "迟到收官不得触发放弃");
    }

    private static void awaitUntil(long timeoutMs, BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    private Optional<Path> findRecordedJmx() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(workspace, "recording-*.jmx")) {
            for (Path p : ds) {
                return Optional.of(p);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}
