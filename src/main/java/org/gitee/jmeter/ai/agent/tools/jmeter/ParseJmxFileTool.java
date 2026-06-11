package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.gitee.jmeter.ai.utils.JMeterElementManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool to parse an external JMX script file and return its component tree
 * or query elements by properties. Does not require JMeter GUI to be open.
 */
public class ParseJmxFileTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "parse_jmx_file";
    }

    @Override
    public String getDescription() {
        return "Parse an external JMX script file. " +
                "Without filter parameters, returns the full component tree (same format as get_test_plan_tree). " +
                "With filter parameters (elementType/propertyName/propertyValue), queries and returns matching elements with pagination. " +
                "Does not require the file to be loaded in JMeter GUI.";
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
                        "elementType": {
                            "type": "string",
                            "description": "Filter by element type (e.g., 'HTTPSamplerProxy', 'ThreadGroup'). Only used in query mode."
                        },
                        "propertyName": {
                            "type": "string",
                            "description": "Exact property name to search for. Special names: 'name' for component display name, 'comment' for component comment."
                        },
                        "propertyValue": {
                            "type": "string",
                            "description": "Property value to search for. Matched using matchMode."
                        },
                        "matchMode": {
                            "type": "string",
                            "enum": ["exact", "contains"],
                            "description": "Match mode for property value (default: 'contains'). Property name always uses exact match."
                        },
                        "includeProperties": {
                            "type": "boolean",
                            "description": "Whether to include element properties in the output (default: true)"
                        },
                        "maxDepth": {
                            "type": "integer",
                            "description": "Maximum depth to traverse (-1 for unlimited, 0 for current node only, default: -1 for tree mode, 0 for query mode)"
                        },
                        "offset": {
                            "type": "integer",
                            "description": "Number of results to skip for pagination (default: 0). Only used in query mode."
                        },
                        "limit": {
                            "type": "integer",
                            "description": "Maximum number of results to return (default: 20, max: 50). Only used in query mode."
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

        // Determine mode: tree or query
        String elementType = getStringParameter(parameters, "elementType", "");
        String propertyName = getStringParameter(parameters, "propertyName", "");
        String propertyValue = getStringParameter(parameters, "propertyValue", "");
        boolean hasFilter = !elementType.isEmpty() || !propertyName.isEmpty() || !propertyValue.isEmpty();

        try {
            HashTree tree = SaveService.loadTree(file);

            if (tree == null || tree.list().isEmpty()) {
                return ToolResult.error("JMX file contains no test plan: " + file.getAbsolutePath());
            }

            JMeterTreeNode rootNode = JMeterTreeUtils.convertHashTreeToTreeNodes(tree);
            if (rootNode == null) {
                return ToolResult.error("JMX file contains no valid test plan: " + file.getAbsolutePath());
            }

            if (hasFilter) {
                return executeQuery(rootNode, elementType, propertyName, propertyValue, parameters);
            } else {
                return executeTreeMode(rootNode, parameters);
            }
        } catch (JsonProcessingException e) {
            log.error("Error serializing parsed JMX tree to JSON", e);
            return ToolResult.error("Failed to serialize tree to JSON: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error parsing JMX file: {}", filePath, e);
            return ToolResult.error("Failed to parse JMX file: " + e.getMessage());
        }
    }

    private ToolResult executeTreeMode(JMeterTreeNode rootNode, Map<String, Object> parameters)
            throws JsonProcessingException {
        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", -1);

        Map<String, Object> treeData = JMeterTreeUtils.buildTreeData(
                rootNode, includeProperties, maxDepth, 0);

        removeElementIds(treeData);

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);
        return ToolResult.success(json);
    }

    private ToolResult executeQuery(JMeterTreeNode rootNode, String elementType,
                                     String propertyName, String propertyValue,
                                     Map<String, Object> parameters) throws JsonProcessingException {
        String matchMode = getStringParameter(parameters, "matchMode", "contains");
        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", 0);
        int offset = getIntParameter(parameters, "offset", 0);
        int limit = getIntParameter(parameters, "limit", 20);

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
            candidates = JMeterTreeUtils.findNodesByTypeAndGui(rootNode, simpleClassName, guiClassName);
        } else {
            candidates = new ArrayList<>();
            JMeterTreeUtils.collectAllNodes(rootNode, candidates);
        }

        // Match properties
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
            treeData.remove("elementId");
            treeData.put("matchedProperties", me.matchedProps);
            elements.add(treeData);
        }

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("total", total);
        wrapper.put("offset", offset);
        wrapper.put("limit", limit);
        wrapper.put("elements", elements);

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
        return ToolResult.success(json);
    }

    @SuppressWarnings("unchecked")
    private static void removeElementIds(Map<String, Object> node) {
        node.remove("elementId");
        Object children = node.get("children");
        if (children instanceof Iterable<?>) {
            for (Object child : (Iterable<Object>) children) {
                if (child instanceof Map) {
                    removeElementIds((Map<String, Object>) child);
                }
            }
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
