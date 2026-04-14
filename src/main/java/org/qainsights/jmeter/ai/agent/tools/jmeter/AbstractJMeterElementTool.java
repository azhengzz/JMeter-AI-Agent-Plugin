package org.qainsights.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.agent.tools.ValidationResult;
import org.qainsights.jmeter.ai.agent.tools.jmeter.property.SchemaBasedPropertyHandler;
import org.qainsights.jmeter.ai.agent.validation.ComponentSchemaLoader;
import org.qainsights.jmeter.ai.agent.validation.ComponentValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * Abstract base class for JMeter element manipulation tools.
 * Provides common functionality for creating and updating JMeter test plan elements.
 */
public abstract class AbstractJMeterElementTool extends AbstractTool {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    protected final ComponentValidator componentValidator;
    protected final SchemaBasedPropertyHandler propertyHandler;

    /**
     * Initialize the base class with component validator and property handler.
     * Subclasses should call this constructor via super().
     */
    public AbstractJMeterElementTool() {
        // Initialize validator with schema loader
        String jmeterHome = JMeterUtils.getJMeterHome();
        if (jmeterHome != null) {
            Path skillsDir = Path.of(jmeterHome, "bin", "jmeter-agent", "skills");
            ComponentSchemaLoader schemaLoader = new ComponentSchemaLoader(skillsDir);
            this.componentValidator = new ComponentValidator(schemaLoader);
        } else {
            Logger log = LoggerFactory.getLogger(getClass());
            log.warn("JMeter home not found, component validation will be disabled");
            this.componentValidator = null;
        }

        // Initialize schema-based property handler
        this.propertyHandler = new SchemaBasedPropertyHandler();
    }

    /**
     * Parse the properties parameter, handling both Map and JSON string formats.
     *
     * @param propertiesValue The properties value from parameters (could be Map or JSON String)
     * @return Parsed properties as Map, or empty Map if null/invalid
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parsePropertiesParameter(Object propertiesValue) {
        if (propertiesValue == null) {
            return Map.of();
        }

        // If already a Map, return it directly
        if (propertiesValue instanceof Map) {
            return (Map<String, Object>) propertiesValue;
        }

        // If it's a String, try to parse as JSON
        if (propertiesValue instanceof String) {
            String jsonString = (String) propertiesValue;
            if (jsonString.isEmpty() || jsonString.isBlank()) {
                return Map.of();
            }

            try {
                return OBJECT_MAPPER.readValue(jsonString, Map.class);
            } catch (Exception e) {
                Logger log = LoggerFactory.getLogger(getClass());
                log.warn("Failed to parse properties as JSON, treating as empty: {}", e.getMessage());
                return Map.of();
            }
        }

        Logger log = LoggerFactory.getLogger(getClass());
        log.warn("Unexpected properties type: {}, expected Map or String", propertiesValue.getClass());
        return Map.of();
    }

    /**
     * Build a user-friendly error message for validation failures.
     *
     * @param elementType  The component type
     * @param validation   The validation result
     * @return Formatted error message
     */
    protected String buildValidationErrorMessage(String elementType, ValidationResult validation) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Component Parameter Validation Failed**\n\n");
        sb.append("Component Type: ").append(elementType).append("\n\n");
        sb.append("Errors:\n");
        for (String error : validation.getErrors()) {
            sb.append("  - ").append(error).append("\n");
        }
        sb.append("\n**Solution**: Please provide all required parameters with correct values.\n");
        sb.append("Refer to component documentation for parameter details.");
        return sb.toString();
    }

    /**
     * Build a user-friendly error message for validation failures on existing elements.
     *
     * @param elementName  The element name
     * @param elementType  The element type
     * @param validation   The validation result
     * @return Formatted error message
     */
    protected String buildValidationErrorMessage(String elementName, String elementType,
                                                 ValidationResult validation) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Property Validation Failed**\n\n");
        sb.append("Element: ").append(elementName).append(" (").append(elementType).append(")\n\n");
        sb.append("Errors:\n");
        for (String error : validation.getErrors()) {
            sb.append("  - ").append(error).append("\n");
        }
        sb.append("\n**Solution**: Please provide valid properties with correct values.\n");
        sb.append("Refer to component documentation for property details.");
        return sb.toString();
    }
}
