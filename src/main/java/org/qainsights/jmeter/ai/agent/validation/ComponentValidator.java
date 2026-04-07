package org.qainsights.jmeter.ai.agent.validation;

import org.qainsights.jmeter.ai.agent.tools.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Validates component parameters against schema definitions.
 */
public class ComponentValidator {
    private static final Logger log = LoggerFactory.getLogger(ComponentValidator.class);

    private final ComponentSchemaLoader schemaLoader;

    public ComponentValidator(ComponentSchemaLoader schemaLoader) {
        this.schemaLoader = schemaLoader;
    }

    /**
     * Validate component parameters.
     * Returns ValidationResult.valid() if:
     * - No schema exists (skip validation - backward compatibility)
     * - All validations pass
     *
     * @param elementType The component type
     * @param properties  The properties to validate
     * @return Validation result with errors if any
     */
    public ValidationResult validate(String elementType, Map<String, Object> properties) {
        ComponentSchema schema = schemaLoader.loadSchema(elementType);

        // No schema = skip validation (backward compatibility)
        if (schema == null) {
            log.debug("No validation schema found for component type: {}", elementType);
            return ValidationResult.valid();
        }

        log.debug("Validating component type: {} with schema", elementType);

        ValidationResult.Builder builder = ValidationResult.builder();

        // Validate unknown parameters (parameters not defined in schema)
        validateUnknownParameters(schema, properties, builder);

        // Validate required parameters
        validateRequiredParameters(schema, properties, builder);

        // Validate parameter types
        validateParameterTypes(schema, properties, builder);

        // Validate parameter values (enum, range, pattern)
        validateParameterValues(schema, properties, builder);

        return builder.build();
    }

    /**
     * Validate that all provided parameters are defined in the schema.
     * Reports error for unknown parameters.
     */
    private void validateUnknownParameters(ComponentSchema schema,
                                           Map<String, Object> properties,
                                           ValidationResult.Builder builder) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        // Collect unknown parameters
        java.util.List<String> unknownParams = new java.util.ArrayList<>();

        for (String propName : properties.keySet()) {
            if (propName == null || propName.isEmpty()) {
                continue;
            }

            // Check if parameter exists in schema
            ComponentSchema.PropertyDefinition propDef = schema.getProperty(propName);
            if (propDef == null) {
                unknownParams.add(propName);
            }
        }

        // If there are unknown parameters, report them with available parameters
        if (!unknownParams.isEmpty()) {
            // Get all available parameter names from schema
            java.util.List<String> availableParams = schema.getProperties().stream()
                    .map(p -> p.getName())
                    .filter(name -> name != null && !name.isEmpty())
                    .sorted()
                    .toList();

            String error = String.format(
                "Unknown parameter(s) [%s] are not defined in the component schema. Available parameters: %s",
                String.join(", ", unknownParams),
                availableParams
            );
            builder.addError(error);
            log.debug("Validation failed: {}", error);
        }
    }

    /**
     * Validate that all required parameters are present.
     */
    private void validateRequiredParameters(ComponentSchema schema,
                                           Map<String, Object> properties,
                                           ValidationResult.Builder builder) {
        if (properties == null) {
            properties = new java.util.HashMap<>();
        }

        for (ComponentSchema.PropertyDefinition propDef : schema.getRequiredProperties()) {
            String paramName = propDef.getName();
            if (paramName == null || paramName.isEmpty()) {
                continue; // Skip invalid property definitions
            }
            if (!properties.containsKey(paramName)) {
                String error = String.format(
                    "Missing required parameter '%s'%s",
                    paramName,
                    propDef.getDescription() != null ? ". " + propDef.getDescription() : ""
                );
                builder.addError(error);
                log.debug("Validation failed: {}", error);
            }
        }
    }

    /**
     * Validate parameter types.
     */
    private void validateParameterTypes(ComponentSchema schema,
                                       Map<String, Object> properties,
                                       ValidationResult.Builder builder) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Object value = entry.getValue();

            if (propName == null || propName.isEmpty()) {
                continue;
            }

            ComponentSchema.PropertyDefinition propDef = schema.getProperty(propName);
            if (propDef == null || propDef.getType() == null) {
                continue; // Unknown property or no type specified - allow for flexibility
            }

            if (!isValidType(value, propDef.getType())) {
                String error = String.format(
                    "Parameter '%s' has invalid type. Expected %s but got %s",
                    propName,
                    propDef.getType(),
                    value != null ? value.getClass().getSimpleName() : "null"
                );
                builder.addError(error);
                log.debug("Validation failed: {}", error);
            }
        }
    }

    /**
     * Validate parameter values (enum, range, pattern).
     */
    private void validateParameterValues(ComponentSchema schema,
                                        Map<String, Object> properties,
                                        ValidationResult.Builder builder) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Object value = entry.getValue();

            if (propName == null || propName.isEmpty()) {
                continue;
            }

            ComponentSchema.PropertyDefinition propDef = schema.getProperty(propName);
            if (propDef == null) {
                continue;
            }

            // Skip null values
            if (value == null) {
                continue;
            }

            // Enum validation
            if (propDef.getEnumValues() != null && !propDef.getEnumValues().isEmpty()) {
                String strValue = value.toString();
                if (!propDef.getEnumValues().contains(strValue)) {
                    String error = String.format(
                        "Parameter '%s' has invalid value '%s'. Allowed values: %s",
                        propName, value, propDef.getEnumValues()
                    );
                    builder.addError(error);
                    log.debug("Validation failed: {}", error);
                }
            }

            // Range validation for integers
            if (propDef.getType() == ComponentSchema.PropertyType.INTEGER && value instanceof Number) {
                long numValue = ((Number) value).longValue();

                if (propDef.getMinValue() != null && numValue < propDef.getMinValue()) {
                    String error = String.format(
                        "Parameter '%s' value %d is below minimum %d",
                        propName, numValue, propDef.getMinValue()
                    );
                    builder.addError(error);
                    log.debug("Validation failed: {}", error);
                }

                if (propDef.getMaxValue() != null && numValue > propDef.getMaxValue()) {
                    String error = String.format(
                        "Parameter '%s' value %d exceeds maximum %d",
                        propName, numValue, propDef.getMaxValue()
                    );
                    builder.addError(error);
                    log.debug("Validation failed: {}", error);
                }
            }

            // Pattern validation (regex)
            if (propDef.getPattern() != null && !propDef.getPattern().isEmpty()) {
                String strValue = value.toString();
                try {
                    if (!strValue.matches(propDef.getPattern())) {
                        String error = String.format(
                            "Parameter '%s' value '%s' does not match required pattern",
                            propName, strValue
                        );
                        builder.addError(error);
                        log.debug("Validation failed: {}", error);
                    }
                } catch (Exception e) {
                    log.warn("Invalid regex pattern for parameter {}: {}", propName, propDef.getPattern(), e);
                }
            }
        }
    }

    /**
     * Check if a value matches the expected type.
     */
    private boolean isValidType(Object value, ComponentSchema.PropertyType expectedType) {
        if (value == null) {
            return true; // Null is valid for optional parameters
        }

        return switch (expectedType) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case NUMBER -> value instanceof Number;
            case OBJECT -> value instanceof Map;
            case ARRAY -> value instanceof Iterable || value.getClass().isArray();
        };
    }

    /**
     * Get the schema loader used by this validator.
     *
     * @return The ComponentSchemaLoader instance
     */
    public ComponentSchemaLoader getSchemaLoader() {
        return schemaLoader;
    }
}
