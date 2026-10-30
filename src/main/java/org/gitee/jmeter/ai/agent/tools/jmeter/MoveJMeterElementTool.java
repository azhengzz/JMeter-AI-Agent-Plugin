package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.util.Map;

/**
 * Tool to move JMeter test plan elements to different parent nodes.
 * Implements drag-and-drop-like functionality for test plan restructuring.
 */
public class MoveJMeterElementTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(MoveJMeterElementTool.class);

    @Override
    public String getName() {
        return "move_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Move a JMeter test plan element to a different parent node at a specified position. " +
                "Useful for restructuring test plans, reorganizing elements, or moving elements between thread groups. " +
                "Supports positioning at 'first', 'last', 'before:<id>', or 'after:<id>'. " +
                "Use get_test_plan_tree or find_element to get elementIds.";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
                "type": "object",
                "properties": {
                    "elementId": {
                        "type": "integer",
                        "description": "The elementId of the element to move. Use get_test_plan_tree or find_element to get the elementId."
                    },
                    "targetParentId": {
                        "type": "integer",
                        "description": "The elementId of the target parent node where the element should be moved."
                    },
                    "position": {
                        "type": "string",
                        "description": "Position where to insert the element: 'first' (at beginning), 'last' (at end), 'before:<id>' (before element with elementId), 'after:<id>' (after element with elementId). Default is 'last'.",
                        "default": "last"
                    }
                },
                "required": ["elementId", "targetParentId"]
            }
            """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // 1. Get and validate parameters
        int elementId = getIntParameter(parameters, "elementId", -1);
        int targetParentId = getIntParameter(parameters, "targetParentId", -1);
        String position = getStringParameter(parameters, "position", "last");

        // Basic validation
        if (elementId <= 0) {
            return ToolResult.error("Invalid elementId: " + elementId + ". Must be a positive integer.");
        }
        if (targetParentId <= 0) {
            return ToolResult.error("Invalid targetParentId: " + targetParentId + ". Must be a positive integer.");
        }

        // Validate position format early (before any JMeter operations)
        if (!JMeterTreeUtils.isValidPositionFormat(position)) {
            return ToolResult.error("Invalid position format: '" + position + "'. " +
                    "Valid formats are: 'first' (insert at beginning), " +
                    "'last' (insert at end, default), " +
                    "'before:<id>' (insert before element with elementId), " +
                    "'after:<id>' (insert after element with elementId).");
        }


        try {
            // 2. Get GuiPackage instance
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return ToolResult.error("JMeter GUI is not available");
            }

            // 3. Get the tree root node
            JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (rootNode == null) {
                return ToolResult.error("Test plan root is not available");
            }

            // 4. Find source and target nodes
            JMeterTreeNode sourceNode = JMeterTreeUtils.findNodeByElementId(rootNode, elementId);
            if (sourceNode == null) {
                return ToolResult.error("Could not find element with elementId: " + elementId +
                        ". Use get_test_plan_tree to get current elementIds.");
            }

            JMeterTreeNode targetParent = JMeterTreeUtils.findNodeByElementId(rootNode, targetParentId);
            if (targetParent == null) {
                return ToolResult.error("Could not find target parent node with elementId: " + targetParentId +
                        ". Use get_test_plan_tree to get current elementIds.");
            }

            // 5. Validate move operation
            ToolResult validation = validateMove(sourceNode, targetParent);
            if (!validation.isSuccess()) {
                return validation;
            }

            // 6. Pre-validate the before:/after: reference (hard error, aligned with
            //    batch_move_jmeter_elements; the old lenient fallback silently appended at the
            //    end, masking input errors — and for same-parent moves fed the stale-index bug).
            ToolResult refValidation = validatePositionRef(position, elementId, targetParent, rootNode);
            if (!refValidation.isSuccess()) {
                return refValidation;
            }

            // 7. Perform the move (insert index is computed inside the EDT block on the
            //    post-removal tree — see performMove)
            return performMove(sourceNode, targetParent, position, rootNode);

        } catch (Exception e) {
            log.error("Error moving element with elementId: {}", elementId, e);
            return ToolResult.error("Failed to move element: " + e.getMessage());
        }
    }

    /**
     * Validate the move operation.
     */
    private ToolResult validateMove(JMeterTreeNode sourceNode, JMeterTreeNode targetParent) {
        TestElement sourceElement = sourceNode.getTestElement();

        // Check 1: Cannot move TestPlan root
        if (JMeterTreeUtils.isTestPlanRootNode(sourceNode)) {
            return ToolResult.error("Cannot move TestPlan root node for safety reasons. " +
                    "The TestPlan root node must always exist in the test plan.");
        }

        // Check 2: Cannot move into descendant (circular reference)
        if (JMeterTreeUtils.isTargetInSubtreeOfSource(targetParent, sourceNode)) {
            return ToolResult.error("Cannot move a parent node into its own descendant. " +
                    "This would create an invalid tree structure.");
        }

        // Check 3: Parent-child compatibility (covers ThreadGroup, TestFragment, Non-Test Element constraints)
        if (!JMeterElementManager.isNodeCompatible(targetParent, sourceElement)) {
            String parentTypeName = targetParent.getTestElement().getClass().getSimpleName();
            String childTypeName = sourceElement.getClass().getSimpleName();
            String parentNodeSupportedTypes = JMeterElementManager.getSupportedChildTypesDescription(targetParent);
            String childElementSupportedParents = JMeterElementManager.getSupportedParentTypesDescription(sourceElement);

            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("Cannot move element '**").append(sourceElement.getName())
                    .append("**' (type: ").append(childTypeName).append(") ")
                    .append("to parent node '**").append(targetParent.getTestElement().getName())
                    .append("**' (type: ").append(parentTypeName).append("').\n\n");
            errorMsg.append("**Compatibility Rule Violation**\n\n");
            errorMsg.append("**1. Current parent node constraints:**\n");
            errorMsg.append("   ").append(parentNodeSupportedTypes != null ? parentNodeSupportedTypes : "Unable to determine.").append("\n\n");
            errorMsg.append("**2. Child element requirements:**\n");
            errorMsg.append("   ").append(childElementSupportedParents != null ? childElementSupportedParents : "Unable to determine.").append("\n\n");
            errorMsg.append("**Solution**: Move this element to a compatible parent node type listed above.");

            return ToolResult.error(errorMsg.toString());
        }

        return ToolResult.success(""); // Validation passed
    }

    /**
     * Pre-validate a before:/after: position reference. Mirrors batch-move semantics: the
     * reference must exist, must not be the element being moved, and must currently be a child
     * of the target parent. Anything else is a hard error (no silent 'last' degradation).
     */
    private ToolResult validatePositionRef(String position, int elementId,
                                           JMeterTreeNode targetParent, JMeterTreeNode rootNode) {
        if (!position.startsWith("before:") && !position.startsWith("after:")) {
            return ToolResult.success("");
        }
        int refId;
        try {
            refId = Integer.parseInt(position.substring(position.indexOf(':') + 1));
        } catch (NumberFormatException e) {
            return ToolResult.error("Invalid position format: '" + position
                    + "'. The reference id must be an integer.");
        }
        if (refId == elementId) {
            return ToolResult.error("position '" + position + "' references elementId " + refId
                    + " which is itself being moved");
        }
        JMeterTreeNode refNode = JMeterTreeUtils.findNodeByElementId(rootNode, refId);
        if (refNode == null) {
            return ToolResult.error("position '" + position + "' references elementId " + refId
                    + " which cannot be found");
        }
        if (refNode.getParent() != targetParent) {
            return ToolResult.error("position '" + position + "' references elementId " + refId
                    + " which is not a child of the target parent");
        }
        return ToolResult.success("");
    }

    /**
     * Perform the actual move operation. Two-phase on the post-removal tree (same shape as
     * BatchMoveJMeterElementTool): the insert index MUST be computed AFTER
     * removeNodeFromParent — a pre-removal childCount/index goes stale once the source
     * leaves the target parent and historically threw ArrayIndexOutOfBoundsException,
     * detaching the element (silent data loss), and shifted same-parent before:/after:
     * references by one.
     */
    private ToolResult performMove(JMeterTreeNode sourceNode, JMeterTreeNode targetParent,
                                   String position, JMeterTreeNode rootNode) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        JMeterTreeNode oldParent = (JMeterTreeNode) sourceNode.getParent();

        // Store element info for result message
        TestElement element = sourceNode.getTestElement();
        String elementName = element.getName();
        String elementType = element.getClass().getSimpleName();
        String oldParentPath = JMeterTreeUtils.getNodePath(oldParent);

        log.info("Moving element: {} ({}) from '{}' to '{}' at position {}",
                elementName, elementType, oldParentPath, targetParent.getName(), position);

        // All JMeterTreeModel and GuiPackage.updateCurrentGui calls must run on EDT
        // (JMeterTreeModel extends DefaultTreeModel, see JMeter source). Running them
        // on tool-executor corrupts JTree internal TreeState and triggers EDT NPEs.
        Exception edtError = org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner.run(guiPackage, () -> {
            // Phase A — remove first.
            guiPackage.getTreeModel().removeNodeFromParent(sourceNode);

            // Phase B — compute the insert index against the post-removal tree. calculateInsertPosition
            // returning -1 here can only mean the reference vanished between pre-validation and this
            // EDT run (TOCTOU: concurrent GUI edit) — append at the end rather than throw: the removal
            // is already committed, and an exception now would detach the element (data loss). Do NOT
            // remove this fallback as "dead code"; it is the never-throw guarantee for Phase A.
            int insertIndex = JMeterTreeUtils.calculateInsertPosition(targetParent, position, rootNode);
            if (insertIndex < 0 || insertIndex > targetParent.getChildCount()) {
                insertIndex = targetParent.getChildCount();
            }
            guiPackage.getTreeModel().insertNodeInto(sourceNode, targetParent, insertIndex);

            // Refresh tree structure
            if (oldParent != null) {
                guiPackage.getTreeModel().nodeStructureChanged(oldParent);
            }
            guiPackage.getTreeModel().nodeStructureChanged(targetParent);

            // Update GUI selection
            TreePath newPath = new TreePath(sourceNode.getPath());
            guiPackage.getTreeListener().getJTree().setSelectionPath(newPath);
            // configure-only refresh. Do NOT use updateCurrentGui(): it runs updateCurrentNode()
            // → modifyTestElement(), which writes the shared GUI component's cached "enabled"
            // field back onto the TestElement and can silently disable the moved element.
            guiPackage.refreshCurrentGui();
        });
        if (edtError != null) {
            log.error("Failed to move element: {} ({})", elementName, elementType, edtError);
            return ToolResult.error("Failed to move element: " + edtError.getMessage());
        }

        // Build success message (after EDT confirmed success)
        StringBuilder result = new StringBuilder();
        result.append("Successfully moved element: **").append(elementName)
                .append("** (").append(elementType).append(")\n\n");
        result.append("From: `").append(oldParentPath).append("`\n");
        result.append("To: `").append(JMeterTreeUtils.getNodePath(targetParent)).append("`\n");
        result.append("New elementId: ").append(System.identityHashCode(sourceNode));

        log.info("Successfully moved element: {} ({}) from '{}' to '{}'",
                elementName, elementType, oldParentPath, targetParent.getName());

        return ToolResult.success(result.toString());
    }
}
