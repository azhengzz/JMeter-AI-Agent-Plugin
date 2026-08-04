package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batch delete multiple JMeter elements. The TestPlan root is never deleted. If the target
 * set contains both an ancestor and its descendant, the descendant is skipped (it is removed
 * together with the ancestor). All targets are validated before any is deleted; if any target
 * is invalid (not found / root / not removable), nothing is deleted.
 */
public class BatchDeleteJMeterElementTool extends AbstractBatchJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(BatchDeleteJMeterElementTool.class);

    @Override
    public String getName() {
        return "batch_delete_jmeter_elements";
    }

    @Override
    public String getDescription() {
        return "Delete multiple JMeter elements in one operation. The TestPlan root cannot be deleted. " +
                "If an element is a descendant of another element in the list, it is skipped (removed with the ancestor). " +
                "All elements are validated before any is deleted. " +
                "A single GUI refresh is performed after all deletions. " +
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
                            "description": "Array of elementIds to delete. If an element is a descendant of another in this list, it is skipped. Use get_test_plan_tree or find_element to get elementIds.",
                            "minItems": 1,
                            "maxItems": 50
                        }
                    },
                    "required": ["elementIds"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // 1. Parse + validate params
        List<Integer> elementIds = parseElementIds(parameters.get("elementIds"));
        ToolResult sizeCheck = validateBatchSize(elementIds);
        if (!sizeCheck.isSuccess()) {
            return sizeCheck;
        }

        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return ToolResult.error("Cannot delete elements: " + status.getErrorMessage());
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

        // 4. Validate ALL targets up front (any invalid -> abort, delete nothing)
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
            int id = entry.getKey();
            JMeterTreeNode node = entry.getValue();
            TestElement te = node.getTestElement();
            String name = te != null ? te.getName() : node.getName();
            if (JMeterTreeUtils.isTestPlanRootNode(node)) {
                violations.add("elementId " + id + " (\"" + name + "\") is the TestPlan root and cannot be deleted");
            } else if (!JMeterTreeUtils.canRemoveNode(node)) {
                violations.add("elementId " + id + " (\"" + name + "\") cannot be removed (in use or unsupported)");
            }
        }
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("Cannot delete elements:\n");
            for (String v : violations) {
                sb.append("- ").append(v).append("\n");
            }
            return ToolResult.error(sb.toString());
        }

        // 5. Ancestry reduction: a target that is a descendant of another target is removed
        //    together with its ancestor, so skip it (DefaultMutableTreeNode.remove is non-recursive
        //    at the parent link, so this is for correct accounting / avoiding redundant work;
        //    duplicate ids are already collapsed by the LinkedHashMap).
        Set<JMeterTreeNode> skippedNodes = new LinkedHashSet<>();
        Map<JMeterTreeNode, String> ancestorNames = new LinkedHashMap<>();
        for (JMeterTreeNode node : nodeMap.values()) {
            for (JMeterTreeNode other : nodeMap.values()) {
                if (other != node && JMeterTreeUtils.isDescendant(node, other)) {
                    skippedNodes.add(node);
                    TestElement otherTe = other.getTestElement();
                    ancestorNames.put(node, otherTe != null ? otherTe.getName() : other.getName());
                    break;
                }
            }
        }

        // 6. De-select current node
        deselectCurrentNode(guiPackage);

        // 7. Single EDT block: remove non-skipped targets atomically, then refresh once
        List<ElementResult> results = new ArrayList<>();
        Set<JMeterTreeNode> parents = new LinkedHashSet<>();
        Exception edtError = EdtRunner.run(guiPackage, () -> {
            for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
                int id = entry.getKey();
                JMeterTreeNode node = entry.getValue();
                TestElement te = node.getTestElement();
                String name = te != null ? te.getName() : node.getName();

                if (skippedNodes.contains(node)) {
                    results.add(ElementResult.noted(id, name,
                            "skipped (removed with ancestor \"" + ancestorNames.get(node) + "\")"));
                    continue;
                }
                // Capture parent BEFORE remove (it becomes null afterwards)
                JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
                if (parent == null) {
                    results.add(ElementResult.noted(id, name, "skipped (already detached)"));
                    continue;
                }
                try {
                    // removeNodeFromParent arg must stay JMeterTreeNode to hit JMeter's TestPlan-safe overload
                    guiPackage.getTreeModel().removeNodeFromParent(node);
                    if (te != null) {
                        guiPackage.removeNode(te);
                    }
                    parents.add(parent);
                    results.add(ElementResult.ok(id, name));
                } catch (Exception e) {
                    results.add(ElementResult.fail(id, name, e.getMessage()));
                    log.error("Failed to delete element: {} (elementId: {})", name, id, e);
                }
            }
            // Trailing refresh is best-effort: deletions are already committed above, so a refresh
            // failure must not turn a successful batch into a ToolResult.error.
            try {
                for (JMeterTreeNode p : parents) {
                    guiPackage.getTreeModel().nodeStructureChanged(p);
                }
                guiPackage.getTreeListener().getJTree().setSelectionRow(0);
                // configure-only refresh; never updateCurrentGui() (see MoveJMeterElementTool).
                guiPackage.refreshCurrentGui();
            } catch (Exception refreshError) {
                log.warn("GUI refresh failed after batch delete (deletions already applied)", refreshError);
            }
        });
        if (edtError != null) {
            log.error("Failed to batch delete elements", edtError);
            return ToolResult.error("Failed to delete elements: " + edtError.getMessage());
        }

        // 8. Off-EDT lifecycle callback for every targeted element (incl. skipped descendants)
        for (JMeterTreeNode node : nodeMap.values()) {
            TestElement te = node.getTestElement();
            if (te != null) {
                try {
                    te.removed();
                } catch (Exception e) {
                    log.warn("Error calling removed() on test element: {}", e.getMessage());
                }
            }
        }

        // 9. Aggregated result
        return buildBatchResult(results, "deleted", null, r -> r.hasNote() ? r.note : "deleted");
    }
}
