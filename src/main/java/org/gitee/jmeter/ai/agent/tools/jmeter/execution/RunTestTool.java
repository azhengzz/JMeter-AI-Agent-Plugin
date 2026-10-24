package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool to start, stop, or shutdown JMeter test execution.
 * Injects AgentResultCollector into the GUI tree before starting
 * so results are captured for the AI agent.
 */
public class RunTestTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RunTestTool.class);

    /** 冒烟为 1 线程 × 1 迭代的最小运行，阻塞等待其完成以直接给出 PASS/FAIL 判定。 */
    /**
     * 冒烟完成等待上限。不是证据天花板（门禁点仲裁会为超窗完成且通过的运行补章）
     * ——是控制权归还机制：永不完成的冒烟（黑洞端点/无限循环计划/引擎楔死）到达
     * 此上限即返回 "still running"，模型重获控制权去诊断、stop 引擎、修计划。
     */
    private static final long SMOKE_COMPLETION_TIMEOUT_MS = 180_000;
    private static final long SMOKE_POLL_INTERVAL_MS = 200;

    /** goal 计数/证据回写通道；null（直构测试）时 goal 接线整体旁路。 */
    private final org.gitee.jmeter.ai.agent.session.SessionManager sessionManager;

    public RunTestTool() {
        this(null);
    }

    public RunTestTool(org.gitee.jmeter.ai.agent.session.SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String getName() {
        return "run_test";
    }

    @Override
    public String getDescription() {
        return "Start, stop, or shutdown the current JMeter test plan. " +
                "Custom JMeter properties can be injected before starting. " +
                "When starting, a result collector is injected to capture sample data. " +
                "Starts are rejected if any listener writes to an existing output file " +
                "while resultcollector.action_if_file_exists is ASK (the JMeter default): " +
                "the modal dialog it triggers cannot be answered inside an agent run — " +
                "point listeners at new output files. " +
                "mode='smoke' runs a minimal-cost validation of ALL thread groups " +
                "(1 thread x 1 iteration each, assertions kept, timers skipped) without " +
                "modifying the saved thread group configuration. " +
                "Use get_test_status to check progress and get_test_results to view results after execution.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": ["start", "stop", "shutdown"],
                            "description": "Action to perform: 'start' begins execution, 'stop' forces immediate stop, 'shutdown' waits for current samples to finish",
                            "default": "start"
                        },
                        "mode": {
                            "type": "string",
                            "enum": ["normal", "smoke"],
                            "description": "Start mode: 'normal' runs the plan as configured; 'smoke' validates ALL thread groups at minimal cost (1 thread x 1 iteration, scheduler off, timers skipped, assertions kept). Only valid with action='start' (the ignore_timers flag is not applicable in smoke mode).",
                            "default": "normal"
                        },
                        "ignore_timers": {
                            "type": "boolean",
                            "description": "If true, skip timer delays during execution (useful for quick validation). Not applicable in smoke mode, which always skips timers.",
                            "default": false
                        },
                        "properties": {
                            "type": "object",
                            "description": "Optional JMeter properties to inject before starting, equivalent to command-line -J properties. Values can be strings, numbers, or booleans and are converted to strings.",
                            "additionalProperties": {
                                "type": ["string", "number", "boolean"]
                            }
                        }
                    }
                }
                """;
    }

    @Override
    public ValidationResult validateParameters(Map<String, Object> parameters) {
        String action = getStringParameter(parameters, "action", "start");
        String mode = getStringParameter(parameters, "mode", "normal");
        if (!"normal".equals(mode) && !"smoke".equals(mode)) {
            return ValidationResult.invalid("Parameter 'mode' must be 'normal' or 'smoke', got: " + mode);
        }
        if ("smoke".equals(mode) && !"start".equalsIgnoreCase(action)) {
            return ValidationResult.invalid("mode='smoke' is only valid with action='start'");
        }
        if (!"start".equalsIgnoreCase(action)) {
            return ValidationResult.valid();
        }

        Object propertiesValue = parameters.get("properties");
        if (propertiesValue == null) {
            return ValidationResult.valid();
        }
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return ValidationResult.invalid("Parameter 'properties' must be an object");
        }

        ValidationResult.Builder builder = ValidationResult.builder();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank()) {
                builder.addError("Property names in 'properties' must be non-blank strings");
                continue;
            }

            // JMeter 在 AbstractAction 类加载期冻结 resultcollector.action_if_file_exists，
            // 运行时注入对本回合无效，且注入的值会永久留在进程属性里——后续运行的
            // 文件人闸预检（实时读属性）将与 JMeter 冻结判定不同源：预检放行后
            // 弹三选一模态框，无人应答即卡死 EDT，必须在源头拒绝
            if ("resultcollector.action_if_file_exists".equals(name)) {
                builder.addError("Property 'resultcollector.action_if_file_exists' cannot be "
                        + "injected at runtime: JMeter freezes it at startup, so it has no "
                        + "effect on this or later runs. Point listeners at new output files, "
                        + "or ask the user to configure it in jmeter.properties/user.properties "
                        + "and restart JMeter.");
                continue;
            }

            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                builder.addError("Property '" + name + "' must be a string, number, or boolean");
            }
        }
        return builder.build();
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String action = getStringParameter(parameters, "action", "start");

        return switch (action.toLowerCase()) {
            case "start" -> doStart(parameters);
            case "stop" -> doStop();
            case "shutdown" -> doShutdown();
            default -> ToolResult.error("Unknown action: " + action + ". Use 'start', 'stop', or 'shutdown'.");
        };
    }

    private ToolResult doStart(Map<String, Object> parameters) {
        boolean smoke = "smoke".equals(getStringParameter(parameters, "mode", "normal"));
        Map<String, String> runProperties = normalizeRunProperties(
                (Map<?, ?>) parameters.get("properties"));

        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        // Check if test is already running
        if (JMeterContextService.getTestStartTime() > 0) {
            return ToolResult.error("A test is already running. Use action 'stop' or 'shutdown' to stop it first.");
        }

        // Check test plan has thread groups
        JMeterTreeNode root = (JMeterTreeNode) gui.getTreeModel().getRoot();
        if (!hasThreadGroups(root)) {
            return ToolResult.error("Test plan has no thread groups. Add at least one thread group before running.");
        }

        boolean ignoreTimers = getBooleanParameter(parameters, "ignore_timers", false);

        // Smoke mode: force the validation-mode scale explicitly — user-level
        // jmeter.properties may override JMeter's defaults, so set them per-run.
        // (Boundary: TreeClonerForValidation reads these as static finals at class
        // load, so the force only takes effect before the first validation run of
        // the process; JMeter's own defaults are 1/1/true anyway.)
        final java.util.Map<String, String> savedValidationProps = new java.util.LinkedHashMap<>();
        if (smoke) {
            for (String key : new String[]{
                    "testplan_validation.nb_threads_per_thread_group",
                    "testplan_validation.number_iterations",
                    "testplan_validation.ignore_timers"}) {
                savedValidationProps.put(key, org.apache.jmeter.util.JMeterUtils.getProperty(key));
            }
            runProperties.put("testplan_validation.nb_threads_per_thread_group", "1");
            runProperties.put("testplan_validation.number_iterations", "1");
            runProperties.put("testplan_validation.ignore_timers", "true");
        }

        // Reset and inject collector (tag this run as Agent-initiated; smoke 模式同时
        // 标记运行种类——门禁点证据仲裁只认冒烟运行的采集)
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT, smoke);
        AgentResultCollector collector = new AgentResultCollector();

        // 冒烟可能把未保存计划的保存目标重指到 autosave 快照——记录原值供 finally 恢复，
        // 否则用户后续 Ctrl+S/退出保存静默写入隐藏的时间戳文件而不再弹 Save As
        final String prevTestPlanFile = gui.getTestPlanFile();
        final AtomicBoolean autosaveApplied = new AtomicBoolean(false);

        // Execute tree injection and start on EDT
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            // VALIDATE_TG executes only the currently SELECTED thread groups; smoke mode
            // selects all of them for the dispatch and restores the user's selection after
            // (Start.doAction reads the tree synchronously, so restoring right after the
            // dispatch returns is safe).
            TreePath[] savedSelection = smoke
                    ? gui.getTreeListener().getJTree().getSelectionPaths() : null;
            try {
                ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(gui.getClass().getClassLoader());

                    // 文件存在人闸预检（smoke 与 normal 通用）：JMeter 的
                    // popupCheckExistingFileListener 会对「输出文件已存在的
                    // ResultCollector」按 action_if_file_exists 弹三选一模态框
                    // （AbstractAction 类加载期冻结该属性，运行时无法定向关闭），
                    // 自主回合无人应答即卡死 EDT，派发前直接拒跑并说明修法。
                    // 必须先于 runProperties 注入执行——本回合注入的值骗得过本
                    // 预检（实时读属性）却骗不过 JMeter 的冻结判定，先注入后
                    // 预检 = 放行后照旧弹窗
                    String fileGate = listenerFileExistsAsk(root);
                    if (fileGate != null) {
                        errorRef.set(new RuntimeException(fileGate));
                        return;
                    }

                    // Find TestPlan node (first child of root)
                    JMeterTreeNode testPlanNode = findTestPlanNode(root);
                    if (testPlanNode == null) {
                        errorRef.set(new RuntimeException("Could not find TestPlan node in tree"));
                        return;
                    }

                    // Remove any leftover collector from a previous run
                    AgentResultCollector.removeFromGuiTree();

                    // Inject collector into the tree
                    gui.getTreeModel().addComponent(collector, testPlanNode);
                    log.info("Injected AgentResultCollector into test plan tree");

                    runProperties.forEach(JMeterUtils::setProperty);
                    if (!runProperties.isEmpty()) {
                        log.info("Injected {} JMeter properties before test start", runProperties.size());
                    }

                    if (smoke) {
                        List<TreePath> tgPaths = new ArrayList<>();
                        collectThreadGroupPaths(root, tgPaths);
                        gui.getTreeListener().getJTree()
                                .setSelectionPaths(tgPaths.toArray(new TreePath[0]));
                    }

                    // 未保存计划会让 Start 的 popupShouldSave 弹 YES/NO 模态框——
                    // 同族自主回合人闸（smoke 与 normal 同险）；先指到 autosave
                    // 快照走静默 shouldSaveBeforeRun 分支。预设失败还硬派发会弹出
                    // 无人应答的 should_save 模态框卡死 EDT——响亮拒绝
                    String autosaveGate = pointUnsavedPlanAtAutosave(
                            gui, smoke ? "goal-smoke-" : "agent-run-", autosaveApplied);
                    if (autosaveGate != null) {
                        errorRef.set(new RuntimeException(autosaveGate));
                        return;
                    }

                    // Trigger start via ActionRouter. doActionNow (not actionPerformed):
                    // actionPerformed only invokeLaters performAction, so the finally
                    // below would restore the user's tree selection BEFORE Start.doAction
                    // reads getSelectedNodes() — a smoke run would then validate whatever
                    // the user/last-tool happened to select, not all thread groups.
                    // doActionNow runs pre-listeners → Start.doAction → post-listeners
                    // synchronously on this EDT dispatch, inside the all-TG selection.
                    String actionName = smoke
                            ? ActionNames.VALIDATE_TG
                            : (ignoreTimers ? ActionNames.ACTION_START_NO_TIMERS : ActionNames.ACTION_START);
                    // Arm re-injection: Start.doAction may synchronously fire SAVE
                    // (popupShouldSave) which strips our collector for a clean .jmx; the Save
                    // POST-listener restores it before startEngine clones the tree.
                    AgentResultCollector.armForStartReinject();
                    ActionRouter.getInstance().doActionNow(
                            new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionName));
                    log.info("Triggered test start action: {}", actionName);

                } finally {
                    Thread.currentThread().setContextClassLoader(originalCl);
                }
            } catch (Exception e) {
                errorRef.set(e);
            } finally {
                if (autosaveApplied.get()) {
                    // 同步 SAVE 已在 doActionNow 内完成，autosave 文件已落盘——这里只
                    // 解除持久关联，恢复「未保存」语义（setTestPlanFile(null) 是 JMeter
                    // 核心清关联的正规方式）
                    gui.setTestPlanFile(prevTestPlanFile);
                    // 启动内部的 SAVE 触发 Save post-listener 已把 autosave 路径写进
                    // 本实例端口文件——恢复原关联后必须回写一次，否则对端的
                    // list_instances / 按 jmx 委派一直解析到废弃的时间戳快照
                    // （syncJmxPathNow 自守卫：IPC 关闭/端口文件不存在时 no-op）
                    org.gitee.jmeter.ai.selection.SelectionInitCommand.syncJmxPathNow();
                }
                if (smoke) {
                    gui.getTreeListener().getJTree().setSelectionPaths(savedSelection);
                    // restore pre-smoke testplan_validation.* values (unset → remove)
                    savedValidationProps.forEach((key, oldValue) -> {
                        if (oldValue == null) {
                            org.apache.jmeter.util.JMeterUtils.getJMeterProperties().remove(key);
                        } else {
                            org.apache.jmeter.util.JMeterUtils.setProperty(key, oldValue);
                        }
                    });
                }
                latch.countDown();
            }
        });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                return ToolResult.error("Timed out waiting for test start on EDT");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while starting test");
        }

        if (errorRef.get() != null) {
            return ToolResult.error("Failed to start test: " + errorRef.get().getMessage());
        }

        // Wait for engine to confirm start
        boolean started = false;
        for (int i = 0; i < 100; i++) {
            if (JMeterContextService.getTestStartTime() > 0) {
                started = true;
                break;
            }
            // 超短冒烟可能在轮询读到 testStartTime 前就跑完（endTest 会将其清零）：
            // 采集器已记录到「结束且不在跑」同样视为已启动，交由 finishSmokeRun 判定；
            // 真未启动（引擎从未跑）采集器无记录，超时报错路径不变
            if (AgentResultCollector.getTestEndTimeMs() > 0
                    && !AgentResultCollector.isTestRunning()) {
                started = true;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!started) {
            return ToolResult.error("Test did not start within expected time. Check JMeter logs for errors.");
        }

        int totalThreads = JMeterContextService.getTotalThreads();
        if (smoke) {
            if (AgentResultCollector.getLastProvenance() != AgentResultCollector.RunProvenance.AGENT) {
                // guard→派发窗口内用户自己的 GUI 运行抢占了引擎（本冒烟被 Start 拒绝，
                // 采集器已被用户运行 reset(USER) 接管）：采集到的是用户运行——绝不能
                // 当作冒烟判定/证据，也不消耗冒烟预算
                return ToolResult.error("A user-initiated run took over the test engine before "
                        + "this smoke could start; the smoke was NOT executed and consumed no "
                        + "smoke budget. Wait for the user's run to finish, then retry "
                        + "run_test mode=smoke.");
            }
            return finishSmokeRun(totalThreads);
        }
        return ToolResult.success(String.format(
                "Test started successfully.\n- Total threads: %d\n- Ignore timers: %b\n- Injected properties: %d\n" +
                "Use get_test_status to monitor progress and get_test_results to view results.",
                totalThreads, ignoreTimers, runProperties.size()));
    }

    /**
     * Smoke tail: count the attempt against the active goal, block until the minimal
     * run completes (1 thread x 1 iteration finishes quickly), then emit a machine
     * PASS/FAIL verdict from the captured error count and record the verification
     * evidence on the goal.
     */
    private ToolResult finishSmokeRun(int totalThreads) {
        countSmokeAttempt();
        boolean completed = false;
        long deadline = System.currentTimeMillis() + SMOKE_COMPLETION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (AgentResultCollector.getTestEndTimeMs() > 0
                    && !AgentResultCollector.isTestRunning()) {
                completed = true;
                break;
            }
            try {
                Thread.sleep(SMOKE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Smoke run interrupted before completion. "
                        + "Use get_test_status / get_test_results to inspect partial results.");
            }
        }
        if (!completed) {
            // inconclusive latest run: stale smoke-PASS evidence must not survive
            recordSmokeEvidence(false);
            return ToolResult.success(String.format(
                    "Smoke validation still running after %d ms (all thread groups, 1 thread x 1 iteration). "
                            + "Use get_test_status to wait for completion and get_test_results for the verdict.",
                    SMOKE_COMPLETION_TIMEOUT_MS));
        }

        AgentResultCollector.SummaryStats summary = AgentResultCollector.getSummary();
        boolean pass = isSmokePass(summary);
        recordSmokeEvidence(pass);
        if (summary.totalSamples == 0) {
            // 零样本 = 什么都没验证（禁用采样器/控制器条件为假/空线程组），不得记
            // PASS 解锁 goal 完成；recordSmokeEvidence(false) 已清掉可能的陈旧 PASS 证据
            return ToolResult.success("Smoke validation completed: FAIL (zero samples executed — "
                    + "the plan sent no traffic; check for disabled samplers, false controller "
                    + "conditions, or empty thread groups). Nothing was verified, so this run "
                    + "cannot count as smoke evidence. Enable or add samplers, then re-run.");
        }
        return ToolResult.success(String.format(
                "Smoke validation completed: %s\n- Thread groups: %d, samples: %d, errors: %d"
                        + "%n- Mode: all thread groups, 1 thread x 1 iteration each (scheduler off, "
                        + "timers skipped, assertions kept)%n%s",
                pass ? "PASS (zero errors)" : "FAIL",
                totalThreads, summary.totalSamples, summary.totalErrors,
                pass ? "Hard standard met for the smoke leg. Call update_goal action='complete' "
                        + "once the user's acceptance criteria are also satisfied."
                        : "Inspect failures with get_test_results (include_details=true, "
                        + "status_filter=failure) — assertion failure messages are included — "
                        + "then fix and re-run the smoke."));
    }

    /** 冒烟硬标准：零 error 且确实发过流量——零样本运行什么都没验证，不得记 PASS。 */
    static boolean isSmokePass(AgentResultCollector.SummaryStats summary) {
        return summary.totalSamples > 0 && summary.totalErrors == 0;
    }

    /**
     * 运行文件存在人闸预检（smoke 与 normal 通用）：树里存在「输出文件已存在的
     * ResultCollector」且生效动作非交互安全（resultcollector.action_if_file_exists
     * 非 APPEND/DELETE，默认 ASK）时返回拒跑原因；安全返回 null。判定输入与 JMeter
     * {@code AbstractAction.popupCheckExistingFileListener} 同源（本插件注入的
     * AgentResultCollector 不是 ResultCollector 子类，不会命中本扫描）。
     * 调用方必须在本回合 runProperties 注入之前调用——该属性被 JMeter 类加载期
     * 冻结，注入只骗得过本预检（实时读属性）而骗不过弹窗判定。
     */
    private static String listenerFileExistsAsk(JMeterTreeNode root) {
        String action = org.apache.jmeter.util.JMeterUtils.getPropDefault(
                "resultcollector.action_if_file_exists", "ASK");
        if ("APPEND".equals(action) || "DELETE".equals(action)) {
            return null; // 用户显式配置的非交互语义，交给 JMeter 原样执行
        }
        List<JMeterTreeNode> collectors = new ArrayList<>();
        collectNodesByClass(root, org.apache.jmeter.reporters.ResultCollector.class, collectors);
        for (JMeterTreeNode node : collectors) {
            String filename = node.getTestElement().getPropertyAsString("filename");
            if (filename != null && !filename.isBlank()
                    && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(filename))) {
                return "Listener '" + node.getName() + "' writes to the existing file '"
                        + filename + "', and resultcollector.action_if_file_exists=" + action
                        + " would show a modal dialog during this agent-initiated run that "
                        + "nobody can answer. Point the listener at a new output file, then "
                        + "retry run_test. (Alternatively set the property to APPEND/DELETE "
                        + "in jmeter.properties/user.properties — JMeter freezes it at "
                        + "startup, so it only takes effect after a restart and cannot be "
                        + "injected via run_test properties.)";
            }
        }
        return null;
    }

    /**
     * 未保存计划的 autosave 预设（smoke 与 normal 通用）：把保存目标指到工作空间
     * autosave 快照，使 Start 的 popupShouldSave 走静默 shouldSaveBeforeRun 分支而非
     * YES/NO 模态框（自主回合无人应答会卡死 EDT；doActionNow SAVE 写文件无交互，
     * 采集器节点被 Save 前置监听器剥离、后置监听器回注）。已有关联文件时 no-op；
     * 预设成功置位 {@code applied} 并返回 null；失败返回拒跑原因。
     */
    private static String pointUnsavedPlanAtAutosave(
            GuiPackage gui, String filePrefix, AtomicBoolean applied) {
        if (gui.getTestPlanFile() != null && !gui.getTestPlanFile().isEmpty()) {
            return null;
        }
        try {
            java.nio.file.Path autosaveDir =
                    org.gitee.jmeter.ai.utils.WorkspacePaths.resolveWorkspace()
                            .resolve(org.gitee.jmeter.ai.agent.snapshot.SnapshotNames.AUTOSAVE_DIR);
            java.nio.file.Files.createDirectories(autosaveDir);
            // 文件名经 SnapshotNames 纯函数生成（含 instanceId）：双实例共享 workspace
            // 目录时同毫秒不撞名
            gui.setTestPlanFile(autosaveDir.resolve(
                    org.gitee.jmeter.ai.agent.snapshot.SnapshotNames.runAutosaveFileName(
                            filePrefix,
                            org.gitee.jmeter.ai.instance.InstanceContext.instanceId(),
                            System.currentTimeMillis())).toString());
            applied.set(true);
            log.info("Test start: unsaved plan pointed at autosave path {}",
                    gui.getTestPlanFile());
            return null;
        } catch (Exception ex) {
            return "Cannot start on an unsaved plan: could not prepare the autosave path ("
                    + ex.getMessage() + "). Save the plan manually, then retry run_test.";
        }
    }

    private static void collectNodesByClass(JMeterTreeNode node, Class<?> target,
                                             List<JMeterTreeNode> out) {
        if (target.isInstance(node.getTestElement())) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodesByClass((JMeterTreeNode) node.getChildAt(i), target, out);
        }
    }

    /** Count this smoke attempt on the active goal (no-op without an active goal). */
    private void countSmokeAttempt() {
        if (sessionManager == null) {
            return;
        }
        org.gitee.jmeter.ai.agent.run.AgentRunContext runContext =
                org.gitee.jmeter.ai.agent.run.AgentRunContext.current();
        if (runContext == null) {
            return;
        }
        if (runContext.getSessionKey().startsWith(
                org.gitee.jmeter.ai.agent.run.AgentRunSpec.SUBAGENT_SESSION_PREFIX)) {
            return; // subagent keys must not grow stray session files here
        }
        if (org.gitee.jmeter.ai.instance.DelegationGuard.isActive()) {
            return; // 委派回合不写宿主 goal 的冒烟预算（goal 归宿主会话）
        }
        try {
            var session = sessionManager.get(runContext.getSessionKey());
            if (session == null) {
                return;
            }
            org.gitee.jmeter.ai.agent.session.GoalState goal =
                    org.gitee.jmeter.ai.agent.session.GoalState.load(session);
            if (goal != null && goal.isOngoing()) {
                goal.recordSmokeAttempt();
                org.gitee.jmeter.ai.agent.session.GoalState.store(session, goal);
                sessionManager.saveSession(session);
            }
        } catch (Exception e) {
            log.debug("Smoke attempt counting skipped", e);
        }
    }

    /** Record smoke PASS/FAIL evidence on the active goal (no-op without one). */
    private void recordSmokeEvidence(boolean pass) {
        if (sessionManager == null) {
            return;
        }
        org.gitee.jmeter.ai.agent.run.AgentRunContext runContext =
                org.gitee.jmeter.ai.agent.run.AgentRunContext.current();
        if (runContext == null) {
            return;
        }
        if (runContext.getSessionKey().startsWith(
                org.gitee.jmeter.ai.agent.run.AgentRunSpec.SUBAGENT_SESSION_PREFIX)) {
            return; // subagent keys must not grow stray session files here
        }
        if (org.gitee.jmeter.ai.instance.DelegationGuard.isActive()) {
            return; // 委派回合不写宿主 goal 的验证证据（goal 归宿主会话）
        }
        try {
            var session = sessionManager.get(runContext.getSessionKey());
            if (session == null) {
                return;
            }
            org.gitee.jmeter.ai.agent.session.GoalState goal =
                    org.gitee.jmeter.ai.agent.session.GoalState.load(session);
            if (goal != null && goal.isOngoing()) {
                if (pass) {
                    goal.recordSmokePass();
                } else {
                    goal.clearSmokePass();
                }
                org.gitee.jmeter.ai.agent.session.GoalState.store(session, goal);
                sessionManager.saveSession(session);
            }
        } catch (Exception e) {
            log.debug("Smoke evidence recording skipped", e);
        }
    }

    private void collectThreadGroupPaths(JMeterTreeNode node, List<TreePath> paths) {
        if (node.getTestElement() instanceof org.apache.jmeter.threads.AbstractThreadGroup) {
            paths.add(new TreePath(node.getPath()));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectThreadGroupPaths((JMeterTreeNode) node.getChildAt(i), paths);
        }
    }

    static Map<String, String> normalizeRunProperties(Map<?, ?> properties) {
        if (properties == null) {
            // 调用方省略 properties 时也必须返回可变 Map：冒烟模式会向返回值
            // 无条件写入 testplan_validation 强制键，不可变 Map.of() 会直接抛
            // UnsupportedOperationException，冒烟腿全程启动不了
            return new LinkedHashMap<>();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        properties.forEach((name, value) -> normalized.put((String) name, value.toString()));
        return normalized;
    }

    private ToolResult doStop() {
        if (JMeterContextService.getTestStartTime() == 0) {
            return ToolResult.error("No test is currently running.");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_STOP));
                log.info("Triggered test stop action");
            } catch (Exception e) {
                log.error("Failed to stop test", e);
            }
        });

        return ToolResult.success("Stop command sent. The test will be stopped immediately.");
    }

    private ToolResult doShutdown() {
        if (JMeterContextService.getTestStartTime() == 0) {
            return ToolResult.error("No test is currently running.");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_SHUTDOWN));
                log.info("Triggered test shutdown action");
            } catch (Exception e) {
                log.error("Failed to shutdown test", e);
            }
        });

        return ToolResult.success("Shutdown command sent. The test will stop after current samples complete.");
    }

    private JMeterTreeNode findTestPlanNode(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof org.apache.jmeter.testelement.TestPlan) {
                return child;
            }
        }
        return null;
    }

    private boolean hasThreadGroups(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (hasThreadGroupsRecursive(child)) return true;
        }
        return false;
    }

    private boolean hasThreadGroupsRecursive(JMeterTreeNode node) {
        if (node.getTestElement() instanceof org.apache.jmeter.threads.AbstractThreadGroup) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasThreadGroupsRecursive((JMeterTreeNode) node.getChildAt(i))) return true;
        }
        return false;
    }

    @Override
    public long getTimeoutMs(Map<String, Object> parameters) {
        boolean smoke = "smoke".equals(getStringParameter(parameters, "mode", "normal"));
        // smoke：完成等待上限 + EDT 派发与引擎确认余量；其余动作维持 15s 守卫——
        // 之前为冒烟抬到 120s 的一刀切把 stop/shutdown 等高频动作的挂死守卫也
        // 拖慢了 8 倍
        return smoke ? SMOKE_COMPLETION_TIMEOUT_MS + 30_000L : 15_000L;
    }
}
