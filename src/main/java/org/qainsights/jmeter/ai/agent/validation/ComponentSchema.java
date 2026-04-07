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
        private String pattern;
        private String description;

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
    }

    /**
     * Supported property types for validation.
     */
    public enum PropertyType {
        STRING,
        INTEGER,
        BOOLEAN,
        NUMBER,
        OBJECT,
        ARRAY;

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
