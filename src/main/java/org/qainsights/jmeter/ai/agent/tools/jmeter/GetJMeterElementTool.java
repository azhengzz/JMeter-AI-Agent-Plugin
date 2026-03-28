package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;

import java.util.Map;

/**
 * Tool to get information about the currently selected JMeter element.
 * Corresponds to the @this command.
 */
public class GetJMeterElementTool extends AbstractTool {

    @Override
    public String getName() {
        return "get_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Get detailed information about the currently selected JMeter test element in the test plan tree. " +
                "Returns the element type, name, properties, and configuration details.";
    }

    @Override
    public String getParameterSchema() {
        return "{\"type\": \"object\", \"properties\": {}}";
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        JMeterTreeNode selectedNode = guiPackage.getTreeListener().getCurrentNode();
        if (selectedNode == null) {
            return ToolResult.success("No element is currently selected in the test plan.");
        }

        TestElement element = selectedNode.getTestElement();
        if (element == null) {
            return ToolResult.error("Selected node has no valid test element");
        }

        StringBuilder info = new StringBuilder();
        info.append("## Selected JMeter Element\n\n");

        // Element type and name
        info.append("**Type:** ").append(element.getClass().getSimpleName()).append("\n");
        info.append("**Name:** ").append(element.getName()).append("\n\n");

        // Get parent path
        String path = getElementPath(selectedNode);
        if (!path.isEmpty()) {
            info.append("**Path in Test Plan:** ").append(path).append("\n\n");
        }

        // Properties
        info.append("**Properties:**\n");
        PropertyIterator propIterator = element.propertyIterator();
        int propCount = 0;
        while (propIterator.hasNext()) {
            JMeterProperty prop = propIterator.next();
            String propName = prop.getName();
            // Skip internal TestElement properties
            if (!propName.startsWith("TestElement.")) {
                String propValue = prop.getStringValue();
                if (propValue != null && !propValue.isEmpty()) {
                    info.append("- ").append(propName).append(": ").append(truncate(propValue, 100)).append("\n");
                    propCount++;
                }
            }
            // Limit properties to avoid overwhelming output
            if (propCount >= 30) {
                info.append("- ... (and more properties)\n");
                break;
            }
        }

        if (propCount == 0) {
            info.append("(No additional properties)\n");
        }

        return ToolResult.success(info.toString());
    }

    /**
     * Get the path of the element in the test plan tree
     */
    private String getElementPath(JMeterTreeNode node) {
        StringBuilder path = new StringBuilder();
        JMeterTreeNode current = node;
        while (current != null) {
            TestElement element = current.getTestElement();
            if (element != null && element.getName() != null) {
                if (path.length() > 0) {
                    path.insert(0, " > ");
                }
                path.insert(0, element.getName());
            }
            current = (JMeterTreeNode) current.getParent();
        }
        return path.toString();
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }
}
