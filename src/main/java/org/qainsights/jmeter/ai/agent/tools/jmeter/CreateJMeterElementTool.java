package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.ValidationResult;
import org.qainsights.jmeter.ai.agent.tools.jmeter.property.SchemaBasedPropertyHandler;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.qainsights.jmeter.ai.agent.validation.ComponentValidator;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.util.Map;

/**
 * Tool to create new JMeter elements in the test plan with optional properties.
 */
public class CreateJMeterElementTool extends AbstractJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(CreateJMeterElementTool.class);

    @Override
    public String getName() {
        return "create_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Create a new JMeter test element and add it to the test plan. " +
                "Supports creating HTTP Samplers, Thread Groups, Controllers, Timers, Assertions, Listeners, etc. " +
                "Properties can be set on the created element using the 'properties' parameter.";
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
                        "parentId": {
                            "type": "integer",
                            "description": "Optional elementId of the parent node where to add the element. Use get_test_plan_tree or find_element to get elementId. If not specified, adds to currently selected node."
                        },
                        "properties": {
                            "type": "object",
                            "description": "Optional properties to set on the element. Property names should match JMeter property names (e.g., 'HTTPSampler.domain', 'HTTPSampler.path', 'ThreadGroup.num_threads', 'LoopController.loops'). Values can be strings, numbers, or booleans.",
                            "additionalProperties": true
                        }
                    },
                    "required": ["elementType", "elementName"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // Extract parameters
        String elementType = getStringParameter(parameters, "elementType", null);
        String elementName = getStringParameter(parameters, "elementName", null);
        Integer parentId = getIntParameter(parameters, "parentId", -1);

        // Parse properties parameter - handle both Map and JSON string
        Map<String, Object> properties = parsePropertiesParameter(parameters.get("properties"));

        // Validate required parameters
        if (elementType == null || elementType.isEmpty()) {
            return ToolResult.error("elementType is required");
        }
        if (elementName == null || elementName.isEmpty()) {
            return ToolResult.error("elementName is required");
        }

        // Check schema exists for the element type
        if (componentValidator != null) {
            org.qainsights.jmeter.ai.agent.validation.ComponentSchema schema =
                    componentValidator.getSchemaLoader().loadSchema(elementType);
            if (schema == null) {
                return ToolResult.error("Unknown elementType: '" + elementType + "'. " +
                        "No schema definition found. Please check the element type or refer to the component reference.");
            }
        }

        // Apply schema validation and defaults
        ValidationResult validationResult = validateComponent(elementType, properties);
        if (!validationResult.isValid()) {
            return ToolResult.error(buildValidationErrorMessage(elementType, validationResult));
        }

        // Apply default values from schema for properties that were not provided
        properties = applyDefaultValues(elementType, properties);
        log.info("Properties after applying defaults for {} ({} properties):", elementType, properties.size());
        properties.forEach((key, value) -> log.info("  {} = {}", key, value));

        // Check test plan readiness
        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return ToolResult.error("Cannot create element: " + status.getErrorMessage());
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        try {
            // Determine parent node
            JMeterTreeNode parentNode;
            if (parentId != null && parentId > 0) {
                // Find parent node by elementId
                JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
                parentNode = JMeterTreeUtils.findNodeByElementId(rootNode, parentId);
                if (parentNode == null) {
                    return ToolResult.error("Could not find parent node with elementId: " + parentId +
                            ". The node may have been removed. Use get_test_plan_tree to get current elementIds.");
                }
                // Select the parent node for visual feedback
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new TreePath(parentNode.getPath()));
                log.info("Selected parent node by elementId: {}, name: {}", parentId, parentNode.getName());
            } else {
                // Use the currently selected node
                parentNode = guiPackage.getTreeListener().getCurrentNode();
                if (parentNode == null) {
                    return ToolResult.error("No node is currently selected in the test plan");
                }
            }

            // Create element with properties
            TestElement newElement = createElementWithProperties(elementType, elementName, properties);
            if (newElement == null) {
                return ToolResult.error("Failed to create element of type: " + elementType);
            }

            // Check compatibility
            ToolResult compatibilityResult = checkCompatibility(parentNode, newElement, elementType);
            if (!compatibilityResult.isSuccess()) {
                return compatibilityResult;
            }

            // Add element to test plan
            ToolResult addResult = addElementToTestPlan(guiPackage, newElement, parentNode);
            if (!addResult.isSuccess()) {
                return addResult;
            }

            // Build and return success result
            return buildSuccessResult(elementName, elementType, properties);

        } catch (Exception e) {
            log.error("Error creating JMeter element", e);
            return ToolResult.error("Failed to create element: " + e.getMessage());
        }
    }

    /**
     * Validate component parameters against schema.
     *
     * @param elementType  The component type
     * @param properties   The provided properties (may be null)
     * @return ValidationResult containing validation status
     */
    private ValidationResult validateComponent(String elementType, Map<String, Object> properties) {
        if (componentValidator == null) {
            return ValidationResult.valid();
        }

        ValidationResult validation = componentValidator.validate(elementType, properties);
        if (!validation.isValid()) {
            log.warn("Component validation failed for {}: {}", elementType, validation.getErrors());
        }

        return validation;
    }

    /**
     * Check if a child element is compatible with the parent node.
     *
     * @param parentNode   The parent node
     * @param newElement   The element to add
     * @param elementType  The element type for error messages
     * @return ToolResult indicating compatibility status
     */
    private ToolResult checkCompatibility(JMeterTreeNode parentNode, TestElement newElement, String elementType) {
        if (JMeterElementManager.isNodeCompatible(parentNode, newElement)) {
            return ToolResult.success("");
        }

        String parentTypeName = parentNode.getTestElement().getClass().getSimpleName();
        String parentNodeSupportedTypes = JMeterElementManager.getSupportedChildTypesDescription(parentNode);
        String childElementSupportedParents = JMeterElementManager.getSupportedParentTypesDescription(newElement);

        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append("Cannot add element of type '").append(elementType)
                .append("' to the parent node '").append(parentNode.getTestElement().getName())
                .append("' (type: ").append(parentTypeName).append("').\n\n");
        errorMsg.append("**Compatibility Rule Violation**\n\n");
        errorMsg.append("1. Current parent node constraints:\n   ");
        errorMsg.append(parentNodeSupportedTypes != null ? parentNodeSupportedTypes : "Unable to determine.");
        errorMsg.append("\n\n");
        errorMsg.append("2. Child element requirements:\n   ");
        errorMsg.append(childElementSupportedParents != null ? childElementSupportedParents : "Unable to determine.");
        errorMsg.append("\n\n**Solution**: Try adding this element to a compatible parent node type listed above.");

        return ToolResult.error(errorMsg.toString());
    }

    /**
     * Add an element to the test plan tree.
     *
     * @param guiPackage The GuiPackage instance
     * @param newElement The element to add
     * @param parentNode The parent node
     * @return ToolResult indicating success or failure
     */
    private ToolResult addElementToTestPlan(GuiPackage guiPackage, TestElement newElement,
                                             JMeterTreeNode parentNode) {
        // The addComponent() call must be on EDT because it configures GUI components
        // Use invokeLater to schedule on EDT
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                // Fix: use JMeter's classloader so ClassFinder can scan all jars (e.g., ResultRenderer in ApacheJMeter_components.jar)
                ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(guiPackage.getClass().getClassLoader());
                    guiPackage.getTreeModel().addComponent(newElement, parentNode);
                    log.info("Successfully added element to the tree model");

                    // After adding, refresh and select the new element
                    // Do this immediately in the same invokeLater to ensure proper ordering
                    try {
                        guiPackage.getMainFrame().getTree()
                                .expandPath(new javax.swing.tree.TreePath(parentNode.getPath()));

                        if (parentNode.getChildCount() > 0) {
                            JMeterTreeNode lastChild = (JMeterTreeNode) parentNode.getChildAt(parentNode.getChildCount() - 1);
                            guiPackage.getTreeListener().getJTree()
                                    .setSelectionPath(new javax.swing.tree.TreePath(lastChild.getPath()));
                            log.info("Selected newly added element: {}", lastChild.getName());
                        }
                    } catch (Exception e) {
                        log.error("Failed to expand tree or select element on EDT", e);
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(originalClassLoader);
                }
            } catch (Exception e) {
                log.error("Failed to add element to tree model on EDT", e);
            }
        });

        return ToolResult.success("");
    }

    /**
     * Build a success result message.
     *
     * @param elementName The element name
     * @param elementType The element type
     * @param properties  The properties that were set
     * @return ToolResult with success message
     */
    private ToolResult buildSuccessResult(String elementName, String elementType, Map<String, Object> properties) {
        StringBuilder result = new StringBuilder();
        result.append("Successfully created element: **").append(elementName).append("** (").append(elementType).append(")");

        if (properties != null && !properties.isEmpty()) {
            result.append("\n\nSet properties:\n");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                result.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return ToolResult.success(result.toString());
    }

    /**
     * Create a TestElement with the specified properties.
     *
     * @param elementType The type of element to create
     * @param elementName The name for the element
     * @param properties  Optional properties to set on the element
     * @return The created TestElement, or null if creation failed
     */
    private TestElement createElementWithProperties(String elementType, String elementName,
                                                     Map<String, Object> properties) {
        log.info("Creating element of type: {} with name: {}", elementType, elementName);

        // Get the class info for the element type
        String normalizedType = JMeterElementManager.normalizeElementType(elementType);
        JMeterElementManager.ElementClassInfo classInfo = JMeterElementManager.getElementClassInfo(normalizedType);
        if (classInfo == null) {
            log.error("Could not find class info for element type: {}", elementType);
            return null;
        }

        try {
            Class<?> elementClass = Class.forName(classInfo.getModelClassName());
            Class<? extends org.apache.jmeter.gui.JMeterGUIComponent> guiClass =
                    Class.forName(classInfo.getGuiClassName())
                            .asSubclass(org.apache.jmeter.gui.JMeterGUIComponent.class);

            TestElement element = JMeterElementManager.createTestElement(
                    elementClass.asSubclass(TestElement.class), guiClass);

            // Set the name
            element.setName(elementName);

            // For HTTP-related elements, ensure HTTPsampler.Arguments is initialized
            // This prevents NoSuchElementException when JMeter code calls get(schema.getArguments())
            if (isHttpRelatedElement(normalizedType) && (properties == null || !properties.containsKey("HTTPsampler.Arguments"))) {
                log.info("Initializing empty HTTPsampler.Arguments for HTTP-related element: {}", normalizedType);
                org.apache.jmeter.config.Arguments emptyArgs = new org.apache.jmeter.config.Arguments();
                element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(
                        "HTTPsampler.Arguments", emptyArgs));
            }

            // Set properties if provided (including HTTPsampler.Arguments if needed)
            if (properties != null && !properties.isEmpty()) {
                try {
                    setElementProperties(element, properties, normalizedType);
                } catch (Exception e) {
                    log.error("Failed to set properties on element, returning null", e);
                    return null;
                }
            }

            log.info("Successfully created element: {}", element.getClass().getSimpleName());
            return element;

        } catch (Exception e) {
            log.error("Failed to create test element of type: {}", elementType, e);
            return null;
        }
    }

    /**
     * Set properties on a TestElement using schema-based property handler.
     * Properties are routed based on schema type definitions.
     *
     * @param element       The element to set properties on
     * @param properties    The properties to set
     * @param elementType   The component type for schema lookup
     */
    private void setElementProperties(TestElement element, Map<String, Object> properties, String elementType) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        // Load schema for this element type
        org.qainsights.jmeter.ai.agent.validation.ComponentSchema schema = null;
        if (componentValidator != null) {
            schema = componentValidator.getSchemaLoader().loadSchema(elementType);
        }

        // Use schema-based property handler to set all properties
        propertyHandler.setProperties(element, properties, schema);
    }

    /**
     * Check if an element type is HTTP-related and requires HTTPsampler.Arguments.
     *
     * @param elementType The normalized element type
     * @return true if the element is HTTP-related
     */
    private boolean isHttpRelatedElement(String elementType) {
        // HTTP samplers that extend HTTPSamplerBase and require HTTPsampler.Arguments
        return elementType.equals("httpsampler") ||
                elementType.equals("httpdefaults") ||
                elementType.equals("ajpsampler") ||
                elementType.equals("graphqlhttprequest") ||
                elementType.equals("httptestsample");
    }

    /**
     * Apply default values from schema for properties that were not provided.
     * Recursively applies default values for nested object properties.
     *
     * @param elementType The component type
     * @param properties  The provided properties (may be null)
     * @return A new map with default values applied
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyDefaultValues(String elementType, Map<String, Object> properties) {
        if (componentValidator == null) {
            return properties;
        }

        org.qainsights.jmeter.ai.agent.validation.ComponentSchema schema =
            componentValidator.getSchemaLoader().loadSchema(elementType);

        if (schema == null) {
            return properties;
        }

        // Create a new map to avoid modifying the original
        Map<String, Object> result = new java.util.HashMap<>();
        if (properties != null) {
            result.putAll(properties);
        }

        // Apply default values for missing properties
        for (org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyDefinition propDef :
             schema.getProperties()) {
            String propName = propDef.getName();
            if (propName == null || propName.isEmpty()) {
                continue;
            }

            // Check if this is a nested object property
            if (propDef.getType() == org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyType.OBJECT
                    && propDef.hasNestedProperties()) {
                // Handle nested object property defaults
                Object currentValue = result.get(propName);
                if (currentValue == null) {
                    // Property not provided, create defaults map
                    Map<String, Object> nestedDefaults = createNestedDefaults(propDef);
                    if (!nestedDefaults.isEmpty()) {
                        result.put(propName, nestedDefaults);
                        log.info("Applied default nested object for {} with properties: {}", propName, nestedDefaults.keySet());
                    }
                } else if (currentValue instanceof Map) {
                    // Property provided as map, merge with defaults
                    Map<String, Object> nestedProps = (Map<String, Object>) currentValue;
                    Map<String, Object> merged = mergeNestedDefaults(propDef, nestedProps);
                    result.put(propName, merged);
                    if (!merged.equals(nestedProps)) {
                        log.info("Merged defaults for {} properties: {}", propName, merged.keySet());
                    }
                }
                continue;
            }

            // Skip if property is already set
            if (result.containsKey(propName)) {
                continue;
            }

            // Apply default value if exists
            Object defaultValue = propDef.getDefaultValue();
            if (defaultValue != null) {
                result.put(propName, defaultValue);
                log.info("Applied default value for {}: {}", propName, defaultValue);
            }
        }

        return result;
    }

    /**
     * Create a map of default values for nested object properties.
     */
    private Map<String, Object> createNestedDefaults(
            org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyDefinition propDef) {
        Map<String, Object> defaults = new java.util.HashMap<>();
        if (propDef.getNestedProperties() == null) {
            return defaults;
        }

        for (org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyDefinition nestedDef :
                propDef.getNestedProperties()) {
            String nestedPropName = nestedDef.getName();
            if (nestedPropName == null || nestedPropName.isEmpty()) {
                continue;
            }

            Object defaultValue = nestedDef.getDefaultValue();
            if (defaultValue != null) {
                defaults.put(nestedPropName, defaultValue);
            }

            // Recursively create defaults for deeper nested objects
            if (nestedDef.getType() == org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyType.OBJECT
                    && nestedDef.hasNestedProperties()) {
                Map<String, Object> deeperDefaults = createNestedDefaults(nestedDef);
                if (!deeperDefaults.isEmpty()) {
                    defaults.put(nestedPropName, deeperDefaults);
                }
            }
        }

        return defaults;
    }

    /**
     * Merge provided nested properties with schema defaults.
     * Provided values take precedence over defaults.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeNestedDefaults(
            org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyDefinition propDef,
            Map<String, Object> providedProps) {
        Map<String, Object> result = new java.util.HashMap<>(providedProps);

        if (propDef.getNestedProperties() == null) {
            return result;
        }

        for (org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyDefinition nestedDef :
                propDef.getNestedProperties()) {
            String nestedPropName = nestedDef.getName();
            if (nestedPropName == null || nestedPropName.isEmpty()) {
                continue;
            }

            // Skip if property is already provided
            if (result.containsKey(nestedPropName)) {
                // But check if it's a nested object that needs deeper merging
                Object currentValue = result.get(nestedPropName);
                if (currentValue instanceof Map && nestedDef.hasNestedProperties()) {
                    Map<String, Object> merged = mergeNestedDefaults(nestedDef, (Map<String, Object>) currentValue);
                    result.put(nestedPropName, merged);
                }
                continue;
            }

            // Apply default value
            Object defaultValue = nestedDef.getDefaultValue();
            if (defaultValue != null) {
                result.put(nestedPropName, defaultValue);
            }

            // Recursively handle deeper nested objects
            if (nestedDef.getType() == org.qainsights.jmeter.ai.agent.validation.ComponentSchema.PropertyType.OBJECT
                    && nestedDef.hasNestedProperties()) {
                Map<String, Object> deeperDefaults = createNestedDefaults(nestedDef);
                if (!deeperDefaults.isEmpty()) {
                    result.put(nestedPropName, deeperDefaults);
                }
            }
        }

        return result;
    }
}
