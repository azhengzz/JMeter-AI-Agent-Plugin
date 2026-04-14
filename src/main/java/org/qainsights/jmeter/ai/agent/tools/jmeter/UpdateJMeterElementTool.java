package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.ValidationResult;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool to update properties of existing JMeter test plan elements.
 * Supports updating HTTP samplers, thread groups, controllers, timers, assertions, etc.
 */
public class UpdateJMeterElementTool extends AbstractJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(UpdateJMeterElementTool.class);

    @Override
    public String getName() {
        return "update_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Update properties of an existing JMeter test plan element. " +
                "Supports updating HTTP samplers, thread groups, controllers, timers, assertions, listeners, etc. " +
                "Properties can be updated using the 'properties' parameter. " +
                "Use get_test_plan_tree or find_element to get the elementId of the element to update.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementId": {
                            "type": "integer",
                            "description": "The elementId of the element to update. Use get_test_plan_tree or find_element to get the elementId."
                        },
                        "properties": {
                            "type": "object",
                            "description": "Properties to update on the element. Property names should match JMeter property names (e.g., 'HTTPSampler.domain', 'HTTPSampler.path', 'ThreadGroup.num_threads'). Values can be strings, numbers, or booleans.",
                            "additionalProperties": true
                        }
                    },
                    "required": ["elementId"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // Extract parameters
        Integer elementId = getIntParameter(parameters, "elementId", -1);
        Map<String, Object> properties = parsePropertiesParameter(parameters.get("properties"));

        // Validate required parameters
        if (elementId == null || elementId <= 0) {
            return ToolResult.error("elementId is required and must be a positive integer");
        }

        // Check test plan readiness
        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return ToolResult.error("Cannot update element: " + status.getErrorMessage());
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        try {
            // Find target node
            JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            JMeterTreeNode targetNode = JMeterTreeUtils.findNodeByElementId(rootNode, elementId);

            if (targetNode == null) {
                return ToolResult.error("Could not find element with elementId: " + elementId +
                        ". The element may have been removed. Use get_test_plan_tree to get current elementIds.");
            }

            // Get element information
            TestElement element = targetNode.getTestElement();
            String elementName = element.getName();
            String elementType = element.getClass().getSimpleName();

            // Validate element can be updated
            ToolResult validation = validateElementUpdate(targetNode);
            if (!validation.isSuccess()) {
                return validation;
            }

            // Validate properties against schema if provided
            if (properties != null && !properties.isEmpty()) {
                ValidationResult propertyValidation = validateProperties(targetNode, properties);
                if (!propertyValidation.isValid()) {
                    return ToolResult.error(buildValidationErrorMessage(elementName, elementType, propertyValidation));
                }
            }

            // Perform the update
            ToolResult updateResult = updateElementProperties(targetNode, properties);
            if (!updateResult.isSuccess()) {
                return updateResult;
            }

            // Build and return success result
            return buildSuccessResult(elementName, elementType, properties);

        } catch (Exception e) {
            log.error("Error updating JMeter element", e);
            return ToolResult.error("Failed to update element: " + e.getMessage());
        }
    }

    /**
     * Validate that the element can be updated.
     */
    private ToolResult validateElementUpdate(JMeterTreeNode targetNode) {
        TestElement element = targetNode.getTestElement();

        // Check if element is read-only (e.g., TestPlan root has special handling)
        if (element == null) {
            return ToolResult.error("Cannot update element: element is null");
        }

        // Add any additional validation logic here if needed
        // For example, check if element is in a state that allows updates

        return ToolResult.success("");
    }

    /**
     * Validate properties against component schema.
     */
    private ValidationResult validateProperties(JMeterTreeNode targetNode, Map<String, Object> properties) {
        if (componentValidator == null) {
            return ValidationResult.valid();
        }

        // Get element type for schema lookup
        String elementType = JMeterTreeUtils.getElementType(targetNode);
        if (elementType == null) {
            log.warn("Could not determine element type for schema validation");
            return ValidationResult.valid(); // Allow update without schema validation
        }

        ValidationResult validation = componentValidator.validate(elementType, properties);
        if (!validation.isValid()) {
            log.warn("Property validation failed for {}: {}", elementType, validation.getErrors());
        }

        return validation;
    }

    /**
     * Update properties on the element.
     */
    private ToolResult updateElementProperties(JMeterTreeNode targetNode, Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            log.info("No properties to update");
            return ToolResult.success("");
        }

        TestElement element = targetNode.getTestElement();

        try {
            // Get element type for schema-based property handling
            String elementType = JMeterTreeUtils.getElementType(targetNode);

            // Load schema for property routing
            org.qainsights.jmeter.ai.agent.validation.ComponentSchema schema = null;
            if (componentValidator != null && elementType != null) {
                schema = componentValidator.getSchemaLoader().loadSchema(elementType);
            }

            // Use schema-based property handler to update properties
            propertyHandler.setProperties(element, properties, schema);

            // Refresh the tree to show updated properties
            refreshTreeAfterUpdate(targetNode);

            log.info("Successfully updated {} properties on element: {}",
                    properties.size(), element.getName());

            return ToolResult.success("");

        } catch (Exception e) {
            log.error("Failed to update properties on element", e);
            return ToolResult.error("Failed to update properties: " + e.getMessage());
        }
    }

    /**
     * Refresh the tree after property update.
     */
    private void refreshTreeAfterUpdate(JMeterTreeNode targetNode) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }

        try {
            // Notify tree model that node has changed
            guiPackage.getTreeModel().nodeChanged(targetNode);
            log.info("Tree node changed notification sent for: {}", targetNode.getName());

            // IMPORTANT: Use SwingUtilities.invokeLater to ensure GUI update happens on EDT
            // This prevents thread blocking and ensures proper Swing thread safety
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    // Refresh the GUI panel from the updated TestElement
                    // This calls bindingGroup.updateUi(element) which updates all bound fields
                    guiPackage.refreshCurrentGui();
                    log.info("Successfully refreshed GUI on EDT for element: {}", targetNode.getName());
                } catch (Exception e) {
                    log.error("Failed to refresh GUI on EDT", e);
                }
            });

            log.info("Successfully initiated tree and GUI refresh for updated element: {}", targetNode.getName());

        } catch (Exception e) {
            log.error("Failed to refresh tree after update", e);
        }
    }

    /**
     * Build a success result message.
     */
    private ToolResult buildSuccessResult(String elementName, String elementType, Map<String, Object> properties) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully updated element: **").append(elementName)
                .append("** (").append(elementType).append(")");

        if (properties != null && !properties.isEmpty()) {
            result.append("\n\nUpdated properties:\n");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                result.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append("\n");
            }
        } else {
            result.append("\n\nNo properties were updated.");
        }

        return ToolResult.success(result.toString());
    }

}
