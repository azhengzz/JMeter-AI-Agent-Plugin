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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Move multiple JMeter elements to a shared parent at a shared position. All elements are
 * validated before any is moved; if any validation fails, nothing is moved. Sources are
 * removed first, then inserted in input order at the computed index, so input order is
 * preserved for 'first'/'last'/'before:&lt;id&gt;'/'after:&lt;id&gt;'.
 */
public class BatchMoveJMeterElementTool extends AbstractBatchJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(BatchMoveJMeterElementTool.class);

    @Override
    public String getName() {
        return "batch_move_jmeter_elements";
    }

    @Override
    public String getDescription() {
        return "Move multiple JMeter elements to a shared parent node at a shared position in one operation. " +
                "Position: 'first' | 'last' (default) | 'before:<id>' | 'after:<id>'. Input order is preserved at the destination. " +
                "All elements are validated before any is moved; if validation fails, nothing is moved. " +
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
                            "description": "Array of elementIds to move. All are moved to the same targetParent at the same position. Input order is preserved at the destination.",
                            "minItems": 1,
                            "maxItems": 50
                        },
                        "targetParentId": {
                            "type": "integer",
                            "description": "The elementId of the destination parent for all elements."
                        },
                        "position": {
                            "type": "string",
                            "description": "Shared insert position for all elements: 'first', 'last' (default), 'before:<id>', 'after:<id>'. The reference id for before/after must NOT be one of the elements being moved and must be a current child of targetParent.",
                            "default": "last"
                        }
                    },
                    "required": ["elementIds", "targetParentId"]
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

        int targetParentId = getIntParameter(parameters, "targetParentId", -1);
        if (targetParentId <= 0) {
            return ToolResult.error("Invalid targetParentId: " + targetParentId + ". Must be a positive integer.");
        }

        String position = getStringParameter(parameters, "position", "last");
        if (!JMeterTreeUtils.isValidPositionFormat(position)) {
            return ToolResult.error("Invalid position format: '" + position + "'. " +
                    "Valid formats are: 'first', 'last' (default), 'before:<id>', 'after:<id>'.");
        }

        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return ToolResult.error("Cannot move elements: " + status.getErrorMessage());
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

        // 3. Resolve all sources + target parent (find-all-first; abort if any missing)
        ResolvedNodes resolved = resolveNodeMap(rootNode, elementIds);
        JMeterTreeNode targetParent = JMeterTreeUtils.findNodeByElementId(rootNode, targetParentId);
        List<Integer> missing = new ArrayList<>(resolved.notFound());
        if (targetParent == null) {
            missing.add(targetParentId);
        }
        if (!missing.isEmpty()) {
            return ToolResult.error("Could not find elements with elementIds: " + missing +
                    ". The elements may have been removed. Use get_test_plan_tree to get current elementIds.");
        }
        Map<Integer, JMeterTreeNode> nodeMap = resolved.nodeMap();

        // 4. Validate ALL targets up front (accumulate every violation, then abort with no mutation)
        List<String> violations = new ArrayList<>();

        // 4a. position reference (batch-strict: single-element Move silently falls back to 'last';
        //     batch must hard-fail to avoid silent reordering)
        if (position.startsWith("before:") || position.startsWith("after:")) {
            int refId = Integer.parseInt(position.substring(position.indexOf(':') + 1));
            if (elementIds.contains(refId)) {
                violations.add("position '" + position + "' references elementId " + refId +
                        " which is itself being moved");
            } else {
                JMeterTreeNode refNode = JMeterTreeUtils.findNodeByElementId(rootNode, refId);
                if (refNode == null) {
                    violations.add("position '" + position + "' references elementId " + refId +
                            " which cannot be found");
                } else if (refNode.getParent() != targetParent) {
                    violations.add("position '" + position + "' references elementId " + refId +
                            " which is not a child of the target parent");
                }
            }
        }

        // 4b. target parent must not itself be in the source set
        if (nodeMap.containsValue(targetParent)) {
            violations.add("targetParentId " + targetParentId + " is itself one of the elements being moved");
        }

        // 4c. per-source: root / cycle / compatibility
        for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
            int id = entry.getKey();
            JMeterTreeNode source = entry.getValue();
            TestElement sourceElement = source.getTestElement();
            String name = sourceElement != null ? sourceElement.getName() : source.getName();

            if (JMeterTreeUtils.isTestPlanRootNode(source)) {
                violations.add("elementId " + id + " (\"" + name + "\") is the TestPlan root and cannot be moved");
            } else if (JMeterTreeUtils.isTargetInSubtreeOfSource(targetParent, source)) {
                violations.add("elementId " + id + " (\"" + name + "\") cannot be moved into its own descendant");
            } else if (!JMeterElementManager.isNodeCompatible(targetParent, sourceElement)) {
                violations.add("elementId " + id + " (\"" + name + "\", type " +
                        (sourceElement != null ? sourceElement.getClass().getSimpleName() : "unknown") +
                        ") is not a compatible child of the target parent");
            }
        }

        // 4d. source-vs-source ancestry: moving both a node and its current descendant to the
        //     same parent would insert them as siblings and silently flatten the descendant
        //     out of the node (BatchDelete handles the symmetric case via ancestry reduction).
        //     Reject so the caller expresses intent explicitly.
        for (Map.Entry<Integer, JMeterTreeNode> a : nodeMap.entrySet()) {
            int idA = a.getKey();
            JMeterTreeNode source = a.getValue();
            TestElement srcTe = source.getTestElement();
            String srcName = srcTe != null ? srcTe.getName() : source.getName();
            for (Map.Entry<Integer, JMeterTreeNode> b : nodeMap.entrySet()) {
                int idB = b.getKey();
                if (idA != idB && JMeterTreeUtils.isDescendant(source, b.getValue())) {
                    TestElement ancTe = b.getValue().getTestElement();
                    violations.add("elementId " + idA + " (\"" + srcName +
                            "\") is a descendant of elementId " + idB + " (\"" +
                            (ancTe != null ? ancTe.getName() : b.getValue().getName()) +
                            "\") which is also being moved; move the ancestor only, or move them separately");
                    break; // one ancestry violation per source is enough
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("Cannot move elements:\n");
            for (String v : violations) {
                sb.append("- ").append(v).append("\n");
            }
            return ToolResult.error(sb.toString());
        }

        // 5. De-select current node
        deselectCurrentNode(guiPackage);

        // 6. Single EDT block — two-phase to avoid index drift across multiple moves:
        //    Phase A: remove all sources (capturing old parents before remove);
        //    Phase B: compute base index against the post-removal tree, then insert all sources
        //             in input order at base, base+1, ... (preserves order for every position).
        Exception edtError = EdtRunner.run(guiPackage, () -> {
            Set<JMeterTreeNode> oldParents = new LinkedHashSet<>();
            // Phase A — remove
            for (JMeterTreeNode source : nodeMap.values()) {
                JMeterTreeNode oldParent = (JMeterTreeNode) source.getParent();
                guiPackage.getTreeModel().removeNodeFromParent(source);
                if (oldParent != null) {
                    oldParents.add(oldParent);
                }
            }
            // Phase B — insert in input order
            int base = JMeterTreeUtils.calculateInsertPosition(targetParent, position, rootNode);
            if (base < 0) {
                base = targetParent.getChildCount(); // defensive; pre-validation prevents this
            }
            int idx = base;
            for (JMeterTreeNode source : nodeMap.values()) {
                guiPackage.getTreeModel().insertNodeInto(source, targetParent, idx);
                idx++;
            }
            // Trailing refresh is best-effort: the moves (Phase A/B above) are already committed,
            // so a refresh failure must not turn a successful batch into a ToolResult.error.
            // (nodeStructureChanged only notifies the JTree view; the model is already correct.)
            try {
                guiPackage.getTreeModel().nodeStructureChanged(targetParent);
                for (JMeterTreeNode p : oldParents) {
                    if (p != targetParent) {
                        guiPackage.getTreeModel().nodeStructureChanged(p);
                    }
                }
                guiPackage.getTreeListener().getJTree().setSelectionRow(0);
                // configure-only refresh; never updateCurrentGui() (see MoveJMeterElementTool).
                guiPackage.refreshCurrentGui();
            } catch (Exception refreshError) {
                log.warn("GUI refresh failed after batch move (moves already applied)", refreshError);
            }
        });
        if (edtError != null) {
            log.error("Failed to batch move elements", edtError);
            return ToolResult.error("Failed to move elements: " + edtError.getMessage());
        }

        // 7. Aggregated result
        List<ElementResult> results = new ArrayList<>();
        for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
            TestElement te = entry.getValue().getTestElement();
            results.add(ElementResult.ok(entry.getKey(), te != null ? te.getName() : entry.getValue().getName()));
        }
        String headerExtra = "Destination: " + JMeterTreeUtils.getNodePath(targetParent) +
                " (position: " + position + ")\n";
        return buildBatchResult(results, "moved", headerExtra, r -> "OK");
    }
}
