package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch toggle (enable/disable/toggle) the enabled state of multiple JMeter elements.
 * Applies one shared {@code action} to every element, with a single GUI refresh at the end.
 */
public class BatchToggleJMeterElementTool extends AbstractBatchJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(BatchToggleJMeterElementTool.class);

    @Override
    public String getName() {
        return "batch_toggle_jmeter_elements";
    }

    @Override
    public String getDescription() {
        return "Enable, disable, or toggle the enabled state of multiple JMeter elements in one operation. " +
                "Disabled elements are skipped during test execution (greyed out in the GUI). " +
                "Supports action: 'enable' (force enable), 'disable' (force disable), 'toggle' (invert each element's state, default). " +
                "A single GUI refresh is performed after all updates. " +
                "Use get_test_plan_tree or find_element to get elementIds.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementIds": {
                            "type": "array",
                            "items": { "type": "integer" },
                            "description": "Array of elementIds to enable/disable/toggle. Use get_test_plan_tree or find_element to get elementIds.",
                            "minItems": 1,
                            "maxItems": 50
                        },
                        "action": {
                            "type": "string",
                            "enum": ["enable", "disable", "toggle"],
                            "description": "Action applied to every element: 'enable' force-enables, 'disable' force-disables, 'toggle' inverts each element's current state. Default 'toggle'.",
                            "default": "toggle"
                        }
                    },
                    "required": ["elementIds"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // 1. Parse + validate params
        String action = getStringParameter(parameters, "action", "toggle");
        if (!"enable".equals(action) && !"disable".equals(action) && !"toggle".equals(action)) {
            return ToolResult.error("Invalid action: '" + action +
                    "'. Must be one of: 'enable', 'disable', 'toggle'.");
        }

        List<Integer> elementIds = parseElementIds(parameters.get("elementIds"));
        ToolResult sizeCheck = validateBatchSize(elementIds);
        if (!sizeCheck.isSuccess()) {
            return sizeCheck;
        }

        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return ToolResult.error("Cannot toggle elements: " + status.getErrorMessage());
        }

        // 2. GuiPackage + root
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }
        JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        if (rootNode == null) {
            return ToolResult.error("Test plan root is not available");
        }

        // 3. Resolve all nodes up front (find-all-first; abort if any missing)
        ResolvedNodes resolved = resolveNodeMap(rootNode, elementIds);
        if (!resolved.notFound().isEmpty()) {
            return ToolResult.error("Could not find elements with elementIds: " + resolved.notFound() +
                    ". The elements may have been removed. Use get_test_plan_tree to get current elementIds.");
        }
        Map<Integer, JMeterTreeNode> nodeMap = resolved.nodeMap();

        // 4. De-select current node to avoid GUI binding a node being toggled
        deselectCurrentNode(guiPackage);

        // 5. Single EDT block: decide per element + mutate atomically, then refresh once.
        //    node.setEnabled(...) already fires nodeChanged internally, so no extra nodeChanged.
        List<ElementResult> results = new ArrayList<>();
        Exception edtError = EdtRunner.run(guiPackage, () -> {
            for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
                int id = entry.getKey();
                JMeterTreeNode node = entry.getValue();
                TestElement te = node.getTestElement();
                if (te == null) {
                    results.add(ElementResult.fail(id, node.getName(), "element has no TestElement"));
                    continue;
                }
                String name = te.getName();
                boolean wasEnabled = te.isEnabled();
                boolean newState = "enable".equals(action) ? true
                        : "disable".equals(action) ? false : !wasEnabled;

                if (wasEnabled == newState) {
                    results.add(ElementResult.noted(id, name,
                            "already " + (wasEnabled ? "enabled" : "disabled") + ", no change"));
                    continue;
                }
                try {
                    // JMeter EnableComponent pattern: update node + GUI panel atomically on EDT.
                    node.setEnabled(newState);
                    // getGui may return null when the element's GUI class is unresolvable
                    // (e.g. a missing third-party plugin); the node is the source of truth, so
                    // a missing panel must not flip this element to a failure.
                    var gui = guiPackage.getGui(te);
                    if (gui != null) {
                        gui.setEnabled(newState);
                    }
                    results.add(ElementResult.noted(id, name,
                            (wasEnabled ? "enabled" : "disabled") + " -> " + (newState ? "enabled" : "disabled")));
                } catch (Exception e) {
                    results.add(ElementResult.fail(id, name, e.getMessage()));
                    log.error("Failed to toggle element: {} (elementId: {})", name, id, e);
                }
            }
            // Trailing refresh is best-effort: toggles are already committed above, so a refresh
            // failure must not turn a successful batch into a ToolResult.error.
            try {
                // configure-only refresh; never updateCurrentGui() (see MoveJMeterElementTool).
                guiPackage.refreshCurrentGui();
            } catch (Exception refreshError) {
                log.warn("GUI refresh failed after batch toggle (toggles already applied)", refreshError);
            }
        });
        if (edtError != null) {
            log.error("Failed to batch toggle elements", edtError);
            return ToolResult.error("Failed to toggle elements: " + edtError.getMessage());
        }

        // 6. Aggregated result
        String verb = "enable".equals(action) ? "enabled"
                : "disable".equals(action) ? "disabled" : "toggled";
        return buildBatchResult(results, verb, null, r -> r.hasNote() ? r.note : "OK");
    }
}
