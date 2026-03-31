package org.qainsights.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;

import java.util.Map;

/**
 * Tool to get the complete JMeter TestPlan tree structure in JSON format.
 * Returns all elements with their properties and instance IDs.
 */
public class GetTestPlanTreeTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_test_plan_tree";
    }

    @Override
    public String getDescription() {
        return "Get the complete JMeter TestPlan tree structure in JSON format. " +
                "Returns all elements with their types, names, properties, and instance IDs. " +
                "This is useful for understanding the full structure of your test plan.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "includeProperties": {
                            "type": "boolean",
                            "description": "Whether to include element properties in the output (default: true)"
                        },
                        "maxDepth": {
                            "type": "integer",
                            "description": "Maximum depth to traverse (0 for unlimited, default: 0)"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", 0);

        try {
            JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (root == null) {
                return ToolResult.error("Test plan root is not available");
            }

            Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(root, includeProperties, maxDepth, 0);
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);
            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            log.error("Error serializing test plan tree to JSON", e);
            return ToolResult.error("Failed to serialize tree to JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error building test plan tree", e);
            return ToolResult.error("Failed to build test plan tree: " + e.getMessage());
        }
    }
}
