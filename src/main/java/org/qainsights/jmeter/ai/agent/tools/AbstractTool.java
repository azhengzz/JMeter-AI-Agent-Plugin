package org.qainsights.jmeter.ai.agent.tools;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Abstract base class for tools.
 * Provides common functionality including error handling and logging.
 */
public abstract class AbstractTool implements Tool {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            // Validate parameters
            ValidationResult validation = validateParameters(parameters);
            if (!validation.isValid()) {
                String errorMsg = "Validation failed: " + String.join("; ", validation.getErrors());
                log.warn("Tool execution validation failed for {}: {}", getName(), errorMsg);
                return ToolResult.error(errorMsg);
            }

            // Execute the tool
            log.debug("Executing tool {} with parameters: {}", getName(), parameters);
            ToolResult result = executeInternal(parameters);

            if (result.isSuccess()) {
                log.debug("Tool {} executed successfully", getName());
            } else {
                log.warn("Tool {} execution failed: {}", getName(), result.getError());
            }

            return result;
        } catch (Exception e) {
            log.error("Error executing tool {}", getName(), e);
            return ToolResult.error("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Internal execution method to be implemented by subclasses.
     * Parameters are already validated at this point.
     *
     * @param parameters Validated parameters
     * @return Tool execution result
     */
    protected abstract ToolResult executeInternal(Map<String, Object> parameters);

    /**
     * Get a string parameter from the parameters map
     */
    protected String getStringParameter(Map<String, Object> parameters, String key, String defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * Get an integer parameter from the parameters map
     */
    protected int getIntParameter(Map<String, Object> parameters, String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for parameter {}: {}", key, value);
            return defaultValue;
        }
    }

    /**
     * Get a boolean parameter from the parameters map
     */
    protected boolean getBooleanParameter(Map<String, Object> parameters, String key, boolean defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
