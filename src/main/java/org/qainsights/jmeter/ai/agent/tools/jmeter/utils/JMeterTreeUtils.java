package org.qainsights.jmeter.ai.agent.tools.jmeter.utils;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;

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
    private static final int MAX_NESTED_DEPTH = 3;

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
        data.put("elementId", System.identityHashCode(node));
        data.put("depth", currentDepth);

        TestElement element = node.getTestElement();
        if (element != null) {
            data.put("elementType", element.getClass().getSimpleName());
            // elementId 每次组件参数值变更后，element.hashCode() 会发生改变
            // data.put("elementId", element.hashCode());
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
    public static Map<String, Object> buildPropertiesData(TestElement element) {
        return buildPropertiesData(element, DEFAULT_MAX_PROPERTIES, DEFAULT_MAX_STRING_LENGTH, 0);
    }

    /**
     * Build data structure for element properties.
     *
     * @param element The test element
     * @param maxProperties Maximum number of properties to include
     * @param maxLength Maximum string length for property values
     * @return Map of property names to values (can be String or nested Map for ObjectProperty)
     */
    public static Map<String, Object> buildPropertiesData(TestElement element, int maxProperties, int maxLength) {
        return buildPropertiesData(element, maxProperties, maxLength, 0);
    }

    /**
     * Build data structure for element properties with nested object support.
     *
     * @param element The test element
     * @param maxProperties Maximum number of properties to include
     * @param maxLength Maximum string length for property values
     * @param nestedDepth Current nested depth (for preventing infinite recursion)
     * @return Map of property names to values (can be String or nested Map for ObjectProperty)
     */
    private static Map<String, Object> buildPropertiesData(TestElement element, int maxProperties, int maxLength, int nestedDepth) {
        Map<String, Object> props = new LinkedHashMap<>();
        PropertyIterator propIterator = element.propertyIterator();
        int count = 0;

        while (propIterator.hasNext() && count < maxProperties) {
            JMeterProperty prop = propIterator.next();
            String propName = prop.getName();

            // Skip internal TestElement properties
            if (!propName.startsWith("TestElement.")) {
                if (prop instanceof TestElementProperty) {
                    // Handle nested TestElement properties
                    TestElement nestedElement = ((TestElementProperty) prop).getElement();
                    if (nestedElement != null && nestedDepth < MAX_NESTED_DEPTH) {
                        // Try to extract collection items (e.g., Arguments, HTTPFileArgs)
                        List<Map<String, Object>> collectionItems = extractCollectionItems(nestedElement, maxLength);
                        if (collectionItems != null) {
                            // Output array directly (matches create/update input format)
                            props.put(propName, collectionItems);
                            count++;
                        } else {
                            // Output nested properties as flat map (e.g., LoopController)
                            Map<String, Object> nestedProps = buildPropertiesData(nestedElement, maxProperties, maxLength, nestedDepth + 1);
                            props.put(propName, nestedProps);
                            count++;
                        }
                        // --- Original logic (kept for reference) ---
                        // Map<String, Object> nestedProps = new LinkedHashMap<>();
                        // nestedProps.put("__type", nestedElement.getClass().getSimpleName());
                        // nestedProps.put("__nestedProperties", buildPropertiesData(nestedElement, maxProperties, maxLength, nestedDepth + 1));
                        // props.put(propName, nestedProps);
                    } else if (nestedElement != null) {
                        // Max depth reached, use string representation
                        String propValue = nestedElement.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(nestedElement));
                        props.put(propName, propValue);
                        count++;
                    }
                } else if (prop instanceof CollectionProperty) {
                    List<Map<String, Object>> items = convertCollectionToList((CollectionProperty) prop, maxLength);
                    if (items != null && !items.isEmpty()) {
                        props.put(propName, items);
                        count++;
                    }
                } else {
                    String propValue = prop.getStringValue();
                    if (propValue != null && !propValue.isEmpty()) {
                        props.put(propName, truncate(propValue, maxLength));
                        count++;
                    }
                }
            }
        }

        if (count >= maxProperties && propIterator.hasNext()) {
            props.put("...", maxProperties + "+ properties");
        }

        return props;
    }

    /**
     * Extract collection items from a nested TestElement if it contains a CollectionProperty.
     * Returns the items as a list of property maps (matching create/update input format),
     * or null if the element does not contain a CollectionProperty.
     */
    private static List<Map<String, Object>> extractCollectionItems(TestElement element, int maxLength) {
        PropertyIterator iter = element.propertyIterator();
        while (iter.hasNext()) {
            JMeterProperty prop = iter.next();
            if (prop.getName().startsWith("TestElement.")) {
                continue;
            }
            if (prop instanceof CollectionProperty) {
                return convertCollectionToList((CollectionProperty) prop, maxLength);
            }
        }
        return null;
    }

    /**
     * Convert a CollectionProperty containing TestElementProperty items
     * into a list of property maps (matching create/update input format).
     */
    private static List<Map<String, Object>> convertCollectionToList(CollectionProperty collProp, int maxLength) {
        List<Map<String, Object>> items = new ArrayList<>();
        PropertyIterator collIter = collProp.iterator();
        while (collIter.hasNext()) {
            JMeterProperty item = collIter.next();
            if (item instanceof TestElementProperty) {
                TestElement itemElement = ((TestElementProperty) item).getElement();
                if (itemElement != null) {
                    Map<String, Object> itemProps = new LinkedHashMap<>();
                    PropertyIterator itemPropIter = itemElement.propertyIterator();
                    while (itemPropIter.hasNext()) {
                        JMeterProperty itemProp = itemPropIter.next();
                        String itemPropName = itemProp.getName();
                        if (!itemPropName.startsWith("TestElement.")) {
                            String val = itemProp.getStringValue();
                            if (val != null && !val.isEmpty()) {
                                itemProps.put(itemPropName, truncate(val, maxLength));
                            }
                        }
                    }
                    if (!itemProps.isEmpty()) {
                        items.add(itemProps);
                    }
                }
            }
        }
        return items;
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
     * Find a node in the tree by its element ID (identityHashCode).
     *
     * @param rootNode  The root node to search from
     * @param elementId The element ID to search for
     * @return The matching node, or null if not found
     */
    public static JMeterTreeNode findNodeByElementId(JMeterTreeNode rootNode, int elementId) {
        if (rootNode == null) {
            return null;
        }
        if (System.identityHashCode(rootNode) == elementId) {
            return rootNode;
        }
        int childCount = rootNode.getChildCount();
        for (int i = 0; i < childCount; i++) {
            JMeterTreeNode child = (JMeterTreeNode) rootNode.getChildAt(i);
            JMeterTreeNode found = findNodeByElementId(child, elementId);
            if (found != null) {
                return found;
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

    /**
     * Find the element type for a given node by looking up the GUI class name.
     * This performs a reverse lookup in the ELEMENT_CLASS_MAP to find the element type.
     *
     * @param node The node to get element type for
     * @return The normalized element type, or null if not found
     */
    public static String getElementType(JMeterTreeNode node) {
        if (node == null) {
            return null;
        }

        TestElement element = node.getTestElement();
        if (element == null) {
            return null;
        }

        // Get the GUI class name and model class name to determine element type
        String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
        String modelClass = element.getClass().getName();
        if (guiClass == null || guiClass.isEmpty()) {
            return null;
        }

        // First, try exact match on both GUI and model class (handles shared GUI classes like TestBeanGUI)
        for (Map.Entry<String, JMeterElementManager.ElementClassInfo> entry :
                JMeterElementManager.getElementClassMap().entrySet()) {
            if (guiClass.equals(entry.getValue().getGuiClassName())
                    && modelClass.equals(entry.getValue().getModelClassName())) {
                return entry.getKey();
            }
        }

        // Fallback: GUI class only (for backward compatibility)
        for (Map.Entry<String, JMeterElementManager.ElementClassInfo> entry :
                JMeterElementManager.getElementClassMap().entrySet()) {
            if (guiClass.equals(entry.getValue().getGuiClassName())) {
                return entry.getKey();
            }
        }

        return null;
    }
}
