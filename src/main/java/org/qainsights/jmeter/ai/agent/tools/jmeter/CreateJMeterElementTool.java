package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.util.Map;

/**
 * Tool to create new JMeter elements in the test plan with optional properties.
 */
public class CreateJMeterElementTool extends AbstractTool {

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
                return ToolResult.error("Cannot add element of type '" + elementType +
                        "' to the parent node of type '" + parentNode.getTestElement().getClass().getSimpleName() +
                        "'. The node type is not compatible.");
            }

            // Add the element to the test plan
            guiPackage.getTreeModel().addComponent(newElement, parentNode);
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

            // Set properties if provided
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
     *
     * @param element    The element to set properties on
     * @param properties The properties to set
     */
    private void setElementProperties(TestElement element, Map<String, Object> properties) {
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Object propValue = entry.getValue();

            try {
                if (propValue == null) {
                    log.warn("Skipping null property: {}", propName);
                    continue;
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
}
