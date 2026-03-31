package org.qainsights.jmeter.ai.agent.tools.jmeter.utils;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for JMeter tree operations.
 * Provides common methods for building tree data structures and querying elements.
 */
public class JMeterTreeUtils {

    private static final int DEFAULT_MAX_PROPERTIES = 500;
    private static final int DEFAULT_MAX_STRING_LENGTH = 2000;

    private JMeterTreeUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Build data structure representation of the tree starting from the given node.
     *
     * @param node The root node to build from
     * @param includeProperties Whether to include element properties
     * @param maxDepth Maximum depth to traverse (0 for unlimited)
     * @param currentDepth Current depth level
     * @return Map containing node data
     */
    public static Map<String, Object> buildTreeData(JMeterTreeNode node, boolean includeProperties,
                                                      int maxDepth, int currentDepth) {
        return buildTreeData(node, includeProperties, maxDepth, currentDepth,
                DEFAULT_MAX_PROPERTIES, DEFAULT_MAX_STRING_LENGTH);
    }

    /**
     * Build data structure representation of the tree starting from the given node.
     *
     * @param node The root node to build from
     * @param includeProperties Whether to include element properties
     * @param maxDepth Maximum depth to traverse (0 for unlimited)
     * @param currentDepth Current depth level
     * @param maxProperties Maximum number of properties to include per element
     * @param maxLength Maximum string length for property values
     * @return Map containing node data
     */
    public static Map<String, Object> buildTreeData(JMeterTreeNode node, boolean includeProperties,
                                                      int maxDepth, int currentDepth,
                                                      int maxProperties, int maxLength) {
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
                data.put("properties", buildPropertiesData(element, maxProperties, maxLength));
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
                childrenList.add(buildTreeData(child, includeProperties, maxDepth, currentDepth + 1,
                        maxProperties, maxLength));
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
     *
     * @param element The test element
     * @return Map of property names to values
     */
    public static Map<String, String> buildPropertiesData(TestElement element) {
        return buildPropertiesData(element, DEFAULT_MAX_PROPERTIES, DEFAULT_MAX_STRING_LENGTH);
    }

    /**
     * Build data structure for element properties.
     *
     * @param element The test element
     * @param maxProperties Maximum number of properties to include
     * @param maxLength Maximum string length for property values
     * @return Map of property names to values
     */
    public static Map<String, String> buildPropertiesData(TestElement element, int maxProperties, int maxLength) {
        Map<String, String> props = new LinkedHashMap<>();
        PropertyIterator propIterator = element.propertyIterator();
        int count = 0;

        while (propIterator.hasNext() && count < maxProperties) {
            JMeterProperty prop = propIterator.next();
            String propName = prop.getName();

            // Skip internal TestElement properties
            if (!propName.startsWith("TestElement.")) {
                String propValue = prop.getStringValue();
                if (propValue != null && !propValue.isEmpty()) {
                    props.put(propName, truncate(propValue, maxLength));
                    count++;
                }
            }
        }

        if (count >= maxProperties && propIterator.hasNext()) {
            props.put("...", maxProperties + "+ properties");
        }

        return props;
    }

    /**
     * Get the full path of a node from root.
     *
     * @param node The node to get path for
     * @return String representation of the path
     */
    public static String getNodePath(JMeterTreeNode node) {
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
     * Find a node in the tree by element name.
     *
     * @param rootNode The root node to search from
     * @param name The name to search for
     * @return The first matching node, or null if not found
     */
    public static JMeterTreeNode findNodeByName(JMeterTreeNode rootNode, String name) {
        return findNodeByName(rootNode, name, false);
    }

    /**
     * Find a node in the tree by element name.
     *
     * @param rootNode The root node to search from
     * @param name The name to search for
     * @param exactMatch If true, requires exact match; if false, uses contains
     * @return The first matching node, or null if not found
     */
    public static JMeterTreeNode findNodeByName(JMeterTreeNode rootNode, String name, boolean exactMatch) {
        if (rootNode == null || name == null) {
            return null;
        }

        TestElement element = rootNode.getTestElement();
        if (element != null) {
            String elementName = element.getName();
            if (elementName != null) {
                boolean matches = exactMatch
                    ? elementName.equals(name)
                    : elementName.contains(name);
                if (matches) {
                    return rootNode;
                }
            }
        }

        // Search children
        int childCount = rootNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            JMeterTreeNode child = (JMeterTreeNode) rootNode.getChildAt(i);
            JMeterTreeNode found = findNodeByName(child, name, exactMatch);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Find nodes in the tree by element type.
     *
     * @param rootNode The root node to search from
     * @param elementType The simple class name to search for (e.g., "HTTPSamplerProxy")
     * @return List of matching nodes
     */
    public static List<JMeterTreeNode> findNodesByType(JMeterTreeNode rootNode, String elementType) {
        List<JMeterTreeNode> results = new ArrayList<>();
        findNodesByType(rootNode, elementType, results);
        return results;
    }

    private static void findNodesByType(JMeterTreeNode node, String elementType, List<JMeterTreeNode> results) {
        TestElement element = node.getTestElement();
        if (element != null) {
            String className = element.getClass().getSimpleName();
            if (className.equals(elementType)) {
                results.add(node);
            }
        }

        // Search children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(i);
            findNodesByType(child, elementType, results);
        }
    }

    /**
     * Find a node in the tree by path.
     *
     * @param rootNode The root node to search from
     * @param path The path to search for (e.g., "Test Plan > Thread Group > HTTP Request")
     * @param separator The path separator (default: " > ")
     * @return The matching node, or null if not found
     */
    public static JMeterTreeNode findNodeByPath(JMeterTreeNode rootNode, String path, String separator) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split(separator);
        return findNodeByPath(rootNode, parts, 0);
    }

    /**
     * Find a node in the tree by path using default separator.
     *
     * @param rootNode The root node to search from
     * @param path The path to search for
     * @return The matching node, or null if not found
     */
    public static JMeterTreeNode findNodeByPath(JMeterTreeNode rootNode, String path) {
        return findNodeByPath(rootNode, path, " > ");
    }

    private static JMeterTreeNode findNodeByPath(JMeterTreeNode node, String[] pathParts, int index) {
        if (index >= pathParts.length) {
            return node;
        }

        String targetName = pathParts[index].trim();

        // Check current node
        TestElement element = node.getTestElement();
        if (element != null && targetName.equals(element.getName())) {
            return findNodeByPath(node, pathParts, index + 1);
        }

        // Search children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(i);
            TestElement childElement = child.getTestElement();
            if (childElement != null && targetName.equals(childElement.getName())) {
                JMeterTreeNode found = findNodeByPath(child, pathParts, index + 1);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Truncate string to max length.
     *
     * @param str The string to truncate
     * @param max Maximum length
     * @return Truncated string or original if under limit
     */
    public static String truncate(String str, int max) {
        if (str == null) {
            return null;
        }
        if (str.length() <= max) {
            return str;
        }
        return str.substring(0, max) + "...";
    }
}
