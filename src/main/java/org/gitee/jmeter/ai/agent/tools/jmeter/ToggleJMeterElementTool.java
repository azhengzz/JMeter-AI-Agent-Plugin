package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool to enable, disable, or toggle the enabled state of JMeter test plan elements.
 * Follows JMeter's EnableComponent pattern for dual state update.
 */
public class ToggleJMeterElementTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ToggleJMeterElementTool.class);

    @Override
    public String getName() {
        return "toggle_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Enable, disable, or toggle the enabled state of a JMeter test plan element. " +
                "Disabled elements are skipped during test execution (shown as greyed out in the GUI). " +
                "Supports actions: 'enable' (force enable), 'disable' (force disable), 'toggle' (invert current state). " +
                "Use get_test_plan_tree or find_element to get the elementId.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementId": {
                            "type": "integer",
                            "description": "The elementId of the element to enable/disable/toggle. Use get_test_plan_tree or find_element to get the elementId."
                        },
                        "action": {
                            "type": "string",
                            "enum": ["enable", "disable", "toggle"],
                            "description": "Action to perform: 'enable' to force enable, 'disable' to force disable, 'toggle' to invert the current state. Default is 'toggle'.",
                            "default": "toggle"
                        }
                    },
                    "required": ["elementId"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        int elementId = getIntParameter(parameters, "elementId", -1);
        String action = getStringParameter(parameters, "action", "toggle");

        if (elementId <= 0) {
            return ToolResult.error("Invalid elementId: " + elementId +
                    ". Must be a positive integer. Use get_test_plan_tree to get current elementIds.");
        }

        if (!"enable".equals(action) && !"disable".equals(action) && !"toggle".equals(action)) {
            return ToolResult.error("Invalid action: '" + action +
                    "'. Must be one of: 'enable', 'disable', 'toggle'.");
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

            JMeterTreeNode targetNode = JMeterTreeUtils.findNodeByElementId(rootNode, elementId);
            if (targetNode == null) {
                return ToolResult.error("Could not find element with elementId: " + elementId +
                        ". The element may have been removed. Use get_test_plan_tree to get current elementIds.");
            }

            TestElement testElement = targetNode.getTestElement();
            if (testElement == null) {
                return ToolResult.error("Element has no valid TestElement associated.");
            }

            String elementName = testElement.getName();
            String elementType = testElement.getClass().getSimpleName();
            boolean wasEnabled = testElement.isEnabled();

            boolean newEnabledState;
            switch (action) {
                case "enable":
                    newEnabledState = true;
                    break;
                case "disable":
                    newEnabledState = false;
                    break;
                default:
                    newEnabledState = !wasEnabled;
                    break;
            }

            if (wasEnabled == newEnabledState) {
                String stateStr = wasEnabled ? "enabled" : "disabled";
                return ToolResult.success("Element **" + elementName + "** (" + elementType +
                        ") is already " + stateStr + ". No change needed." +
                        "\n\nElement Id: " + elementId);
            }

            // Follow JMeter's EnableComponent.enableComponents() pattern:
            // 1. Update tree node (also notifies treeModel to repaint)
            targetNode.setEnabled(newEnabledState);
            // 2. Update GUI component panel
            guiPackage.getGui(testElement).setEnabled(newEnabledState);

            String oldStateStr = wasEnabled ? "enabled" : "disabled";
            String newStateStr = newEnabledState ? "enabled" : "disabled";
            String actionVerb = newEnabledState ? "Enabled" : "Disabled";

            StringBuilder result = new StringBuilder();
            result.append(actionVerb).append(" element: **").append(elementName)
                    .append("** (").append(elementType).append(")\n\n");
            result.append("State: ").append(oldStateStr).append(" -> ").append(newStateStr).append("\n");
            result.append("Path: ").append(JMeterTreeUtils.getNodePath(targetNode)).append("\n");
            result.append("Element Id: ").append(elementId);

            log.info("{} element: {} ({}) with elementId: {}", actionVerb, elementName, elementType, elementId);
            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.error("Error toggling element with elementId: {}", elementId, e);
            return ToolResult.error("Failed to toggle element: " + e.getMessage());
        }
    }
}
