package org.gitee.jmeter.ai.selection;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Close;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.action.ExitCommand;
import org.apache.jmeter.gui.action.Load;
import org.apache.jmeter.gui.action.LoadRecentProject;
import org.apache.jmeter.gui.action.Save;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
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
            // 生成每实例 session key(= instanceId),供 AiChatPanel / IpcServer / 关闭整合 / 委派共用。
            org.gitee.jmeter.ai.instance.InstanceContext.init();
            SelectionTracker.install();
            registerRunCaptureListeners();
            registerCloseConsolidationHooks();
            // 一次性 best-effort 归档遗留全局会话到共享 HISTORY.md(含文件 IO,放后台线程)。
            Thread migrator = new Thread(
                    org.gitee.jmeter.ai.instance.LegacySessionMigrator::migrate, "legacy-migrate");
            migrator.setDaemon(true);
            migrator.start();
            // 一次性 best-effort 回收失活且超 TTL 的孤立会话文件(含端口文件探活 IO,放后台线程)。
            if (org.gitee.jmeter.ai.utils.AiConfig.isSessionPerInstance()) {
                Thread reaper = new Thread(
                        SelectionInitCommand::reapOrphanSessions, "session-reap");
                reaper.setDaemon(true);
                reaper.start();
            }
            if (org.gitee.jmeter.ai.utils.AiConfig.isIpcEnabled()) {
                // jmxPath 维护(打开/保存/关闭/新建时原子写回本实例端口文件),仅 IPC 开启时有端口文件可写。
                registerJmxPathListeners();
                // IPC start() 含 bind + 写端口文件等 IO,放普通线程避免短暂卡 EDT
                // (SelectionTracker.install 必须留在 EDT 装监听器)。
                Thread t = new Thread(() -> {
                    try {
                        org.gitee.jmeter.ai.ipc.IpcServer.getInstance().start();
                        // 端口文件已写入后,补抓一次当前已加载计划(如 jmeter -t xxx.jmx 启动),
                        // 使闲置实例也广播其 jmxPath。读 GuiPackage 必须在 EDT。
                        EventQueue.invokeLater(SelectionInitCommand::syncJmxPathNow);
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

    /**
     * 关闭期记忆整合接线(两条退出路径):
     * <ul>
     *   <li>主路径(EDT):{@code ExitCommand} 前置动作监听 → 关闭整合对话框(询问深度提炼)。
     *       深度提炼只写 MEMORY.md,不推进会话索引,避免随后 JMeter 自身"未保存改动"框被取消时
     *       裁剪当前会话上下文。</li>
     *   <li>兜底(JVM shutdown hook):非用户退出(崩溃/Restart/{@code System.exit})仅静默归档
     *       HISTORY.md(无对话框、无 LLM、不读 GuiPackage);幂等守卫使其在用户已走主路径时 no-op。</li>
     * </ul>
     */
    private static void registerCloseConsolidationHooks() {
        try {
            ActionRouter router = ActionRouter.getInstance();
            router.addPreActionListener(ExitCommand.class, e -> {
                try {
                    // 前置监听在 EDT 上、doAction 之前同步执行(ExitCommand 随后才 CHECK_DIRTY + System.exit)
                    org.gitee.jmeter.ai.gui.CloseConsolidationDialog.handleExit();
                } catch (Throwable t) {
                    log.error("Close-consolidation dialog failed (best-effort): {}", t.toString());
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(
                    org.gitee.jmeter.ai.agent.memory.CloseConsolidationCoordinator::archiveSilently,
                    "ai-close-consolidation"));
            log.info("Close-consolidation hooks registered (ExitCommand pre-listener + shutdown hook)");
        } catch (Throwable t) {
            log.error("Failed to register close-consolidation hooks", t);
        }
    }

    /**
     * 注册 jmxPath 维护监听:对 {@code Load}/{@code LoadRecentProject}/{@code Save}/{@code Close}
     * 类 {@code Command} 挂 post-action 监听(EDT,doAction 之后触发),把当前计划文件路径原子写回
     * 本实例 {@code port-{pid}.json}。{@code Close} 同时覆盖 File→New(经 {@code clearTestPlan} 把路径置 null)。
     *
     * <p>读 {@link GuiPackage#getTestPlanFile()} 是自校正的:Close 被用户在"未保存改动"框取消时,
     * doAction 不清计划,post 监听读到的仍是原路径(恰为真实状态)。无计划返回 null→写空串。
     */
    private static void registerJmxPathListeners() {
        try {
            ActionListener sync = e -> syncJmxPathNow();
            ActionRouter router = ActionRouter.getInstance();
            router.addPostActionListener(Load.class, sync);
            router.addPostActionListener(LoadRecentProject.class, sync);
            router.addPostActionListener(Save.class, sync);
            router.addPostActionListener(Close.class, sync);
            log.info("jmxPath sync listeners registered (Load/LoadRecentProject/Save/Close)");
        } catch (Throwable t) {
            log.error("Failed to register jmxPath listeners", t);
        }
    }

    /**
     * 读当前计划文件路径并原子写回本实例端口文件的 {@code jmxPath}(无计划写空)。best-effort,异常不抛。
     * 须在 EDT 调用(读 {@link GuiPackage})。端口文件不存在(IPC 未就绪)时 {@code updateJmxPath} 返回 false。
     */
    private static void syncJmxPathNow() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp == null) {
                return;
            }
            String file = gp.getTestPlanFile();
            File ipcDir = InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()));
            InstanceRegistry.updateJmxPath(ipcDir, InstanceRegistry.currentPid(), file == null ? "" : file);
        } catch (Throwable t) {
            log.error("jmxPath sync failed (best-effort): {}", t.toString());
        }
    }

    /**
     * 启动期 best-effort 回收失活且超 TTL 的孤立每实例会话文件。读 {@code sessions/} 目录与
     * {@code ipc/} 端口文件探活,全在后台线程执行(不触 EDT)。当前实例的会话恒不回收。
     */
    private static void reapOrphanSessions() {
        try {
            java.nio.file.Path sessionsDir = org.gitee.jmeter.ai.utils.WorkspacePaths
                    .resolveWorkspace().resolve("sessions");
            File ipcDir = InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()));
            long ttlMs = org.gitee.jmeter.ai.utils.AiConfig.getSessionReapTtlMs();
            org.gitee.jmeter.ai.instance.SessionReaper.reap(
                    sessionsDir, ipcDir, ttlMs, org.gitee.jmeter.ai.instance.InstanceContext.instanceId());
        } catch (Throwable t) {
            log.error("Session reap failed (best-effort): {}", t.toString());
        }
    }
}
