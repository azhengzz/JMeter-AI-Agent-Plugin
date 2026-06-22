package org.gitee.jmeter.ai.agent.tools.jmeter.property;

import org.apache.jmeter.testelement.TestElement;
import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
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
                ComponentSchema.PropertyDefinition propDef = schema != null ? schema.getProperty(propName) : null;

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
                } else if ("HTTPsampler.Files".equals(propName) && propDef.hasItemProperties()) {
                    handleHttpFileArgsProperty(element, propName, propValue);
                } else if ("Arguments.arguments".equals(propName) && propDef.hasItemProperties()) {
                    handleArgumentsProperty(element, propName, propValue);
                } else if ("SystemSampler.arguments".equals(propName) && propDef.hasItemProperties()) {
                    handleSystemSamplerArgumentsProperty(element, propName, propValue);
                } else if ("SystemSampler.environment".equals(propName) && propDef.hasItemProperties()) {
                    handleSystemSamplerEnvironmentProperty(element, propName, propValue);
                } else if ("HeaderManager.headers".equals(propName) && propDef.hasItemProperties()) {
                    handleHeaderManagerProperty(element, propName, propValue);
                } else if ("CookieManager.cookies".equals(propName) && propDef.hasItemProperties()) {
                    handleCookieManagerProperty(element, propName, propValue);
                } else if ("ultimatethreadgroupdata".equals(propName)) {
                    handleUltimateThreadGroupData(element, propName, propValue);
                } else if ("ParameterTestFragmentController.arguments".equals(propName) && propDef.hasItemProperties()) {
                    handleParameterTestFragmentArgumentsProperty(element, propName, propValue);
                } else if ("ParameterTestFragmentController.ReturnValueArguments".equals(propName) && propDef.hasItemProperties()) {
                    handleParameterTestFragmentReturnValueProperty(element, propName, propValue);
                } else if ("ParameterIncludeController.Arguments".equals(propName) && propDef.hasItemProperties()) {
                    handleParameterTestFragmentArgumentsProperty(element, propName, propValue);
                } else if ("ParameterIncludeController.ReturnValueArguments".equals(propName) && propDef.hasItemProperties()) {
                    handleParameterIncludeControllerReturnValueProperty(element, propName, propValue);
                } else if ("HTTPUDConfigElement.http_header_parameters_name".equals(propName) && propDef.hasItemProperties()) {
                    handleHttpudHeaderParametersProperty(element, propName, propValue);
                } else if ("HTTPUDArgumentsGui.HTTPUDArguments".equals(propName) && propDef.hasItemProperties()) {
                    handleHttpudArgumentsProperty(element, propName, propValue);
                } else if (propDef.hasItemClass() && propDef.hasItemProperties()) {
                    handleTestBeanTableProperty(element, propName, propValue, propDef);
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
     * Handle nested object property (e.g., ThreadGroup.main_controller, SampleSaveConfiguration).
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

        // Special handling for SampleSaveConfiguration (not a TestElement)
        if (className.contains("SampleSaveConfiguration")) {
            handleSampleSaveConfiguration(element, propName, nestedProps);
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
     * Handle SampleSaveConfiguration property.
     * SampleSaveConfiguration is not a TestElement, so it needs special handling.
     */
    @SuppressWarnings("unchecked")
    private void handleSampleSaveConfiguration(TestElement element, String propName,
                                               Map<String, Object> configProps) {
        try {
            Class<?> saveConfigClass = Class.forName("org.apache.jmeter.samplers.SampleSaveConfiguration");
            Object saveConfig = saveConfigClass.getDeclaredConstructor().newInstance();

            // Map of property names to actual setter method names for non-standard naming
            java.util.Map<String, String> setterNameMap = new java.util.HashMap<>();
            setterNameMap.put("xml", "setAsXml");
            setterNameMap.put("saveAssertionResultsFailureMessage", "setAssertionResultsFailureMessage");
            // Note: responseDataOnError and assertionsResultsToSave have no setters (read-only or final)

            // Set boolean properties using reflection
            for (Map.Entry<String, Object> entry : configProps.entrySet()) {
                String configPropName = entry.getKey();
                Object configPropValue = entry.getValue();

                if (configPropValue == null) {
                    continue;
                }

                // Skip read-only properties
                if ("responseDataOnError".equals(configPropName) || "assertionsResultsToSave".equals(configPropName)) {
                    log.debug("Skipping read-only property: {}", configPropName);
                    continue;
                }

                // Convert to Boolean if needed
                Boolean boolValue = null;
                if (configPropValue instanceof Boolean) {
                    boolValue = (Boolean) configPropValue;
                } else if (configPropValue instanceof String) {
                    boolValue = Boolean.parseBoolean((String) configPropValue);
                }

                if (boolValue != null) {
                    try {
                        // Use mapped setter name if available, otherwise build standard name
                        String setterName = setterNameMap.get(configPropName);
                        if (setterName == null) {
                            setterName = "set" + configPropName.substring(0, 1).toUpperCase()
                                    + configPropName.substring(1);
                        }
                        java.lang.reflect.Method setter = saveConfigClass.getMethod(setterName, boolean.class);
                        setter.invoke(saveConfig, boolValue);
                        log.info("Set SampleSaveConfiguration.{} = {}", configPropName, boolValue);
                    } catch (NoSuchMethodException e) {
                        log.warn("No setter found for SampleSaveConfiguration property: {}", configPropName);
                    }
                }
            }

            // Use ObjectProperty to wrap SampleSaveConfiguration (not TestElementProperty)
            element.setProperty(new org.apache.jmeter.testelement.property.ObjectProperty(propName, saveConfig));
            log.info("Successfully set SampleSaveConfiguration with {} properties", configProps.size());

        } catch (Exception e) {
            log.warn("Failed to create/set SampleSaveConfiguration", e);
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
     * Handle HTTPsampler.Files property for file uploads.
     * Creates HTTPFileArgs containing HTTPFileArg objects via reflection.
     */
    @SuppressWarnings("unchecked")
    private void handleHttpFileArgsProperty(TestElement element, String propName, Object propValue) {
        try {
            Class<?> httpFileArgsClass = Class.forName("org.apache.jmeter.protocol.http.util.HTTPFileArgs");
            Class<?> httpFileArgClass = Class.forName("org.apache.jmeter.protocol.http.util.HTTPFileArg");

            Object httpFileArgs = httpFileArgsClass.getDeclaredConstructor().newInstance();

            List<Object> items = convertToList(propValue);
            for (Object item : items) {
                if (!(item instanceof Map)) {
                    log.warn("HTTPFileArg item must be a map, got: {}", item.getClass());
                    continue;
                }

                Map<String, Object> fileProps = (Map<String, Object>) item;
                String path = getStringValue(fileProps, "File.path");
                String paramname = getStringValue(fileProps, "File.paramname", "");
                String mimetype = getStringValue(fileProps, "File.mimetype", "application/octet-stream");

                if (path == null) {
                    log.warn("Missing required property File.path in: {}", fileProps);
                    continue;
                }

                Object httpFileArg = httpFileArgClass
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance(path, paramname, mimetype);

                httpFileArgsClass.getMethod("addHTTPFileArg", httpFileArgClass)
                        .invoke(httpFileArgs, httpFileArg);
            }

            element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, (TestElement) httpFileArgs));
            log.info("Set HTTPsampler.Files with {} file(s)", items.size());

        } catch (Exception e) {
            log.warn("Failed to create HTTPFileArgs for property: {}", propName, e);
        }
    }

    /**
     * Handle Arguments.arguments property with itemProperties schema.
     * Supports two storage patterns:
     * 1. Element IS Arguments (e.g., UserDefinedVariables) - CollectionProperty on the element itself
     * 2. Element HAS Arguments (e.g., BackendListener) - TestElementProperty wrapping an Arguments object
     */
    @SuppressWarnings("unchecked")
    private void handleArgumentsProperty(TestElement element, String propName, Object propValue) {
        List<Object> items = convertToList(propValue);
        List<org.apache.jmeter.config.Argument> newArgs = new ArrayList<>();

        for (Object argItem : items) {
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
            newArgs.add(argument);
        }

        if (newArgs.isEmpty()) {
            log.warn("No valid arguments provided, skipping Arguments.arguments update");
            return;
        }

        // Build first, then replace to prevent data loss
        org.apache.jmeter.config.Arguments targetArgs = resolveArgumentsTarget(element, propName);
        if (targetArgs == null) {
            log.error("Cannot find Arguments object on element: {}", element.getClass().getSimpleName());
            return;
        }

        // Clear existing arguments
        org.apache.jmeter.testelement.property.JMeterProperty existing = targetArgs.getProperty("Arguments.arguments");
        if (existing instanceof org.apache.jmeter.testelement.property.CollectionProperty) {
            ((org.apache.jmeter.testelement.property.CollectionProperty) existing).clear();
        }

        for (org.apache.jmeter.config.Argument arg : newArgs) {
            targetArgs.addArgument(arg);
        }

        log.info("Set Arguments.arguments with {} arguments on {}", newArgs.size(), element.getClass().getSimpleName());
    }

    /**
     * Resolve the Arguments object to modify.
     * - If element is Arguments (e.g., UserDefinedVariables), return element directly.
     * - If element has Arguments as a TestElementProperty (e.g., BackendListener with "arguments" key),
     *   return the nested Arguments object.
     */
    private org.apache.jmeter.config.Arguments resolveArgumentsTarget(TestElement element, String propName) {
        if (element instanceof org.apache.jmeter.config.Arguments) {
            return (org.apache.jmeter.config.Arguments) element;
        }

        // Search for a TestElementProperty wrapping Arguments
        org.apache.jmeter.testelement.property.PropertyIterator iter = element.propertyIterator();
        while (iter.hasNext()) {
            org.apache.jmeter.testelement.property.JMeterProperty prop = iter.next();
            if (prop instanceof org.apache.jmeter.testelement.property.TestElementProperty) {
                TestElement nested = ((org.apache.jmeter.testelement.property.TestElementProperty) prop).getElement();
                if (nested instanceof org.apache.jmeter.config.Arguments) {
                    return (org.apache.jmeter.config.Arguments) nested;
                }
            }
        }
        return null;
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
                    // In postBodyRaw mode, the body data argument has no name
                    argName = "";
                    log.info("Argument.name not provided, defaulting to empty string (postBodyRaw mode)");
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

        // Build new headers first, only clear existing if new items are valid
        List<Object> newHeaders = new ArrayList<>();
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
                newHeaders.add(header);
            }
        } catch (Exception e) {
            log.warn("Failed to create Header objects for {}", propName, e);
        }

        if (newHeaders.isEmpty() && !items.isEmpty()) {
            log.warn("No valid headers created, skipping update to preserve existing data");
            return;
        }

        // Clear existing then add new
        org.apache.jmeter.testelement.property.JMeterProperty existingHeaders = element.getProperty(propName);
        if (existingHeaders instanceof org.apache.jmeter.testelement.property.CollectionProperty) {
            ((org.apache.jmeter.testelement.property.CollectionProperty) existingHeaders).clear();
        }

        try {
            Class<?> headerClass = Class.forName("org.apache.jmeter.protocol.http.control.Header");
            java.lang.reflect.Method addMethod = element.getClass().getMethod("add", headerClass);
            for (Object header : newHeaders) {
                addMethod.invoke(element, header);
            }
            log.info("Set HeaderManager.headers with {} header(s)", newHeaders.size());
        } catch (Exception e) {
            log.warn("Failed to add headers to HeaderManager", e);
        }
    }

    /**
     * Handle CookieManager.cookies property with itemProperties schema.
     * Creates Cookie objects and adds them to the CookieManager via reflection.
     */
    @SuppressWarnings("unchecked")
    private void handleCookieManagerProperty(TestElement element, String propName, Object propValue) {
        List<Object> items = convertToList(propValue);

        // Build new cookies first, only clear existing if new items are valid
        List<Object> newCookies = new ArrayList<>();
        try {
            Class<?> cookieClass = Class.forName("org.apache.jmeter.protocol.http.control.Cookie");

            for (Object item : items) {
                if (!(item instanceof Map)) {
                    log.warn("Cookie item must be a map, got: {}", item.getClass());
                    continue;
                }

                Map<String, Object> cookieProps = (Map<String, Object>) item;
                // Cookie name is stored as TestElement.name in JMeter, not as Cookie.name
                String cookieName = getStringValue(cookieProps, "Cookie.name");
                if (cookieName == null) {
                    cookieName = getStringValue(cookieProps, "TestElement.name");
                }
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
                newCookies.add(cookie);
            }
        } catch (Exception e) {
            log.warn("Failed to create Cookie objects for {}", propName, e);
        }

        if (newCookies.isEmpty() && !items.isEmpty()) {
            log.warn("No valid cookies created, skipping update to preserve existing data");
            return;
        }

        // Clear existing then add new
        org.apache.jmeter.testelement.property.JMeterProperty existingCookies = element.getProperty(propName);
        if (existingCookies instanceof org.apache.jmeter.testelement.property.CollectionProperty) {
            ((org.apache.jmeter.testelement.property.CollectionProperty) existingCookies).clear();
        }

        try {
            Class<?> cookieClass = Class.forName("org.apache.jmeter.protocol.http.control.Cookie");
            java.lang.reflect.Method addMethod = element.getClass().getMethod("add", cookieClass);
            for (Object cookie : newCookies) {
                addMethod.invoke(element, cookie);
            }
            log.info("Set CookieManager.cookies with {} cookie(s)", newCookies.size());
        } catch (Exception e) {
            log.warn("Failed to add cookies to CookieManager", e);
        }
    }

    /**
     * Handle TestBean table properties with itemClass (e.g., ValueAssertion.valuesCheckTable, VariableAssertion.variablesCheckTable).
     * Creates item instances via reflection, sets their properties from itemProperties schema, wraps in TestElementProperty.
     */
    @SuppressWarnings("unchecked")
    private void handleTestBeanTableProperty(TestElement element, String propName, Object propValue,
                                             ComponentSchema.PropertyDefinition propDef) {
        String itemClassName = propDef.getItemClass();
        if (itemClassName == null || itemClassName.isEmpty()) {
            log.warn("itemClass not defined for property: {}, falling back to generic handler", propName);
            handleGenericCollectionProperty(element, propName, propValue);
            return;
        }

        try {
            Class<?> itemClass = Class.forName(itemClassName);
            List<Object> items = convertToList(propValue);
            List<org.apache.jmeter.testelement.property.TestElementProperty> teProps = new ArrayList<>();

            for (Object item : items) {
                if (!(item instanceof Map)) {
                    log.warn("TestBean table item must be a map, got: {}", item.getClass());
                    continue;
                }

                Map<String, Object> itemProps = (Map<String, Object>) item;
                TestElement rowElement = (TestElement) itemClass.getDeclaredConstructor().newInstance();
                rowElement.setProperty(TestElement.TEST_CLASS, itemClassName);

                for (ComponentSchema.PropertyDefinition itemPropDef : propDef.getItemProperties()) {
                    String itemPropName = itemPropDef.getName();
                    Object itemPropValue = itemProps.get(itemPropName);
                    if (itemPropValue == null) {
                        continue;
                    }
                    handleSimpleProperty(rowElement, itemPropName, itemPropValue);
                }

                teProps.add(new org.apache.jmeter.testelement.property.TestElementProperty(
                        String.valueOf(System.currentTimeMillis()), rowElement));
            }

            org.apache.jmeter.testelement.property.CollectionProperty collectionProp =
                    new org.apache.jmeter.testelement.property.CollectionProperty(propName, new ArrayList<>());
            for (org.apache.jmeter.testelement.property.TestElementProperty tep : teProps) {
                collectionProp.addProperty(tep);
            }

            element.setProperty(collectionProp);
            log.info("Set TestBean table property: {} with {} rows (itemClass: {})", propName, teProps.size(), itemClassName);

        } catch (Exception e) {
            log.warn("Failed to handle TestBean table property: {}, falling back to generic handler", propName, e);
            handleGenericCollectionProperty(element, propName, propValue);
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
     * Handle ultimatethreadgroupdata: convert list of Maps to 2D CollectionProperty.
     * Each row is [Start Threads Count, Initial Delay, Startup Time, Hold Load For, Shutdown Time].
     */
    @SuppressWarnings("unchecked")
    private void handleUltimateThreadGroupData(TestElement element, String propName, Object propValue) {
        try {
            Class<?> collectionPropClass = Class.forName("org.apache.jmeter.testelement.property.CollectionProperty");
            Class<?> stringPropClass = Class.forName("org.apache.jmeter.testelement.property.StringProperty");
            Class<?> jMeterPropClass = Class.forName("org.apache.jmeter.testelement.property.JMeterProperty");

            List<Object> rows = convertToList(propValue);

            Object outerCollectionProp = collectionPropClass
                    .getConstructor(String.class, java.util.Collection.class)
                    .newInstance(propName, new ArrayList<>());

            java.lang.reflect.Method addPropertyMethod = collectionPropClass.getMethod("addProperty", jMeterPropClass);

            String[] fieldOrder = {"Start Threads Count", "Initial Delay", "Startup Time", "Hold Load For", "Shutdown Time"};

            for (int i = 0; i < rows.size(); i++) {
                Object row = rows.get(i);
                List<String> values;

                if (row instanceof Map) {
                    Map<String, Object> rowMap = (Map<String, Object>) row;
                    values = new ArrayList<>();
                    for (String field : fieldOrder) {
                        Object val = rowMap.get(field);
                        values.add(val != null ? val.toString() : "0");
                    }
                } else if (row instanceof List) {
                    List<Object> listRow = (List<Object>) row;
                    values = new ArrayList<>();
                    for (Object item : listRow) {
                        values.add(item != null ? item.toString() : "0");
                    }
                } else {
                    log.warn("Row {} is neither Map nor List, skipping", i);
                    continue;
                }

                Object innerCollectionProp = collectionPropClass
                        .getConstructor(String.class, java.util.Collection.class)
                        .newInstance(String.valueOf(System.currentTimeMillis() + i), new ArrayList<>());

                for (int j = 0; j < values.size(); j++) {
                    String uniqueName = String.valueOf(System.currentTimeMillis() + i + j);
                    Object stringProp = stringPropClass
                            .getConstructor(String.class, String.class)
                            .newInstance(uniqueName, values.get(j));
                    addPropertyMethod.invoke(innerCollectionProp, stringProp);
                }

                addPropertyMethod.invoke(outerCollectionProp, innerCollectionProp);
            }

            element.setProperty((org.apache.jmeter.testelement.property.JMeterProperty) outerCollectionProp);
            log.info("Set UltimateThreadGroup data: {} with {} rows", propName, rows.size());

        } catch (Exception e) {
            log.error("Failed to create CollectionProperty for {}", propName, e);
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

    /**
     * Handle ParameterTestFragmentController.arguments property.
     * Creates ParameterIncludeControllerArgument objects with name, value, desc, required, notNull.
     */
    @SuppressWarnings("unchecked")
    private void handleParameterTestFragmentArgumentsProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        try {
            Class<?> argClass = Class.forName("com.gitee.qa.jmeter.control.util.ParameterIncludeControllerArgument");

            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) {
                    continue;
                }

                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) {
                    continue;
                }

                String value = getStringValue(argProps, "Argument.value", "");
                String desc = getStringValue(argProps, "Argument.desc", "");
                Boolean required = getBooleanValue(argProps, "ParameterIncludeControllerArgument.required", false);
                Boolean notNull = getBooleanValue(argProps, "ParameterIncludeControllerArgument.notNull", false);

                Object arg = argClass
                        .getConstructor(String.class, String.class, String.class, boolean.class, boolean.class)
                        .newInstance(name, value, desc, notNull, required);

                args.addArgument((org.apache.jmeter.config.Argument) arg);
            }
        } catch (Exception e) {
            log.warn("Failed to create ParameterIncludeControllerArgument, falling back to standard Argument", e);
            // Fallback to standard Argument
            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) continue;
                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) continue;
                String value = getStringValue(argProps, "Argument.value", "");
                String desc = getStringValue(argProps, "Argument.desc", "");
                org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(name, value, "=", desc);
                args.addArgument(argument);
            }
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set {} with {} arguments", propName, args.getArguments().size());
    }

    /**
     * Handle ParameterTestFragmentController.ReturnValueArguments property.
     * Creates ParameterTestFragmentReturnValueArgument objects with name and desc.
     */
    @SuppressWarnings("unchecked")
    private void handleParameterTestFragmentReturnValueProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        try {
            Class<?> argClass = Class.forName("com.gitee.qa.jmeter.control.util.ParameterTestFragmentReturnValueArgument");

            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) {
                    continue;
                }

                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) {
                    continue;
                }

                String desc = getStringValue(argProps, "Argument.desc", "");

                Object arg = argClass
                        .getConstructor(String.class, String.class)
                        .newInstance(name, desc);

                args.addArgument((org.apache.jmeter.config.Argument) arg);
            }
        } catch (Exception e) {
            log.warn("Failed to create ParameterTestFragmentReturnValueArgument, falling back to standard Argument", e);
            // Fallback to standard Argument
            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) continue;
                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) continue;
                String desc = getStringValue(argProps, "Argument.desc", "");
                org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(name, "", "=", desc);
                args.addArgument(argument);
            }
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set {} with {} arguments", propName, args.getArguments().size());
    }

    /**
     * Handle ParameterIncludeController.ReturnValueArguments property.
     * Creates ParameterIncludeControllerReturnValueArgument objects with name, value, desc.
     * Wraps them in an Arguments TestElement via TestElementProperty so the GUI's
     * configure() cast (Arguments) getObjectValue() succeeds.
     */
    @SuppressWarnings("unchecked")
    private void handleParameterIncludeControllerReturnValueProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        try {
            Class<?> argClass = Class.forName("com.gitee.qa.jmeter.control.util.ParameterIncludeControllerReturnValueArgument");

            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) {
                    continue;
                }

                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) {
                    continue;
                }

                String value = getStringValue(argProps, "Argument.value", "");
                String desc = getStringValue(argProps, "Argument.desc", "");

                Object arg = argClass
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance(name, value, desc);

                args.addArgument((org.apache.jmeter.config.Argument) arg);
            }
        } catch (Exception e) {
            log.warn("Failed to create ParameterIncludeControllerReturnValueArgument, falling back to standard Argument", e);
            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) continue;
                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) continue;
                String value = getStringValue(argProps, "Argument.value", "");
                String desc = getStringValue(argProps, "Argument.desc", "");
                org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(name, value, "=", desc);
                args.addArgument(argument);
            }
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set {} with {} arguments", propName, args.getArguments().size());
    }

    /**
     * Handle HTTPUDConfigElement.http_header_parameters_name property.
     * Stores Argument objects (name/value) inside an Arguments wrapper as TestElementProperty.
     */
    @SuppressWarnings("unchecked")
    private void handleHttpudHeaderParametersProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        for (Object argItem : convertToList(propValue)) {
            if (!(argItem instanceof Map)) {
                continue;
            }

            Map<String, Object> argProps = (Map<String, Object>) argItem;
            String name = getStringValue(argProps, "Argument.name");
            if (name == null) {
                log.warn("Missing Argument.name in header: {}", argProps);
                continue;
            }

            String value = getStringValue(argProps, "Argument.value", "");
            org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(name, value);
            args.addArgument(argument);
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set {} with {} header(s)", propName, args.getArguments().size());
    }

    /**
     * Handle HTTPUDArgumentsGui.HTTPUDArguments property.
     * Stores HTTPUDArgument objects (name/value/desc/required) inside an Arguments wrapper as TestElementProperty.
     */
    @SuppressWarnings("unchecked")
    private void handleHttpudArgumentsProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        try {
            Class<?> argClass = Class.forName("com.gitee.qa.jmeter.protocol.httpud.util.HTTPUDArgument");

            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) {
                    continue;
                }

                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) {
                    continue;
                }

                String value = getStringValue(argProps, "Argument.value", "");
                String desc = getStringValue(argProps, "Argument.desc", "");
                Boolean required = getBooleanValue(argProps, "HTTPUDArgument.required", false);

                Object arg = argClass
                        .getConstructor(String.class, String.class, String.class, boolean.class)
                        .newInstance(name, value, desc, required);

                args.addArgument((org.apache.jmeter.config.Argument) arg);
            }
        } catch (Exception e) {
            log.warn("Failed to create HTTPUDArgument, falling back to standard Argument", e);
            for (Object argItem : convertToList(propValue)) {
                if (!(argItem instanceof Map)) continue;
                Map<String, Object> argProps = (Map<String, Object>) argItem;
                String name = getStringValue(argProps, "Argument.name");
                if (name == null) continue;
                String value = getStringValue(argProps, "Argument.value", "");
                String desc = getStringValue(argProps, "Argument.desc", "");
                org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(name, value, "=", desc);
                args.addArgument(argument);
            }
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set {} with {} argument(s)", propName, args.getArguments().size());
    }

    /**
     * Handle SystemSampler.arguments property with itemProperties schema.
     * Supports both array of strings and array of objects with Argument.value.
     */
    @SuppressWarnings("unchecked")
    private void handleSystemSamplerArgumentsProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments args = new org.apache.jmeter.config.Arguments();

        for (Object argItem : convertToList(propValue)) {
            if (argItem == null) {
                continue;
            }

            String argValue;
            if (argItem instanceof Map) {
                // Array of objects format: [{"Argument.value": "-c"}, {"Argument.value": "echo hello"}]
                argValue = getStringValue((Map<String, Object>) argItem, "Argument.value");
                if (argValue == null) {
                    log.warn("Missing Argument.value in: {}", argItem);
                    continue;
                }
            } else if (argItem instanceof String) {
                // Simple array format: ["-c", "echo hello"]
                argValue = (String) argItem;
            } else {
                log.warn("Unsupported argument type: {}, expected String or Map", argItem.getClass());
                continue;
            }

            // For SystemSampler, arguments are value-only (like command line args)
            org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument();
            argument.setValue(argValue);
            args.addArgument(argument);
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, args));
        log.info("Set SystemSampler.arguments with {} arguments", args.getArguments().size());
    }

    /**
     * Handle SystemSampler.environment property with itemProperties schema.
     * Environment variables are name-value pairs.
     */
    @SuppressWarnings("unchecked")
    private void handleSystemSamplerEnvironmentProperty(TestElement element, String propName, Object propValue) {
        org.apache.jmeter.config.Arguments envVars = new org.apache.jmeter.config.Arguments();

        for (Object envItem : convertToList(propValue)) {
            if (!(envItem instanceof Map)) {
                log.warn("Environment item must be a map, got: {}", envItem.getClass());
                continue;
            }

            Map<String, Object> envProps = (Map<String, Object>) envItem;
            String envName = getStringValue(envProps, "Argument.name");
            if (envName == null) {
                log.warn("Missing Argument.name in: {}", envProps);
                continue;
            }

            String envValue = getStringValue(envProps, "Argument.value", "");
            org.apache.jmeter.config.Argument argument = new org.apache.jmeter.config.Argument(envName, envValue);
            envVars.addArgument(argument);
        }

        element.setProperty(new org.apache.jmeter.testelement.property.TestElementProperty(propName, envVars));
        log.info("Set SystemSampler.environment with {} variables", envVars.getArguments().size());
    }
}
