package org.gitee.jmeter.ai.selection;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.action.Save;
import org.apache.jmeter.gui.action.Start;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceLoader 入口。JMeter 启动时通过 META-INF/services 自动发现本类。
 *
 * <p>仅响应 {@link ActionNames#ADD_ALL}（GUI 初始化完成事件，此时 MainFrame 已 setVisible），
 * 借此作为安全时机调用 {@link SelectionTracker#install()} 注册全局 L1/L2 监听器。
 *
 * <p>用 AtomicBoolean 防止用户重复加载测试计划时多次 install 导致监听器叠加。
 */
public class SelectionInitCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(SelectionInitCommand.class);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    public SelectionInitCommand() {
        // 构造时打日志，证明 ServiceLoader 已经发现并实例化本类
        log.info("SelectionInitCommand instantiated by JMeter ServiceLoader");
    }

    @Override
    public void doAction(ActionEvent e) {
        log.info("SelectionInitCommand.doAction: actionCommand={}", e.getActionCommand());
        if (!ActionNames.ADD_ALL.equals(e.getActionCommand())) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            log.info("SelectionInitCommand: already installed, skipping");
            return;
        }
        log.info("SelectionInitCommand received ADD_ALL, scheduling SelectionTracker.install()");
        EventQueue.invokeLater(() -> {
            SelectionTracker.install();
            registerRunCaptureListeners();
            if (org.gitee.jmeter.ai.utils.AiConfig.isIpcEnabled()) {
                // IPC start() 含 bind + 写端口文件等 IO,放普通线程避免短暂卡 EDT
                // (SelectionTracker.install 必须留在 EDT 装监听器)。
                Thread t = new Thread(() -> {
                    try {
                        org.gitee.jmeter.ai.ipc.IpcServer.getInstance().start();
                    } catch (Exception ex) {
                        log.error("IPC server failed to start", ex);
                    }
                }, "ipc-start");
                t.setDaemon(true);
                t.start();
            }
        });
    }

    /**
     * Register run-capture pre-action listeners on the ActionRouter. Runs once (the ADD_ALL
     * handler is CAS-guarded). The {@code Save.class} listener strips the collector node
     * before any save (anti-jmx-leak, always on); the {@code Start.class} listener injects
     * the collector before GUI-initiated runs and is gated by {@code agent.runcapture.enabled}.
     */
    private static void registerRunCaptureListeners() {
        try {
            ActionRouter router = ActionRouter.getInstance();
            // Anti-leak + save-before-run correctness, UNCONDITIONAL (independent of the
            // capture toggle): JMeter's Start.doAction fires SAVE via popupShouldSave BEFORE
            // startEngine clones the tree, so strip (clean .jmx) on Save PRE, then re-inject
            // on Save POST if a start armed it (USER via onTestStartAction, AGENT via
            // RunTestTool.armForStartReinject). Start POST disarms after startEngine clones.
            router.addPreActionListener(Save.class, AgentResultCollector::stripCollectorNode);
            router.addPostActionListener(Save.class, AgentResultCollector::reinjectIfArmed);
            router.addPostActionListener(Start.class, AgentResultCollector::clearStartArmed);
            if (org.gitee.jmeter.ai.utils.AiConfig.isRunCaptureEnabled()) {
                router.addPreActionListener(Start.class, AgentResultCollector::onTestStartAction);
            }
            log.info("Run-capture listeners registered (capture.enabled={})",
                    org.gitee.jmeter.ai.utils.AiConfig.isRunCaptureEnabled());
        } catch (Throwable t) {
            log.error("Failed to register run-capture listeners", t);
        }
    }

    @Override
    public Set<String> getActionNames() {
        return Collections.singleton(ActionNames.ADD_ALL);
    }
}
