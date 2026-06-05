package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.ValidationResult;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
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
                "Supports universal properties: 'name' to update element name, 'comment' to update element comment. " +
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

            // Split properties: universal (name, comment) vs schema-defined
            Map<String, String> universalProps = new java.util.LinkedHashMap<>();
            Map<String, Object> schemaProps = new java.util.LinkedHashMap<>();
            splitProperties(properties, universalProps, schemaProps);

            // Validate schema-defined properties
            if (!schemaProps.isEmpty()) {
                ValidationResult propertyValidation = validateProperties(targetNode, schemaProps);
                if (!propertyValidation.isValid()) {
                    return ToolResult.error(buildValidationErrorMessage(elementName, elementType, propertyValidation));
                }
            }

            boolean hasUpdates = false;

            // Apply universal properties (name, comment)
            if (!universalProps.isEmpty()) {
                applyUniversalProperties(element, universalProps);
                hasUpdates = true;
            }

            // Apply schema-defined properties
            if (!schemaProps.isEmpty()) {
                ToolResult updateResult = updateElementProperties(targetNode, schemaProps);
                if (!updateResult.isSuccess()) {
                    return updateResult;
                }
                hasUpdates = true;
            }

            // Unified tree refresh after all updates
            if (hasUpdates) {
                refreshTreeAfterUpdate(targetNode);
            }

            // Build and return success result
            return buildSuccessResult(element, elementType, properties, universalProps);

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
     * Uses validateUpdate() which does NOT check for missing required parameters,
     * as update operations typically only modify a subset of properties.
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

        // Use validateUpdate() for update operations - skips required parameter checks
        ValidationResult validation = componentValidator.validateUpdate(elementType, properties);
        if (!validation.isValid()) {
            log.warn("Property validation failed for {}: {}", elementType, validation.getErrors());
        }

        return validation;
    }

    /**
     * Update schema-defined properties on the element.
     * Refresh is handled by the caller (executeInternal) after all updates are done.
     */
    private ToolResult updateElementProperties(JMeterTreeNode targetNode, Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            log.info("No schema properties to update");
            return ToolResult.success("");
        }

        TestElement element = targetNode.getTestElement();

        try {
            // Get element type for schema-based property handling
            String elementType = JMeterTreeUtils.getElementType(targetNode);
            log.info("Update element type from getElementType(): {}, element class: {}",
                    elementType, element.getClass().getSimpleName());

            // Load schema for property routing
            org.gitee.jmeter.ai.agent.validation.ComponentSchema schema = null;
            if (componentValidator != null && elementType != null) {
                schema = componentValidator.getSchemaLoader().loadSchema(elementType);
                log.info("Schema loaded for type '{}': {}", elementType, schema != null ? "found" : "NOT FOUND");
            } else {
                log.warn("Cannot load schema: componentValidator={}, elementType={}",
                        componentValidator != null ? "present" : "null", elementType);
            }

            // Use schema-based property handler to update properties
            propertyHandler.setProperties(element, properties, schema);

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

                    // Workaround: Some JMeter GUI panels (e.g., HeaderPanel) update their
                    // internal model in configure() but don't fire tableChanged events,
                    // so the JTable still shows stale data. Force-refresh any JTables.
                    refreshTables(guiPackage.getCurrentGui());

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
    private ToolResult buildSuccessResult(TestElement element, String elementType,
                                          Map<String, Object> properties,
                                          Map<String, String> universalProps) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully updated element: **").append(element.getName())
                .append("** (").append(elementType).append(")");

        boolean hasUpdates = false;

        if (universalProps != null && !universalProps.isEmpty()) {
            result.append("\n\nUpdated universal properties:\n");
            for (Map.Entry<String, String> entry : universalProps.entrySet()) {
                result.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append("\n");
            }
            hasUpdates = true;
        }

        if (properties != null && !properties.isEmpty()) {
            result.append("\n\nUpdated properties:\n");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                result.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append("\n");
            }
            hasUpdates = true;
        }

        if (!hasUpdates) {
            result.append("\n\nNo properties were updated.");
        }

        return ToolResult.success(result.toString());
    }

}
