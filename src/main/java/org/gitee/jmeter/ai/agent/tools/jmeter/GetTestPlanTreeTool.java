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
 * Tool to get the complete JMeter TestPlan tree structure in JSON format.
 * Returns all elements with their properties and element IDs.
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
                "Returns all elements with their types, names, properties, and element IDs. " +
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
                            "description": "Maximum depth to traverse (-1 for unlimited, 0 for current node only, 1 for first-level children, default: -1)"
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
        int maxDepth = getIntParameter(parameters, "maxDepth", -1);

        try {
            JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (rootNode == null) {
                return ToolResult.error("Test plan root is not available");
            }

            // Skip the virtual root node and start from the actual test plan
            // JMeter's tree model has a virtual root (usually named "Test Plan" or "Root")
            // The actual test plan is the first child of this virtual root
            JMeterTreeNode actualRoot = rootNode;
            if (rootNode.getChildCount() == 1) {
                JMeterTreeNode firstChild = (JMeterTreeNode) rootNode.getChildAt(0);
                // Check if this is the actual test plan (has a TestElement)
                if (firstChild.getTestElement() != null) {
                    actualRoot = firstChild;
                    log.debug("Skipping virtual root node, using actual test plan: {}", firstChild.getName());
                }
            }

            Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(actualRoot, includeProperties, maxDepth, 0);
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
