package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;

import java.util.Map;

/**
 * Tool to create new JMeter elements in the test plan.
 */
public class CreateJMeterElementTool extends AbstractTool {

    @Override
    public String getName() {
        return "create_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Create a new JMeter test element and add it to the test plan. " +
                "Supports creating HTTP Samplers, Thread Groups, Controllers, Timers, Assertions, Listeners, etc.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementType": {
                            "type": "string",
                            "description": "Type of element to create (e.g., 'httpsampler', 'threadgroup', 'loopcontroller', 'constanttimer', 'durationassertion', 'responseassertion', 'viewresultstree')"
                        },
                        "elementName": {
                            "type": "string",
                            "description": "Name for the new element"
                        },
                        "parentPath": {
                            "type": "string",
                            "description": "Optional path to parent node where to add the element (if not specified, adds to currently selected node)"
                        }
                    },
                    "required": ["elementType", "elementName"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String elementType = getStringParameter(parameters, "elementType", null);
        String elementName = getStringParameter(parameters, "elementName", null);
        String parentPath = getStringParameter(parameters, "parentPath", null);

        if (elementType == null || elementType.isEmpty()) {
            return ToolResult.error("elementType is required");
        }
        if (elementName == null || elementName.isEmpty()) {
            return ToolResult.error("elementName is required");
        }

        try {
            // Check if test plan is ready
            JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
            if (!status.isReady()) {
                return ToolResult.error("Cannot create element: " + status.getErrorMessage());
            }

            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return ToolResult.error("JMeter GUI is not available");
            }

            // Save the current selected node to restore later
            JMeterTreeNode originalSelectedNode = guiPackage.getTreeListener().getCurrentNode();

            // If parentPath is specified, select that node first
            if (parentPath != null && !parentPath.isEmpty()) {
                JMeterTreeNode parentNode = findNodeByPath(parentPath);
                if (parentNode == null) {
                    return ToolResult.error("Could not find parent node with path: " + parentPath);
                }
                // Select the parent node
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new javax.swing.tree.TreePath(parentNode.getPath()));
                log.info("Selected parent node by path: {}", parentPath);
            }

            // Use JMeterElementManager to add the element (it handles all the logic including ThreadGroup's LoopController)
            boolean success = JMeterElementManager.addElement(elementType, elementName);

            // Restore original selection if we changed it
            if (parentPath != null && !parentPath.isEmpty() && originalSelectedNode != null) {
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new javax.swing.tree.TreePath(originalSelectedNode.getPath()));
                log.info("Restored original selection");
            }

            if (!success) {
                return ToolResult.error("Failed to add element to test plan tree. " +
                        "Make sure the selected/parent node can contain this type of element.");
            }

            // Expand and select the newly added element
            refreshTreeAndSelectNewElement();

            return ToolResult.success("Successfully created element: **" + elementName + "** (" + elementType + ")");

        } catch (Exception e) {
            log.error("Error creating JMeter element", e);
            return ToolResult.error("Failed to create element: " + e.getMessage());
        }
    }

    /**
     * Find a JMeter tree node by its path.
     * The path can be in format "Test Plan > Thread Group > Controller" or "Test Plan/Thread Group/Controller"
     *
     * @param path The path to search for
     * @return The found node, or null if not found
     */
    private JMeterTreeNode findNodeByPath(String path) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return null;
        }

        // Normalize the path (replace > and / with consistent separator)
        String normalizedPath = path.trim().replaceAll("[>/]", ">");
        String[] pathParts = normalizedPath.split(">");

        for (int i = 0; i < pathParts.length; i++) {
            pathParts[i] = pathParts[i].trim();
        }

        log.info("Searching for node with path parts: {}", java.util.Arrays.toString(pathParts));

        // Start from root
        JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        if (root == null) {
            return null;
        }

        return searchNode(root, pathParts, 0);
    }

    /**
     * Recursively search for a node matching the path.
     *
     * @param currentNode The current node to search from
     * @param pathParts    The path parts to match
     * @param currentIndex  The current index in path parts
     * @return The found node, or null if not found
     */
    private JMeterTreeNode searchNode(JMeterTreeNode currentNode, String[] pathParts, int currentIndex) {
        if (currentIndex >= pathParts.length) {
            return currentNode;
        }

        String targetName = pathParts[currentIndex];
        log.debug("Searching for '{}' at index {}, current node: {}", targetName, currentIndex, currentNode.getName());

        // Check if current node matches
        if (currentIndex == 0 && !targetName.equalsIgnoreCase(currentNode.getName())) {
            // Root doesn't match, try searching from children
            return searchInChildren(currentNode, pathParts, 0);
        }

        if (targetName.equalsIgnoreCase(currentNode.getName())) {
            // Current node matches, move to next part
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                JMeterTreeNode child = (JMeterTreeNode) currentNode.getChildAt(i);
                JMeterTreeNode result = searchNode(child, pathParts, currentIndex + 1);
                if (result != null) {
                    return result;
                }
            }
            // If we're at the last part and current node matches, return it
            if (currentIndex == pathParts.length - 1) {
                return currentNode;
            }
        } else {
            // Current node doesn't match, search in children
            return searchInChildren(currentNode, pathParts, currentIndex);
        }

        return null;
    }

    /**
     * Search in children nodes for the matching path.
     *
     * @param currentNode The parent node
     * @param pathParts    The path parts to match
     * @param currentIndex  The current index in path parts
     * @return The found node, or null if not found
     */
    private JMeterTreeNode searchInChildren(JMeterTreeNode currentNode, String[] pathParts, int currentIndex) {
        for (int i = 0; i < currentNode.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) currentNode.getChildAt(i);
            JMeterTreeNode result = searchNode(child, pathParts, currentIndex);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Refresh the tree and select the newly added element.
     */
    private void refreshTreeAndSelectNewElement() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }

        try {
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                return;
            }

            // Refresh the tree to show the new element
            guiPackage.getTreeModel().nodeStructureChanged(currentNode);
            log.info("Successfully refreshed the tree");

            // Expand the node to show the new element
            guiPackage.getMainFrame().getTree()
                    .expandPath(new javax.swing.tree.TreePath(currentNode.getPath()));

            // Select the newly added element (last child of current node)
            if (currentNode.getChildCount() > 0) {
                JMeterTreeNode lastChild = (JMeterTreeNode) currentNode.getChildAt(currentNode.getChildCount() - 1);
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new javax.swing.tree.TreePath(lastChild.getPath()));
                log.info("Selected newly added element: {}", lastChild.getName());
            }
        } catch (Exception e) {
            log.error("Failed to refresh tree or select element", e);
        }
    }
}
