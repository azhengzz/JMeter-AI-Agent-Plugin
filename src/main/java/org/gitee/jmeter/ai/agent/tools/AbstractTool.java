package org.gitee.jmeter.ai.agent.tools;

import org.gitee.jmeter.ai.agent.model.ToolResult;
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
     * Get an integer parameter from the parameters map. Strict: garbage strings used to
     * silently return the default (masking wrong input); out-of-range numbers used to
     * silently truncate via {@code intValue()}. Both now raise a teaching IAE, caught by
     * {@link #execute} into a {@code ToolResult.error}.
     */
    protected int getIntParameter(Map<String, Object> parameters, String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            long wide = n.longValue();
            if (wide < Integer.MIN_VALUE || wide > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Parameter '" + key + "' is out of integer range: " + wide);
            }
            return (int) wide;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Parameter '" + key + "' must be an integer, got: '" + value + "'");
        }
    }

    /**
     * Get a boolean parameter from the parameters map. Strict: unrecognized strings
     * ("yes"/"1"/"maybe") used to silently mean {@code false} via parseBoolean — now a
     * teaching IAE. Accepts native booleans and case-insensitive "true"/"false" strings.
     */
    protected boolean getBooleanParameter(Map<String, Object> parameters, String key, boolean defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString().trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
                "Parameter '" + key + "' must be a boolean (true/false), got: '" + value + "'");
    }
}
