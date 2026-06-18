package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.util.Enumeration;
import java.util.Map;

/**
 * Tool to copy a JMeter test plan element and paste it under a target parent node.
 * Mirrors JMeter's Copy + Paste mechanism (deep clone via TestElement.clone(),
 * recursive add via treeModel.addComponent()).
 */
public class CopyPasteJMeterElementTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CopyPasteJMeterElementTool.class);

    @Override
    public String getName() {
        return "copy_paste_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Copy a JMeter test plan element and paste it under a target parent node. " +
                "Creates a deep clone of the element including all its children. " +
                "If targetParentId is not specified, pastes the clone under the source element's own parent (duplicate-in-place). " +
                "Supports positioning: 'first', 'last', 'before:<id>', 'after:<id>'. " +
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
                            "description": "The elementId of the element to copy. Use get_test_plan_tree or find_element to get the elementId."
                        },
                        "targetParentId": {
                            "type": "integer",
                            "description": "Optional elementId of the target parent node where to paste the cloned element. Defaults to the source element's parent (duplicate-in-place behavior)."
                        },
                        "position": {
                            "type": "string",
                            "description": "Position where to insert the cloned element: 'first' (at beginning), 'last' (at end), 'before:<id>' (before element with elementId), 'after:<id>' (after element with elementId). Default is 'last'.",
                            "default": "last"
                        }
                    },
                    "required": ["elementId"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        int elementId = getIntParameter(parameters, "elementId", -1);
        int targetParentId = getIntParameter(parameters, "targetParentId", -1);
        String position = getStringParameter(parameters, "position", "last");

        if (elementId <= 0) {
            return ToolResult.error("Invalid elementId: " + elementId + ". Must be a positive integer.");
        }

        if (!isValidPositionFormat(position)) {
            return ToolResult.error("Invalid position format: '" + position + "'. " +
                    "Valid formats are: 'first', 'last' (default), 'before:<id>', 'after:<id>'.");
        }

        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return ToolResult.error("JMeter GUI is not available");
            }

            JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (rootNode == null) {
                return ToolResult.error("Test plan root is not available");
            }

            // Find source node
            JMeterTreeNode sourceNode = JMeterTreeUtils.findNodeByElementId(rootNode, elementId);
            if (sourceNode == null) {
                return ToolResult.error("Could not find element with elementId: " + elementId +
                        ". Use get_test_plan_tree to get current elementIds.");
            }

            // Determine target parent
            JMeterTreeNode targetParent;
            if (targetParentId > 0) {
                targetParent = JMeterTreeUtils.findNodeByElementId(rootNode, targetParentId);
                if (targetParent == null) {
                    return ToolResult.error("Could not find target parent node with elementId: " + targetParentId +
                            ". Use get_test_plan_tree to get current elementIds.");
                }
            } else {
                targetParent = (JMeterTreeNode) sourceNode.getParent();
                if (targetParent == null) {
                    return ToolResult.error("Source element has no parent node.");
                }
            }

            // Validate
            ToolResult validation = validateCopyPaste(sourceNode, targetParent);
            if (!validation.isSuccess()) {
                return validation;
            }

            // Deep-clone the source node
            JMeterTreeNode clonedNode = cloneTreeNode(sourceNode);

            // Calculate insert position
            int insertIndex = calculateInsertPosition(targetParent, position, rootNode);

            // Paste
            return performPaste(clonedNode, targetParent, insertIndex);

        } catch (Exception e) {
            log.error("Error copy-pasting element with elementId: {}", elementId, e);
            return ToolResult.error("Failed to copy-paste element: " + e.getMessage());
        }
    }

    // --- Cloning (mirrors JMeter Copy.java) ---

    private JMeterTreeNode cloneTreeNode(JMeterTreeNode sourceNode) {
        JMeterTreeNode clonedNode = (JMeterTreeNode) sourceNode.clone();
        clonedNode.setUserObject(sourceNode.getTestElement().clone());
        cloneChildren(clonedNode, sourceNode);
        return clonedNode;
    }

    @SuppressWarnings("JdkObsolete")
    private void cloneChildren(JMeterTreeNode clonedParent, JMeterTreeNode sourceParent) {
        Enumeration<?> children = sourceParent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode sourceChild = (JMeterTreeNode) children.nextElement();
            JMeterTreeNode clonedChild = (JMeterTreeNode) sourceChild.clone();
            clonedChild.setUserObject(sourceChild.getTestElement().clone());
            clonedParent.add(clonedChild);
            cloneChildren((JMeterTreeNode) clonedParent.getLastChild(), sourceChild);
        }
    }

    // --- Pasting (mirrors JMeter Paste.java) ---

    private ToolResult performPaste(JMeterTreeNode clonedNode, JMeterTreeNode targetParent, int insertIndex) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        TestElement element = clonedNode.getTestElement();
        String elementName = element.getName();
        String elementType = element.getClass().getSimpleName();
        String targetPath = JMeterTreeUtils.getNodePath(targetParent);
        int childCount = countDescendants(clonedNode);

        final JMeterTreeNode[] addedNodeHolder = new JMeterTreeNode[1];

        Exception edtError = org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner.run(guiPackage, () -> {
            // Add root cloned element (mirrors Paste.addNode())
            JMeterTreeNode addedNode = guiPackage.getTreeModel().addComponent(
                    clonedNode.getTestElement(), targetParent);
            addedNodeHolder[0] = addedNode;

            // Recursively add children
            addNodeRecursive(addedNode, clonedNode, guiPackage);

            // Reposition if not 'last' (addComponent always appends at end)
            int lastIdx = targetParent.getChildCount() - 1;
            if (insertIndex < lastIdx) {
                JMeterTreeNode lastChild = (JMeterTreeNode) targetParent.getChildAt(lastIdx);
                guiPackage.getTreeModel().removeNodeFromParent(lastChild);
                guiPackage.getTreeModel().insertNodeInto(lastChild, targetParent, insertIndex);
                addedNodeHolder[0] = lastChild;
            }

            // Expand parent and select new node
            guiPackage.getMainFrame().getTree()
                    .expandPath(new TreePath(targetParent.getPath()));
            guiPackage.getTreeListener().getJTree()
                    .setSelectionPath(new TreePath(addedNodeHolder[0].getPath()));
        });
        if (edtError != null) {
            log.error("Failed to paste cloned element on EDT", edtError);
            return ToolResult.error("Failed to paste cloned element: " + edtError.getMessage());
        }

        int newElementId = System.identityHashCode(addedNodeHolder[0]);
        StringBuilder result = new StringBuilder();
        result.append("Successfully copied and pasted element: **").append(elementName)
                .append("** (").append(elementType).append(")\n\n");
        result.append("Target parent: `").append(targetPath).append("`\n");
        result.append("New elementId: ").append(newElementId).append("\n");
        result.append("Cloned children: ").append(childCount);

        log.info("Successfully copy-pasted element: {} ({}) with new elementId: {}",
                elementName, elementType, newElementId);
        return ToolResult.success(result.toString());
    }

    private void addNodeRecursive(JMeterTreeNode targetParent, JMeterTreeNode clonedSource,
                                  GuiPackage guiPackage) {
        for (int i = 0; i < clonedSource.getChildCount(); i++) {
            JMeterTreeNode clonedChild = (JMeterTreeNode) clonedSource.getChildAt(i);
            try {
                JMeterTreeNode addedChild = guiPackage.getTreeModel().addComponent(
                        clonedChild.getTestElement(), targetParent);
                addNodeRecursive(addedChild, clonedChild, guiPackage);
            } catch (Exception e) {
                log.error("Failed to add cloned child node: {}", clonedChild.getName(), e);
                throw new RuntimeException("Failed to add cloned child: " + e.getMessage(), e);
            }
        }
    }

    // --- Validation ---

    private ToolResult validateCopyPaste(JMeterTreeNode sourceNode, JMeterTreeNode targetParent) {
        TestElement sourceElement = sourceNode.getTestElement();

        // Cannot copy TestPlan root
        if (isTestPlanRootNode(sourceNode)) {
            return ToolResult.error("Cannot copy TestPlan root node for safety reasons.");
        }

        // Cannot paste into a descendant of the source
        if (isTargetInSubtreeOfSource(targetParent, sourceNode)) {
            return ToolResult.error("Cannot paste into a descendant of the source element. " +
                    "This would create an invalid tree structure.");
        }

        // Parent-child compatibility
        if (!JMeterElementManager.isNodeCompatible(targetParent, sourceElement)) {
            String parentTypeName = targetParent.getTestElement().getClass().getSimpleName();
            String childTypeName = sourceElement.getClass().getSimpleName();
            String parentNodeSupportedTypes = JMeterElementManager.getSupportedChildTypesDescription(targetParent);
            String childElementSupportedParents = JMeterElementManager.getSupportedParentTypesDescription(sourceElement);

            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("Cannot paste element '**").append(sourceElement.getName())
                    .append("**' (type: ").append(childTypeName).append(") ")
                    .append("under parent node '**").append(targetParent.getTestElement().getName())
                    .append("**' (type: ").append(parentTypeName).append("').\n\n");
            errorMsg.append("**Compatibility Rule Violation**\n\n");
            errorMsg.append("**1. Current parent node constraints:**\n");
            errorMsg.append("   ").append(parentNodeSupportedTypes != null ? parentNodeSupportedTypes : "Unable to determine.").append("\n\n");
            errorMsg.append("**2. Child element requirements:**\n");
            errorMsg.append("   ").append(childElementSupportedParents != null ? childElementSupportedParents : "Unable to determine.").append("\n\n");
            errorMsg.append("**Solution**: Paste this element under a compatible parent node type listed above.");

            return ToolResult.error(errorMsg.toString());
        }

        return ToolResult.success("");
    }

    // --- Helpers ---

    private boolean isTestPlanRootNode(JMeterTreeNode node) {
        if (node == null) return false;
        TestElement te = node.getTestElement();
        if (te == null) return false;
        if (te instanceof TestPlan) return true;
        JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
        return parent != null && parent.getTestElement() == null;
    }

    private boolean isTargetInSubtreeOfSource(JMeterTreeNode targetNode, JMeterTreeNode sourceNode) {
        if (targetNode == null || sourceNode == null) return false;
        if (targetNode == sourceNode) return true;
        return isDescendant(targetNode, sourceNode);
    }

    private boolean isDescendant(JMeterTreeNode node, JMeterTreeNode ancestor) {
        for (int i = 0; i < ancestor.getChildCount(); i++) {
            javax.swing.tree.TreeNode child = ancestor.getChildAt(i);
            if (child == node) return true;
            if (child instanceof JMeterTreeNode jmeterChild && isDescendant(node, jmeterChild)) return true;
        }
        return false;
    }

    private boolean isValidPositionFormat(String position) {
        if (position == null || "last".equals(position)) return true;
        if ("first".equals(position)) return true;
        if (position.startsWith("before:") || position.startsWith("after:")) {
            try {
                return Integer.parseInt(position.substring(position.indexOf(':') + 1)) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private int calculateInsertPosition(JMeterTreeNode targetParent, String position, JMeterTreeNode root) {
        if ("first".equals(position)) return 0;
        if (position == null || "last".equals(position)) return targetParent.getChildCount();

        if (position.startsWith("before:")) {
            try {
                int refId = Integer.parseInt(position.substring("before:".length()));
                JMeterTreeNode refNode = JMeterTreeUtils.findNodeByElementId(root, refId);
                if (refNode != null && refNode.getParent() == targetParent) {
                    return targetParent.getIndex(refNode);
                }
            } catch (NumberFormatException ignored) {}
        }

        if (position.startsWith("after:")) {
            try {
                int refId = Integer.parseInt(position.substring("after:".length()));
                JMeterTreeNode refNode = JMeterTreeUtils.findNodeByElementId(root, refId);
                if (refNode != null && refNode.getParent() == targetParent) {
                    return targetParent.getIndex(refNode) + 1;
                }
            } catch (NumberFormatException ignored) {}
        }

        return targetParent.getChildCount();
    }

    private int countDescendants(JMeterTreeNode node) {
        int count = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += 1 + countDescendants((JMeterTreeNode) node.getChildAt(i));
        }
        return count;
    }
}
