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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Tool to batch update properties of multiple JMeter elements of the same type.
 */
public class BatchUpdateJMeterElementTool extends AbstractBatchJMeterElementTool {

    private static final Logger log = LoggerFactory.getLogger(BatchUpdateJMeterElementTool.class);

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
        // Parse elementIds + properties
        List<Integer> elementIds = parseElementIds(parameters.get("elementIds"));
        Map<String, Object> properties = parsePropertiesParameter(parameters.get("properties"));

        // Validate input
        ToolResult sizeCheck = validateBatchSize(elementIds);
        if (!sizeCheck.isSuccess()) {
            return sizeCheck;
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

            // Step 1: Find all nodes (resolve up front; abort if any missing)
            ResolvedNodes resolved = resolveNodeMap(rootNode, elementIds);
            if (!resolved.notFound().isEmpty()) {
                return ToolResult.error("Could not find elements with elementIds: " + resolved.notFound() +
                        ". The elements may have been removed. Use get_test_plan_tree to get current elementIds.");
            }
            Map<Integer, JMeterTreeNode> nodeMap = resolved.nodeMap();

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

            // Step 6: Apply updates and refresh GUI atomically on EDT (mirrors the single-element
            // UpdateJMeterElementTool pattern — putting property writes on EDT serializes them
            // with any configure() call and prevents the half-written-TestElement race).
            deselectCurrentNode(guiPackage);

            List<ElementResult> results = new ArrayList<>();
            applyBatchUpdateOnEdt(guiPackage, nodeMap, universalProps, schemaProps, schema, results);

            // Step 7: Build aggregated result (type + updated-properties block as header extra)
            StringBuilder headerExtra = new StringBuilder();
            headerExtra.append("Type: ").append(elementType).append("\n\nUpdated properties:\n");
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                headerExtra.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            return buildBatchResult(results, "updated", headerExtra.toString(), r -> "OK");

        } catch (Exception e) {
            log.error("Error batch updating JMeter elements", e);
            return ToolResult.error("Failed to batch update elements: " + e.getMessage());
        }
    }

    private void applyBatchUpdateOnEdt(GuiPackage guiPackage, Map<Integer, JMeterTreeNode> nodeMap,
                                        Map<String, String> universalProps, Map<String, Object> schemaProps,
                                        ComponentSchema schema, List<ElementResult> results) {
        // Property writes + GUI refresh run atomically on EDT (mirrors the single-element
        // UpdateJMeterElementTool pattern); this serializes TestElement writes with any
        // concurrent EDT configure() call and prevents the half-written-TestElement race.
        Exception edtError = org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner.run(guiPackage, () -> {
            // Per-element property writes (each try/catch so one failure doesn't stop the rest)
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
                    results.add(ElementResult.ok(id, elementName));
                    log.info("Successfully updated element: {} (elementId: {})", elementName, id);
                } catch (Exception e) {
                    results.add(ElementResult.fail(id, elementName, e.getMessage()));
                    log.error("Failed to update element: {} (elementId: {})", elementName, id, e);
                }
            }
            // Trailing refresh is best-effort: property writes are already committed above, so
            // a refresh failure must not mask the per-element results.
            try {
                for (JMeterTreeNode node : nodeMap.values()) {
                    guiPackage.getTreeModel().nodeChanged(node);
                }
                guiPackage.refreshCurrentGui();
                refreshTables(guiPackage.getCurrentGui());
                log.info("Successfully refreshed GUI after batch update");
            } catch (Exception e) {
                log.warn("GUI refresh failed after batch update (property writes already applied)", e);
            }
        });
        if (edtError != null) {
            log.error("Failed to batch update elements on EDT", edtError);
        }
    }

}
