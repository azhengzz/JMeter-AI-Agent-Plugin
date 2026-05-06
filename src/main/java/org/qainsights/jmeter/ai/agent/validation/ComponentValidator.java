package org.qainsights.jmeter.ai.agent.validation;

import org.qainsights.jmeter.ai.agent.tools.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
     * Validate component parameters for UPDATE operations.
     * Unlike validate(), this method does NOT check for missing required parameters,
     * as update operations typically only modify a subset of properties.
     *
     * Returns ValidationResult.valid() if:
     * - No schema exists (skip validation - backward compatibility)
     * - All validations pass
     *
     * @param elementType The component type
     * @param properties  The properties to validate
     * @return Validation result with errors if any
     */
    public ValidationResult validateUpdate(String elementType, Map<String, Object> properties) {
        ComponentSchema schema = schemaLoader.loadSchema(elementType);

        // No schema = skip validation (backward compatibility)
        if (schema == null) {
            log.debug("No validation schema found for component type: {} (update mode)", elementType);
            return ValidationResult.valid();
        }

        log.debug("Validating component type: {} with schema (update mode - skipping required checks)", elementType);

        ValidationResult.Builder builder = ValidationResult.builder();

        // Validate unknown parameters (parameters not defined in schema)
        validateUnknownParameters(schema, properties, builder);

        // NOTE: Skip validateRequiredParameters() for update operations
        // Most update scenarios only modify a subset of properties

        // Validate parameter types
        validateParameterTypes(schema, properties, builder);

        // Validate parameter values (enum, range, pattern)
        validateParameterValues(schema, properties, builder);

        return builder.build();
    }

    /**
     * Validate that all provided parameters are defined in the schema.
     * Reports error for unknown parameters. Recursively validates nested objects.
     */
    private void validateUnknownParameters(ComponentSchema schema,
                                           Map<String, Object> properties,
                                           ValidationResult.Builder builder) {
        if (properties == null || properties.isEmpty()) {
            return;
        }

        // Collect unknown parameters
        java.util.List<String> unknownParams = new java.util.ArrayList<>();

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Object propValue = entry.getValue();

            if (propName == null || propName.isEmpty()) {
                continue;
            }

            // Check if parameter exists in schema
            ComponentSchema.PropertyDefinition propDef = schema.getProperty(propName);
            if (propDef == null) {
                unknownParams.add(propName);
                continue;
            }

            // Recursively validate nested object properties
            if (propDef.getType() == ComponentSchema.PropertyType.OBJECT && propValue instanceof Map) {
                validateNestedUnknownParameters(propDef, (Map<String, Object>) propValue, builder);
            }

            // Validate array item properties (e.g., HTTPsampler.Arguments)
            if (propDef.hasItemProperties() && isArrayValue(propValue)) {
                validateArrayItemUnknownParameters(propDef, propValue, builder);
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
     * Validate that all provided nested parameters are defined in the schema.
     */
    @SuppressWarnings("unchecked")
    private void validateNestedUnknownParameters(ComponentSchema.PropertyDefinition parentDef,
                                                  Map<String, Object> nestedProperties,
                                                  ValidationResult.Builder builder) {
        if (!parentDef.hasNestedProperties() || nestedProperties == null || nestedProperties.isEmpty()) {
            return;
        }

        // Collect unknown nested parameters
        java.util.List<String> unknownParams = new java.util.ArrayList<>();

        for (String nestedPropName : nestedProperties.keySet()) {
            if (nestedPropName == null || nestedPropName.isEmpty()) {
                continue;
            }

            // Find nested property definition
            ComponentSchema.PropertyDefinition nestedDef = findNestedPropertyDef(parentDef, nestedPropName);
            if (nestedDef == null) {
                unknownParams.add(parentDef.getName() + "." + nestedPropName);
                continue;
            }

            // Recursively validate deeper nested objects
            Object nestedPropValue = nestedProperties.get(nestedPropName);
            if (nestedDef.getType() == ComponentSchema.PropertyType.OBJECT && nestedPropValue instanceof Map) {
                validateNestedUnknownParameters(nestedDef, (Map<String, Object>) nestedPropValue, builder);
            }
        }

        // Report unknown nested parameters
        if (!unknownParams.isEmpty()) {
            String error = String.format(
                "Unknown nested parameter(s) [%s] are not defined in the schema",
                String.join(", ", unknownParams)
            );
            builder.addError(error);
            log.debug("Validation failed: {}", error);
        }
    }

    /**
     * Validate that all required parameters are present.
     * Recursively validates nested object required parameters.
     */
    private void validateRequiredParameters(ComponentSchema schema,
                                           Map<String, Object> properties,
                                           ValidationResult.Builder builder) {
        if (properties == null) {
            properties = new java.util.HashMap<>();
        }

        // Pass 1: Check top-level required properties are present
        for (ComponentSchema.PropertyDefinition propDef : schema.getRequiredProperties()) {
            String paramName = propDef.getName();
            if (paramName == null || paramName.isEmpty()) {
                continue;
            }

            if (!properties.containsKey(paramName)) {
                String error = String.format(
                    "Missing required parameter '%s'%s",
                    paramName,
                    propDef.getDescription() != null ? ". " + propDef.getDescription() : ""
                );
                builder.addError(error);
                log.debug("Validation failed: {}", error);
                continue;
            }

            // Recursively validate required nested parameters
            Object propValue = properties.get(paramName);
            if (propDef.getType() == ComponentSchema.PropertyType.OBJECT && propValue instanceof Map) {
                validateNestedRequiredParameters(propDef, (Map<String, Object>) propValue, builder);
            }
        }

        // Pass 2: Validate required item properties in ALL provided array properties
        // (not just required ones — optional arrays can have required item fields)
        for (ComponentSchema.PropertyDefinition propDef : schema.getProperties()) {
            String paramName = propDef.getName();
            if (paramName == null || paramName.isEmpty()) {
                continue;
            }

            Object propValue = properties.get(paramName);
            if (propValue == null || !isArrayValue(propValue)) {
                continue;
            }

            // Validate required array item parameters (e.g., HTTPsampler.Arguments)
            if (propDef.hasItemProperties()) {
                validateArrayItemRequiredParameters(propDef, propValue, builder);
            }
        }
    }

    /**
     * Validate that all required nested parameters are present.
     */
    @SuppressWarnings("unchecked")
    private void validateNestedRequiredParameters(ComponentSchema.PropertyDefinition parentDef,
                                                  Map<String, Object> nestedProperties,
                                                  ValidationResult.Builder builder) {
        if (!parentDef.hasNestedProperties() || nestedProperties == null || nestedProperties.isEmpty()) {
            return;
        }

        // Get required nested properties
        for (ComponentSchema.PropertyDefinition nestedDef : parentDef.getNestedProperties()) {
            if (!nestedDef.isRequired()) {
                continue;
            }

            String nestedPropName = nestedDef.getName();
            if (nestedPropName == null || nestedPropName.isEmpty()) {
                continue;
            }

            if (!nestedProperties.containsKey(nestedPropName)) {
                String error = String.format(
                    "Missing required nested parameter '%s.%s'%s",
                    parentDef.getName(), nestedPropName,
                    nestedDef.getDescription() != null ? ". " + nestedDef.getDescription() : ""
                );
                builder.addError(error);
                log.debug("Validation failed: {}", error);
                continue;
            }

            // Recursively validate deeper nested required parameters
            Object nestedPropValue = nestedProperties.get(nestedPropName);
            if (nestedDef.getType() == ComponentSchema.PropertyType.OBJECT && nestedPropValue instanceof Map) {
                validateNestedRequiredParameters(nestedDef, (Map<String, Object>) nestedPropValue, builder);
            }
        }
    }

    /**
     * Validate required parameters in array items (e.g., HTTPsampler.Arguments).
     * Checks that each item has all required properties defined in itemProperties schema.
     */
    @SuppressWarnings("unchecked")
    private void validateArrayItemRequiredParameters(ComponentSchema.PropertyDefinition propDef,
                                                      Object propValue,
                                                      ValidationResult.Builder builder) {
        if (!propDef.hasItemProperties()) {
            return;
        }

        // Convert to list
        java.util.List<Map<String, Object>> items = convertToArrayItems(propValue);
        if (items.isEmpty()) {
            return;
        }

        // Get required item properties
        java.util.List<ComponentSchema.PropertyDefinition> requiredItemProps = propDef.getItemProperties().stream()
                .filter(ComponentSchema.PropertyDefinition::isRequired)
                .toList();

        if (requiredItemProps.isEmpty()) {
            return; // No required properties to validate
        }

        // Validate each item
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (item == null || item.isEmpty()) {
                continue;
            }

            // Check each required property
            for (ComponentSchema.PropertyDefinition requiredProp : requiredItemProps) {
                String requiredPropName = requiredProp.getName();
                if (requiredPropName == null || requiredPropName.isEmpty()) {
                    continue;
                }

                if (!item.containsKey(requiredPropName)) {
                    String error = String.format(
                        "Missing required parameter in array item %d: '%s[%d].%s'%s",
                        i, propDef.getName(), i, requiredPropName,
                        requiredProp.getDescription() != null ? ". " + requiredProp.getDescription() : ""
                    );
                    builder.addError(error);
                    log.debug("Validation failed: {}", error);
                }
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
                continue;
            }

            // Recursively validate nested object properties
            if (propDef.getType() == ComponentSchema.PropertyType.OBJECT && value instanceof Map) {
                validateNestedProperties(propDef, (Map<String, Object>) value, builder);
            }

            // Validate collection items against itemProperties schema
            if (propDef.hasItemProperties() && value instanceof Map) {
                validateItemProperties(propDef, (Map<String, Object>) value, builder);
            }
        }
    }

    /**
     * Validate collection item properties against itemProperties schema.
     * Used for properties like HTTPsampler.Arguments where each item should conform to itemProperties.
     */
    @SuppressWarnings("unchecked")
    private void validateItemProperties(ComponentSchema.PropertyDefinition propDef,
                                       Map<String, Object> itemValues,
                                       ValidationResult.Builder builder) {
        if (!propDef.hasItemProperties() || itemValues == null || itemValues.isEmpty()) {
            return;
        }

        // For HTTPsampler.Arguments, the keys are argument names and values are argument values
        // We need to validate that the structure conforms to the itemProperties schema
        for (Map.Entry<String, Object> entry : itemValues.entrySet()) {
            String itemName = entry.getKey();
            Object itemValue = entry.getValue();

            // Check if the item name matches any item property name
            boolean nameMatches = propDef.getItemProperties().stream()
                .anyMatch(p -> itemName.equals(p.getName()));

            // Or check if the item value is a simple type (string, number, boolean)
            // In HTTPsampler.Arguments, values are typically strings or numbers
            if (!nameMatches && itemValue != null) {
                // The value itself might be a structured property, check if any item property matches
                boolean isStructured = propDef.getItemProperties().stream()
                    .anyMatch(p -> isValidType(itemValue, p.getType()));

                if (!isStructured) {
                    // Unknown property in the item
                    builder.addError(String.format(
                        "Parameter '%s' contains unknown item property '%s'. Valid properties are: %s",
                        propDef.getName(), itemName,
                        propDef.getItemProperties().stream()
                            .map(p -> p.getName())
                            .filter(n -> n != null && !n.isEmpty())
                            .toList()
                    ));
                    log.debug("Validation failed: unknown item property {}.{}",
                        propDef.getName(), itemName);
                }
            }
        }
    }

    /**
     * Recursively validate nested object properties.
     */
    @SuppressWarnings("unchecked")
    private void validateNestedProperties(ComponentSchema.PropertyDefinition propDef,
                                         Map<String, Object> nestedValue,
                                         ValidationResult.Builder builder) {
        if (!propDef.hasNestedProperties()) {
            return;
        }

        for (Map.Entry<String, Object> entry : nestedValue.entrySet()) {
            String nestedPropName = entry.getKey();
            Object nestedPropValue = entry.getValue();

            // Find nested property definition
            ComponentSchema.PropertyDefinition nestedDef = findNestedPropertyDef(propDef, nestedPropName);

            if (nestedDef == null) {
                builder.addError(String.format(
                    "Unknown nested property '%s.%s'",
                    propDef.getName(), nestedPropName
                ));
                log.debug("Validation failed: unknown nested property {}.{}",
                    propDef.getName(), nestedPropName);
                continue;
            }

            // Validate type
            if (!isValidType(nestedPropValue, nestedDef.getType())) {
                builder.addError(String.format(
                    "Nested property '%s.%s' has invalid type. Expected %s but got %s",
                    propDef.getName(), nestedPropName, nestedDef.getType(),
                    nestedPropValue != null ? nestedPropValue.getClass().getSimpleName() : "null"
                ));
                log.debug("Validation failed: nested property {}.{} type mismatch",
                    propDef.getName(), nestedPropName);
                continue;
            }

            // Validate value constraints (enum, range, pattern)
            validatePropertyConstraints(nestedDef, nestedPropValue, builder);

            // Recursively validate deeper nested objects
            if (nestedDef.getType() == ComponentSchema.PropertyType.OBJECT && nestedPropValue instanceof Map) {
                validateNestedProperties(nestedDef, (Map<String, Object>) nestedPropValue, builder);
            }
        }
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
     * Validate property constraints (enum, range, pattern).
     */
    private void validatePropertyConstraints(ComponentSchema.PropertyDefinition propDef,
                                            Object value,
                                            ValidationResult.Builder builder) {
        if (value == null) {
            return;
        }

        // Array type validation - check if value is a valid array format
        if (propDef.getType() == ComponentSchema.PropertyType.ARRAY) {
            log.info("Validating array property '{}': value={}, type={}",
                    propDef.getName(), value, value.getClass().getName());
            if (!isValidArrayValue(value)) {
                builder.addError(String.format(
                    "Property '%s' must be an array (e.g., ['value1', 'value2']). Received: %s (type: %s)",
                    propDef.getName(), value, value.getClass().getSimpleName()
                ));
                log.debug("Validation failed: property {} is not a valid array", propDef.getName());
                return;
            }
        }

        // ARRAY_2D type validation - check if value is a valid nested array format
        if (propDef.getType() == ComponentSchema.PropertyType.ARRAY_2D) {
            log.info("Validating ARRAY_2D property '{}': value={}, type={}",
                    propDef.getName(), value, value.getClass().getName());
            ValidationResult nestedValidation = validateArray2D(value, propDef);
            if (!nestedValidation.isValid()) {
                builder.addErrors(nestedValidation.getErrors());
                return;
            }
        }

        // Enum validation
        if (propDef.getEnumValues() != null && !propDef.getEnumValues().isEmpty()) {
            String strValue = value.toString();
            if (!propDef.getEnumValues().contains(strValue)) {
                builder.addError(String.format(
                    "Property '%s' has invalid value '%s'. Allowed values: %s",
                    propDef.getName(), value, propDef.getEnumValues()
                ));
                log.debug("Validation failed: property {} enum value mismatch", propDef.getName());
            }
        }

        // Range validation for integers
        if (propDef.getType() == ComponentSchema.PropertyType.INTEGER && value instanceof Number) {
            long numValue = ((Number) value).longValue();

            if (propDef.getMinValue() != null && numValue < propDef.getMinValue()) {
                builder.addError(String.format(
                    "Property '%s' value %d is below minimum %d",
                    propDef.getName(), numValue, propDef.getMinValue()
                ));
                log.debug("Validation failed: property {} below minimum", propDef.getName());
            }

            if (propDef.getMaxValue() != null && numValue > propDef.getMaxValue()) {
                builder.addError(String.format(
                    "Property '%s' value %d exceeds maximum %d",
                    propDef.getName(), numValue, propDef.getMaxValue()
                ));
                log.debug("Validation failed: property {} exceeds maximum", propDef.getName());
            }
        }

        // Range validation for Long
        if (propDef.getType() == ComponentSchema.PropertyType.LONG && value instanceof Number) {
            long numValue = ((Number) value).longValue();

            if (propDef.getMinValue() != null && numValue < propDef.getMinValue()) {
                builder.addError(String.format(
                    "Property '%s' value %d is below minimum %d",
                    propDef.getName(), numValue, propDef.getMinValue()
                ));
                log.debug("Validation failed: property {} below minimum", propDef.getName());
            }

            if (propDef.getMaxValue() != null && numValue > propDef.getMaxValue()) {
                builder.addError(String.format(
                    "Property '%s' value %d exceeds maximum %d",
                    propDef.getName(), numValue, propDef.getMaxValue()
                ));
                log.debug("Validation failed: property {} exceeds maximum", propDef.getName());
            }
        }

        // Range validation for Float/Double
        if ((propDef.getType() == ComponentSchema.PropertyType.FLOAT || propDef.getType() == ComponentSchema.PropertyType.DOUBLE)
                && value instanceof Number) {
            double numValue = ((Number) value).doubleValue();

            if (propDef.getDoubleMinValue() != null && numValue < propDef.getDoubleMinValue()) {
                builder.addError(String.format(
                    "Property '%s' value %s is below minimum %s",
                    propDef.getName(), numValue, propDef.getDoubleMinValue()
                ));
                log.debug("Validation failed: property {} below minimum", propDef.getName());
            }

            if (propDef.getDoubleMaxValue() != null && numValue > propDef.getDoubleMaxValue()) {
                builder.addError(String.format(
                    "Property '%s' value %s exceeds maximum %s",
                    propDef.getName(), numValue, propDef.getDoubleMaxValue()
                ));
                log.debug("Validation failed: property {} exceeds maximum", propDef.getName());
            }
        }

        // Pattern validation (regex)
        if (propDef.getPattern() != null && !propDef.getPattern().isEmpty()) {
            String strValue = value.toString();
            try {
                if (!strValue.matches(propDef.getPattern())) {
                    builder.addError(String.format(
                        "Property '%s' value '%s' does not match required pattern '%s'",
                        propDef.getName(), strValue, propDef.getPattern()
                    ));
                    log.debug("Validation failed: property {} pattern mismatch", propDef.getName());
                }
            } catch (Exception e) {
                log.warn("Invalid regex pattern for property {}: {}", propDef.getName(), propDef.getPattern(), e);
            }
        }
    }

    /**
     * Validate parameter values (enum, range, pattern).
     * Recursively validates nested object properties.
     */
    @SuppressWarnings("unchecked")
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

            // Validate value constraints (enum, range, pattern)
            validatePropertyConstraints(propDef, value, builder);

            // Recursively validate nested object properties
            if (propDef.getType() == ComponentSchema.PropertyType.OBJECT && value instanceof Map) {
                validateNestedParameterValues(propDef, (Map<String, Object>) value, builder);
            }

            // Validate array item property values (e.g., HTTPsampler.Arguments)
            if (propDef.hasItemProperties() && isArrayValue(value)) {
                validateArrayItemParameterValues(propDef, value, builder);
            }
        }
    }

    /**
     * Validate nested parameter values recursively.
     */
    @SuppressWarnings("unchecked")
    private void validateNestedParameterValues(ComponentSchema.PropertyDefinition parentDef,
                                              Map<String, Object> nestedProperties,
                                              ValidationResult.Builder builder) {
        if (!parentDef.hasNestedProperties() || nestedProperties == null || nestedProperties.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : nestedProperties.entrySet()) {
            String nestedPropName = entry.getKey();
            Object nestedPropValue = entry.getValue();

            // Find nested property definition
            ComponentSchema.PropertyDefinition nestedDef = findNestedPropertyDef(parentDef, nestedPropName);
            if (nestedDef == null) {
                continue; // Already reported in validateNestedProperties
            }

            // Skip null values
            if (nestedPropValue == null) {
                continue;
            }

            // Validate value constraints
            validatePropertyConstraints(nestedDef, nestedPropValue, builder);

            // Recursively validate deeper nested objects
            if (nestedDef.getType() == ComponentSchema.PropertyType.OBJECT && nestedPropValue instanceof Map) {
                validateNestedParameterValues(nestedDef, (Map<String, Object>) nestedPropValue, builder);
            }
        }
    }

    /**
     * Validate parameter values (enum, range, pattern) in array items.
     * Checks that each item's property values conform to schema constraints.
     */
    @SuppressWarnings("unchecked")
    private void validateArrayItemParameterValues(ComponentSchema.PropertyDefinition propDef,
                                                   Object propValue,
                                                   ValidationResult.Builder builder) {
        if (!propDef.hasItemProperties()) {
            return;
        }

        // Convert to list
        java.util.List<Map<String, Object>> items = convertToArrayItems(propValue);
        if (items.isEmpty()) {
            return;
        }

        // Validate each item
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (item == null || item.isEmpty()) {
                continue;
            }

            // Check each property in the item
            for (java.util.Map.Entry<String, Object> itemEntry : item.entrySet()) {
                String itemPropName = itemEntry.getKey();
                Object itemPropValue = itemEntry.getValue();

                if (itemPropName == null || itemPropName.isEmpty()) {
                    continue;
                }

                // Skip null values
                if (itemPropValue == null) {
                    continue;
                }

                // Find item property definition
                ComponentSchema.PropertyDefinition itemPropDef = propDef.getItemProperties().stream()
                        .filter(p -> itemPropName.equals(p.getName()))
                        .findFirst()
                        .orElse(null);

                if (itemPropDef == null) {
                    continue; // Already reported in validateArrayItemUnknownParameters
                }

                // Validate value constraints (enum, range, pattern)
                validatePropertyConstraints(itemPropDef, itemPropValue, builder);
            }
        }
    }

    /**
     * Validate unknown parameters in array items (e.g., HTTPsampler.Arguments).
     * Checks that each item's properties match the itemProperties schema definition.
     */
    @SuppressWarnings("unchecked")
    private void validateArrayItemUnknownParameters(ComponentSchema.PropertyDefinition propDef,
                                                     Object propValue,
                                                     ValidationResult.Builder builder) {
        if (!propDef.hasItemProperties()) {
            return;
        }

        // Convert to list
        java.util.List<Map<String, Object>> items = convertToArrayItems(propValue);
        if (items.isEmpty()) {
            return;
        }

        // Get valid item property names
        java.util.List<String> validItemProps = propDef.getItemProperties().stream()
                .map(p -> p.getName())
                .filter(name -> name != null && !name.isEmpty())
                .toList();

        // Validate each item
        java.util.List<String> allUnknownParams = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            if (item == null || item.isEmpty()) {
                continue;
            }

            // Check each property in the item
            for (String itemPropName : item.keySet()) {
                if (itemPropName == null || itemPropName.isEmpty()) {
                    continue;
                }

                // Check if property is in valid item properties
                if (!validItemProps.contains(itemPropName)) {
                    allUnknownParams.add(propDef.getName() + "[" + i + "]." + itemPropName);
                }
            }
        }

        // Report unknown parameters
        if (!allUnknownParams.isEmpty()) {
            String error = String.format(
                "Unknown array item parameter(s) [%s] are not defined in the schema. Valid item properties: %s",
                String.join(", ", allUnknownParams),
                validItemProps
            );
            builder.addError(error);
            log.debug("Validation failed: {}", error);
        }
    }

    /**
     * Check if a value is a valid array format.
     * Valid formats: Iterable (List, Set, etc.), native array, or JSON array string.
     */
    private boolean isValidArrayValue(Object value) {
        if (value == null) {
            return false;
        }
        // Check for Iterable (List, Set, etc.)
        if (value instanceof Iterable) {
            return true;
        }
        // Check for native array
        if (value.getClass().isArray()) {
            return true;
        }
        // Reject string values - AI should pass actual arrays, not string representations
        if (value instanceof String) {
            log.debug("Array property received as String instead of List/Array: {}", value);
            return false;
        }
        return false;
    }

    /**
     * Validate ARRAY_2D (nested array) property.
     * Checks that outer array contains inner arrays, and inner elements match innerItemType.
     */
    @SuppressWarnings("unchecked")
    private ValidationResult validateArray2D(Object value, ComponentSchema.PropertyDefinition propDef) {
        ValidationResult.Builder builder = new ValidationResult.Builder();

        // Check outer array format
        if (!isValidArrayValue(value)) {
            builder.addError(String.format(
                "Property '%s' must be a nested array (e.g., [['val1', 'val2'], ['val3', 'val4']]). Received: %s (type: %s)",
                propDef.getName(), value, value.getClass().getSimpleName()
            ));
            return builder.build();
        }

        // Convert to list and validate each inner array
        List<Object> outerList = convertToSimpleList(value);
        ComponentSchema.PropertyType innerItemType = propDef.getInnerItemType();

        if (innerItemType == null) {
            log.debug("Property '{}' has no innerItemType defined, skipping inner element validation", propDef.getName());
            return ValidationResult.valid();
        }

        for (int i = 0; i < outerList.size(); i++) {
            Object innerItem = outerList.get(i);

            // Check if inner element is an array
            if (!isValidArrayValue(innerItem)) {
                builder.addError(String.format(
                    "Property '%s' inner element at index %d must be an array. Received: %s (type: %s)",
                    propDef.getName(), i, innerItem,
                    innerItem != null ? innerItem.getClass().getSimpleName() : "null"
                ));
                continue;
            }

            // Validate inner array elements
            List<Object> innerList = convertToSimpleList(innerItem);
            for (int j = 0; j < innerList.size(); j++) {
                Object element = innerList.get(j);
                if (!isValidType(element, innerItemType)) {
                    builder.addError(String.format(
                        "Property '%s' inner element [%d][%d] has invalid type. Expected: %s, Received: %s",
                        propDef.getName(), i, j, innerItemType,
                        element != null ? element.getClass().getSimpleName() : "null"
                    ));
                }
            }
        }

        return builder.build();
    }

    /**
     * Convert an array or collection value to a simple list.
     */
    private List<Object> convertToSimpleList(Object value) {
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
     * Check if a value is an array or collection type.
     */
    private boolean isArrayValue(Object value) {
        if (value == null) {
            return false;
        }
        return value instanceof Iterable || value.getClass().isArray();
    }

    /**
     * Convert an array or collection value to a list of maps.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> convertToArrayItems(Object value) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();

        if (value == null) {
            return result;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
        } else if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (Object item : array) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
        }

        return result;
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
            case INTEGER -> value instanceof Integer;
            case LONG -> value instanceof Long || value instanceof Integer;  // Integer can be promoted to Long
            case FLOAT -> value instanceof Float;
            case DOUBLE -> value instanceof Double || value instanceof Float;  // Float can be promoted to Double
            case BOOLEAN -> value instanceof Boolean;
            case NUMBER -> value instanceof Number;  // Generic numeric type (legacy)
            case OBJECT -> value instanceof Map;
            case ARRAY -> value instanceof Iterable || value.getClass().isArray();
            case ARRAY_2D -> value instanceof Iterable || value.getClass().isArray();  // Top-level is iterable
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
