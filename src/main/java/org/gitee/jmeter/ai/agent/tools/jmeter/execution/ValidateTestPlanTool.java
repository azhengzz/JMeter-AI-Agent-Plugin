package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.jmeter.AbstractJMeterElementTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static (no-traffic) validation of the current test plan. Two check families,
 * each reporting errors only — no style advice (e.g. "sampler without assertion"
 * is deliberately absent):
 * <ol>
 *   <li>Structure — enabled thread group present, GUI class set, required content
 *       present (e.g. a JSR223 sampler must carry a script or filename);</li>
 *   <li>Logic — structural misuse (empty while-condition, zero-iteration loop).</li>
 * </ol>
 * The former schema-rescan family (existing elements re-validated against their
 * component schemas) was removed: legitimate serialization artifacts — opaque
 * ObjectProperty values such as listener saveConfig, undocumented-but-valid
 * properties — surfaced as false errors on imported plans.
 * Deliberately NOT checked (definition sites are not statically enumerable — a
 * {@code ${var}} may be created inside JSR223 scripts or post-processors, and an HTTP
 * sampler's domain may come from HTTP Request Defaults merged at runtime): undefined
 * {@code ${var}} references and missing HTTP sampler target. The smoke run is the
 * authoritative arbiter for those.
 * Read-only and concurrency-safe; usable in any main-agent turn, independent of goal mode.
 */
public class ValidateTestPlanTool extends AbstractJMeterElementTool {

    @Override
    public String getName() {
        return "validate_test_plan";
    }

    @Override
    public boolean isConcurrencySafe() {
        return true;
    }

    /** Read-only tree analysis. Visible to subagents. */
    @Override
    public Set<String> getScopes() {
        return Set.of(SCOPE_CORE, SCOPE_SUBAGENT);
    }

    /** goal 证据回写通道；null（直构测试）时旁路。 */
    private final org.gitee.jmeter.ai.agent.session.SessionManager goalSessionManager;

    public ValidateTestPlanTool() {
        this(null);
    }

    public ValidateTestPlanTool(org.gitee.jmeter.ai.agent.session.SessionManager sessionManager) {
        this.goalSessionManager = sessionManager;
    }

    /** 静态校验结果回写为活跃 goal 的验证证据（PASS 记录 / FAIL 作废旧证据）。 */
    private void recordStaticEvidence(boolean pass) {
        if (goalSessionManager == null) {
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
            var session = goalSessionManager.get(runContext.getSessionKey());
            if (session == null) {
                return;
            }
            org.gitee.jmeter.ai.agent.session.GoalState goal =
                    org.gitee.jmeter.ai.agent.session.GoalState.load(session);
            if (goal != null && goal.isOngoing()) {
                if (pass) {
                    goal.recordStaticPass();
                } else {
                    goal.clearStaticPass();
                }
                org.gitee.jmeter.ai.agent.session.GoalState.store(session, goal);
                goalSessionManager.saveSession(session);
            }
        } catch (Exception e) {
            // 证据回写 best-effort：失败不改变校验结果本身
            org.slf4j.LoggerFactory.getLogger(ValidateTestPlanTool.class)
                    .debug("Static evidence recording skipped", e);
        }
    }

    @Override
    public String getDescription() {
        return "Statically validate the current JMeter test plan without sending any traffic. "
                + "Checks structure completeness (enabled thread group, required content such as "
                + "JSR223 scripts) and structural logic errors. "
                + "Variable definition sites (JSR223, post-processors, HTTP Request Defaults) are NOT "
                + "statically enumerable, so undefined ${var} references and missing HTTP targets are "
                + "left to the smoke run. "
                + "Returns an overall PASS/FAIL verdict plus grouped error details. "
                + "Use it after modifying a test plan, before running it.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {}
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return ToolResult.error("JMeter GUI is not available");
        }
        JMeterTreeNode root = (JMeterTreeNode) gui.getTreeModel().getRoot();
        if (root == null) {
            return ToolResult.error("Test plan root is not available");
        }

        List<ElementView> views = new ArrayList<>();
        collectViews(root, true, "", views);

        List<String> structure = analyzeStructure(views);
        List<String> logic = analyzeLogic(views);

        boolean pass = structure.isEmpty() && logic.isEmpty();
        recordStaticEvidence(pass);
        return ToolResult.success(render(structure, logic));
    }

    // --- tree walk (thin; logic lives in the analyzers for testability) ---

    private void collectViews(JMeterTreeNode node, boolean ancestorsEnabled, String parentPath,
                              List<ElementView> views) {
        TestElement element = node.getTestElement();
        if (element == null || AgentResultCollector.ELEMENT_NAME.equals(element.getName())) {
            // virtual root or the injected collector node — not user content
            visitChildren(node, ancestorsEnabled, parentPath, views);
            return;
        }

        String path = parentPath.isEmpty() ? element.getName() : parentPath + " > " + element.getName();
        boolean enabled = ancestorsEnabled && element.isEnabled();

        ElementView view = new ElementView(
                path,
                element.getName(),
                element.getClass().getSimpleName(),
                element.getPropertyAsString(TestElement.GUI_CLASS),
                JMeterTreeUtils.getElementType(node),
                enabled,
                JMeterTreeUtils.buildPropertiesData(element));

        views.add(view);
        visitChildren(node, enabled, path, views);
    }

    private void visitChildren(JMeterTreeNode node, boolean enabled, String path, List<ElementView> views) {
        for (int i = 0; i < node.getChildCount(); i++) {
            collectViews((JMeterTreeNode) node.getChildAt(i), enabled, path, views);
        }
    }

    // --- analyzers (pure; unit-testable without a GUI) ---

    static List<String> analyzeStructure(List<ElementView> views) {
        List<String> errors = new ArrayList<>();
        boolean hasEnabledThreadGroup = views.stream()
                .anyMatch(v -> v.enabled() && v.className() != null && v.className().endsWith("ThreadGroup"));
        if (!hasEnabledThreadGroup) {
            errors.add("Test Plan | No enabled thread group. Add or enable at least one thread group "
                    + "before the plan can run. Fix: create a threadgroup element or toggle it enabled.");
        }

        for (ElementView v : views) {
            if (!v.enabled()) {
                continue;
            }
            if (v.guiClass() == null || v.guiClass().isBlank()) {
                errors.add(v.path + " | Element has no GUI class set; it cannot be saved or rendered. "
                        + "Fix: recreate the element with create_jmeter_element.");
            }
            // No HTTPSamplerProxy target check: the domain legitimately comes from HTTP
            // Request Defaults merged at runtime (also paths may be absolute URLs), so a
            // blank sampler domain is not a static error. The smoke run catches real
            // target problems.
            if ("JSR223Sampler".equals(v.className())) {
                String script = asString(v.props().get("script"));
                String filename = asString(v.props().get("filename"));
                boolean scriptSatisfied = (script != null && !script.isBlank())
                        || (filename != null && !filename.isBlank());
                if (!scriptSatisfied) {
                    errors.add(v.path + " | JSR223 sampler has an empty script. "
                            + "Fix: set the 'script' property (or 'filename' to load from a file).");
                }
            }
        }
        return errors;
    }

    static List<String> analyzeLogic(List<ElementView> views) {
        List<String> errors = new ArrayList<>();
        for (ElementView v : views) {
            if (!v.enabled()) {
                continue;
            }
            if ("WhileController".equals(v.className())) {
                String condition = asString(v.props().get("WhileController.condition"));
                boolean hasRef = condition != null && condition.contains("${");
                if (condition == null || (condition.isBlank() && !hasRef)) {
                    errors.add(v.path + " | While controller has an empty condition, which loops forever "
                            + "once entered. Fix: set WhileController.condition (typically ${last_ok} style).");
                }
            }
            if ("LoopController".equals(v.className())) {
                String loops = asString(v.props().get("LoopController.loops"));
                // Forever 勾选（continue_forever）时运行期忽略 loops 值无限执行，GUI
                // 常态存储 loops=0——合法浸泡配置不算"永不执行"
                if (!isTrue(v.props().get("LoopController.continue_forever"))
                        && "0".equals(loops == null ? null : loops.trim())) {
                    errors.add(v.path + " | Loop controller has 0 loops; its children will never execute. "
                            + "Fix: set LoopController.loops to at least 1 (or -1 for infinite).");
                }
            }
            if (v.className() != null && v.className().endsWith("ThreadGroup")) {
                // main_controller (LoopController) nested map carries the TG loop count
                Object mainController = v.props().get("ThreadGroup.main_controller");
                if (mainController instanceof Map<?, ?> mc) {
                    Object loops = mc.get("LoopController.loops");
                    if (!isTrue(mc.get("LoopController.continue_forever"))
                            && loops != null && "0".equals(String.valueOf(loops).trim())) {
                        errors.add(v.path + " | Thread group loop count is 0; it will never execute. "
                                + "Fix: set the thread group's loop count to at least 1 (or -1/infinite).");
                    }
                }
            }
        }
        return errors;
    }

    /** 布尔属性判定：Boolean 与 "true" 字符串两种序列化形态都认。 */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    // --- rendering ---

    private String render(List<String> structure, List<String> logic) {
        boolean pass = structure.isEmpty() && logic.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("## Test Plan Validation: ").append(pass ? "PASS" : "FAIL").append("\n\n");
        appendGroup(sb, "1. Structure", structure, "structure is complete");
        appendGroup(sb, "2. Logic", logic, "no structural logic errors");
        sb.append("Note: undefined ${var} references and missing HTTP targets are not checked — ")
                .append("variable definition sites (JSR223, post-processors) and HTTP Request Defaults ")
                .append("merges are not statically enumerable; the smoke run is the arbiter for those.\n");
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String title, List<String> errors, String okLine) {
        sb.append("### ").append(title).append(" — ")
                .append(errors.isEmpty() ? "OK (" + okLine + ")" : errors.size() + " error(s)").append("\n");
        for (String error : errors) {
            sb.append("- ").append(error).append("\n");
        }
        sb.append("\n");
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : (value != null ? String.valueOf(value) : null);
    }

    /** Snapshot of one tree node for the pure analyzers. */
    record ElementView(String path, String name, String className, String guiClass, String elementType,
                       boolean enabled, Map<String, Object> props) {
    }
}
