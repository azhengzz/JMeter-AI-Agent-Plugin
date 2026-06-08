package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;

import java.util.Map;

/**
 * Tool to get information about the currently selected JMeter element.
 * Returns the element data in JSON format with the same structure as FindElementTool.
 * Corresponds to the @this command.
 */
public class GetSelectedElementTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_selected_element";
    }

    @Override
    public String getDescription() {
        return "Get detailed information about the currently selected JMeter test element in JSON format. " +
                "Returns the element type, name, properties, path, and child elements structure. " +
                "The output format is consistent with find_element tool for easy processing.";
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
                            "description": "Maximum depth to traverse from selected element (-1 for unlimited, 0 for current node only, 1 for first-level children, default: -1)"
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

        JMeterTreeNode selectedNode = guiPackage.getTreeListener().getCurrentNode();
        if (selectedNode == null) {
            return ToolResult.error("No element is currently selected in the test plan.");
        }

        if (selectedNode.getTestElement() == null) {
            return ToolResult.error("Selected node has no valid test element");
        }

        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", -1);

        try {
            Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                    selectedNode, includeProperties, maxDepth, 0);
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);

            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            log.error("Error serializing selected element data", e);
            return ToolResult.error("Failed to serialize element data: " + e.getMessage());
        }
    }
}
