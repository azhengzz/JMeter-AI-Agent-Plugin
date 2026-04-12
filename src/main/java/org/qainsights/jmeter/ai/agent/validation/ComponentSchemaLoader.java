package org.qainsights.jmeter.ai.agent.validation;

import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads component validation schemas from YAML files.
 * Uses SnakeYAML for parsing.
 */
public class ComponentSchemaLoader {
    private static final Logger log = LoggerFactory.getLogger(ComponentSchemaLoader.class);

    private final Path referencesDir;
    private final Map<String, ComponentSchema> schemaCache = new HashMap<>();
    private final Yaml yaml;

    /**
     * Create a schema loader.
     * Will search for schema files in {skillsDir}/jmeter/references/
     *
     * @param skillsDir The base skills directory
     */
    public ComponentSchemaLoader(Path skillsDir) {
        this.referencesDir = skillsDir.resolve("jmeter/references");
        this.yaml = new Yaml();
        loadAllSchemas();
    }

    /**
     * Load schema for a specific component type.
     * Returns null if no schema exists (component not validated).
     *
     * @param elementType The component type (will be normalized)
     * @return The schema, or null if not found
     */
    public ComponentSchema loadSchema(String elementType) {
        if (elementType == null) {
            return null;
        }
        String normalizedType = JMeterElementManager.normalizeElementType(elementType);
        return schemaCache.get(normalizedType);
    }

