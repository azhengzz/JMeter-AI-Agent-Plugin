package org.qainsights.jmeter.ai.agent.tools.jmeter.property;

import org.apache.jmeter.testelement.TestElement;
import org.qainsights.jmeter.ai.agent.validation.ComponentSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles JMeter element properties based on schema definitions.
 * Routes properties to appropriate handlers based on schema type.
 */
public class SchemaBasedPropertyHandler {

    private static final Logger log = LoggerFactory.getLogger(SchemaBasedPropertyHandler.class);

    /**
     * Set properties on a TestElement based on schema definitions.
     *
     * @param element    The element to set properties on
     * @param properties The properties to set
     * @param schema      The component schema
     */
    public void setProperties(TestElement element, Map<String, Object> properties, ComponentSchema schema) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Object propValue = entry.getValue();

            propName = mapPropertyName(propName);

            if (propValue == null) {
                log.warn("Skipping null property: {}", propName);
                continue;
            }

            try {
                // Get property definition from schema
                ComponentSchema.PropertyDefinition propDef = schema.getProperty(propName);

                // Route based on schema type or use default handling
                if (propDef != null) {
                    handleBySchemaType(element, propName, propValue, properties, propDef);
                } else {
                    // Unknown property - use default handling
                    handleSimpleProperty(element, propName, propValue);
                }
            } catch (Exception e) {
                log.error("Failed to set property: {} = {}", propName, propValue, e);
            }
        }
    }

    /**
     * Handle property based on schema type definition.
     */
    @SuppressWarnings("unchecked")
    private void handleBySchemaType(TestElement element, String propName, Object propValue,
                                      Map<String, Object> allProperties, ComponentSchema.PropertyDefinition propDef) {
        switch (propDef.getType()) {
            case OBJECT:
                if (propDef.hasNestedProperties()) {
                    handleNestedObjectProperty(element, propName, (Map<String, Object>) propValue, propDef);
                } else {
                    handleSimpleProperty(element, propName, propValue);
                }
                break;

            case ARRAY:
                // Special handling for HTTPsampler.Arguments
                if ("HTTPsampler.Arguments".equals(propName) && propDef.hasItemProperties()) {
                    handleHttpArgumentsProperty(element, propName, propValue);
                } else if ("Arguments.arguments".equals(propName) && propDef.hasItemProperties()) {
                    handleArgumentsProperty(element, propName, propValue);
                } else if ("HeaderManager.headers".equals(propName) && propDef.hasItemProperties()) {
                    handleHeaderManagerProperty(element, propName, propValue);
                } else if ("CookieManager.cookies".equals(propName) && propDef.hasItemProperties()) {
                    handleCookieManagerProperty(element, propName, propValue);
                } else {
                    handleGenericCollectionProperty(element, propName, propValue);
                }
                break;

            case ARRAY_2D:
                handleNestedArrayProperty(element, propName, propValue);
                break;

            default:
                handleSimpleProperty(element, propName, propValue);
                break;
        }
    }

    /**
     * Handle nested object property (e.g., ThreadGroup.main_controller).
     */
    @SuppressWarnings("unchecked")
    private void handleNestedObjectProperty(TestElement element, String propName,
                                             Map<String, Object> propValue,
                                             ComponentSchema.PropertyDefinition propDef) {
        if (!(propValue instanceof Map)) {
            log.warn("Expected Map for nested object property: {}, got: {}", propName, propValue.getClass());
            return;
        }

        Map<String, Object> nestedProps = (Map<String, Object>) propValue;
        String className = propDef.getClassName();

        if (className == null || className.isEmpty()) {
            log.warn("Property {} has nested properties but no class specified", propName);
            return;
        }

        try {
            // Create nested object using reflection
            Class<?> nestedClass = Class.forName(className);
            TestElement nestedElement = (TestElement) nestedClass.getDeclaredConstructor().newInstance();

            nestedElement.setProperty(TestElement.TEST_CLASS, className);
            String guiClassName = deriveGuiClassName(className);
            if (guiClassName != null) {
                nestedElement.setProperty(TestElement.GUI_CLASS, guiClassName);
            }

            // Recursively set nested properties
            for (Map.Entry<String, Object> entry : nestedProps.entrySet()) {
                String nestedPropName = entry.getKey();
                Object nestedPropValue = entry.getValue();

                if (nestedPropValue == null) {
                    continue;
                }

                // For nested objects, find the property definition
                ComponentSchema.PropertyDefinition nestedDef = findNestedPropertyDef(propDef, nestedPropName);
                if (nestedDef != null) {
                    handleBySchemaType(nestedElement, nestedPropName, nestedPropValue, nestedProps, nestedDef);
                } else {
                    handleSimpleProperty(nestedElement, nestedPropName, nestedPropValue);
                }
            }

            // Set nested object on parent
            setNestedObjectOnParent(element, propName, nestedElement, className);

            log.info("Successfully set nested object property: {}", propName);

        } catch (Exception e) {
            log.warn("Failed to create/set nested object for property: {}", propName, e);
        }
    }

    /**
     * Handle HTTPsampler.Arguments property with itemProperties schema.
     * Only supports array format.
     */
    @SuppressWarnings("unchecked")
    private void handleHttpArgumentsProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        // Only support array format
        if (propValue instanceof List || propValue.getClass().isArray()) {
            handleHttpArgumentsArray(convertToList(propValue), args);
        } else {
            log.warn("HTTPsampler.Arguments must be an array, got: {}", propValue.getClass());
            return;
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set HTTPsampler.Arguments with {} arguments", args.getArguments().size());
    }

    /**
     * Handle Arguments.arguments property with itemProperties schema.
     */
    @SuppressWarnings("unchecked")
    private void handleArgumentsProperty(TestElement element, String propName, Object propValue) {
        for (Object argItem : convertToList(propValue)) {
            if (!(argItem instanceof Map)) {
                continue;
            }

            Map<String, Object> argProps = (Map<String, Object>) argItem;
            String argName = getStringValue(argProps, "Argument.name");
            if (argName == null) {
                continue;
            }

            String argValue = getStringValue(argProps, "Argument.value", "");
            String argDesc = getStringValue(argProps, "Argument.desc");
            String metadata = getStringValue(argProps, "Argument.metadata", "=");

            org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(argName, argValue, metadata);
            if (argDesc != null && !argDesc.isEmpty()) {
                argument.setDescription(argDesc);
            }

            ((org.apache.jmeter.config.Arguments) element).addArgument(argument);
        }
        log.info("Added {} arguments to {}", convertToList(propValue).size(), propName);
    }

    /**
     * Handle HTTPsampler.Arguments in array format: [{"Argument.name": "name", "Argument.value": "zhangsan"}]
     */
    @SuppressWarnings("unchecked")
    private void handleHttpArgumentsArray(List<Object> argList, org.apache.jmeter.config.Arguments args) {
        try {
            Class<?> httpArgClass = Class.forName("org.apache.jmeter.protocol.http.util.HTTPArgument");

            for (Object argItem : argList) {
                if (!(argItem instanceof Map)) {
                    log.warn("Array item must be a map, got: {}", argItem.getClass());
                    continue;
                }

                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String argName = getStringValue(argProps, "Argument.name");
                String argValue = getStringValue(argProps, "Argument.value");
                Boolean useEquals = getBooleanValue(argProps, "HTTPArgument.use_equals", true);
                Boolean alwaysEncode = getBooleanValue(argProps, "HTTPArgument.always_encode", false);
                String metadata = getStringValue(argProps, "Argument.metadata", "=");

                if (argName == null) {
                    log.warn("Missing required property Argument.name in: {}", argProps);
                    continue;
                }

                Object httpArg = httpArgClass
                        .getConstructor(String.class, String.class, boolean.class)
                        .newInstance(argName, argValue != null ? argValue : "", useEquals);

                if (alwaysEncode != null) {
                    java.lang.reflect.Method setAlwaysEncoded = httpArg.getClass().getMethod("setAlwaysEncoded", boolean.class);
                    setAlwaysEncoded.invoke(httpArg, alwaysEncode);
                }
                if (metadata != null) {
                    java.lang.reflect.Method setMetaData = httpArg.getClass().getMethod("setMetaData", String.class);
                    setMetaData.invoke(httpArg, metadata);
                }

                args.addArgument((org.apache.jmeter.config.Argument) httpArg);
            }
        } catch (Exception e) {
            log.warn("Failed to create HTTPArgument from array", e);
        }
    }

    /**
     * Handle HeaderManager.headers property with itemProperties schema.
     * Creates Header objects and adds them to the HeaderManager via reflection.
     */
    @SuppressWarnings("unchecked")
    private void handleHeaderManagerProperty(TestElement element, String propName, Object propValue) {
        List<Object> items = convertToList(propValue);

        try {
            Class<?> headerClass = Class.forName("org.apache.jmeter.protocol.http.control.Header");

            for (Object item : items) {
                if (!(item instanceof Map)) {
                    log.warn("Header item must be a map, got: {}", item.getClass());
                    continue;
                }

                Map<String, Object> headerProps = (Map<String, Object>) item;
                String headerName = getStringValue(headerProps, "Header.name");
                String headerValue = getStringValue(headerProps, "Header.value");

                if (headerName == null) {
                    log.warn("Missing required property Header.name in: {}", headerProps);
                    continue;
                }

                Object header = headerClass
                        .getConstructor(String.class, String.class)
                        .newInstance(headerName, headerValue != null ? headerValue : "");

                element.getClass().getMethod("add", headerClass).invoke(element, header);
                log.info("Added header: {} = {}", headerName, headerValue);
            }
        } catch (Exception e) {
            log.warn("Failed to create Header objects for {}", propName, e);
        }
    }

    /**
     * Handle CookieManager.cookies property with itemProperties schema.
     * Creates Cookie objects and adds them to the CookieManager via reflection.
     */
    @SuppressWarnings("unchecked")
    private void handleCookieManagerProperty(TestElement element, String propName, Object propValue) {
        List<Object> items = convertToList(propValue);

        try {
            Class<?> cookieClass = Class.forName("org.apache.jmeter.protocol.http.control.Cookie");

            for (Object item : items) {
                if (!(item instanceof Map)) {
                    log.warn("Cookie item must be a map, got: {}", item.getClass());
                    continue;
                }

                Map<String, Object> cookieProps = (Map<String, Object>) item;
                String cookieName = getStringValue(cookieProps, "Cookie.name");
                String cookieValue = getStringValue(cookieProps, "Cookie.value");
                String domain = getStringValue(cookieProps, "Cookie.domain");
                String path = getStringValue(cookieProps, "Cookie.path", "/");
                Boolean secure = getBooleanValue(cookieProps, "Cookie.secure", false);
                Long expires = getLongValue(cookieProps, "Cookie.expires", 0L);
                Boolean pathSpecified = getBooleanValue(cookieProps, "Cookie.path_specified", true);
                Boolean domainSpecified = getBooleanValue(cookieProps, "Cookie.domain_specified", true);

                if (cookieName == null) {
                    log.warn("Missing required property Cookie.name in: {}", cookieProps);
                    continue;
                }

                Object cookie = cookieClass
                        .getConstructor(String.class, String.class, String.class, String.class,
                                boolean.class, long.class, boolean.class, boolean.class)
                        .newInstance(cookieName,
                                cookieValue != null ? cookieValue : "",
                                domain,
                                path,
                                secure,
                                expires,
                                pathSpecified,
                                domainSpecified);

                element.getClass().getMethod("add", cookieClass).invoke(element, cookie);
                log.info("Added cookie: {} (domain: {}, path: {})", cookieName, domain, path);
            }
        } catch (Exception e) {
            log.warn("Failed to create Cookie objects for {}", propName, e);
        }
    }

    /**
     * Handle generic collection properties (e.g., Assertion.test_strings).
     */
    @SuppressWarnings("unchecked")
    private void handleGenericCollectionProperty(TestElement element, String propName, Object propValue) {
        try {
            Class<?> collectionPropClass = Class.forName("org.apache.jmeter.testelement.property.CollectionProperty");
            Class<?> stringPropClass = Class.forName("org.apache.jmeter.testelement.property.StringProperty");
            Class<?> jMeterPropClass = Class.forName("org.apache.jmeter.testelement.property.JMeterProperty");

            List<Object> items = convertToList(propValue);

            Object collectionProp = collectionPropClass
                    .getConstructor(String.class, java.util.Collection.class)
                    .newInstance(propName, new ArrayList<>());

            java.lang.reflect.Method addPropertyMethod = collectionPropClass.getMethod("addProperty", jMeterPropClass);

            for (int i = 0; i < items.size(); i++) {
                String uniqueName = String.valueOf(System.currentTimeMillis() + i);
                Object stringProp = stringPropClass
                        .getConstructor(String.class, String.class)
                        .newInstance(uniqueName, items.get(i).toString());
                addPropertyMethod.invoke(collectionProp, stringProp);
            }

            element.setProperty((org.apache.jmeter.testelement.property.JMeterProperty) collectionProp);
            log.info("Set collection property: {} with {} items", propName, items.size());

        } catch (Exception e) {
            log.warn("Failed to create CollectionProperty for {}, trying fallback", propName, e);
            String joinedValue = convertToList(propValue).stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            element.setProperty(propName, joinedValue);
        }
    }

    /**
     * Handle nested array properties (Array of Array) for UserParameters.thread_values.
     */
    @SuppressWarnings("unchecked")
    private void handleNestedArrayProperty(TestElement element, String propName, Object propValue) {
        try {
            Class<?> collectionPropClass = Class.forName("org.apache.jmeter.testelement.property.CollectionProperty");
            Class<?> stringPropClass = Class.forName("org.apache.jmeter.testelement.property.StringProperty");
            Class<?> jMeterPropClass = Class.forName("org.apache.jmeter.testelement.property.JMeterProperty");

            List<Object> outerList = convertToList(propValue);

            Object outerCollectionProp = collectionPropClass
                    .getConstructor(String.class, java.util.Collection.class)
                    .newInstance(propName, new ArrayList<>());

            java.lang.reflect.Method addPropertyMethod = collectionPropClass.getMethod("addProperty", jMeterPropClass);

            for (int i = 0; i < outerList.size(); i++) {
                Object innerItem = outerList.get(i);
                if (!(innerItem instanceof List)) {
                    log.warn("Nested array item {} is not a list, skipping", i);
                    continue;
                }

                List<Object> innerList = (List<Object>) innerItem;
                Object innerCollectionProp = collectionPropClass
                        .getConstructor(String.class, java.util.Collection.class)
                        .newInstance(String.valueOf(System.currentTimeMillis() + i), new ArrayList<>());

                for (int j = 0; j < innerList.size(); j++) {
                    String uniqueName = String.valueOf(System.currentTimeMillis() + i + j);
                    Object stringProp = stringPropClass
                            .getConstructor(String.class, String.class)
                            .newInstance(uniqueName, innerList.get(j).toString());
                    addPropertyMethod.invoke(innerCollectionProp, stringProp);
                }

                addPropertyMethod.invoke(outerCollectionProp, innerCollectionProp);
            }

            element.setProperty((org.apache.jmeter.testelement.property.JMeterProperty) outerCollectionProp);
            log.info("Set nested array property: {} with {} inner arrays", propName, outerList.size());

        } catch (Exception e) {
            log.error("Failed to create nested CollectionProperty for {}", propName, e);
        }
    }

    /**
     * Handle simple property (String, Integer, Boolean, Number).
     */
    private void handleSimpleProperty(TestElement element, String propName, Object propValue) {
        try {
            if (propValue instanceof Number) {
                // Use explicit JMeter Property types for precise control
                if (propValue instanceof Integer) {
                    element.setProperty(new org.apache.jmeter.testelement.property.IntegerProperty(propName, ((Integer) propValue).intValue()));
                } else if (propValue instanceof Long) {
                    element.setProperty(new org.apache.jmeter.testelement.property.LongProperty(propName, ((Long) propValue).longValue()));
                } else if (propValue instanceof Float) {
                    element.setProperty(new org.apache.jmeter.testelement.property.FloatProperty(propName, ((Float) propValue).floatValue()));
                } else if (propValue instanceof Double) {
                    element.setProperty(new org.apache.jmeter.testelement.property.DoubleProperty(propName, ((Double) propValue).doubleValue()));
                } else {
                    // Fallback for other numeric types (e.g., BigDecimal)
                    element.setProperty(new org.apache.jmeter.testelement.property.DoubleProperty(propName, ((Number) propValue).doubleValue()));
                }
            } else if (propValue instanceof Boolean) {
                element.setProperty(propName, (Boolean) propValue);
            } else {
                element.setProperty(propName, propValue.toString());
            }
            log.info("Set property: {} = {}", propName, propValue);
        } catch (Exception e) {
            log.error("Failed to set simple property: {} = {}", propName, propValue, e);
        }
    }

    /**
     * Convert an array or collection value to a List.
     */
    private List<Object> convertToList(Object value) {
        List<Object> result = new ArrayList<>();

        if (value == null) {
            return result;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    result.add(item);
                }
            }
        } else if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (Object item : array) {
                if (item != null) {
                    result.add(item);
                }
            }
        }

        return result;
    }

    /**
     * Find a nested property definition by name.
     */
    private ComponentSchema.PropertyDefinition findNestedPropertyDef(
            ComponentSchema.PropertyDefinition parentDef, String propName) {
        if (parentDef.getNestedProperties() == null) {
            return null;
        }
        return parentDef.getNestedProperties().stream()
                .filter(p -> propName.equals(p.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Derive GUI class name from model class name.
     */
    private String deriveGuiClassName(String className) {
        if (className.contains("LoopController")) {
            return "org.apache.jmeter.control.gui.LoopControlPanel";
        } else if (className.contains("ThreadGroup")) {
            return "org.apache.jmeter.threads.gui.ThreadGroupGui";
        } else if (className.contains("GenericController")) {
            return "org.apache.jmeter.control.gui.GenericControllerGui";
        } else if (className.contains("DurationAssertion")) {
            return "org.apache.jmeter.assertions.gui.DurationAssertionGui";
        } else if (className.contains("ResponseAssertion")) {
            return "org.apache.jmeter.assertions.gui.ResponseAssertionGui";
        } else if (className.contains("JSONPathAssertion")) {
            return "org.apache.jmeter.assertions.gui.JSONPathAssertionGui";
        } else if (className.contains("ConstantTimer")) {
            return "org.apache.jmeter.timers.gui.ConstantTimerGui";
        }
        return null;
    }

    /**
     * Set a nested object on its parent element.
     */
    private void setNestedObjectOnParent(TestElement parent, String propName,
                                         TestElement nested, String nestedClassName) {
        try {
            // Special handling for ThreadGroup.main_controller -> setSamplerController
            if (parent instanceof org.apache.jmeter.threads.ThreadGroup
                    && nestedClassName.contains("LoopController")) {
                ((org.apache.jmeter.threads.ThreadGroup) parent).setSamplerController(
                    (org.apache.jmeter.control.LoopController) nested);
                log.info("Set ThreadGroup.samplerController (main_controller)");
                return;
            }

            // Default: use TestElementProperty
            parent.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, nested));
            log.info("Set nested property {} using TestElementProperty", propName);

        } catch (Exception e) {
            log.warn("Failed to set nested object {} on parent", propName, e);
        }
    }

    /**
     * Check if the request is in raw body mode.
     */
    private boolean isRawBodyMode(TestElement element, Map<String, Object> allProperties) {
        String postBodyRaw = element.getPropertyAsString("HTTPSampler.postBodyRaw", null);
        if ("true".equals(postBodyRaw)) {
            return true;
        }

        if (allProperties != null) {
            for (Map.Entry<String, Object> prop : allProperties.entrySet()) {
                if ("HTTPSampler.postBodyRaw".equals(prop.getKey())) {
                    Object propVal = prop.getValue();
                    if (propVal instanceof Boolean && (Boolean) propVal) {
                        return true;
                    }
                    if (propVal instanceof String && "true".equalsIgnoreCase((String) propVal)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Map property names for JMeter historical naming quirks.
     */
    private String mapPropertyName(String propName) {
        if (propName == null) {
            return null;
        }
        if ("Assertion.test_strings".equals(propName)) {
            return "Asserion.test_strings";
        }
        return propName;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Boolean getBooleanValue(Map<String, Object> map, String key, Boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private Long getLongValue(Map<String, Object> map, String key, Long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
