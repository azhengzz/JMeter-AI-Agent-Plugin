package org.qainsights.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;

import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool to get the complete JMeter TestPlan tree structure in JSON format.
 * Returns all elements with their properties and instance IDs.
 */
public class GetTestPlanTreeTool extends AbstractTool {

    private static final int MAX_PROPERTIES = 500;
    private static final int MAX_STRING_LENGTH = 2000;
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

            Map<String, Object> treeData = buildTreeData(root, includeProperties, maxDepth, 0);
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

    /**
     * Build data structure representation of the tree starting from the given node.
     */
    private Map<String, Object> buildTreeData(JMeterTreeNode node, boolean includeProperties,
                                               int maxDepth, int currentDepth) {
        Map<String, Object> data = new LinkedHashMap<>();

        // Node basic info
        data.put("instanceId", System.identityHashCode(node));
        data.put("depth", currentDepth);

        TestElement element = node.getTestElement();
        if (element != null) {
            data.put("elementType", element.getClass().getSimpleName());
            data.put("elementId", element.hashCode());
            data.put("name", element.getName());

            // Properties
            if (includeProperties) {
                data.put("properties", buildPropertiesData(element));
            }
        } else {
            data.put("elementType", "null");
            data.put("name", node.getName());
        }

        // Path from root
        data.put("path", getNodePath(node));

        // Children
        int childCount = node.getChildCount();
        data.put("childCount", childCount);

        if (childCount > 0 && (maxDepth == 0 || currentDepth < maxDepth)) {
            List<Map<String, Object>> childrenList = new ArrayList<>(childCount);
            for (int i = 0; i < childCount; i++) {
                JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(i);
                childrenList.add(buildTreeData(child, includeProperties, maxDepth, currentDepth + 1));
            }
            data.put("children", childrenList);
        } else if (childCount > 0) {
            // Depth limited
            data.put("childrenLimited", true);
        }

        return data;
    }

    /**
     * Build data structure for element properties.
     */
    private Map<String, String> buildPropertiesData(TestElement element) {
        Map<String, String> props = new LinkedHashMap<>();
        PropertyIterator propIterator = element.propertyIterator();
        int count = 0;

        while (propIterator.hasNext() && count < MAX_PROPERTIES) {
            JMeterProperty prop = propIterator.next();
            String propName = prop.getName();

            // Skip internal TestElement properties
            if (!propName.startsWith("TestElement.")) {
                String propValue = prop.getStringValue();
                if (propValue != null && !propValue.isEmpty()) {
                    props.put(propName, truncate(propValue, MAX_STRING_LENGTH));
                    count++;
                }
            }
        }

        if (count >= MAX_PROPERTIES && propIterator.hasNext()) {
            props.put("...", MAX_PROPERTIES + "+ properties");
        }

        return props;
    }

    /**
     * Get the full path of a node from root.
     */
    private String getNodePath(JMeterTreeNode node) {
        TreePath treePath = new TreePath(node.getPath());
        StringBuilder path = new StringBuilder();
        Object[] pathArray = treePath.getPath();

        for (int i = 0; i < pathArray.length; i++) {
            if (i > 0) {
                path.append(" > ");
            }
            Object pathComponent = pathArray[i];
            if (pathComponent instanceof JMeterTreeNode) {
                JMeterTreeNode treeNode = (JMeterTreeNode) pathComponent;
                TestElement elem = treeNode.getTestElement();
                if (elem != null && elem.getName() != null) {
                    path.append(elem.getName());
                } else {
                    path.append(treeNode.getName());
                }
            } else {
                path.append(pathComponent.toString());
            }
        }

        return path.toString();
    }

    /**
     * Truncate string to max length.
     */
    private String truncate(String str, int max) {
        if (str == null) {
            return null;
        }
        if (str.length() <= max) {
            return str;
        }
        return str.substring(0, max) + "...";
    }
}