    /**
     * Load all schema files from the references directory.
     */
    private void loadAllSchemas() {
        if (referencesDir == null || !Files.exists(referencesDir)) {
            log.warn("References directory not found: {}", referencesDir);
            return;
        }

        try {
            // Recursively find all .schema.yaml files
            List<Path> schemaFiles = new ArrayList<>();
            Files.walk(referencesDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".schema.yaml"))
                    .forEach(schemaFiles::add);

            log.info("Found {} schema files in {}", schemaFiles.size(), referencesDir);

            for (Path schemaFile : schemaFiles) {
                ComponentSchema schema = parseSchemaFile(schemaFile);
                if (schema != null && schema.getComponentType() != null) {
                    String normalizedType = JMeterElementManager.normalizeElementType(schema.getComponentType());
                    schemaCache.put(normalizedType, schema);
                    log.info("Loaded schema for component type: {} from {}", normalizedType, schemaFile);
                }
            }

            log.info("Total schemas loaded: {}", schemaCache.size());

        } catch (IOException e) {
            log.warn("Error loading schema files from: {}", referencesDir, e);
        }
    }

    /**
     * Parse a YAML schema file using SnakeYAML.
     *
     * @param schemaFile The schema file path
     * @return The parsed schema, or null if parsing failed
     */
    @SuppressWarnings("unchecked")
    private ComponentSchema parseSchemaFile(Path schemaFile) {
        try {
            String content = Files.readString(schemaFile);
            Map<String, Object> data = yaml.load(content);

            if (data == null || !data.containsKey("component")) {
                log.warn("Invalid schema file (missing component section): {}", schemaFile);
                return null;
            }

            Map<String, Object> componentData = (Map<String, Object>) data.get("component");
            ComponentSchema schema = new ComponentSchema();

            // Parse component section
            schema.setComponentType(getStringValue(componentData, "type"));
            schema.setComponentName(getStringValue(componentData, "name"));
            schema.setDescription(getStringValue(componentData, "description"));

            // Parse properties section
            if (data.containsKey("properties")) {
                List<Map<String, Object>> propertiesList = (List<Map<String, Object>>) data.get("properties");
                if (propertiesList != null) {
                    for (Map<String, Object> propData : propertiesList) {
                        ComponentSchema.PropertyDefinition propDef = parsePropertyDefinition(propData);
                        if (propDef != null && propDef.getName() != null && !propDef.getName().isEmpty()) {
                            schema.addProperty(propDef);
                        }
                    }
                }
            }

            return schema;

        } catch (Exception e) {
            log.warn("Failed to parse schema file: {}", schemaFile, e);
            return null;
        }
    }

    /**
     * Parse a property definition from YAML map.
     */
    @SuppressWarnings("unchecked")
    private ComponentSchema.PropertyDefinition parsePropertyDefinition(Map<String, Object> propData) {
        if (propData == null) {
            return null;
        }

        try {
            ComponentSchema.PropertyDefinition propDef = new ComponentSchema.PropertyDefinition();

            propDef.setName(getStringValue(propData, "name"));

            String typeStr = getStringValue(propData, "type");
            if (typeStr != null) {
                propDef.setType(typeStr);
                log.debug("Property '{}' type: '{}' -> parsed as: {}", propDef.getName(), typeStr, propDef.getType());
            } else {
                log.debug("Property '{}' has no type specified", propDef.getName());
            }

            propDef.setRequired(getBooleanValue(propData, "required", false));
            propDef.setDescription(getStringValue(propData, "description"));
            propDef.setPattern(getStringValue(propData, "pattern"));

            // Parse class name for Object type
            propDef.setClassName(getStringValue(propData, "class"));

            // Parse nested properties for Object type
            if (propData.containsKey("properties")) {
                Object nestedPropsObj = propData.get("properties");
                if (nestedPropsObj instanceof List) {
                    List<ComponentSchema.PropertyDefinition> nestedProps = new ArrayList<>();
                    for (Object nestedPropObj : (List<?>) nestedPropsObj) {
                        if (nestedPropObj instanceof Map) {
                            ComponentSchema.PropertyDefinition nestedProp =
                                parsePropertyDefinition((Map<String, Object>) nestedPropObj);
                            if (nestedProp != null && nestedProp.getName() != null) {
                                nestedProps.add(nestedProp);
                            }
                        }
                    }
                    if (!nestedProps.isEmpty()) {
                        propDef.setNestedProperties(nestedProps);
                    }
                }
            }

            // Parse item type for collection properties
            propDef.setItemType(getStringValue(propData, "itemType"));

            // Parse inner item type for ARRAY_2D properties
            propDef.setInnerItemType(getStringValue(propData, "innerItemType"));

            // Parse item properties for collection properties
            if (propData.containsKey("itemProperties")) {
                Object itemPropsObj = propData.get("itemProperties");
                if (itemPropsObj instanceof List) {
                    List<ComponentSchema.PropertyDefinition> itemProps = new ArrayList<>();
                    for (Object itemPropObj : (List<?>) itemPropsObj) {
                        if (itemPropObj instanceof Map) {
                            ComponentSchema.PropertyDefinition itemProp =
                                parsePropertyDefinition((Map<String, Object>) itemPropObj);
                            if (itemProp != null && itemProp.getName() != null) {
                                itemProps.add(itemProp);
                            }
                        }
                    }
                    if (!itemProps.isEmpty()) {
                        propDef.setItemProperties(itemProps);
                    }
                }
            }

            // Parse default value
            if (propData.containsKey("default")) {
                Object defaultValue = propData.get("default");
                propDef.setDefaultValue(defaultValue);
            }

            // Parse enum values
            if (propData.containsKey("enum")) {
                Object enumValue = propData.get("enum");
                List<String> enumValues = new ArrayList<>();
                if (enumValue instanceof List) {
                    for (Object item : (List<?>) enumValue) {
                        if (item != null) {
                            enumValues.add(item.toString());
                        }
                    }
                }
                if (!enumValues.isEmpty()) {
                    propDef.setEnumValues(enumValues);
                }
            }

            // Parse min/max values
            if (propData.containsKey("min")) {
                Object minVal = propData.get("min");
                if (minVal instanceof Number) {
                    propDef.setMinValue(((Number) minVal).intValue());
                } else if (minVal instanceof String) {
                    try {
                        propDef.setMinValue(Integer.parseInt((String) minVal));
                    } catch (NumberFormatException e) {
                        log.debug("Failed to parse min value: {}", minVal);
                    }
                }
            }

            if (propData.containsKey("max")) {
                Object maxVal = propData.get("max");
                if (maxVal instanceof Number) {
                    propDef.setMaxValue(((Number) maxVal).intValue());
                } else if (maxVal instanceof String) {
                    try {
                        propDef.setMaxValue(Integer.parseInt((String) maxVal));
                    } catch (NumberFormatException e) {
                        log.debug("Failed to parse max value: {}", maxVal);
                    }
                }
            }

            return propDef;

        } catch (Exception e) {
            log.warn("Failed to parse property definition: {}", propData, e);
            return null;
        }
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * Get all loaded schema types.
     *
     * @return Set of component types with schemas
     */
    public java.util.Set<String> getLoadedSchemaTypes() {
        return schemaCache.keySet();
    }
}
