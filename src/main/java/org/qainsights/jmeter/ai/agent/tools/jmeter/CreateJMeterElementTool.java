package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.agent.tools.ValidationResult;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.qainsights.jmeter.ai.agent.validation.ComponentSchemaLoader;
import org.qainsights.jmeter.ai.agent.validation.ComponentValidator;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.nio.file.Path;
import java.util.Map;

/**
 * Tool to create new JMeter elements in the test plan with optional properties.
 */
public class CreateJMeterElementTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CreateJMeterElementTool.class);
    private final ComponentValidator componentValidator;

    public CreateJMeterElementTool() {
        // Initialize validator with schema loader
        String jmeterHome = JMeterUtils.getJMeterHome();
        if (jmeterHome != null) {
            Path skillsDir = Path.of(jmeterHome, "bin", "jmeter-agent", "skills");
            ComponentSchemaLoader schemaLoader = new ComponentSchemaLoader(skillsDir);
            this.componentValidator = new ComponentValidator(schemaLoader);
        } else {
            log.warn("JMeter home not found, component validation will be disabled");
            this.componentValidator = null;
        }
    }

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
                            "description": "Optional instanceId of the parent node where to add the element. Use get_test_plan_tree or find_element to get instanceId. If not specified, adds to currently selected node."
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
        String elementType = getStringParameter(parameters, "elementType", null);
        String elementName = getStringParameter(parameters, "elementName", null);
        Integer parentId = getIntParameter(parameters, "parentId", -1);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        if (elementType == null || elementType.isEmpty()) {
            return ToolResult.error("elementType is required");
        }
        if (elementName == null || elementName.isEmpty()) {
            return ToolResult.error("elementName is required");
        }

        // Schema validation (if schema exists)
        if (componentValidator != null) {
            ValidationResult validation = componentValidator.validate(elementType, properties);
            if (!validation.isValid()) {
                String errorMsg = buildValidationErrorMessage(elementType, validation);
                log.warn("Component validation failed for {}: {}", elementType, errorMsg);
                return ToolResult.error(errorMsg);
            }
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

            // Determine the parent node where to add the element
            JMeterTreeNode parentNode;
            if (parentId != null && parentId > 0) {
                // Find parent node by instanceId
                JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
                parentNode = JMeterTreeUtils.findNodeByInstanceId(rootNode, parentId);
                if (parentNode == null) {
                    return ToolResult.error("Could not find parent node with instanceId: " + parentId +
                            ". The node may have been removed. Use get_test_plan_tree to get current instanceIds.");
                }
                // Select the parent node for visual feedback
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new TreePath(parentNode.getPath()));
                log.info("Selected parent node by instanceId: {}, name: {}", parentId, parentNode.getName());
            } else {
                // Use the currently selected node
                parentNode = guiPackage.getTreeListener().getCurrentNode();
                if (parentNode == null) {
                    return ToolResult.error("No node is currently selected in the test plan");
                }
            }

            // Create the element with properties
            TestElement newElement = createElementWithProperties(elementType, elementName, properties);

            if (newElement == null) {
                return ToolResult.error("Failed to create element of type: " + elementType);
            }

            // Check compatibility
            if (!JMeterElementManager.isNodeCompatible(parentNode, newElement)) {
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

            // Add the element to the test plan
            // guiPackage.getTreeModel().addComponent(newElement, parentNode);

            // Fix: use JMeter's classloader so ClassFinder can scan all jars (e.g., ResultRenderer in ApacheJMeter_components.jar)
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(guiPackage.getClass().getClassLoader());
                guiPackage.getTreeModel().addComponent(newElement, parentNode);
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
            log.info("Successfully added element to the tree model");

            // Restore original selection if we changed it
            if (parentId != null && parentId > 0 && originalSelectedNode != null) {
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new TreePath(originalSelectedNode.getPath()));
                log.info("Restored original selection");
            }

            // Refresh the tree and select the newly added element
            refreshTreeAndSelectNewElement(parentNode);

            StringBuilder result = new StringBuilder();
            result.append("Successfully created element: **").append(elementName).append("** (").append(elementType).append(")");

            if (properties != null && !properties.isEmpty()) {
                result.append("\n\nSet properties:\n");
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    result.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.error("Error creating JMeter element", e);
            return ToolResult.error("Failed to create element: " + e.getMessage());
        }
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

            // Special handling for ThreadGroup - it requires a LoopController
            if (normalizedType.equals("threadgroup")) {
                log.info("Initializing ThreadGroup with a LoopController");
                org.apache.jmeter.threads.ThreadGroup threadGroup =
                        (org.apache.jmeter.threads.ThreadGroup) element;

                // Create and initialize a LoopController
                org.apache.jmeter.control.LoopController loopController =
                        new org.apache.jmeter.control.LoopController();
                loopController.setLoops(1);
                loopController.setFirst(true);
                loopController.setProperty(TestElement.TEST_CLASS,
                        org.apache.jmeter.control.LoopController.class.getName());
                loopController.setProperty(TestElement.GUI_CLASS,
                        org.apache.jmeter.control.gui.LoopControlPanel.class.getName());

                // Set the controller on the ThreadGroup
                threadGroup.setSamplerController(loopController);
                log.info("LoopController initialized for ThreadGroup");
            }

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
                setElementProperties(element, properties);
            }

            log.info("Successfully created element: {}", element.getClass().getSimpleName());
            return element;

        } catch (Exception e) {
            log.error("Failed to create test element of type: {}", elementType, e);
            return null;
        }
    }

    /**
     * Set properties on a TestElement.
     * Supports special handling for complex properties like HTTPsampler.Arguments.
     *
     * @param element    The element to set properties on
     * @param properties The properties to set
     */
    private void setElementProperties(TestElement element, Map<String, Object> properties) {
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Object propValue = entry.getValue();

            // Apply property name mapping for JMeter historical naming quirks
            propName = mapPropertyName(propName);

            try {
                if (propValue == null) {
                    log.warn("Skipping null property: {}", propName);
                    continue;
                }

                // Special handling for HTTPsampler.Arguments
                if ("HTTPsampler.Arguments".equals(propName)) {
                    org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

                    // If the value is a Map, add HTTPArgument objects for each entry
                    if (propValue instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argMap = (Map<String, Object>) propValue;

                        try {
                            // Use reflection to create HTTPArgument instances
                            Class<?> httpArgClass = Class.forName("org.apache.jmeter.protocol.http.util.HTTPArgument");

                            // Check if this is a raw body request
                            // Priority 1: Check if HTTPSampler.postBodyRaw is set to true (already processed)
                            boolean isRawBody = "true".equals(element.getPropertyAsString("HTTPSampler.postBodyRaw", "false"));
                            // Priority 2: Check if HTTPSampler.postBodyRaw exists in original properties (not yet processed)
                            if (!isRawBody) {
                                for (java.util.Map.Entry<String, Object> prop : properties.entrySet()) {
                                    if ("HTTPSampler.postBodyRaw".equals(prop.getKey())) {
                                        Object propVal = prop.getValue();
                                        if (propVal instanceof Boolean && (Boolean) propVal) {
                                            isRawBody = true;
                                            break;
                                        }
                                        if (propVal instanceof String && "true".equalsIgnoreCase((String) propVal)) {
                                            isRawBody = true;
                                            break;
                                        }
                                    }
                                }
                            }

                            String rawBodyValue = null;
                            log.info("Processing HTTPsampler.Arguments with {} entries, rawBody mode: {}", argMap.size(), isRawBody);

                            if (isRawBody && argMap.size() == 1) {
                                // Raw body mode: extract the single value as the body content
                                java.util.Map.Entry<String, Object> singleEntry = argMap.entrySet().iterator().next();
                                rawBodyValue = singleEntry.getValue() != null ? singleEntry.getValue().toString() : "";
                                log.info("Raw body mode detected, content length: {}", rawBodyValue.length());
                            }

                            if (rawBodyValue != null) {
                                // Create raw body HTTPArgument (name="", value=body content)
                                Object httpArg = httpArgClass
                                        .getConstructor(String.class, String.class, boolean.class)
                                        .newInstance("", rawBodyValue, false);
                                args.addArgument((org.apache.jmeter.config.Argument) httpArg);
                                log.info("Added raw body HTTP argument, length: {}", rawBodyValue.length());
                            } else {
                                // Regular query parameters - create HTTPArgument for each entry
                                for (Map.Entry<String, Object> argEntry : argMap.entrySet()) {
                                    String argName = argEntry.getKey();
                                    String argValue = argEntry.getValue() != null ? argEntry.getValue().toString() : "";

                                    // Create HTTPArgument using constructor
                                    Object httpArg = httpArgClass
                                            .getConstructor(String.class, String.class, boolean.class)
                                            .newInstance(argName, argValue, false);

                                    // Add to Arguments
                                    args.addArgument((org.apache.jmeter.config.Argument) httpArg);
                                    log.info("Added HTTP argument: {} = {}", argName, argValue);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to create HTTPArgument, falling back to Argument", e);
                            // Fallback: use regular Argument
                            for (Map.Entry<String, Object> argEntry : argMap.entrySet()) {
                                String argName = argEntry.getKey();
                                String argValue = argEntry.getValue() != null ? argEntry.getValue().toString() : "";
                                args.addArgument(argName, argValue);
                                log.info("Added argument: {} = {}", argName, argValue);
                            }
                        }
                    }

                    element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(
                            propName, args));
                    log.info("Set property: {} = {}", propName, args);
                    continue;
                }

                // Special handling for HeaderManager.headers
                if ("HeaderManager.headers".equals(propName) && element.getClass().getName().contains("HeaderManager")) {
                    try {
                        // Use reflection to create Header objects
                        Class<?> headerClass = Class.forName("org.apache.jmeter.protocol.http.control.Header");

                        for (Map.Entry<String, Object> headerEntry : ((Map<String, Object>) propValue).entrySet()) {
                            String headerName = headerEntry.getKey();
                            String headerValue = headerEntry.getValue() != null ? headerEntry.getValue().toString() : "";

                            // Create Header using constructor: Header(String name, String value)
                            Object header = headerClass
                                    .getConstructor(String.class, String.class)
                                    .newInstance(headerName, headerValue);

                            // Add header using HeaderManager.add() method (not addHeader!)
                            element.getClass().getMethod("add", headerClass).invoke(element, header);
                            log.info("Added header: {} = {}", headerName, headerValue);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to create Header objects", e);
                        // Fallback: set as string
                        element.setProperty(propName, propValue.toString());
                    }
                    continue;
                }

                // Special handling for array/collection properties (e.g., Assertion.test_strings)
                if (isArrayValue(propValue)) {
                    try {
                        // Use reflection to create CollectionProperty and StringProperty
                        Class<?> collectionPropClass = Class.forName("org.apache.jmeter.testelement.property.CollectionProperty");
                        Class<?> stringPropClass = Class.forName("org.apache.jmeter.testelement.property.StringProperty");
                        Class<?> jmeterPropClass = Class.forName("org.apache.jmeter.testelement.property.JMeterProperty");

                        // Convert array/list to collection first
                        java.util.List<String> items = convertToList(propValue);

                        // Create CollectionProperty with name and empty collection
                        Object collectionProp = collectionPropClass
                                .getConstructor(String.class, java.util.Collection.class)
                                .newInstance(propName, new java.util.ArrayList<>());

                        // Get the addProperty method (not addItem!)
                        java.lang.reflect.Method addPropertyMethod = collectionPropClass.getMethod("addProperty", jmeterPropClass);

                        // Add each item as StringProperty
                        int index = 0;
                        for (String item : items) {
                            // Create StringProperty with a unique name (using index-based ID)
                            String uniqueName = String.valueOf(System.currentTimeMillis() + index);
                            Object stringProp = stringPropClass
                                    .getConstructor(String.class, String.class)
                                    .newInstance(uniqueName, item);

                            // Add to collection using addProperty
                            addPropertyMethod.invoke(collectionProp, stringProp);
                            index++;
                            log.info("Added item to {}: {}", propName, item);
                        }

                        // Set the collection property on the element
                        element.setProperty((org.apache.jmeter.testelement.property.JMeterProperty) collectionProp);
                        log.info("Set collection property: {} with {} items", propName, items.size());
                        continue;

                    } catch (Exception e) {
                        log.warn("Failed to create CollectionProperty for {}, trying fallback", propName, e);
                        // Fallback: join as comma-separated string
                        String joinedValue = convertToList(propValue).stream().collect(java.util.stream.Collectors.joining(","));
                        element.setProperty(propName, joinedValue);
                        log.info("Set property (fallback): {} = {}", propName, joinedValue);
                        continue;
                    }
                }

                // Convert the value to appropriate type
                if (propValue instanceof Number) {
                    if (propValue instanceof Integer || propValue instanceof Long) {
                        element.setProperty(propName, String.valueOf(((Number) propValue).longValue()));
                    } else {
                        element.setProperty(propName, String.valueOf(((Number) propValue).doubleValue()));
                    }
                } else if (propValue instanceof Boolean) {
                    element.setProperty(propName, String.valueOf(propValue));
                } else {
                    // Default to string
                    element.setProperty(propName, propValue.toString());
                }

                log.info("Set property: {} = {}", propName, propValue);

            } catch (Exception e) {
                log.warn("Failed to set property: {} = {}", propName, propValue, e);
            }
        }
    }

    /**
     * Refresh the tree and select the newly added element.
     *
     * @param parentNode The parent node where the element was added
     */
    private void refreshTreeAndSelectNewElement(JMeterTreeNode parentNode) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }

        try {
            // Refresh the tree to show the new element
            guiPackage.getTreeModel().nodeStructureChanged(parentNode);
            log.info("Successfully refreshed the tree");

            // Expand the parent node to show the new element
            guiPackage.getMainFrame().getTree()
                    .expandPath(new javax.swing.tree.TreePath(parentNode.getPath()));

            // Select the newly added element (last child of parent node)
            if (parentNode.getChildCount() > 0) {
                JMeterTreeNode lastChild = (JMeterTreeNode) parentNode.getChildAt(parentNode.getChildCount() - 1);
                guiPackage.getTreeListener().getJTree()
                        .setSelectionPath(new javax.swing.tree.TreePath(lastChild.getPath()));
                log.info("Selected newly added element: {}", lastChild.getName());
            }
        } catch (Exception e) {
            log.error("Failed to refresh tree or select element", e);
        }
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
     * Check if a value is an array or collection type.
     *
     * @param value The value to check
     * @return true if the value is an array or collection
     */
    private boolean isArrayValue(Object value) {
        if (value == null) {
            return false;
        }
        return value instanceof Iterable || value.getClass().isArray();
    }

    /**
     * Convert an array or collection value to a List of strings.
     *
     * @param value The value to convert
     * @return List of string values
     */
    private java.util.List<String> convertToList(Object value) {
        java.util.List<String> result = new java.util.ArrayList<>();

        if (value == null) {
            return result;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        } else if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (Object item : array) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }

        return result;
    }

    /**
     * Build a user-friendly error message for validation failures.
     *
     * @param elementType  The component type
     * @param validation   The validation result
     * @return Formatted error message
     */
    private String buildValidationErrorMessage(String elementType, ValidationResult validation) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Component Parameter Validation Failed**\n\n");
        sb.append("Component Type: ").append(elementType).append("\n\n");
        sb.append("Errors:\n");
        for (String error : validation.getErrors()) {
            sb.append("  - ").append(error).append("\n");
        }
        sb.append("\n**Solution**: Please provide all required parameters with correct values.\n");
        sb.append("Refer to component documentation for parameter details.");
        return sb.toString();
    }

    /**
     * Map property names to handle JMeter historical naming quirks.
     * This handles cases where JMeter uses incorrect spelling for backward compatibility.
     *
     * @param propName The property name from the user/AI
     * @return The actual property name to use in JMeter
     */
    private String mapPropertyName(String propName) {
        if (propName == null) {
            return null;
        }

        // Handle JMeter's historical spelling error: Asserion.test_strings (not Assertion.test_strings)
        if ("Assertion.test_strings".equals(propName)) {
            return "Asserion.test_strings";
        }

        return propName;
    }
}
