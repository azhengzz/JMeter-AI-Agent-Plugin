package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.ValidationResult;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool to batch update properties of multiple JMeter elements of the same type.
 */
public class BatchUpdateJMeterElementTool extends AbstractJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(BatchUpdateJMeterElementTool.class);
    private static final int MAX_BATCH_SIZE = 50;

    @Override
    public String getName() {
        return "batch_update_jmeter_elements";
    }

    @Override
    public String getDescription() {
        return "Batch update properties of multiple JMeter elements of the same type. " +
                "All elements must be the same elementType. " +
                "Schema validation is performed once, and the GUI is refreshed once after all updates. " +
                "Use get_test_plan_tree or find_element to get elementIds.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementIds": {
                            "type": "array",
                            "items": {
                                "type": "integer"
                            },
                            "description": "Array of elementIds to update. All elements must be the same elementType. Use get_test_plan_tree or find_element to get elementIds.",
                            "minItems": 1,
                            "maxItems": 50
                        },
                        "properties": {
                            "type": "object",
                            "description": "Properties to update on all specified elements. Supports universal properties: 'name' to update element name, 'comment' to update element comment.",
                            "additionalProperties": true
                        }
                    },
                    "required": ["elementIds", "properties"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // Parse elementIds
        Object elementIdsRaw = parameters.get("elementIds");
        if (!(elementIdsRaw instanceof List)) {
            return ToolResult.error("elementIds must be a non-empty array of integers");
        }
        List<Integer> elementIds = new ArrayList<>();
        for (Object item : (List<?>) elementIdsRaw) {
            if (item instanceof Number) {
                elementIds.add(((Number) item).intValue());
            }
        }

        // Parse properties
        Map<String, Object> properties = parsePropertiesParameter(parameters.get("properties"));

        // Validate input
        if (elementIds.isEmpty()) {
            return ToolResult.error("elementIds must be a non-empty array with at most " + MAX_BATCH_SIZE + " elements");
        }
        if (elementIds.size() > MAX_BATCH_SIZE) {
            return ToolResult.error("elementIds exceeds maximum batch size of " + MAX_BATCH_SIZE +
                    ". Got " + elementIds.size() + " elements.");
        }
        if (properties.isEmpty()) {
            return ToolResult.error("properties must be provided for batch update");
        }

        // Check test plan readiness
        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return ToolResult.error("Cannot update elements: " + status.getErrorMessage());
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        try {
            JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();

            // Step 1: Find all nodes
            Map<Integer, JMeterTreeNode> nodeMap = new LinkedHashMap<>();
            List<Integer> notFound = new ArrayList<>();
            for (Integer id : elementIds) {
                JMeterTreeNode node = JMeterTreeUtils.findNodeByElementId(rootNode, id);
                if (node == null) {
                    notFound.add(id);
                } else {
                    nodeMap.put(id, node);
                }
            }
            if (!notFound.isEmpty()) {
                return ToolResult.error("Could not find elements with elementIds: " + notFound +
                        ". The elements may have been removed. Use get_test_plan_tree to get current elementIds.");
            }

            // Step 2: Validate all elements are same type
            String elementType = null;
            Map<Integer, String> typeMap = new LinkedHashMap<>();
            for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
                String type = JMeterTreeUtils.getElementType(entry.getValue());
                typeMap.put(entry.getKey(), type);
                if (elementType == null) {
                    elementType = type;
                }
            }
            if (elementType == null) {
                return ToolResult.error("Could not determine element type for the specified elements");
            }
            List<Integer> mismatchedIds = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : typeMap.entrySet()) {
                if (!elementType.equals(entry.getValue())) {
                    mismatchedIds.add(entry.getKey());
                }
            }
            if (!mismatchedIds.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Cannot batch update: elements have different types.\n");
                for (Map.Entry<Integer, String> entry : typeMap.entrySet()) {
                    sb.append("- elementId ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                sb.append("All elements must be the same type.");
                return ToolResult.error(sb.toString());
            }

            // Step 3: Split properties
            Map<String, String> universalProps = new LinkedHashMap<>();
            Map<String, Object> schemaProps = new LinkedHashMap<>();
            splitProperties(properties, universalProps, schemaProps);

            // Step 4: Validate schema properties once
            if (!schemaProps.isEmpty() && componentValidator != null) {
                ValidationResult validation = componentValidator.validateUpdate(elementType, schemaProps);
                if (!validation.isValid()) {
                    return ToolResult.error(buildValidationErrorMessage(elementType, validation));
                }
            }

            // Step 5: Load schema once
            ComponentSchema schema = null;
            if (componentValidator != null && elementType != null) {
                schema = componentValidator.getSchemaLoader().loadSchema(elementType);
            }

            // Step 6: Apply updates to each element
            List<ElementUpdateResult> results = new ArrayList<>();
            for (Map.Entry<Integer, JMeterTreeNode> entry : nodeMap.entrySet()) {
                int id = entry.getKey();
                JMeterTreeNode node = entry.getValue();
                TestElement element = node.getTestElement();
                String elementName = element.getName();

                try {
                    if (!universalProps.isEmpty()) {
                        applyUniversalProperties(element, universalProps);
                    }
                    if (!schemaProps.isEmpty()) {
                        propertyHandler.setProperties(element, schemaProps, schema);
                    }
                    results.add(new ElementUpdateResult(id, elementName, true, null));
                    log.info("Successfully updated element: {} (elementId: {})", elementName, id);
                } catch (Exception e) {
                    results.add(new ElementUpdateResult(id, elementName, false, e.getMessage()));
                    log.error("Failed to update element: {} (elementId: {})", elementName, id, e);
                }
            }

            // Step 7: Single GUI refresh
            refreshTreeAfterBatchUpdate(guiPackage, nodeMap);

            // Step 8: Build aggregated result
            return buildBatchResult(results, elementType, properties);

        } catch (Exception e) {
            log.error("Error batch updating JMeter elements", e);
            return ToolResult.error("Failed to batch update elements: " + e.getMessage());
        }
    }

    private void refreshTreeAfterBatchUpdate(GuiPackage guiPackage, Map<Integer, JMeterTreeNode> nodeMap) {
        try {
            for (JMeterTreeNode node : nodeMap.values()) {
                guiPackage.getTreeModel().nodeChanged(node);
            }

            SwingUtilities.invokeLater(() -> {
                try {
                    guiPackage.refreshCurrentGui();
                    refreshTables(guiPackage.getCurrentGui());
                    log.info("Successfully refreshed GUI after batch update");
                } catch (Exception e) {
                    log.error("Failed to refresh GUI on EDT after batch update", e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to refresh tree after batch update", e);
        }
    }

    private ToolResult buildBatchResult(List<ElementUpdateResult> results, String elementType,
                                         Map<String, Object> properties) {
        long successCount = results.stream().filter(r -> r.success).count();
        int total = results.size();

        StringBuilder sb = new StringBuilder();

        if (successCount == total) {
            sb.append("Successfully updated ").append(total).append(" of ").append(total)
                    .append(" elements (type: ").append(elementType).append(")\n");

            sb.append("\nUpdated properties:\n");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }

            sb.append("\nDetails:\n");
            for (ElementUpdateResult r : results) {
                sb.append("- elementId ").append(r.elementId).append(": \"").append(r.elementName)
                        .append("\" - OK\n");
            }
        } else {
            sb.append("Updated ").append(successCount).append(" of ").append(total)
                    .append(" elements (type: ").append(elementType).append(")\n");

            List<ElementUpdateResult> succeeded = results.stream().filter(r -> r.success).toList();
            if (!succeeded.isEmpty()) {
                sb.append("\nSucceeded:\n");
                for (ElementUpdateResult r : succeeded) {
                    sb.append("- elementId ").append(r.elementId).append(": \"").append(r.elementName)
                            .append("\" - OK\n");
                }
            }

            List<ElementUpdateResult> failed = results.stream().filter(r -> !r.success).toList();
            if (!failed.isEmpty()) {
                sb.append("\nFailed:\n");
                for (ElementUpdateResult r : failed) {
                    sb.append("- elementId ").append(r.elementId).append(": \"").append(r.elementName)
                            .append("\" - ").append(r.error).append("\n");
                }
            }
        }

        return ToolResult.success(sb.toString());
    }

    private static class ElementUpdateResult {
        final int elementId;
        final String elementName;
        final boolean success;
        final String error;

        ElementUpdateResult(int elementId, String elementName, boolean success, String error) {
            this.elementId = elementId;
            this.elementName = elementName;
            this.success = success;
            this.error = error;
        }
    }
}
