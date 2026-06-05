package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;

import java.util.LinkedHashMap;
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
        return "Find JMeter elements and return their subtree structure in JSON format. " +
                "Supports searching by name, element type, path, or element ID. " +
                "Returns paginated results with total count for name/type searches.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "searchBy": {
                            "type": "string",
                            "enum": ["name", "elementType", "path", "elementId"],
                            "description": "Search criteria: 'name' for element name, 'elementType' for element type (e.g., 'httpsampler'), 'path' for full tree path, 'elementId' for element ID"
                        },
                        "query": {
                            "type": "string",
                            "description": "The search query - element name, elementType (e.g., 'httpsampler', 'threadgroup'), path (e.g., 'Test Plan > Thread Group > HTTP Request'), or elementId (e.g., '12345678')"
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
                        "offset": {
                            "type": "integer",
                            "description": "Number of results to skip for pagination (default: 0, must be >= 0)"
                        },
                        "limit": {
                            "type": "integer",
                            "description": "Maximum number of results to return (default: 20, max: 50, must be > 0)"
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
        int offset = getIntParameter(parameters, "offset", 0);
        int limit = getIntParameter(parameters, "limit", 20);

        if (searchBy.isEmpty() || query.isEmpty()) {
            return ToolResult.error("Parameters 'searchBy' and 'query' are required");
        }

        if (offset < 0) {
            return ToolResult.error("Parameter 'offset' must be >= 0, got: " + offset);
        }
        if (limit <= 0) {
            return ToolResult.error("Parameter 'limit' must be > 0, got: " + limit);
        }
        if (limit > 50) {
            return ToolResult.error("Parameter 'limit' must not exceed 50, got: " + limit);
        }

        try {
            switch (searchBy) {
                case "name":
                    return findByName(searchRoot, query, exactMatch, includeProperties, maxDepth, offset, limit);

                case "elementType":
                    return findByElementType(searchRoot, query, includeProperties, maxDepth, offset, limit);

                case "path":
                    return findByPath(searchRoot, query, includeProperties, maxDepth);

                case "elementId":
                    return findByElementId(searchRoot, query, includeProperties, maxDepth);

                default:
                    return ToolResult.error("Invalid searchBy value: " + searchBy +
                            ". Must be one of: name, elementType, path, elementId");
            }
        } catch (Exception e) {
            log.error("Error finding element", e);
            return ToolResult.error("Failed to find element: " + e.getMessage());
        }
    }

    /**
     * Find element(s) by name with pagination.
     */
    private ToolResult findByName(JMeterTreeNode root, String name, boolean exactMatch,
                                   boolean includeProperties, int maxDepth,
                                   int offset, int limit) throws JsonProcessingException {
        List<JMeterTreeNode> foundNodes = JMeterTreeUtils.findNodesByName(root, name, exactMatch);

        if (foundNodes.isEmpty()) {
            return buildPaginatedResult(0, offset, limit, List.of());
        }

        int total = foundNodes.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(fromIndex + limit, total);

        List<Map<String, Object>> elements = foundNodes.subList(fromIndex, toIndex).stream()
                .map(node -> JMeterTreeUtils.buildTreeData(node, includeProperties, maxDepth, 0))
                .toList();

        return buildPaginatedResult(total, offset, limit, elements);
    }

    /**
     * Find element(s) by elementType with pagination.
     * Accepts normalized elementType (e.g., 'httpsampler') and resolves to Java class for matching.
     */
    private ToolResult findByElementType(JMeterTreeNode root, String elementType,
                                          boolean includeProperties, int maxDepth,
                                          int offset, int limit) throws JsonProcessingException {
        String normalized = JMeterElementManager.normalizeElementType(elementType);
        JMeterElementManager.ElementClassInfo classInfo = JMeterElementManager.getElementClassMap().get(normalized);

        if (classInfo == null) {
            return ToolResult.error("Unknown elementType: '" + elementType +
                    "'. Use elementType values from SKILL.md component reference (e.g., 'httpsampler', 'threadgroup').");
        }

        String modelClassName = classInfo.getModelClassName();
        String simpleClassName = modelClassName.substring(modelClassName.lastIndexOf('.') + 1);
        String guiClassName = classInfo.getGuiClassName();

        List<JMeterTreeNode> foundNodes = JMeterTreeUtils.findNodesByTypeAndGui(root, simpleClassName, guiClassName);

        if (foundNodes.isEmpty()) {
            return buildPaginatedResult(0, offset, limit, List.of());
        }

        int total = foundNodes.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(fromIndex + limit, total);

        List<Map<String, Object>> elements = foundNodes.subList(fromIndex, toIndex).stream()
                .map(node -> JMeterTreeUtils.buildTreeData(node, includeProperties, maxDepth, 0))
                .toList();

        return buildPaginatedResult(total, offset, limit, elements);
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

        return buildPaginatedResult(1, 0, 1, List.of(treeData));
    }

    /**
     * Find element by elementId.
     */
    private ToolResult findByElementId(JMeterTreeNode root, String idStr,
                                       boolean includeProperties, int maxDepth) throws JsonProcessingException {
        int elementId;
        try {
            elementId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return ToolResult.error("elementId must be an integer: " + idStr);
        }

        JMeterTreeNode foundNode = JMeterTreeUtils.findNodeByElementId(root, elementId);

        if (foundNode == null) {
            return ToolResult.error("No element found with elementId: " + elementId);
        }

        Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                foundNode, includeProperties, maxDepth, 0);

        return buildPaginatedResult(1, 0, 1, List.of(treeData));
    }

    /**
     * Build a paginated result wrapper with total count, offset, limit, and elements array.
     */
    private ToolResult buildPaginatedResult(int total, int offset, int limit,
                                             List<Map<String, Object>> elements) throws JsonProcessingException {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("total", total);
        wrapper.put("offset", offset);
        wrapper.put("limit", limit);
        wrapper.put("elements", elements);

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
        return ToolResult.success(json);
    }
}
