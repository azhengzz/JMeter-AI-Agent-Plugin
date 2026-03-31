package org.qainsights.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;

import java.util.List;
import java.util.Map;

/**
 * Tool to find and retrieve a specific JMeter element with its subtree structure.
 * Supports finding elements by name, type, or path and returns the result in JSON format.
 */
public class FindElementTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "find_element";
    }

    @Override
    public String getDescription() {
        return "Find a specific JMeter element and return its subtree structure in JSON format. " +
                "Supports searching by name, element type, or path. " +
                "Returns the matching element(s) with their properties and child elements.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "searchBy": {
                            "type": "string",
                            "enum": ["name", "type", "path"],
                            "description": "Search criteria: 'name' for element name, 'type' for element class name, 'path' for full tree path"
                        },
                        "query": {
                            "type": "string",
                            "description": "The search query - element name, type name (e.g., 'HTTPSamplerProxy'), or path (e.g., 'Test Plan > Thread Group > HTTP Request')"
                        },
                        "exactMatch": {
                            "type": "boolean",
                            "description": "For name search: true for exact match, false for partial match (default: true)"
                        },
                        "includeProperties": {
                            "type": "boolean",
                            "description": "Whether to include element properties in the output (default: true)"
                        },
                        "maxDepth": {
                            "type": "integer",
                            "description": "Maximum depth to traverse from found element (0 for unlimited, default: 0)"
                        },
                        "returnAll": {
                            "type": "boolean",
                            "description": "For type search: return all matching elements (default: false, returns first match only)"
                        }
                    },
                    "required": ["searchBy", "query"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        if (rootNode == null) {
            return ToolResult.error("Test plan root is not available");
        }

        // Skip the virtual root node and start from the actual test plan
        // JMeter's tree model has a virtual root (usually named "Test Plan" or "Root")
        // The actual test plan is the first child of this virtual root
        JMeterTreeNode searchRoot = rootNode;
        if (rootNode.getChildCount() == 1) {
            JMeterTreeNode firstChild = (JMeterTreeNode) rootNode.getChildAt(0);
            // Check if this is the actual test plan (has a TestElement)
            if (firstChild.getTestElement() != null) {
                searchRoot = firstChild;
                log.debug("Skipping virtual root node for search, using actual test plan: {}", firstChild.getName());
            }
        }

        String searchBy = getStringParameter(parameters, "searchBy", "");
        String query = getStringParameter(parameters, "query", "");
        boolean exactMatch = getBooleanParameter(parameters, "exactMatch", true);
        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", 0);
        boolean returnAll = getBooleanParameter(parameters, "returnAll", false);

        if (searchBy.isEmpty() || query.isEmpty()) {
            return ToolResult.error("Parameters 'searchBy' and 'query' are required");
        }

        try {
            switch (searchBy) {
                case "name":
                    return findByName(searchRoot, query, exactMatch, includeProperties, maxDepth);

                case "type":
                    return findByType(searchRoot, query, includeProperties, maxDepth, returnAll);

                case "path":
                    return findByPath(searchRoot, query, includeProperties, maxDepth);

                default:
                    return ToolResult.error("Invalid searchBy value: " + searchBy +
                            ". Must be one of: name, type, path");
            }
        } catch (Exception e) {
            log.error("Error finding element", e);
            return ToolResult.error("Failed to find element: " + e.getMessage());
        }
    }

    /**
     * Find element by name.
     */
    private ToolResult findByName(JMeterTreeNode root, String name, boolean exactMatch,
                                   boolean includeProperties, int maxDepth) throws JsonProcessingException {
        JMeterTreeNode foundNode = JMeterTreeUtils.findNodeByName(root, name, exactMatch);

        if (foundNode == null) {
            return ToolResult.error("No element found with name: " + name +
                    (exactMatch ? "" : " (partial match)"));
        }

        Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                foundNode, includeProperties, maxDepth, 0);
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);

        return ToolResult.success(json);
    }

    /**
     * Find element(s) by type.
     */
    private ToolResult findByType(JMeterTreeNode root, String elementType,
                                   boolean includeProperties, int maxDepth, boolean returnAll)
            throws JsonProcessingException {
        List<JMeterTreeNode> foundNodes = JMeterTreeUtils.findNodesByType(root, elementType);

        if (foundNodes.isEmpty()) {
            return ToolResult.error("No elements found with type: " + elementType);
        }

        if (returnAll) {
            // Return all matches as an array
            List<Map<String, Object>> results = foundNodes.stream()
                    .map(node -> JMeterTreeUtils.buildTreeData(node, includeProperties, maxDepth, 0))
                    .toList();

            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results);
            return ToolResult.success(json);
        } else {
            // Return first match only
            Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                    foundNodes.get(0), includeProperties, maxDepth, 0);
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);

            return ToolResult.success(json);
        }
    }

    /**
     * Find element by path.
     */
    private ToolResult findByPath(JMeterTreeNode root, String path,
                                   boolean includeProperties, int maxDepth) throws JsonProcessingException {
        JMeterTreeNode foundNode = JMeterTreeUtils.findNodeByPath(root, path);

        if (foundNode == null) {
            return ToolResult.error("No element found at path: " + path);
        }

        Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                foundNode, includeProperties, maxDepth, 0);
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);

        return ToolResult.success(json);
    }
}
