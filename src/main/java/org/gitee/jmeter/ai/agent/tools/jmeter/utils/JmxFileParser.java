package org.gitee.jmeter.ai.agent.tools.jmeter.utils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gitee.jmeter.ai.agent.config.AgentConfig;

/**
 * Pure DOM-based parser for JMeter .jmx files.
 *
 * <p>This parser intentionally avoids {@code org.apache.jmeter.save.SaveService.loadTree},
 * because that method triggers {@code ParameterIncludeController.loadIncludedElements}
 * recursion and touches JMeter GUI global state (ObjectTableModel, etc.) when invoked
 * on a non-EDT thread. Instead, the file is parsed as XML and converted into a lightweight
 * POJO tree ({@link ParsedJmxElement}) that mirrors the structure needed by
 * {@code ParseJmxFileTool}.
 *
 * <p>Output property layout matches {@code JMeterTreeUtils.buildPropertiesData}:
 * <ul>
 *   <li>Simple props (string/bool/int/long) → String / Boolean / Integer / Long</li>
 *   <li>{@code <elementProp>} wrapping a {@code <collectionProp>} → {@code List<Map>} (collection items)</li>
 *   <li>{@code <elementProp>} with nested props only → {@code Map<String, Object>}</li>
 *   <li>{@code <collectionProp>} direct → {@code List<Map>}</li>
 * </ul>
 */
public final class JmxFileParser {

    private static final Set<String> PROP_TAGS = Set.of(
            "stringProp", "boolProp", "intProp", "longProp",
            "elementProp", "collectionProp");

    private JmxFileParser() {
    }

    public static ParsedJmxElement parse(File file) throws IOException, SAXException {
        Document doc = parseDocument(file);
        Element root = doc.getDocumentElement();
        if (!"jmeterTestPlan".equals(root.getTagName())) {
            throw new SAXException("Root element is not <jmeterTestPlan>: " + root.getTagName());
        }

        Element outerHashTree = findFirstElementChild(root, "hashTree");
        if (outerHashTree == null) {
            throw new SAXException("No <hashTree> under <jmeterTestPlan>");
        }

        List<ParsedJmxElement> topElements = parseHashTreeChildren(outerHashTree);
        if (topElements.isEmpty()) {
            return null;
        }
        return topElements.get(0);
    }

    private static Document parseDocument(File file) throws IOException, SAXException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file);
        } catch (ParserConfigurationException e) {
            throw new SAXException("XML parser misconfigured: " + e.getMessage(), e);
        }
    }

    private static Element findFirstElementChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<ParsedJmxElement> parseHashTreeChildren(Element hashTree) {
        List<ParsedJmxElement> result = new ArrayList<>();
        NodeList children = hashTree.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = n.getNodeName();
            if ("hashTree".equals(tag) || PROP_TAGS.contains(tag)) {
                continue;
            }
            ParsedJmxElement element = parseElementTag((Element) n);
            element.children = findSiblingHashTreeChildren(n);
            result.add(element);
        }
        return result;
    }

    private static List<ParsedJmxElement> findSiblingHashTreeChildren(Node elementNode) {
        Node sibling = elementNode.getNextSibling();
        while (sibling != null && sibling.getNodeType() != Node.ELEMENT_NODE) {
            sibling = sibling.getNextSibling();
        }
        if (sibling == null || !"hashTree".equals(sibling.getNodeName())) {
            return new ArrayList<>();
        }
        return parseHashTreeChildren((Element) sibling);
    }

    private static ParsedJmxElement parseElementTag(Element el) {
        ParsedJmxElement parsed = new ParsedJmxElement();
        parsed.elementType = el.getAttribute("testclass");
        if (parsed.elementType.isEmpty()) {
            parsed.elementType = el.getTagName();
        }
        parsed.guiClass = el.getAttribute("guiclass");
        parsed.name = el.getAttribute("testname");
        String enabledAttr = el.getAttribute("enabled");
        parsed.enabled = enabledAttr.isEmpty() || Boolean.parseBoolean(enabledAttr);
        parsed.properties = parseChildProperties(el);
        return parsed;
    }

    private static Map<String, Object> parseChildProperties(Element parent) {
        Map<String, Object> props = new LinkedHashMap<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String tag = n.getNodeName();
            if (!PROP_TAGS.contains(tag)) {
                continue;
            }
            Element propEl = (Element) n;
            String name = propEl.getAttribute("name");
            if (name.isEmpty()) {
                continue;
            }
            Object value = parsePropertyValue(propEl, tag);
            if (value != null) {
                props.put(name, value);
            }
        }
        return props;
    }

    private static Object parsePropertyValue(Element propEl, String tag) {
        switch (tag) {
            case "stringProp":
                return truncate(textOf(propEl));
            case "boolProp":
                return Boolean.parseBoolean(textOf(propEl));
            case "intProp":
                return parseInt(textOf(propEl));
            case "longProp":
                return parseLong(textOf(propEl));
            case "elementProp":
                return parseElementPropValue(propEl);
            case "collectionProp":
                return parseCollectionItems(propEl);
            default:
                return truncate(textOf(propEl));
        }
    }

    /**
     * Parse an {@code <elementProp>}.
     *
     * <p>If the element wraps a {@code <collectionProp>}, return a {@code List<Map>} of the
     * collection items (matching JMeterTreeUtils.extractCollectionItems behavior).
     * Otherwise return a {@code Map<String, Object>} of the nested properties.
     */
    private static Object parseElementPropValue(Element elementProp) {
        Element innerCollection = findFirstElementChild(elementProp, "collectionProp");
        if (innerCollection != null) {
            return parseCollectionItems(innerCollection);
        }
        return parseChildProperties(elementProp);
    }

    /**
     * Parse a {@code <collectionProp>}. Each {@code <elementProp>} child becomes a Map.
     * The Map includes "TestElement.name" from the elementProp's {@code name} XML attribute
     * (mirroring how JMeter loads Argument names etc.), plus all nested properties.
     */
    private static List<Map<String, Object>> parseCollectionItems(Element collectionProp) {
        List<Map<String, Object>> items = new ArrayList<>();
        NodeList children = collectionProp.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (!"elementProp".equals(n.getNodeName())) {
                continue;
            }
            Element itemEl = (Element) n;
            Map<String, Object> itemProps = new LinkedHashMap<>();
            String itemElName = itemEl.getAttribute("name");
            if (!itemElName.isEmpty()) {
                itemProps.put("TestElement.name", itemElName);
            }
            Map<String, Object> nested = parseChildProperties(itemEl);
            itemProps.putAll(nested);
            if (!itemProps.isEmpty()) {
                items.add(itemProps);
            }
        }
        return items;
    }

    private static String textOf(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            short type = n.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static Object parseInt(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return text;
        }
    }

    private static Object parseLong(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return text;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        int max = AgentConfig.getInstance().getMaxStringLength();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(truncated)";
    }

    public static class ParsedJmxElement {
        public String elementType;
        public String guiClass;
        public String name;
        public boolean enabled;
        public Map<String, Object> properties;
        public List<ParsedJmxElement> children = new ArrayList<>();
    }
}
