package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.gitee.jmeter.ai.recorder.RecorderEngine;
import org.gitee.jmeter.ai.recorder.RecorderRuntime;
import org.gitee.jmeter.ai.recorder.convert.JmeterElementBridge;
import org.gitee.jmeter.ai.recorder.convert.RecordingModel;
import org.gitee.jmeter.ai.recorder.convert.SkeletonConverter;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Window;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 聊天面板的浏览器录制编排（从 AiChatPanel 抽出的独立控制器，行为不变）：
 * ●REC 三态按钮、引擎事件接线、启动参数窗、产物落点窗、收官转换与延后导入。
 *
 * <p>面板只负责把 {@link #button()} 放进输入卡并转发回合终态事件；所有面板服务
 * （转录行、Owner 窗口、活跃回合判定、渲染代数）经 {@link Host} 注入，控制器不直接依赖面板。
 */
public class RecorderController {

    /** 面板侧服务。 */
    public interface Host {
        void appendSystemMessage(String text, Color color);

        Window window();

        /** 是否有活跃 Agent 回合（产物窗插入禁用与延后弹出的判据）。 */
        boolean turnActive();

        /** 当前会话渲染代数（/new 之后迟到的录制消息不渗入新会话）。 */
        int generation();
    }

    private static final int COUNT_TICK_MS = 500;
    /** ASCII spinner frames（同 ThinkingCard：braille 帧字体覆盖不稳）。 */
    private static final String[] BUSY_FRAMES = {"|", "/", "-", "\\"};
    private static final int BUSY_TICK_MS = 120;
    private static final DateTimeFormatter JMX_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Host host;
    private final RecorderRuntime runtime;
    private final QuietButton button;
    private Timer countTimer;
    private Timer busyTimer;
    private int busyFrame;
    private String busySuffix = "";
    private JmeterElementBridge bridge;
    private RecorderEngine.Status pendingImport;
    private int sessionGeneration = -1;

    public RecorderController(Host host) {
        this(host, RecorderRuntime.get());
    }

    /** 单测注入独立运行时（fake 引擎经 {@link RecorderRuntime#forTest}）。 */
    RecorderController(Host host, RecorderRuntime runtime) {
        this.host = host;
        this.runtime = runtime;
        this.button = new QuietButton("● REC");
        button.setToolTipText("浏览器录制：记录人工操作期间的业务接口请求并转为 JMeter 脚本骨架");
        button.addActionListener(e -> onButtonClicked());
        button.setVisible(RecorderConfig.isEnabled());
        installEngineListener();
    }

    /** 供面板布局（输入卡按钮行）。 */
    public JButton button() {
        return button;
    }

    /** 面板构造末尾调用：按引擎真实状态水合按钮（不创建引擎）。 */
    public void hydrate() {
        if (RecorderConfig.isEnabled()) {
            updateButton(null);
        }
    }

    /** 回合任一终态（完成/取消）后调用：冲刷延后的产物落点窗。 */
    public void onTurnTerminal() {
        RecorderEngine.Status pending = pendingImport;
        if (pending != null) {
            pendingImport = null;
            importResult(pending);
        }
    }

    // ---- 引擎事件接线（回调线程不保证 → 自投 EDT） ----

    private void installEngineListener() {
        runtime.setUiListener(new RecorderEngine.EngineListener() {
            @Override
            public void onState(RecorderEngine.State from, RecorderEngine.State to) {
                SwingUtilities.invokeLater(() -> {
                    if (to == RecorderEngine.State.ARMED) {
                        sessionGeneration = host.generation();
                    }
                    updateButton(to);
                });
            }

            @Override
            public void onFinalized(RecorderEngine.Status status) {
                SwingUtilities.invokeLater(() -> {
                    updateButton(RecorderEngine.State.DONE);
                    if (sessionGeneration >= 0 && sessionGeneration != host.generation()) {
                        return; // /new 之后的迟到完成消息：不渗入新会话
                    }
                    if (host.turnActive()) {
                        // 工具驱动停录时回合仍在跑：延后到回合终态再弹窗。
                        // 转录行会被回合消息冲掉——收官即弹仅含确认按钮的告知窗兜底可见性
                        pendingImport = status;
                        host.appendSystemMessage("录制已收官，回合结束后弹出产物落点窗…",
                                org.gitee.jmeter.ai.gui.theme.ThemeColors.secondaryText());
                        showDeferredImportNotice(status.dir());
                    } else {
                        importResult(status);
                    }
                });
            }

            @Override
            public void onCancelled(String reason) {
                SwingUtilities.invokeLater(() -> {
                    updateButton(RecorderEngine.State.CANCELLED);
                    if (sessionGeneration >= 0 && sessionGeneration != host.generation()) {
                        return;
                    }
                    host.appendSystemMessage("已取消录制（" + reason + "）",
                            org.gitee.jmeter.ai.gui.theme.ThemeColors.secondaryText());
                });
            }
        });
    }

    // ---- 按钮状态机 ----

    private void onButtonClicked() {
        RecorderEngine engine = runtime.engine();
        switch (engine.state()) {
            case IDLE -> promptAndOpen();
            case ARMED -> runAction(engine::begin); // 推送涉及 WS 写，走后台
            case RECORDING -> runAction(engine::stop);
            default -> {
                // FINALIZING 瞬态：忽略连点
            }
        }
    }

    /** 启动参数窗（单测可替换，防模态阻塞）。 */
    protected RecorderStartDialog.Result showStartDialog() {
        return RecorderStartDialog.show(host.window());
    }

    /** IDLE：参数窗 → 后台打开浏览器。 */
    private void promptAndOpen() {
        RecorderStartDialog.Result r = showStartDialog();
        if (!r.confirmed()) {
            updateButton(null);
            return;
        }
        RecorderEngine engine = runtime.engine();
        runAction(() -> engine.open(new RecorderEngine.OpenParams(
                r.url(), r.domains(), r.resourceTypes(), r.maxBodyBytes())));
    }

    private interface RecorderAction {
        void run() throws Exception;
    }

    /** 阻塞动作（open/stop 含浏览器启动与收官宽限）走后台线程，失败回聊天区。 */
    private void runAction(RecorderAction action) {
        busy();
        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    action.run();
                } catch (Exception e) {
                    error = e;
                }
                return null;
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                updateButton(null);
                if (error != null) {
                    host.appendSystemMessage("录制操作失败：" + error.getMessage(),
                            org.gitee.jmeter.ai.gui.theme.ThemeColors.error());
                }
            }
        }.execute();
    }

    /** 过渡态（open/stop 阻塞动作进行中）：禁用按钮并播放字符 spinner。 */
    private void busy() {
        button.setEnabled(false);
        startBusyTimer("");
    }

    private void updateButton(RecorderEngine.State forced) {
        if (button == null) {
            return;
        }
        RecorderEngine.State st = forced != null ? forced : runtime.peekState();
        switch (st) {
            case IDLE, DONE, CANCELLED -> {
                stopCountTimer();
                stopBusyTimer();
                button.setText("● REC");
                button.setDanger(false);
            }
            case ARMED -> {
                stopCountTimer();
                stopBusyTimer();
                button.setText("⏺ START REC");
                button.setDanger(false);
            }
            case RECORDING -> {
                stopBusyTimer();
                button.setDanger(true);
                if (countTimer == null) {
                    // 无锁计数快照：避免 500ms 定时器在 EDT 上取收官锁段（停止期 GUI 冻结）
                    countTimer = new Timer(COUNT_TICK_MS, e ->
                            button.setText("■ STOP " + runtime.requestCountSnapshot()));
                }
                countTimer.start();
            }
            case FINALIZING -> {
                button.setDanger(false);
                startBusyTimer(" 收割");
            }
            default -> {
            }
        }
    }

    /** 过渡 spinner：帧字符（可带「 收割」类后缀）循环刷按钮文案，与计数定时器互斥。 */
    private void startBusyTimer(String suffix) {
        stopCountTimer();
        busySuffix = suffix;
        busyFrame = 0;
        if (busyTimer == null) {
            busyTimer = new Timer(BUSY_TICK_MS, e -> {
                busyFrame = (busyFrame + 1) % BUSY_FRAMES.length;
                button.setText(BUSY_FRAMES[busyFrame] + busySuffix);
            });
        }
        button.setText(BUSY_FRAMES[busyFrame] + suffix);
        busyTimer.start();
    }

    private void stopBusyTimer() {
        if (busyTimer != null) {
            busyTimer.stop();
        }
    }

    private void stopCountTimer() {
        if (countTimer != null) {
            countTimer.stop();
        }
    }

    // ---- 产物落点 ----

    /** 收官导入：后台确定性转换 → EDT 弹产物落点窗三选一。 */
    private void importResult(RecorderEngine.Status status) {
        new SwingWorker<SkeletonConverter.ConversionResult, Void>() {
            @Override
            protected SkeletonConverter.ConversionResult doInBackground() throws Exception {
                bridge(); // schema 装配放后台（不占 EDT）
                return new SkeletonConverter().convert(RecordingModel.load(status.dir()));
            }

            @Override
            protected void done() {
                SkeletonConverter.ConversionResult r;
                try {
                    r = get();
                } catch (Exception ex) {
                    host.appendSystemMessage("录制数据转换失败："
                                    + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()),
                            org.gitee.jmeter.ai.gui.theme.ThemeColors.error());
                    return;
                }
                RecorderResultDialog.Result choice = showResultDialog(
                        host.turnActive(),
                        r.requestCount(), r.samplerCount(), r.domains(),
                        Math.max(0, r.endedAt() - r.startedAt()));
                handleChoice(choice, r, status);
            }
        }.execute();
    }

    /** 产物落点窗（单测可替换，防模态阻塞）。 */
    protected RecorderResultDialog.Result showResultDialog(boolean busy, int requests, int samplers,
            java.util.Set<String> domains, long durationMs) {
        return RecorderResultDialog.show(host.window(), busy, requests, samplers, domains, durationMs);
    }

    /** 收官时回合仍在跑的延后告知窗：仅一个确认按钮（单测可替换，防模态阻塞）。 */
    protected void showDeferredImportNotice(java.nio.file.Path recordingDir) {
        javax.swing.JOptionPane.showMessageDialog(host.window(),
                "<html>录制已收官：Agent 回合正在运行，录制数据已保存在<br>"
                        + recordingDir + "<br>为避免和当前Agent回合冲突，暂时无法更新到当前测试计划。等回合结束后自动弹出产物落点窗，可稍后导入或改选另存。</html>",
                "录制完成", javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    private void handleChoice(RecorderResultDialog.Result choice,
                              SkeletonConverter.ConversionResult r,
                              RecorderEngine.Status status) {
        switch (choice.choice()) {
            case INSERT -> {
                if (host.turnActive()) {
                    // 弹窗后、点击前出现新的树编辑回合：复查并保留数据
                    host.appendSystemMessage("Agent 回合正在编辑测试计划，插入已取消。录制数据保留在 "
                                    + status.dir() + "，可用 import_recording 工具或稍后重试。",
                            org.gitee.jmeter.ai.gui.theme.ThemeColors.error());
                    return;
                }
                try {
                    bridge().insertIntoCurrentPlan(r.tree().get(0));
                    host.appendSystemMessage("已生成 " + r.samplerCount() + " 个采样器并插入测试计划（"
                                    + r.tree().get(0).name() + "）。需要关联/参数化/断言跟我说。",
                            org.gitee.jmeter.ai.gui.theme.ThemeColors.secondaryText());
                } catch (Exception e) {
                    host.appendSystemMessage("插入测试计划失败：" + e.getMessage()
                                    + "。录制数据仍保留在 " + status.dir(),
                            org.gitee.jmeter.ai.gui.theme.ThemeColors.error());
                }
            }
            case SAVE -> saveAsJmx(r, status);
            case DISCARD -> host.appendSystemMessage("已放弃导入，录制数据保留在 " + status.dir(),
                    org.gitee.jmeter.ai.gui.theme.ThemeColors.secondaryText());
            default -> {
            }
        }
    }

    private void saveAsJmx(SkeletonConverter.ConversionResult r, RecorderEngine.Status status) {
        try {
            String ts = LocalDateTime.now().format(JMX_TS);
            Path file = RecorderRuntime.uniquePath(runtime.workspace().resolve(
                    "recording-" + ts + "-" + runtime.instanceId() + ".jmx"));
            // SaveService 序列化是磁盘 IO：不占 EDT
            new SwingWorker<Path, Void>() {
                private Exception error;

                @Override
                protected Path doInBackground() {
                    try {
                        return bridge().saveAsJmx(r.tree(), file);
                    } catch (Exception e) {
                        error = e;
                        return null;
                    }
                }

                @Override
                protected void done() {
                    if (error != null) {
                        host.appendSystemMessage("另存 .jmx 失败：" + error.getMessage()
                                        + "。录制数据仍保留在 " + status.dir(),
                                org.gitee.jmeter.ai.gui.theme.ThemeColors.error());
                    } else {
                        try {
                            host.appendSystemMessage("录制骨架已另存 " + get()
                                            + "（可用 open_jmx_file 打开）。需要优化跟我说。",
                                    org.gitee.jmeter.ai.gui.theme.ThemeColors.secondaryText());
                        } catch (Exception ignored) {
                            // 不可能：doInBackground 已捕获
                        }
                    }
                }
            }.execute();
        } catch (Exception e) {
            host.appendSystemMessage("另存 .jmx 失败：" + e.getMessage()
                            + "。录制数据仍保留在 " + status.dir(),
                    org.gitee.jmeter.ai.gui.theme.ThemeColors.error());
        }
    }

    private JmeterElementBridge bridge() {
        if (bridge == null) {
            bridge = JmeterElementBridge.create();
        }
        return bridge;
    }
}
