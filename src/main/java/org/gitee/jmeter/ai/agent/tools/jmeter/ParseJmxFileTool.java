package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jorphan.collections.HashTree;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;

import java.io.File;
import java.util.Map;

/**
 * Tool to parse an external JMX script file and return its component tree
 * in the same JSON format as get_test_plan_tree.
 * Does not require JMeter GUI to be open.
 */
public class ParseJmxFileTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "parse_jmx_file";
    }

    @Override
    public String getDescription() {
        return "Parse an external JMX script file and return its component tree structure " +
                "in JSON format (same format as get_test_plan_tree). " +
                "Does not require the file to be loaded in JMeter GUI. " +
                "Use this to analyze JMX files before loading them, or to inspect files " +
                "that are not the current test plan. " +
                "Note: elementId values from this tool are based on TestElement objects, " +
                "they cannot be used with find_element or update_jmeter_element.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "filePath": {
                            "type": "string",
                            "description": "Absolute or relative path to the .jmx file to parse"
                        },
                        "includeProperties": {
                            "type": "boolean",
                            "description": "Whether to include element properties in the output (default: true)"
                        },
                        "maxDepth": {
                            "type": "integer",
                            "description": "Maximum depth to traverse (-1 for unlimited, 0 for root node only, 1 for first-level children, default: -1)"
                        }
                    },
                    "required": ["filePath"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String filePath = getStringParameter(parameters, "filePath", "");
        if (filePath == null || filePath.trim().isEmpty()) {
            return ToolResult.error("Parameter 'filePath' is required");
        }

        if (!filePath.toLowerCase().endsWith(".jmx")) {
            return ToolResult.error("File must have .jmx extension: " + filePath);
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return ToolResult.error("File not found: " + file.getAbsolutePath());
        }
        if (!file.isFile()) {
            return ToolResult.error("Path is not a regular file: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            return ToolResult.error("File is not readable: " + file.getAbsolutePath());
        }

        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", -1);

        try {
            HashTree tree = SaveService.loadTree(file);

            if (tree == null || tree.list().isEmpty()) {
                return ToolResult.error("JMX file contains no test plan: " + file.getAbsolutePath());
            }

            JMeterTreeNode rootNode = JMeterTreeUtils.convertHashTreeToTreeNodes(tree);
            if (rootNode == null) {
                return ToolResult.error("JMX file contains no valid test plan: " + file.getAbsolutePath());
            }

            Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                    rootNode, includeProperties, maxDepth, 0);

            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);
            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            log.error("Error serializing parsed JMX tree to JSON", e);
            return ToolResult.error("Failed to serialize tree to JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error parsing JMX file: {}", filePath, e);
            return ToolResult.error("Failed to parse JMX file: " + e.getMessage());
        }
    }
}
