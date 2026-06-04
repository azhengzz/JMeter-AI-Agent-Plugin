package org.qainsights.jmeter.ai.agent.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a component parameter schema definition loaded from YAML.
 * Used for validating JMeter component parameters before creation.
 */
public class ComponentSchema {
    private String componentType;
    private String componentName;
    private String description;
    private List<String> aliases;
    private final List<PropertyDefinition> properties = new ArrayList<>();

    public String getComponentType() {
        return componentType;
    }

    public void setComponentType(String componentType) {
        this.componentType = componentType;
    }

    public String getComponentName() {
        return componentName;
    }

    public void setComponentName(String componentName) {
        this.componentName = componentName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public List<PropertyDefinition> getProperties() {
        return properties;
    }

    public void addProperty(PropertyDefinition property) {
        this.properties.add(property);
    }

    /**
     * Find a property definition by name.
     *
     * @param name The property name
     * @return The property definition, or null if not found
     */
    public PropertyDefinition getProperty(String name) {
        if (name == null) {
            return null;
        }
        return properties.stream()
                .filter(p -> p.getName() != null && p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all required properties.
     *
     * @return List of required property definitions
     */
    public List<PropertyDefinition> getRequiredProperties() {
        return properties.stream()
                .filter(p -> p.getName() != null && !p.getName().isEmpty())
                .filter(PropertyDefinition::isRequired)
                .collect(Collectors.toList());
    }

    /**
     * Represents a property definition in the schema.
     */
    public static class PropertyDefinition {
        private String name;
        private PropertyType type;
        private boolean required;
        private Object defaultValue;
        private List<String> enumValues;
        private Integer minValue;
        private Integer maxValue;
        private Double doubleMinValue;  // For Float/Double type ranges
        private Double doubleMaxValue;  // For Float/Double type ranges
        private String pattern;
        private String description;
        private String className;  // Fully qualified class name for Object type
        private List<PropertyDefinition> nestedProperties;  // Nested properties for Object type
        private String itemClass;  // Fully qualified class name for collection item (e.g., "com.gitee.qa.jmeter.assertions.ValueAssertionTableElement")
        private List<PropertyDefinition> itemProperties;  // Item property definitions for collection properties
        private PropertyType innerItemType;  // Inner element type for ARRAY_2D (e.g., STRING for UserParameters.thread_values)

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public PropertyType getType() {
            return type;
        }

        public void setType(PropertyType type) {
            this.type = type;
        }

        public void setType(String typeStr) {
            this.type = PropertyType.fromString(typeStr);
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
        }

        public List<String> getEnumValues() {
            return enumValues;
        }

        public void setEnumValues(List<String> enumValues) {
            this.enumValues = enumValues;
        }

        public Integer getMinValue() {
            return minValue;
        }

        public void setMinValue(Integer minValue) {
            this.minValue = minValue;
        }

        public Integer getMaxValue() {
            return maxValue;
        }

        public void setMaxValue(Integer maxValue) {
            this.maxValue = maxValue;
        }

        public Double getDoubleMinValue() {
            return doubleMinValue;
        }

        public void setDoubleMinValue(Double doubleMinValue) {
            this.doubleMinValue = doubleMinValue;
        }

        public Double getDoubleMaxValue() {
            return doubleMaxValue;
        }

        public void setDoubleMaxValue(Double doubleMaxValue) {
            this.doubleMaxValue = doubleMaxValue;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public List<PropertyDefinition> getNestedProperties() {
            return nestedProperties;
        }

        public void setNestedProperties(List<PropertyDefinition> nestedProperties) {
            this.nestedProperties = nestedProperties;
        }

        /**
         * Check if this property definition has nested properties (Object type).
         */
        public boolean hasNestedProperties() {
            return nestedProperties != null && !nestedProperties.isEmpty();
        }

        public List<PropertyDefinition> getItemProperties() {
            return itemProperties;
        }

        public void setItemProperties(List<PropertyDefinition> itemProperties) {
            this.itemProperties = itemProperties;
        }

        public String getItemClass() {
            return itemClass;
        }

        public void setItemClass(String itemClass) {
            this.itemClass = itemClass;
        }

        public boolean hasItemClass() {
            return itemClass != null && !itemClass.isEmpty();
        }

        /**
         * Check if this property definition has item properties (collection type).
         */
        public boolean hasItemProperties() {
            return itemProperties != null && !itemProperties.isEmpty();
        }

        public PropertyType getInnerItemType() {
            return innerItemType;
        }

        public void setInnerItemType(PropertyType innerItemType) {
            this.innerItemType = innerItemType;
        }

        public void setInnerItemType(String innerItemTypeStr) {
            this.innerItemType = PropertyType.fromString(innerItemTypeStr);
        }
    }

    /**
     * Supported property types for validation.
     * Maps to JMeter property types:
     * - Integer → intProp
     * - Long → longProp
     * - Float → floatProp
     * - Double → doubleProp
     */
    public enum PropertyType {
        STRING,
        INTEGER,   // Maps to intProp (32-bit)
        LONG,      // Maps to longProp (64-bit)
        FLOAT,     // Maps to FloatProperty (32-bit floating point)
        DOUBLE,    // Maps to DoubleProperty (64-bit floating point)
        BOOLEAN,
        NUMBER,    // Generic number (legacy, prefer specific types)
        OBJECT,
        ARRAY,
        ARRAY_2D;  // Nested array (Array of Array) for components like UserParameters.thread_values

        /**
         * Parse property type from string.
         *
         * @param typeStr The type string
         * @return The property type, or STRING if not recognized
         */
        public static PropertyType fromString(String typeStr) {
            if (typeStr == null) {
                return STRING;
            }
            try {
                return valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return STRING;
            }
        }
    }
}
