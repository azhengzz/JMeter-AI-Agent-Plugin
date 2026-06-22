package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JmxFileParser;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JmxFileParser.ParsedJmxElement;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool to parse an external JMX script file and return its component tree
 * or query elements by properties. Does not require JMeter GUI to be open.
 *
 * <p>Uses {@link JmxFileParser} (pure DOM) instead of {@code SaveService.loadTree}
 * to avoid triggering {@code ParameterIncludeController.loadIncludedElements}
 * recursion which pollutes GUI state when invoked on a non-EDT thread.
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

        String elementType = getStringParameter(parameters, "elementType", "");
        String propertyName = getStringParameter(parameters, "propertyName", "");
        String propertyValue = getStringParameter(parameters, "propertyValue", "");
        boolean hasFilter = !elementType.isEmpty() || !propertyName.isEmpty() || !propertyValue.isEmpty();

        try {
            ParsedJmxElement root = JmxFileParser.parse(file);
            if (root == null) {
                return ToolResult.error("JMX file contains no test plan: " + file.getAbsolutePath());
            }

            if (hasFilter) {
                return executeQuery(root, elementType, propertyName, propertyValue, parameters);
            } else {
                return executeTreeMode(root, parameters);
            }
        } catch (SAXException | IOException e) {
            log.error("Error parsing JMX file: {}", filePath, e);
            return ToolResult.error("Failed to parse JMX file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error parsing JMX file: {}", filePath, e);
            return ToolResult.error("Failed to parse JMX file: " + e.getMessage());
        }
    }

    private ToolResult executeTreeMode(ParsedJmxElement root, Map<String, Object> parameters)
            throws JsonProcessingException {
        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", true);
        int maxDepth = getIntParameter(parameters, "maxDepth", -1);

        Map<String, Object> treeData = buildTreeData(root, includeProperties, maxDepth, 0,
                root.name == null ? "" : root.name);

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(treeData);
        return ToolResult.success(json);
    }

    private ToolResult executeQuery(ParsedJmxElement root, String elementType,
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

        List<ParsedJmxElement> candidates;
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
            candidates = new ArrayList<>();
            findElementsByType(root, simpleClassName, guiClassName, candidates);
        } else {
            candidates = new ArrayList<>();
            collectAllElements(root, candidates);
        }

        List<MatchedElement> matched = new ArrayList<>();
        for (ParsedJmxElement el : candidates) {
            List<String> matchedProps = matchProperties(el, propertyName, propertyValue, exact);
            if (!matchedProps.isEmpty()) {
                matched.add(new MatchedElement(el, matchedProps));
            }
        }

        int total = matched.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(fromIndex + limit, total);

        List<Map<String, Object>> elements = new ArrayList<>();
        for (MatchedElement me : matched.subList(fromIndex, toIndex)) {
            String path = me.element.name == null ? "" : me.element.name;
            Map<String, Object> treeData = buildTreeData(me.element, includeProperties, maxDepth, 0, path);
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

    private static Map<String, Object> buildTreeData(ParsedJmxElement el, boolean includeProperties,
                                                      int maxDepth, int currentDepth, String path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depth", currentDepth);
        data.put("elementType", el.elementType);
        data.put("name", el.name);
        data.put("enabled", el.enabled);
        if (includeProperties && el.properties != null && !el.properties.isEmpty()) {
            data.put("properties", el.properties);
        }
        data.put("path", path);

        int childCount = el.children == null ? 0 : el.children.size();
        data.put("childCount", childCount);

        if (childCount > 0 && (maxDepth < 0 || currentDepth < maxDepth)) {
            List<Map<String, Object>> childrenList = new ArrayList<>(childCount);
            for (ParsedJmxElement child : el.children) {
                String childName = child.name == null ? "" : child.name;
                String childPath = path.isEmpty() ? childName : path + " > " + childName;
                childrenList.add(buildTreeData(child, includeProperties, maxDepth, currentDepth + 1, childPath));
            }
            data.put("children", childrenList);
        } else if (childCount > 0) {
            data.put("hasMoreChildren", true);
        }

        return data;
    }

    private static void collectAllElements(ParsedJmxElement el, List<ParsedJmxElement> result) {
        result.add(el);
        if (el.children != null) {
            for (ParsedJmxElement child : el.children) {
                collectAllElements(child, result);
            }
        }
    }

    private static void findElementsByType(ParsedJmxElement el, String simpleClassName,
                                            String guiClassName, List<ParsedJmxElement> result) {
        if (simpleClassName.equals(el.elementType)
                && (guiClassName == null || guiClassName.equals(el.guiClass))) {
            result.add(el);
        }
        if (el.children != null) {
            for (ParsedJmxElement child : el.children) {
                findElementsByType(child, simpleClassName, guiClassName, result);
            }
        }
    }

    private static List<String> matchProperties(ParsedJmxElement el, String nameQuery,
                                                 String valueQuery, boolean exact) {
        List<String> matches = new ArrayList<>();

        if (el.name != null && matchesCriteria("name", el.name, nameQuery, valueQuery, exact)) {
            matches.add("name");
        }

        String comment = extractComment(el.properties);
        if (comment != null && !comment.isEmpty()
                && matchesCriteria("comment", comment, nameQuery, valueQuery, exact)) {
            matches.add("comment");
        }

        if (el.properties != null) {
            matchPropertyMap(el.properties, "", nameQuery, valueQuery, exact, matches);
        }

        return matches;
    }

    @SuppressWarnings("unchecked")
    private static void matchPropertyMap(Map<String, Object> props, String parentPath,
                                          String nameQuery, String valueQuery, boolean exact,
                                          List<String> matches) {
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("TestElement.")) {
                continue;
            }
            Object value = entry.getValue();
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;

            if (value instanceof Map) {
                matchPropertyMap((Map<String, Object>) value, path, nameQuery, valueQuery, exact, matches);
            } else if (value instanceof List) {
                matchPropertyList((List<Object>) value, path, nameQuery, valueQuery, exact, matches);
            } else {
                String strValue = value == null ? null : value.toString();
                if (matchesCriteria(path, strValue, nameQuery, valueQuery, exact)) {
                    matches.add(path);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void matchPropertyList(List<Object> items, String parentPath,
                                           String nameQuery, String valueQuery, boolean exact,
                                           List<String> matches) {
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            String itemPath = parentPath + "[" + i + "]";
            if (item instanceof Map) {
                matchPropertyMap((Map<String, Object>) item, itemPath, nameQuery, valueQuery, exact, matches);
            } else {
                String strValue = item == null ? null : item.toString();
                if (matchesCriteria(itemPath, strValue, nameQuery, valueQuery, exact)) {
                    matches.add(itemPath);
                }
            }
        }
    }

    private static String extractComment(Map<String, Object> properties) {
        if (properties == null) {
            return null;
        }
        Object teComment = properties.get("TestElement.comments");
        if (teComment instanceof String) {
            return (String) teComment;
        }
        Object tpComment = properties.get("TestPlan.comments");
        if (tpComment instanceof String) {
            return (String) tpComment;
        }
        return null;
    }

    private static boolean matchesCriteria(String propName, String propValue,
                                            String nameQuery, String valueQuery, boolean exact) {
        boolean nameMatch = nameQuery.isEmpty() || propName.equals(nameQuery);
        boolean valueMatch = valueQuery.isEmpty() ||
                (propValue != null && matchText(propValue, valueQuery, exact));
        return nameMatch && valueMatch;
    }

    private static boolean matchText(String text, String query, boolean exact) {
        return exact ? text.equals(query) : text.contains(query);
    }

    private static class MatchedElement {
        final ParsedJmxElement element;
        final List<String> matchedProps;

        MatchedElement(ParsedJmxElement element, List<String> matchedProps) {
            this.element = element;
            this.matchedProps = matchedProps;
        }
    }
}
