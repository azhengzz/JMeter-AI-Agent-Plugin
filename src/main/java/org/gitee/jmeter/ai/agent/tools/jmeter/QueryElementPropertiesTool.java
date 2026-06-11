package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool to query JMeter elements by property name and/or value.
 * Supports nested property matching and text-based contains/exact matching for String values.
 */
public class QueryElementPropertiesTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "query_element_properties";
    }

    @Override
    public String getDescription() {
        return "Query JMeter elements by property name and/or value. " +
                "Supports filtering by elementType and nested property matching. " +
                "String property values support 'exact' and 'contains' match modes.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "elementType": {
                            "type": "string",
                            "description": "Filter by element type (e.g., 'httpsampler', 'threadgroup'). If omitted, searches all elements."
                        },
                        "propertyName": {
                            "type": "string",
                            "description": "Exact property name to search for (e.g., 'HTTPSampler.domain', 'Argument.value'). Always uses exact match regardless of matchMode. Special names: 'name' for component display name, 'comment' for component comment."
                        },
                        "propertyValue": {
                            "type": "string",
                            "description": "Property value to search for. Matched against string values using the matchMode."
                        },
                        "matchMode": {
                            "type": "string",
                            "enum": ["exact", "contains"],
                            "description": "Match mode for property value: 'exact' for exact match, 'contains' for partial text match (default: 'contains'). Property name always uses exact match."
                        },
                        "includeProperties": {
                            "type": "boolean",
                            "description": "Whether to include element properties in the output (default: true)"
                        },
                        "maxDepth": {
                            "type": "integer",
                            "description": "Maximum depth to traverse from found element (-1 for unlimited, 0 for current node only, 1 for first-level children, default: 0)"
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

        JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        if (rootNode == null) {
            return ToolResult.error("Test plan root is not available");
        }

        // Skip virtual root node
        JMeterTreeNode searchRoot = rootNode;
        if (rootNode.getChildCount() == 1) {
            JMeterTreeNode firstChild = (JMeterTreeNode) rootNode.getChildAt(0);
            if (firstChild.getTestElement() != null) {
                searchRoot = firstChild;
            }
        }

        String elementType = getStringParameter(parameters, "elementType", "");
        String propertyName = getStringParameter(parameters, "propertyName", "");
        String propertyValue = getStringParameter(parameters, "propertyValue", "");
        String matchMode = getStringParameter(parameters, "matchMode", "contains");
        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", 0);
        int offset = getIntParameter(parameters, "offset", 0);
        int limit = getIntParameter(parameters, "limit", 20);

        if (propertyName.isEmpty() && propertyValue.isEmpty()) {
            return ToolResult.error("At least one of 'propertyName' or 'propertyValue' must be specified");
        }

        if (!"exact".equals(matchMode) && !"contains".equals(matchMode)) {
            return ToolResult.error("matchMode must be 'exact' or 'contains', got: " + matchMode);
        }

        if (offset < 0) {
            return ToolResult.error("Parameter 'offset' must be >= 0, got: " + offset);
        }
        if (limit <= 0 || limit > 50) {
            return ToolResult.error("Parameter 'limit' must be between 1 and 50, got: " + limit);
        }

        boolean exact = "exact".equals(matchMode);

        // Collect candidate nodes
        List<JMeterTreeNode> candidates;
        if (!elementType.isEmpty()) {
            String normalized = JMeterElementManager.normalizeElementType(elementType);
            JMeterElementManager.ElementClassInfo classInfo = JMeterElementManager.getElementClassMap().get(normalized);
            if (classInfo == null) {
                return ToolResult.error("Unknown elementType: '" + elementType +
                        "'. Use elementType values from SKILL.md component reference.");
            }
            String modelClassName = classInfo.getModelClassName();
            String simpleClassName = modelClassName.substring(modelClassName.lastIndexOf('.') + 1);
            String guiClassName = classInfo.getGuiClassName();
            candidates = JMeterTreeUtils.findNodesByTypeAndGui(searchRoot, simpleClassName, guiClassName);
        } else {
            candidates = new ArrayList<>();
            JMeterTreeUtils.collectAllNodes(searchRoot, candidates);
        }

        // Match properties and build results
        List<MatchedElement> matched = new ArrayList<>();
        for (JMeterTreeNode node : candidates) {
            TestElement element = node.getTestElement();
            if (element == null) continue;

            List<String> matchedProps = JMeterTreeUtils.matchProperties(element, propertyName, propertyValue, exact);
            if (!matchedProps.isEmpty()) {
                matched.add(new MatchedElement(node, matchedProps));
            }
        }

        // Paginate
        int total = matched.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(fromIndex + limit, total);

        List<Map<String, Object>> elements = new ArrayList<>();
        for (MatchedElement me : matched.subList(fromIndex, toIndex)) {
            Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(me.node, includeProperties, maxDepth, 0);
            treeData.put("matchedProperties", me.matchedProps);
            elements.add(treeData);
        }

        return buildPaginatedResult(total, offset, limit, elements);
    }

    private ToolResult buildPaginatedResult(int total, int offset, int limit,
                                             List<Map<String, Object>> elements) {
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("total", total);
            wrapper.put("offset", offset);
            wrapper.put("limit", limit);
            wrapper.put("elements", elements);

            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            return ToolResult.error("Failed to serialize results: " + e.getMessage());
        }
    }

    private static class MatchedElement {
        final JMeterTreeNode node;
        final List<String> matchedProps;

        MatchedElement(JMeterTreeNode node, List<String> matchedProps) {
            this.node = node;
            this.matchedProps = matchedProps;
        }
    }
}
