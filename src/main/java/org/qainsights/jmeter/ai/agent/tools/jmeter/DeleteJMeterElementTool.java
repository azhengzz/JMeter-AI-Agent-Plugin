package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.util.Map;

/**
 * Tool to delete JMeter test plan elements.
 * Removes elements from the test plan tree with proper validation and cleanup.
 */
public class DeleteJMeterElementTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(DeleteJMeterElementTool.class);

    @Override
    public String getName() {
        return "delete_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Delete a JMeter test plan element by its elementId. " +
                "The TestPlan root node cannot be deleted for safety reasons. " +
                "Use get_test_plan_tree or find_element to get the elementId of the element you want to delete.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementId": {
                            "type": "integer",
                            "description": "The elementId of the element to delete. Use get_test_plan_tree or find_element to get the elementId."
                        }
                    },
                    "required": ["elementId"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        int elementId = getIntParameter(parameters, "elementId", -1);

        // Basic validation
        if (elementId <= 0) {
            return ToolResult.error("Invalid elementId: " + elementId + ". Must be a positive integer.");
        }

        return deleteElement(elementId);
    }

    /**
     * Delete a JMeter element with proper validation and cleanup.
     *
     * @param elementId The elementId of the element to delete
     * @return ToolResult indicating success or failure
     */
    private ToolResult deleteElement(int elementId) {
        try {
            // 1. Get GuiPackage instance
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return ToolResult.error("JMeter GUI is not available");
            }

            // 2. Get the tree root node
            JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (rootNode == null) {
                return ToolResult.error("Test plan root is not available");
            }

            // 3. Find target node by elementId
            JMeterTreeNode targetNode = JMeterTreeUtils.findNodeByElementId(rootNode, elementId);
            if (targetNode == null) {
                return ToolResult.error("Could not find element with elementId: " + elementId +
                        ". The element may have been removed. Use get_test_plan_tree to get current elementIds.");
            }

            // 4. Get node information
            TestElement testElement = targetNode.getTestElement();
            String elementName = testElement != null ? testElement.getName() : targetNode.getName();
            String elementType = testElement != null ? testElement.getClass().getSimpleName() : "Unknown";

            // 5. Check if this is the TestPlan root node
            if (isTestPlanRootNode(targetNode)) {
                return ToolResult.error("Cannot delete TestPlan root node for safety reasons. " +
                        "The TestPlan root node must always exist in the test plan.");
            }

            // 6. Check if the element can be removed
            if (!canRemoveNode(targetNode)) {
                return ToolResult.error("Element '" + elementName + "' (" + elementType + ") cannot be removed. " +
                        "It may be in use or does not support removal.");
            }

            // 7. Get parent node for subsequent refresh
            JMeterTreeNode parentNode = (JMeterTreeNode) targetNode.getParent();
            String parentPath = parentNode != null ? JMeterTreeUtils.getNodePath(parentNode) : "Unknown";

            // 8. Perform deletion
            performDeletion(targetNode, testElement);

            // 9. Refresh tree
            if (parentNode != null) {
                refreshTreeAfterDeletion(parentNode);
            }

            // 10. Refresh GUI
            guiPackage.updateCurrentGui();

            // 11. Return success result
            StringBuilder result = new StringBuilder();
            result.append("Successfully deleted element: **").append(elementName).append("** (").append(elementType).append(")");
            result.append("\n\nParent path: ").append(parentPath);
            result.append("\nElement Id: ").append(elementId);

            log.info("Successfully deleted element: {} ({}) with elementId: {}", elementName, elementType, elementId);
            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.error("Error deleting element with elementId: {}", elementId, e);
            return ToolResult.error("Failed to delete element: " + e.getMessage());
        }
    }

    /**
     * Check if the node is a TestPlan root node.
     *
     * @param node The node to check
     * @return true if this is the TestPlan root node
     */
    private boolean isTestPlanRootNode(JMeterTreeNode node) {
        if (node == null) {
            return false;
        }

        TestElement testElement = node.getTestElement();
        if (testElement == null) {
            return false;
        }

        // Method 1: Check if the element type is TestPlan
        if (testElement instanceof org.apache.jmeter.testelement.TestPlan) {
            return true;
        }

        // Method 2: Check if the parent is a virtual root node (no TestElement)
        JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
        if (parent != null && parent.getTestElement() == null) {
            // Parent has no TestElement, meaning parent is the virtual root node
            return true;
        }

        return false;
    }

    /**
     * Check if the node can be safely removed.
     *
     * @param node The node to check
     * @return true if the node can be removed
     */
    private boolean canRemoveNode(JMeterTreeNode node) {
        if (node == null) {
            return false;
        }

        TestElement testElement = node.getTestElement();
        if (testElement == null) {
            return false;
        }

        // Call JMeter's canRemove() method
        return testElement.canRemove();
    }

    /**
     * Perform the actual deletion with proper cleanup.
     * Reference: JMeter Remove.java removeNode() method
     *
     * @param node        The node to delete
     * @param testElement The test element associated with the node
     */
    private void performDeletion(JMeterTreeNode node, TestElement testElement) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            throw new IllegalStateException("GuiPackage is not available");
        }

        // Reference JMeter Remove.java deletion logic:
        // 1. Remove from tree model
        guiPackage.getTreeModel().removeNodeFromParent(node);

        // 2. Remove from GuiPackage
        if (testElement != null) {
            guiPackage.removeNode(testElement);
        }

        // 3. Call element cleanup callback
        if (testElement != null) {
            try {
                testElement.removed();
            } catch (Exception e) {
                log.warn("Error calling removed() on test element: {}", e.getMessage());
            }
        }

        log.info("Removed node from tree: {}", node.getName());
    }

    /**
     * Refresh the tree after deletion.
     *
     * @param parent The parent node of the deleted node
     */
    private void refreshTreeAfterDeletion(JMeterTreeNode parent) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }

        try {
            // Notify tree model that node structure has changed
            guiPackage.getTreeModel().nodeStructureChanged(parent);
            log.debug("Tree structure changed notification sent for parent: {}", parent.getName());

            // Select parent node for visual feedback
            guiPackage.getTreeListener().getJTree()
                    .setSelectionPath(new TreePath(parent.getPath()));

        } catch (Exception e) {
            log.error("Failed to refresh tree after deletion", e);
        }
    }
}
