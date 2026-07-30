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

            // 6. Calculate insert position (shared helper; -1 means a before/after reference was
            //    unresolved — preserve original behavior by falling back to 'last').
            int insertIndex = JMeterTreeUtils.calculateInsertPosition(targetParent, position, rootNode);
            if (insertIndex < 0) {
                log.warn("Reference element for position '{}' not found in target parent, defaulting to 'last'", position);
                insertIndex = targetParent.getChildCount();
            }

            // 7. Perform the move
            return performMove(sourceNode, targetParent, insertIndex);

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
     * Perform the actual move operation.
     */
    private ToolResult performMove(JMeterTreeNode sourceNode, JMeterTreeNode targetParent, int insertIndex) {
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

        log.info("Moving element: {} ({}) from '{}' to '{}' at index {}",
                elementName, elementType, oldParentPath, targetParent.getName(), insertIndex);

        // All JMeterTreeModel and GuiPackage.updateCurrentGui calls must run on EDT
        // (JMeterTreeModel extends DefaultTreeModel, see JMeter source). Running them
        // on tool-executor corrupts JTree internal TreeState and triggers EDT NPEs.
        Exception edtError = org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner.run(guiPackage, () -> {
            // Remove from old parent
            guiPackage.getTreeModel().removeNodeFromParent(sourceNode);

            // Insert into new parent
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
