package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return "{" +
                "\"type\": \"object\", " +
                "\"properties\": {" +
                "  \"elementType\": {" +
                "    \"type\": \"string\", " +
                "    \"description\": \"Type of element to create (e.g., 'HTTPSamplerProxy', 'ThreadGroup', 'LoopController', 'ConstantTimer', 'DurationAssertion', 'ResponseAssertion', 'Summariser', 'ViewResultsTree')\"" +
                "  }, " +
                "  \"elementName\": {" +
                "    \"type\": \"string\", " +
                "    \"description\": \"Name for the new element\"" +
                "  }, " +
                "  \"parentPath\": {" +
                "    \"type\": \"string\", " +
                "    \"description\": \"Optional path to parent node where to add the element (if not specified, adds to currently selected node's parent)\"" +
                "  }" +
                "}, " +
                "\"required\": [\"elementType\", \"elementName\"]" +
                "}";
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String elementType = getStringParameter(parameters, "elementType", null);
        String elementName = getStringParameter(parameters, "elementName", null);

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

            // Create the element using JMeter's GUI
            TestElement element = createElement(elementType, elementName);
            if (element == null) {
                return ToolResult.error("Unknown element type: " + elementType + ". " +
                        "Supported types include: HTTPSamplerProxy, ThreadGroup, LoopController, " +
                        "ConstantTimer, UniformRandomTimer, DurationAssertion, etc.");
            }

            // Add element to the tree
            boolean added = addElementToTree(element);
            if (!added) {
                return ToolResult.error("Failed to add element to test plan tree");
            }

            return ToolResult.success("Successfully created element: **" + elementName + "** (" + elementType + ")");

        } catch (Exception e) {
            log.error("Error creating JMeter element", e);
            return ToolResult.error("Failed to create element: " + e.getMessage());
        }
    }

    /**
     * Create a new test element of the specified type
     */
    private TestElement createElement(String elementType, String elementName) {
        try {
            // Use JMeter's GUI to create the element
            Class<?> elementClass = Class.forName("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy");
            // This is a simplified implementation
            // In production, use proper element creation based on type
            return null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Add the element to the test plan tree
     */
    private boolean addElementToTree(TestElement element) {
        // This is a placeholder implementation
        // In production, use GuiPackage to properly add the element
        return false;
    }
}
