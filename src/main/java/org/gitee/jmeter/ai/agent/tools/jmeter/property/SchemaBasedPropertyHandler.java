package org.gitee.jmeter.ai.agent.tools.jmeter.property;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.ObjectProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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
                if (propDef.getClassName() != null) {
                    handleContainerItemsProperty(element, propName, propValue, propDef);
                } else if (propDef.hasItemClass() && propDef.hasItemProperties()) {
                    handleTestBeanTableProperty(element, propName, propValue, propDef);
                } else {
                    handleGenericCollectionProperty(element, propName, propValue);
                }
                break;

            case ARRAY_2D:
                handleNestedArrayProperty(element, propName, propValue, propDef);
                break;

            default:
                handleSimpleProperty(element, propName, propValue);
                break;
        }
    }

    // =================================================================================
    // Phase 1: Schema-driven container-items template (replaces 11 legacy container methods)
    // =================================================================================

    /**
     * Handle container-driven array properties using no-arg constructor + setter strategy.
     * Merges handleHttpArgumentsProperty / handleHttpFileArgsProperty / handleArgumentsProperty /
     * handleHeaderManagerProperty / handleCookieManagerProperty / handleSystemSamplerArgumentsProperty /
     * handleSystemSamplerEnvironmentProperty / handleParameterTestFragmentArgumentsProperty /
     * handleParameterTestFragmentReturnValueProperty / handleParameterIncludeControllerReturnValueProperty /
     * handleHttpudHeaderParametersProperty / handleHttpudArgumentsProperty.
     *
     * Schema fields used:
     * - class: FQN of container (Arguments, HTTPFileArgs, etc.)
     * - mountMode: TEST_ELEMENT_PROPERTY (nested) / SELF (parent is container) / OBJECT_PROPERTY
     * - containerAddMethod: container's add method (addArgument / add / addHTTPFileArg)
     * - itemClass: FQN of item class
     * - itemProperties: item field definitions (drives setter invocation order)
     * - setterOverride (optional, per-field): overrides default setter name derivation
     *
     * Semantics (matches legacy code):
     * - Empty input → clear container
     * - Non-empty input but all items failed to parse → skip (parse-failure protection)
     * - Otherwise: clear + add
     */
    private void handleContainerItemsProperty(TestElement element, String propName, Object propValue,
                                              ComponentSchema.PropertyDefinition propDef) {
        String containerClassName = propDef.getClassName();
        String itemClassName = propDef.getItemClass();
        if (containerClassName == null || itemClassName == null) {
            log.warn("Property {} missing class or itemClass, fallback to generic", propName);
            handleGenericCollectionProperty(element, propName, propValue);
            return;
        }

        List<Object> items = convertToList(propValue);
        List<Object> builtItems = new ArrayList<>();

        try {
            Class<?> itemClass = Class.forName(itemClassName);
            for (Object item : items) {
                if (!(item instanceof Map)) {
                    log.warn("Item must be a Map for property {}, got: {}", propName, item.getClass());
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> itemProps = (Map<String, Object>) item;
                Object builtItem = buildItemViaNoArgCtorAndSetters(itemClass, itemProps, propDef);
                if (builtItem != null) {
                    builtItems.add(builtItem);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build items for property {}", propName, e);
        }

        // Parse-failure protection: input non-empty but all failed → skip
        if (builtItems.isEmpty() && !items.isEmpty()) {
            log.warn("All items failed to parse, skipping update to preserve existing data: {}", propName);
            return;
        }

        ComponentSchema.MountMode mountMode = propDef.getMountMode();
        if (mountMode == null) {
            log.warn("Property {} has no mountMode declared, defaulting to TEST_ELEMENT_PROPERTY", propName);
            mountMode = ComponentSchema.MountMode.TEST_ELEMENT_PROPERTY;
        }

        try {
            switch (mountMode) {
                case SELF:
                    mountItemsOnSelfContainer(element, propName, propDef, builtItems);
                    break;
                case OBJECT_PROPERTY:
                    log.warn("mountMode=OBJECT_PROPERTY not expected for container-items, skipping: {}", propName);
                    break;
                case TEST_ELEMENT_PROPERTY:
                default:
                    mountItemsOnNestedContainer(element, propName, propDef, builtItems);
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to mount items for property {}", propName, e);
        }
    }

    /**
     * Build item instance via no-arg constructor + setter invocation.
     * Setters are invoked in itemProperties declared order.
     */
    private Object buildItemViaNoArgCtorAndSetters(Class<?> itemClass, Map<String, Object> itemProps,
                                                    ComponentSchema.PropertyDefinition propDef) throws Exception {
        Constructor<?> ctor = itemClass.getDeclaredConstructor();
        Object item = ctor.newInstance();

        if (propDef.hasItemProperties()) {
            for (ComponentSchema.PropertyDefinition fieldDef : propDef.getItemProperties()) {
                String fieldName = fieldDef.getName();
                Object value = itemProps.get(fieldName);
                // Fall back to schema-declared default when user did not provide value
                // (e.g., Argument.name default="" for postBodyRaw mode)
                if (value == null && fieldDef.getDefaultValue() != null) {
                    value = fieldDef.getDefaultValue();
                }
                if (value == null) {
                    continue;
                }
                applySetter(item, fieldName, value, fieldDef.getSetterOverride());
            }
        }
        return item;
    }

    /**
     * Apply a single setter on target object.
     * Tries value's natural type first, then String fallback.
     */
    private void applySetter(Object target, String fieldName, Object value, String override) {
        String setterName = resolveSetterName(fieldName, override);
        Class<?> targetClass = target.getClass();

        Class<?>[] candidateTypes = resolveCandidateParamTypes(value);
        for (Class<?> paramType : candidateTypes) {
            try {
                Method setter = targetClass.getMethod(setterName, paramType);
                Object coerced = coerceValue(value, setter.getParameterTypes()[0]);
                setter.invoke(target, coerced);
                return;
            } catch (NoSuchMethodException ignored) {
                // try next candidate type
            } catch (Exception e) {
                log.warn("Failed to invoke {}({}) on {}", setterName, paramType.getSimpleName(),
                        targetClass.getSimpleName(), e);
                return;
            }
        }
        log.warn("No setter {} matching value type {} on {}", setterName, value.getClass().getSimpleName(),
                targetClass.getSimpleName());
    }

    /**
     * Resolve setter method name.
     * Order: 1) field-level setterOverride; 2) default derivation.
     * Default: strip namespace prefix (after last '.') + snake_case→camelCase + "set" prefix.
     * e.g., "Argument.name" → "setName", "HTTPArgument.use_equals" → "setUseEquals".
     * Non-derivable names (desc/description, metadata/MetaData, always_encode/setAlwaysEncoded, xml/setAsXml)
     * MUST be declared via setterOverride on the field's schema entry.
     */
    private String resolveSetterName(String fieldName, String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        String shortName = fieldName.contains(".")
                ? fieldName.substring(fieldName.lastIndexOf('.') + 1)
                : fieldName;

        StringBuilder sb = new StringBuilder("set");
        boolean nextUpper = true;
        for (char c : shortName.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
                continue;
            }
            if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Candidate parameter types to try when invoking setter, ordered by value's natural type.
     */
    private Class<?>[] resolveCandidateParamTypes(Object value) {
        if (value instanceof Boolean) return new Class<?>[]{boolean.class, Boolean.class, String.class};
        if (value instanceof Integer) return new Class<?>[]{int.class, Integer.class, long.class, Long.class, String.class};
        if (value instanceof Long) return new Class<?>[]{long.class, Long.class, int.class, Integer.class, String.class};
        if (value instanceof Number) return new Class<?>[]{double.class, Double.class, String.class};
        return new Class<?>[]{String.class};
    }

    /**
     * Coerce value to the target parameter type (primitive boxing, String parsing).
     */
    private Object coerceValue(Object value, Class<?> paramType) {
        if (paramType == boolean.class || paramType == Boolean.class) {
            if (value instanceof Boolean) return value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
        }
        if (paramType == int.class || paramType == Integer.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) return Integer.parseInt((String) value);
        }
        if (paramType == long.class || paramType == Long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof String) return Long.parseLong((String) value);
        }
        if (paramType == double.class || paramType == Double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            if (value instanceof String) return Double.parseDouble((String) value);
        }
        if (paramType == String.class) {
            return value.toString();
        }
        return value;
    }

    /**
     * Find add method on container class. Tries exact param match first, then searches
     * for method with single param assignable from itemClass (handles HTTPArgument → addArgument(Argument)).
     */
    private Method findAddMethod(Class<?> containerClass, String addMethod, Class<?> itemClass)
            throws NoSuchMethodException {
        try {
            return containerClass.getMethod(addMethod, itemClass);
        } catch (NoSuchMethodException exactMiss) {
            for (Method m : containerClass.getMethods()) {
                if (m.getName().equals(addMethod) && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(itemClass)) {
                        return m;
                    }
                }
            }
            throw new NoSuchMethodException(addMethod + "(" + itemClass.getSimpleName() + ") on "
                    + containerClass.getSimpleName());
        }
    }

    /**
     * Mount items on element itself (mountMode=SELF, e.g., HeaderManager.headers).
     */
    private void mountItemsOnSelfContainer(TestElement element, String propName,
                                            ComponentSchema.PropertyDefinition propDef,
                                            List<Object> builtItems) throws Exception {
        String addMethod = propDef.getContainerAddMethod();
        if (addMethod == null) {
            log.warn("mountMode=SELF but no containerAddMethod declared, skipping: {}", propName);
            return;
        }

        // Clear existing collection on element
        JMeterProperty existing = element.getProperty(propName);
        if (existing instanceof CollectionProperty) {
            ((CollectionProperty) existing).clear();
        }

        if (builtItems.isEmpty()) {
            log.info("Cleared self-container property: {}", propName);
            return;
        }

        Class<?> itemClass = Class.forName(propDef.getItemClass());
        Method add = findAddMethod(element.getClass(), addMethod, itemClass);
        for (Object item : builtItems) {
            add.invoke(element, item);
        }
        log.info("Set self-container property: {} with {} items via {}", propName, builtItems.size(), addMethod);
    }

    /**
     * Mount items on a nested container, then wrap as TestElementProperty on parent.
     * Strategy: reuse existing container only when propName-precisely-matched or uniquely-typed-matched.
     * - "HTTPsampler.Arguments" on HTTPSamplerProxy → propName exact match → reuse
     * - "Arguments.arguments" on BackendListener → short-name "arguments" → reuse
     * - HTTPUDConfigElement's 3 Arguments containers (HTTPsampler.Arguments / http_header_parameters_name /
     *   HTTPUDArguments) → propName not match, multiple type matches → return null → create new each
     */
    private void mountItemsOnNestedContainer(TestElement element, String propName,
                                              ComponentSchema.PropertyDefinition propDef,
                                              List<Object> builtItems) throws Exception {
        String containerClassName = propDef.getClassName();
        String addMethod = propDef.getContainerAddMethod();
        if (addMethod == null) addMethod = "addArgument";

        Class<?> containerClass = Class.forName(containerClassName);
        Object container = findExistingContainer(element, propName, containerClass);
        boolean isNewContainer = (container == null);

        if (isNewContainer) {
            container = containerClass.getDeclaredConstructor().newInstance();
        } else {
            clearContainerCollectionProperty(container);
        }

        if (!builtItems.isEmpty()) {
            Class<?> itemClass = Class.forName(propDef.getItemClass());
            Method add = findAddMethod(containerClass, addMethod, itemClass);
            for (Object item : builtItems) {
                add.invoke(container, item);
            }
        }

        if (isNewContainer) {
            if (container instanceof TestElement) {
                element.setProperty(new TestElementProperty(propName, (TestElement) container));
            } else {
                element.setProperty(new ObjectProperty(propName, container));
            }
        }
        log.info("Set nested-container property: {} with {} items ({} container)",
                propName, builtItems.size(), isNewContainer ? "new" : "reused");
    }

    /**
     * Find existing container on element by propName exact match.
     *
     * No fallback: propName MUST be the actual property key on element.
     * No type-based fallback: HTTPUDConfigElement has 3 Arguments containers
     * (HTTPsampler.Arguments / http_header_parameters_name / HTTPUDArgumentsGui.HTTPUDArguments),
     * any type-based fallback would mis-route subsequent properties to the first container.
     */
    private Object findExistingContainer(TestElement element, String propName, Class<?> containerClass) {
        JMeterProperty prop = element.getProperty(propName);
        return extractContainerFromProperty(prop, containerClass);
    }

    /** Extract container value from a JMeterProperty if it's a TestElementProperty wrapping the expected containerClass. */
    private Object extractContainerFromProperty(JMeterProperty prop, Class<?> containerClass) {
        if (!(prop instanceof TestElementProperty)) return null;
        Object value = ((TestElementProperty) prop).getObjectValue();
        return (value != null && containerClass.isInstance(value)) ? value : null;
    }

    /** Clear all CollectionProperty items in container (items list reset before adding new). */
    private void clearContainerCollectionProperty(Object container) {
        if (!(container instanceof TestElement)) return;
        TestElement te = (TestElement) container;
        org.apache.jmeter.testelement.property.PropertyIterator iter = te.propertyIterator();
        while (iter.hasNext()) {
            JMeterProperty p = iter.next();
            if (p instanceof CollectionProperty) {
                ((CollectionProperty) p).clear();
            }
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

        // Phase 1.4: route by mountMode for non-TestElement objects (e.g., SampleSaveConfiguration)
        ComponentSchema.MountMode mountMode = propDef.getMountMode();
        if (mountMode == ComponentSchema.MountMode.OBJECT_PROPERTY) {
            handleNonTestElementObject(element, propName, nestedProps, propDef);
            return;
        }

        try {
            // Create nested object using reflection
            Class<?> nestedClass = Class.forName(className);
            TestElement nestedElement = (TestElement) nestedClass.getDeclaredConstructor().newInstance();

            nestedElement.setProperty(TestElement.TEST_CLASS, className);
            String guiClassName = deriveGuiClassName(className, propDef);
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
            setNestedObjectOnParent(element, propName, nestedElement, className, propDef);

            log.info("Successfully set nested object property: {}", propName);

        } catch (Exception e) {
            log.warn("Failed to create/set nested object for property: {}", propName, e);
        }
    }

    /**
     * Handle non-TestElement nested object via no-arg constructor + setter strategy.
     * Phase 1.4: schema-driven replacement for hardcoded handleSampleSaveConfiguration.
     * Triggered by mountMode=OBJECT_PROPERTY. Uses per-field setterOverride.
     */
    private void handleNonTestElementObject(TestElement element, String propName,
                                             Map<String, Object> propValue,
                                             ComponentSchema.PropertyDefinition propDef) {
        String className = propDef.getClassName();
        if (className == null) {
            log.warn("Property {} has mountMode=OBJECT_PROPERTY but no class declared", propName);
            return;
        }
        try {
            Class<?> objClass = Class.forName(className);
            Object obj = objClass.getDeclaredConstructor().newInstance();

            if (propDef.hasNestedProperties()) {
                for (ComponentSchema.PropertyDefinition fieldDef : propDef.getNestedProperties()) {
                    String fieldName = fieldDef.getName();
                    Object value = propValue.get(fieldName);
                    if (value == null) {
                        continue;
                    }
                    applySetter(obj, fieldName, value, fieldDef.getSetterOverride());
                }
            }

            element.setProperty(new ObjectProperty(propName, obj));
            log.info("Set non-TestElement object {} via mountMode=OBJECT_PROPERTY", propName);
        } catch (Exception e) {
            log.warn("Failed to handle non-TestElement object for {}", propName, e);
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
     * Handle nested array properties (Array of Array) for UserParameters.thread_values / ultimatethreadgroupdata.
     * Phase 1.4: supports both List-of-List and List-of-Map input.
     * Map input extracts values by itemProperties declared order (replaces UltimateThreadGroup hardcoded fieldOrder).
     */
    @SuppressWarnings("unchecked")
    private void handleNestedArrayProperty(TestElement element, String propName, Object propValue,
                                            ComponentSchema.PropertyDefinition propDef) {
        try {
            List<Object> outerList = convertToList(propValue);

            CollectionProperty outer = new CollectionProperty(propName, new ArrayList<>());

            for (int i = 0; i < outerList.size(); i++) {
                Object innerItem = outerList.get(i);
                List<String> values;

                if (innerItem instanceof Map && propDef != null && propDef.hasItemProperties()) {
                    // Map input: extract values by itemProperties declared order
                    Map<String, Object> rowMap = (Map<String, Object>) innerItem;
                    values = new ArrayList<>();
                    for (ComponentSchema.PropertyDefinition fieldDef : propDef.getItemProperties()) {
                        Object val = rowMap.get(fieldDef.getName());
                        values.add(val != null ? val.toString() : "");
                    }
                } else if (innerItem instanceof List) {
                    // List input: by position
                    values = new ArrayList<>();
                    for (Object item : (List<Object>) innerItem) {
                        values.add(item != null ? item.toString() : "");
                    }
                } else {
                    log.warn("Nested array item {} is neither Map nor List, skipping", i);
                    continue;
                }

                CollectionProperty inner = new CollectionProperty(
                        String.valueOf(System.currentTimeMillis() + i), new ArrayList<>());
                for (String v : values) {
                    inner.addItem(v);
                }
                outer.addItem(inner);
            }

            element.setProperty(outer);
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
     * Derive GUI class name from schema-declared guiClass.
     */
    private String deriveGuiClassName(String className, ComponentSchema.PropertyDefinition propDef) {
        if (propDef != null && propDef.getGuiClass() != null) {
            return propDef.getGuiClass();
        }
        return null;
    }

    /**
     * Set a nested object on its parent element.
     * Phase 1.4: prefer setterOverride-declared parent setter (e.g., main_controller → setSamplerController).
     */
    private void setNestedObjectOnParent(TestElement parent, String propName,
                                         TestElement nested, String nestedClassName,
                                         ComponentSchema.PropertyDefinition propDef) {
        try {
            // Check propDef's own setterOverride for parent-level setter
            String parentSetter = propDef != null ? propDef.getSetterOverride() : null;

            if (parentSetter != null) {
                Method setter = findSingleParamMethod(parent.getClass(), parentSetter, nested.getClass());
                if (setter != null) {
                    setter.invoke(parent, nested);
                    log.info("Set nested object {} via parent setter {}", propName, parentSetter);
                    return;
                }
                log.warn("Parent setter {}({}) not found on {}", parentSetter,
                        nested.getClass().getSimpleName(), parent.getClass().getSimpleName());
            }

            // Default: use TestElementProperty
            parent.setProperty(new TestElementProperty(propName, nested));
            log.info("Set nested property {} using TestElementProperty", propName);

        } catch (Exception e) {
            log.warn("Failed to set nested object {} on parent", propName, e);
        }
    }

    /**
     * Find a single-parameter method by name on clazz, where the parameter is assignable from valueClass.
     * Tries exact match first, then searches for assignable parameter.
     */
    private Method findSingleParamMethod(Class<?> clazz, String methodName, Class<?> valueClass) {
        try {
            return clazz.getMethod(methodName, valueClass);
        } catch (NoSuchMethodException exactMiss) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                    if (m.getParameterTypes()[0].isAssignableFrom(valueClass)) {
                        return m;
                    }
                }
            }
            return null;
        }
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
