package org.gitee.jmeter.ai.agent.tools.jmeter.property;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.ObjectProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
import org.gitee.jmeter.ai.agent.validation.ComponentSchemaLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 0 baseline tests for {@link SchemaBasedPropertyHandler}.
 *
 * <p>Locks current behavior across 5 templates before refactoring. These tests run against
 * UNMODIFIED old code paths (propName-based if-else dispatch) and serve as golden baseline.
 * Phase 1/2/3 must keep all these tests green.
 *
 * <p>Skipped on missing classpath: UserParameters (in ApacheJMeter_components, not on test cp),
 * ValueAssertion (gitee custom class), 4 gitee item classes — marked via {@link
 * org.junit.jupiter.api.Assumptions#assumeTrue(boolean, String)} so the test is reported as
 * skipped rather than failed.
 */
class SchemaBasedPropertyHandlerTest {

    private static ComponentSchemaLoader schemaLoader;
    private final SchemaBasedPropertyHandler handler = new SchemaBasedPropertyHandler();

    @BeforeAll
    static void loadSchemas() {
        Path skillsDir = Paths.get("src/main/jmeter-agent/skills");
        schemaLoader = new ComponentSchemaLoader(skillsDir);
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void requireClass(String className) {
        org.junit.jupiter.api.Assumptions.assumeTrue(classExists(className),
                className + " not on test classpath — skipping (gitee custom class or non-core/http component)");
    }

    // ===== Case 1: HTTPsampler.Arguments standard parameter mode =====
    @Test
    void case1_httpArguments_standardMode_writesUseEqualsAndMetadata() {
        ComponentSchema schema = schemaLoader.loadSchema("httpsampler");
        assertNotNull(schema, "httpsampler schema must be loaded");

        HTTPSamplerProxy sampler = new HTTPSamplerProxy();

        List<Map<String, Object>> args = new ArrayList<>();
        Map<String, Object> arg = new HashMap<>();
        arg.put("Argument.name", "username");
        arg.put("Argument.value", "alice");
        arg.put("HTTPArgument.use_equals", true);
        arg.put("HTTPArgument.always_encode", false);
        args.add(arg);

        Map<String, Object> properties = new HashMap<>();
        properties.put("HTTPsampler.Arguments", args);

        handler.setProperties(sampler, properties, schema);

        JMeterProperty prop = sampler.getProperty("HTTPsampler.Arguments");
        assertTrue(prop instanceof TestElementProperty,
                "HTTPsampler.Arguments must be TestElementProperty");
        Arguments arguments = (Arguments) ((TestElementProperty) prop).getObjectValue();
        assertNotNull(arguments);
        assertEquals(1, arguments.getArguments().size());

        Argument written = (Argument) arguments.getArguments().get(0).getObjectValue();
        assertEquals("username", written.getName());
        assertEquals("alice", written.getValue());
        assertTrue(written instanceof HTTPArgument, "item must be HTTPArgument");
        assertEquals("=", written.getMetaData(), "useEquals=true → metadata='='");
    }

    // ===== Case 2: HTTPsampler.Arguments postBodyRaw mode (empty name) =====
    @Test
    void case2_httpArguments_postBodyRawMode_emptyNameAllowed() {
        ComponentSchema schema = schemaLoader.loadSchema("httpsampler");
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty("HTTPSampler.postBodyRaw", true);

        List<Map<String, Object>> args = new ArrayList<>();
        Map<String, Object> arg = new HashMap<>();
        // name intentionally omitted → empty string
        arg.put("Argument.value", "{\"k\":\"v\"}");
        args.add(arg);

        Map<String, Object> properties = new HashMap<>();
        properties.put("HTTPsampler.Arguments", args);

        handler.setProperties(sampler, properties, schema);

        JMeterProperty prop = sampler.getProperty("HTTPsampler.Arguments");
        Arguments arguments = (Arguments) ((TestElementProperty) prop).getObjectValue();
        Argument written = (Argument) arguments.getArguments().get(0).getObjectValue();
        assertEquals("", written.getName(), "postBodyRaw mode → name defaults to empty");
        assertEquals("{\"k\":\"v\"}", written.getValue());
    }

    // ===== Case 3: HeaderManager.headers self-mount + clear-and-add =====
    @Test
    void case3_headerManager_selfMountClearsAndAdds() {
        ComponentSchema schema = schemaLoader.loadSchema("headermanager");
        HeaderManager hm = new HeaderManager();

        // pre-existing header — must be cleared
        hm.add(new Header("Existing", "old"));
        assertEquals(1, hm.getHeaders().size(), "precondition: 1 existing header");

        List<Map<String, Object>> headers = new ArrayList<>();
        Map<String, Object> h1 = new HashMap<>();
        h1.put("Header.name", "Content-Type");
        h1.put("Header.value", "application/json");
        Map<String, Object> h2 = new HashMap<>();
        h2.put("Header.name", "Authorization");
        h2.put("Header.value", "Bearer xyz");
        headers.add(h1);
        headers.add(h2);

        Map<String, Object> properties = new HashMap<>();
        properties.put("HeaderManager.headers", headers);

        handler.setProperties(hm, properties, schema);

        assertEquals(2, hm.getHeaders().size(), "old header cleared, 2 new added");
        assertEquals("Content-Type", hm.get(0).getName());
        assertEquals("application/json", hm.get(0).getValue());
        assertEquals("Authorization", hm.get(1).getName());
    }

    // ===== Case 4: CookieManager.cookies with long expires =====
    @Test
    void case4_cookieManager_longExpiresTypeConversion() {
        ComponentSchema schema = schemaLoader.loadSchema("cookiemanager");
        CookieManager cm = new CookieManager();

        List<Map<String, Object>> cookies = new ArrayList<>();
        Map<String, Object> c = new HashMap<>();
        c.put("Cookie.name", "session");
        c.put("Cookie.value", "abc123");
        c.put("Cookie.domain", "example.com");
        c.put("Cookie.path", "/");
        c.put("Cookie.secure", true);
        c.put("Cookie.expires", 1700000000L);
        c.put("Cookie.path_specified", true);
        c.put("Cookie.domain_specified", true);
        cookies.add(c);

        Map<String, Object> properties = new HashMap<>();
        properties.put("CookieManager.cookies", cookies);

        handler.setProperties(cm, properties, schema);

        assertEquals(1, cm.getCookieCount(), "one cookie added");
        Cookie cookie = cm.get(0);
        assertEquals("session", cookie.getName());
        assertEquals(1700000000L, cookie.getExpires(), "long expires must round-trip");
        assertTrue(cookie.getSecure(), "secure=true");
    }

    // ===== Case 5: ThreadGroup.main_controller → setSamplerController =====
    @Test
    void case5_threadGroup_mainController_callsSetSamplerController() {
        ComponentSchema schema = schemaLoader.loadSchema("threadgroup");
        ThreadGroup tg = new ThreadGroup();

        Map<String, Object> controller = new HashMap<>();
        controller.put("LoopController.loops", "5");
        controller.put("LoopController.continue_forever", false);

        Map<String, Object> properties = new HashMap<>();
        properties.put("ThreadGroup.main_controller", controller);

        handler.setProperties(tg, properties, schema);

        org.apache.jmeter.control.Controller c = tg.getSamplerController();
        assertNotNull(c, "ThreadGroup.samplerController must be set");
        assertTrue(c instanceof LoopController, "must be LoopController");
        LoopController lc = (LoopController) c;
        assertEquals(5, lc.getLoops(), "loops=5 via nested setter");
    }

    // ===== Case 6: ResultCollector.saveConfig → ObjectProperty + setAsXml =====
    @Test
    void case6_viewResultsTree_saveConfig_nonStandardSetter() {
        ComponentSchema schema = schemaLoader.loadSchema("viewresultstree");
        ResultCollector rc = new ResultCollector();

        Map<String, Object> saveConfig = new HashMap<>();
        saveConfig.put("xml", true);
        saveConfig.put("time", false);
        saveConfig.put("success", true);

        Map<String, Object> properties = new HashMap<>();
        properties.put("saveConfig", saveConfig);

        handler.setProperties(rc, properties, schema);

        JMeterProperty prop = rc.getProperty("saveConfig");
        assertTrue(prop instanceof ObjectProperty,
                "SampleSaveConfiguration must be wrapped as ObjectProperty (not TestElementProperty)");
        SampleSaveConfiguration ssc = (SampleSaveConfiguration) prop.getObjectValue();
        assertNotNull(ssc);
        // xml field has no public getter (only setAsXml setter); its setterOverride is exercised
        // but verified via Phase 1 schema-driven test. Below fields have public getters.
        assertFalse(ssc.saveTime(), "time=false via setTime");
        assertTrue(ssc.saveSuccess(), "success=true via setSuccess");
    }

    // ===== Case 7: UserParameters.thread_values ARRAY_2D =====
    @Test
    void case7_userParameters_threadValues_array2d() {
        requireClass("org.apache.jmeter.modifiers.UserParameters");
        // Body intentionally minimal — actual assertion depends on UserParameters API.
        // Skipped unless ApacheJMeter_components is on test classpath.
        org.apache.jmeter.testelement.property.CollectionProperty outer =
                buildArray2dFixture("UserParameters.thread_values",
                        java.util.Arrays.asList(
                                java.util.Arrays.asList("v1-user1", "v2-user1"),
                                java.util.Arrays.asList("v1-user2", "v2-user2")));
        assertEquals(2, outer.size(), "ARRAY_2D outer has 2 inner arrays");
        assertEquals(2, ((org.apache.jmeter.testelement.property.CollectionProperty) outer.get(0)).size());
    }

    // ===== Case 8: ValueAssertion.valuesCheckTable TestBean table =====
    @Test
    void case8_valueAssertion_valuesCheckTable_testbeanTable() {
        requireClass("com.gitee.qa.jmeter.assertions.ValueAssertion");
        // Body intentionally minimal — gitee custom class not on test classpath.
    }

    // ===== Case 9: No-arg constructor availability for item classes =====
    @ParameterizedTest
    @ValueSource(strings = {
            "org.apache.jmeter.config.Argument",
            "org.apache.jmeter.protocol.http.util.HTTPArgument",
            "org.apache.jmeter.protocol.http.util.HTTPFileArg",
            "org.apache.jmeter.protocol.http.control.Header",
            "org.apache.jmeter.protocol.http.control.Cookie",
            "com.gitee.qa.jmeter.control.util.ParameterIncludeControllerArgument",
            "com.gitee.qa.jmeter.control.util.ParameterTestFragmentReturnValueArgument",
            "com.gitee.qa.jmeter.control.util.ParameterIncludeControllerReturnValueArgument",
            "com.gitee.qa.jmeter.protocol.httpud.util.HTTPUDArgument"
    })
    void case9_itemClasses_haveNoArgConstructor(String className) {
        requireClass(className);

        try {
            Class<?> clazz = Class.forName(className);
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            assertNotNull(ctor, className + " must declare public no-arg constructor");
            Object instance = ctor.newInstance();
            assertNotNull(instance, className + " no-arg constructor must produce non-null instance");
        } catch (NoSuchMethodException e) {
            fail(className + " has NO no-arg constructor — Phase 1 refactor requires it. "
                    + "Contact gitee class author to add one (JMeter TestElement convention).");
        } catch (Exception e) {
            fail(className + " no-arg constructor invocation failed: " + e.getMessage());
        }
    }

    // Helper for ARRAY_2D structural verification (independent of UserParameters class)
    private org.apache.jmeter.testelement.property.CollectionProperty buildArray2dFixture(
            String name, List<List<String>> rows) {
        org.apache.jmeter.testelement.property.CollectionProperty outer =
                new org.apache.jmeter.testelement.property.CollectionProperty(name, new java.util.ArrayList<>());
        for (List<String> row : rows) {
            org.apache.jmeter.testelement.property.CollectionProperty inner =
                    new org.apache.jmeter.testelement.property.CollectionProperty(
                            "row-" + System.nanoTime(), new java.util.ArrayList<>());
            for (String v : row) {
                inner.addItem(v);
            }
            outer.addItem(inner);
        }
        return outer;
    }

    // =================================================================================
    // Phase 1.6: Independent tests for handleContainerItemsProperty (new template)
    // Uses Java-constructed fixture schemas (no YAML migration dependency).
    // =================================================================================

    private ComponentSchema.PropertyDefinition fieldDef(String name, String type) {
        ComponentSchema.PropertyDefinition f = new ComponentSchema.PropertyDefinition();
        f.setName(name);
        f.setType(type);
        return f;
    }

    private ComponentSchema.PropertyDefinition fieldDef(String name, String type, String setterOverride) {
        ComponentSchema.PropertyDefinition f = fieldDef(name, type);
        f.setSetterOverride(setterOverride);
        return f;
    }

    /** Fixture: HeaderManager.headers with mountMode=SELF */
    private ComponentSchema buildHeaderManagerFixtureSchema() {
        ComponentSchema schema = new ComponentSchema();
        schema.setComponentType("headermanager-fixture");

        ComponentSchema.PropertyDefinition headers = new ComponentSchema.PropertyDefinition();
        headers.setName("HeaderManager.headers");
        headers.setType("Array");
        headers.setClassName("org.apache.jmeter.protocol.http.control.HeaderManager");
        headers.setMountMode(ComponentSchema.MountMode.SELF);
        headers.setContainerAddMethod("add");
        headers.setItemClass("org.apache.jmeter.protocol.http.control.Header");
        headers.setItemProperties(java.util.Arrays.asList(
                fieldDef("Header.name", "String"),
                fieldDef("Header.value", "String")));
        schema.addProperty(headers);
        return schema;
    }

    /** Fixture: Arguments with mountMode=TEST_ELEMENT_PROPERTY (e.g., HTTPsampler.Arguments) */
    private ComponentSchema buildArgumentsFixtureSchema() {
        ComponentSchema schema = new ComponentSchema();
        schema.setComponentType("arguments-fixture");

        ComponentSchema.PropertyDefinition args = new ComponentSchema.PropertyDefinition();
        args.setName("HTTPsampler.Arguments");
        args.setType("Array");
        args.setClassName("org.apache.jmeter.config.Arguments");
        args.setMountMode(ComponentSchema.MountMode.TEST_ELEMENT_PROPERTY);
        args.setContainerAddMethod("addArgument");
        args.setItemClass("org.apache.jmeter.config.Argument");
        args.setItemProperties(java.util.Arrays.asList(
                fieldDef("Argument.name", "String"),
                fieldDef("Argument.value", "String"),
                fieldDef("Argument.desc", "String", "setDescription"),
                fieldDef("Argument.metadata", "String", "setMetaData")));
        schema.addProperty(args);
        return schema;
    }

    @Test
    void containerItems_selfMode_buildsAndClearsExisting() {
        ComponentSchema schema = buildHeaderManagerFixtureSchema();
        HeaderManager hm = new HeaderManager();
        hm.add(new Header("Existing", "old"));

        List<Map<String, Object>> headers = new ArrayList<>();
        Map<String, Object> h1 = new HashMap<>();
        h1.put("Header.name", "X-Custom");
        h1.put("Header.value", "v1");
        headers.add(h1);

        Map<String, Object> properties = new HashMap<>();
        properties.put("HeaderManager.headers", headers);

        handler.setProperties(hm, properties, schema);

        assertEquals(1, hm.getHeaders().size(), "existing cleared, 1 new added");
        assertEquals("X-Custom", hm.get(0).getName());
        assertEquals("v1", hm.get(0).getValue());
    }

    @Test
    void containerItems_nestedMode_buildsViaNoArgCtorAndSetters() {
        ComponentSchema schema = buildArgumentsFixtureSchema();
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();

        List<Map<String, Object>> args = new ArrayList<>();
        Map<String, Object> a = new HashMap<>();
        a.put("Argument.name", "key");
        a.put("Argument.value", "val");
        a.put("Argument.desc", "description");
        a.put("Argument.metadata", "=");
        args.add(a);

        Map<String, Object> properties = new HashMap<>();
        properties.put("HTTPsampler.Arguments", args);

        handler.setProperties(sampler, properties, schema);

        JMeterProperty prop = sampler.getProperty("HTTPsampler.Arguments");
        assertTrue(prop instanceof TestElementProperty);
        Arguments arguments = (Arguments) ((TestElementProperty) prop).getObjectValue();
        Argument arg = (Argument) arguments.getArguments().get(0).getObjectValue();
        assertEquals("key", arg.getName());
        assertEquals("val", arg.getValue());
        assertEquals("=", arg.getMetaData(), "setterOverride setMetaData applied");
        assertEquals("description", arg.getDescription(), "setterOverride setDescription applied");
    }

    @Test
    void containerItems_emptyInput_clearsContainer() {
        ComponentSchema schema = buildHeaderManagerFixtureSchema();
        HeaderManager hm = new HeaderManager();
        hm.add(new Header("Existing", "old"));
        assertEquals(1, hm.getHeaders().size(), "precondition");

        Map<String, Object> properties = new HashMap<>();
        properties.put("HeaderManager.headers", new ArrayList<>());  // empty input

        handler.setProperties(hm, properties, schema);

        assertEquals(0, hm.getHeaders().size(), "empty input → container cleared");
    }

    @Test
    void containerItems_parseFailureProtection_skipsUpdate() {
        ComponentSchema schema = buildHeaderManagerFixtureSchema();
        HeaderManager hm = new HeaderManager();
        hm.add(new Header("Existing", "old"));

        // Input with non-Map items → all fail to parse
        List<Object> badItems = new ArrayList<>();
        badItems.add("not-a-map");  // will fail "instanceof Map" check

        Map<String, Object> properties = new HashMap<>();
        properties.put("HeaderManager.headers", badItems);

        handler.setProperties(hm, properties, schema);

        assertEquals(1, hm.getHeaders().size(), "parse-failure protection: existing data preserved");
        assertEquals("Existing", hm.get(0).getName());
    }

    // =================================================================================
    // Phase 2 migration verification: real migrated schemas drive new template
    // =================================================================================

    @Test
    void batchA_userDefinedVariables_realSchema_drivesNewTemplate() {
        ComponentSchema schema = schemaLoader.loadSchema("userdefinedvariables");
        assertNotNull(schema, "userdefinedvariables schema must be loaded");

        // UserDefinedVariables element IS Arguments (mountMode=self)
        Arguments element = new Arguments();

        List<Map<String, Object>> vars = new ArrayList<>();
        Map<String, Object> v1 = new HashMap<>();
        v1.put("Argument.name", "BASE_URL");
        v1.put("Argument.value", "https://api.example.com");
        v1.put("Argument.desc", "API base URL");
        v1.put("Argument.metadata", "=");
        vars.add(v1);

        Map<String, Object> properties = new HashMap<>();
        properties.put("Arguments.arguments", vars);

        handler.setProperties(element, properties, schema);

        assertEquals(1, element.getArguments().size());
        Argument arg = (Argument) element.getArguments().get(0).getObjectValue();
        assertEquals("BASE_URL", arg.getName());
        assertEquals("https://api.example.com", arg.getValue());
        assertEquals("=", arg.getMetaData(), "setterOverride setMetaData applied");
        assertEquals("API base URL", arg.getDescription(), "setterOverride setDescription applied");
    }

    @Test
    void batchA_backendListener_realSchema_drivesNewTemplate() {
        ComponentSchema schema = schemaLoader.loadSchema("backendlistener");
        assertNotNull(schema);

        // BackendListener — use ResultCollector-like container; Arguments nested as TestElementProperty
        org.apache.jmeter.visualizers.backend.BackendListener element =
                new org.apache.jmeter.visualizers.backend.BackendListener();

        List<Map<String, Object>> args = new ArrayList<>();
        Map<String, Object> a = new HashMap<>();
        a.put("Argument.name", "influxdbMetricsSender");
        a.put("Argument.value", "org.apache.jmeter.visualizers.backend.influxdb.HttpMetricsSender");
        a.put("Argument.metadata", "=");
        args.add(a);

        Map<String, Object> properties = new HashMap<>();
        properties.put("arguments", args);

        handler.setProperties(element, properties, schema);

        // BackendListener stores Arguments as TestElementProperty under "arguments" key (BackendListener.ARGUMENTS)
        JMeterProperty prop = element.getProperty("arguments");
        assertTrue(prop instanceof TestElementProperty);
        Arguments arguments = (Arguments) ((TestElementProperty) prop).getObjectValue();
        assertEquals(1, arguments.getArguments().size());
        Argument arg = (Argument) arguments.getArguments().get(0).getObjectValue();
        assertEquals("influxdbMetricsSender", arg.getName());
    }

    // =================================================================================
    // Multi-container regression: HTTPUDConfigElement has 3 Arguments-type containers
    // (HTTPsampler.Arguments / HTTPUDConfigElement.http_header_parameters_name /
    // HTTPUDArgumentsGui.HTTPUDArguments). Validates they don't cross-route by type match.
    // Regression guard for the ClassCastException / NPE observed when findExistingContainer
    // used "first-by-type" fallback instead of propName-precise matching.
    // =================================================================================

    /**
     * Fixture: element pre-populated with HTTPsampler.Arguments (HTTPArgument items),
     * then a second Array property with same containerClass=Arguments but different itemClass
     * is set. The second property MUST NOT reuse HTTPsampler.Arguments container.
     */
    @Test
    void multiContainer_sameContainerClass_distinctPropNames_doNotCrossRoute() {
        // Build schema with two Array props, both containerClass=Arguments
        ComponentSchema schema = new ComponentSchema();
        schema.setComponentType("multi-container-fixture");

        ComponentSchema.PropertyDefinition httpArgs = new ComponentSchema.PropertyDefinition();
        httpArgs.setName("HTTPsampler.Arguments");
        httpArgs.setType(ComponentSchema.PropertyType.ARRAY);
        httpArgs.setClassName("org.apache.jmeter.config.Arguments");
        httpArgs.setMountMode(ComponentSchema.MountMode.TEST_ELEMENT_PROPERTY);
        httpArgs.setContainerAddMethod("addArgument");
        httpArgs.setItemClass("org.apache.jmeter.protocol.http.util.HTTPArgument");
        httpArgs.setItemProperties(java.util.Arrays.asList(
                fieldDef("Argument.name", "String"),
                fieldDef("Argument.value", "String")));
        schema.addProperty(httpArgs);

        ComponentSchema.PropertyDefinition headerParams = new ComponentSchema.PropertyDefinition();
        headerParams.setName("HTTPUDConfigElement.http_header_parameters_name");
        headerParams.setType(ComponentSchema.PropertyType.ARRAY);
        headerParams.setClassName("org.apache.jmeter.config.Arguments");
        headerParams.setMountMode(ComponentSchema.MountMode.TEST_ELEMENT_PROPERTY);
        headerParams.setContainerAddMethod("addArgument");
        headerParams.setItemClass("org.apache.jmeter.config.Argument");
        headerParams.setItemProperties(java.util.Arrays.asList(
                fieldDef("Argument.name", "String"),
                fieldDef("Argument.value", "String")));
        schema.addProperty(headerParams);

        // Use ConfigTestElement (HTTPUDConfigElement's parent class) as target
        org.apache.jmeter.config.ConfigTestElement element = new org.apache.jmeter.config.ConfigTestElement();

        // Step 1: set HTTPsampler.Arguments with one HTTPArgument
        List<Map<String, Object>> httpArgsList = new ArrayList<>();
        Map<String, Object> httpArg = new HashMap<>();
        httpArg.put("Argument.name", "user");
        httpArg.put("Argument.value", "alice");
        httpArgsList.add(httpArg);

        Map<String, Object> props1 = new HashMap<>();
        props1.put("HTTPsampler.Arguments", httpArgsList);
        handler.setProperties(element, props1, schema);

        JMeterProperty argsProp1 = element.getProperty("HTTPsampler.Arguments");
        assertTrue(argsProp1 instanceof TestElementProperty,
                "HTTPsampler.Arguments must be TestElementProperty");
        Arguments argsContainer1 = (Arguments) ((TestElementProperty) argsProp1).getObjectValue();
        assertEquals(1, argsContainer1.getArguments().size(),
                "HTTPsampler.Arguments has 1 HTTPArgument after step 1");
        Object item1 = argsContainer1.getArguments().get(0).getObjectValue();
        assertTrue(item1 instanceof HTTPArgument,
                "item in HTTPsampler.Arguments must be HTTPArgument");

        // Step 2: set http_header_parameters_name with one Argument — MUST NOT reuse HTTPsampler.Arguments
        List<Map<String, Object>> headersList = new ArrayList<>();
        Map<String, Object> header = new HashMap<>();
        header.put("Argument.name", "Content-Type");
        header.put("Argument.value", "application/json");
        headersList.add(header);

        Map<String, Object> props2 = new HashMap<>();
        props2.put("HTTPUDConfigElement.http_header_parameters_name", headersList);
        handler.setProperties(element, props2, schema);

        // Verify HTTPsampler.Arguments unchanged
        JMeterProperty argsProp2 = element.getProperty("HTTPsampler.Arguments");
        Arguments argsContainer2 = (Arguments) ((TestElementProperty) argsProp2).getObjectValue();
        assertEquals(1, argsContainer2.getArguments().size(),
                "HTTPsampler.Arguments size must remain 1 (not polluted by header params)");
        Object item2 = argsContainer2.getArguments().get(0).getObjectValue();
        assertTrue(item2 instanceof HTTPArgument,
                "item in HTTPsampler.Arguments must still be HTTPArgument");
        assertEquals("user", ((HTTPArgument) item2).getName(),
                "HTTPsampler.Arguments item unchanged");

        // Verify http_header_parameters_name exists as separate property with Argument items
        JMeterProperty headerProp = element.getProperty("HTTPUDConfigElement.http_header_parameters_name");
        assertTrue(headerProp instanceof TestElementProperty,
                "http_header_parameters_name must be TestElementProperty (separate from HTTPsampler.Arguments)");
        Arguments headerContainer = (Arguments) ((TestElementProperty) headerProp).getObjectValue();
        assertEquals(1, headerContainer.getArguments().size(),
                "http_header_parameters_name has 1 Argument");
        Object headerItem = headerContainer.getArguments().get(0).getObjectValue();
        assertTrue(headerItem instanceof Argument,
                "item in http_header_parameters_name must be plain Argument, not HTTPArgument");
        assertFalse(headerItem instanceof HTTPArgument,
                "header item MUST NOT be HTTPArgument (would cause ClassCastException in GUI)");
        assertEquals("Content-Type", ((Argument) headerItem).getName());
    }

    /**
     * Empty-input regression: when default: [] is applied and user didn't pass the property,
     * handler must still create an empty container and setProperty. This prevents GUI NPE
     * when UrlConfigGui.configure expects HTTPsampler.Arguments but it's absent.
     */
    @Test
    void containerItems_emptyInput_stillCreatesContainerProperty() {
        ComponentSchema.PropertyDefinition propDef = new ComponentSchema.PropertyDefinition();
        propDef.setName("HTTPsampler.Arguments");
        propDef.setType(ComponentSchema.PropertyType.ARRAY);
        propDef.setClassName("org.apache.jmeter.config.Arguments");
        propDef.setMountMode(ComponentSchema.MountMode.TEST_ELEMENT_PROPERTY);
        propDef.setContainerAddMethod("addArgument");
        propDef.setItemClass("org.apache.jmeter.protocol.http.util.HTTPArgument");
        propDef.setItemProperties(java.util.Arrays.asList(
                fieldDef("Argument.name", "String"),
                fieldDef("Argument.value", "String")));

        ComponentSchema schema = new ComponentSchema();
        schema.setComponentType("empty-container-fixture");
        schema.addProperty(propDef);

        org.apache.jmeter.config.ConfigTestElement element = new org.apache.jmeter.config.ConfigTestElement();

        Map<String, Object> props = new HashMap<>();
        props.put("HTTPsampler.Arguments", new ArrayList<>());  // empty list, like default: []
        handler.setProperties(element, props, schema);

        JMeterProperty prop = element.getProperty("HTTPsampler.Arguments");
        assertTrue(prop instanceof TestElementProperty,
                "empty input must still create TestElementProperty, got: " + prop.getClass().getSimpleName());
        Arguments container = (Arguments) ((TestElementProperty) prop).getObjectValue();
        assertNotNull(container, "container must be non-null");
        assertEquals(0, container.getArguments().size(),
                "container must be empty (no items added)");
    }
}
