package org.gitee.jmeter.ai.agent.tools.jmeter.utils;

import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JmxFileParser.ParsedJmxElement;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmxFileParserTest {

    private ParsedJmxElement loadFixture() throws IOException, SAXException {
        URL url = getClass().getResource("/fixtures/sample-with-includes.jmx");
        assertNotNull(url, "fixture must be on classpath");
        return JmxFileParser.parse(new File(url.getFile()));
    }

    @Test
    void parsesRootTestPlan() throws Exception {
        ParsedJmxElement root = loadFixture();
        assertNotNull(root);
        assertEquals("TestPlan", root.elementType);
        assertEquals("TestPlanGui", root.guiClass);
        assertEquals("Sample Test Plan", root.name);
        assertTrue(root.enabled);
    }

    @Test
    void rootHasCommentProperty() throws Exception {
        ParsedJmxElement root = loadFixture();
        assertEquals("A plan for parser unit test", root.properties.get("TestPlan.comments"));
        assertEquals(Boolean.FALSE, root.properties.get("TestPlan.functional_mode"));
    }

    @Test
    void parsesThreadGroupWithChildren() throws Exception {
        ParsedJmxElement root = loadFixture();
        assertEquals(1, root.children.size());

        ParsedJmxElement threadGroup = root.children.get(0);
        assertEquals("ThreadGroup", threadGroup.elementType);
        assertEquals("Thread Group", threadGroup.name);
        assertEquals(1, threadGroup.children.size());

        ParsedJmxElement httpSampler = threadGroup.children.get(0);
        assertEquals("HTTPSamplerProxy", httpSampler.elementType);
        assertEquals("Login Request", httpSampler.name);
    }

    @Test
    void parsesTypedPropsAsStringBoolIntLong() throws Exception {
        ParsedJmxElement root = loadFixture();
        ParsedJmxElement threadGroup = root.children.get(0);

        assertEquals("5", threadGroup.properties.get("ThreadGroup.num_threads"));
        assertEquals(Boolean.FALSE, threadGroup.properties.get("ThreadGroup.scheduler"));
        assertEquals(Long.valueOf(1700000000L), threadGroup.properties.get("ThreadGroup.start_time"));
    }

    @Test
    void parsesNestedLoopControllerAsMap() throws Exception {
        ParsedJmxElement root = loadFixture();
        ParsedJmxElement threadGroup = root.children.get(0);
        Object controller = threadGroup.properties.get("ThreadGroup.main_controller");
        assertTrue(controller instanceof Map, "LoopController elementProp without inner collectionProp should be a Map");
        @SuppressWarnings("unchecked")
        Map<String, Object> controllerMap = (Map<String, Object>) controller;
        assertEquals(Boolean.FALSE, controllerMap.get("LoopController.continue_forever"));
        assertEquals(Integer.valueOf(3), controllerMap.get("LoopController.loops"));
    }

    @Test
    void parsesHttpArgumentsCollectionAsListOfMaps() throws Exception {
        ParsedJmxElement root = loadFixture();
        ParsedJmxElement threadGroup = root.children.get(0);
        ParsedJmxElement httpSampler = threadGroup.children.get(0);

        Object args = httpSampler.properties.get("HTTPsampler.Arguments");
        assertTrue(args instanceof List, "Arguments should be a List<Map>");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) args;
        assertEquals(2, items.size());

        Map<String, Object> username = items.get(0);
        assertEquals("username", username.get("TestElement.name"));
        assertEquals("alice", username.get("Argument.value"));
        assertEquals(Boolean.FALSE, username.get("HTTPArgument.always_encode"));

        Map<String, Object> password = items.get(1);
        assertEquals("password", password.get("TestElement.name"));
        assertEquals("secret", password.get("Argument.value"));
    }

    @Test
    void parsesHeaderManagerCollection() throws Exception {
        ParsedJmxElement root = loadFixture();
        ParsedJmxElement headerManager = findElementByType(root, "HeaderManager");
        assertNotNull(headerManager);

        Object headers = headerManager.properties.get("HeaderManager.headers");
        assertTrue(headers instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headerList = (List<Map<String, Object>>) headers;
        assertEquals(2, headerList.size());
        assertEquals("Content-Type", headerList.get(0).get("TestElement.name"));
        assertEquals("application/json", headerList.get(0).get("Header.value"));
    }

    @Test
    void disabledElementHasEnabledFalse() throws Exception {
        ParsedJmxElement root = loadFixture();
        ParsedJmxElement assertion = findElementByType(root, "ResponseAssertion");
        assertNotNull(assertion);
        assertFalse(assertion.enabled);
        assertEquals(Integer.valueOf(8), assertion.properties.get("Assertion.test_type"));
    }

    @Test
    void emptyHashTreeProducesEmptyChildren() throws Exception {
        ParsedJmxElement root = loadFixture();
        ParsedJmxElement headerManager = findElementByType(root, "HeaderManager");
        assertNotNull(headerManager);
        assertEquals(0, headerManager.children.size());
    }

    @Test
    void rejectsNonJmxRoot() throws Exception {
        URL url = getClass().getResource("/fixtures/not-a-jmx.xml");
        if (url == null) {
            return;
        }
        try {
            JmxFileParser.parse(new File(url.getFile()));
            org.junit.jupiter.api.Assertions.fail("expected SAXException");
        } catch (SAXException expected) {
            assertTrue(expected.getMessage().contains("jmeterTestPlan"));
        }
    }

    @Test
    void nullReturnedForEmptyHashTree() throws Exception {
        URL url = getClass().getResource("/fixtures/empty-hashtree.jmx");
        if (url == null) {
            return;
        }
        ParsedJmxElement result = JmxFileParser.parse(new File(url.getFile()));
        assertNull(result);
    }

    private ParsedJmxElement findElementByType(ParsedJmxElement root, String type) {
        if (type.equals(root.elementType)) {
            return root;
        }
        for (ParsedJmxElement child : root.children) {
            ParsedJmxElement found = findElementByType(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
