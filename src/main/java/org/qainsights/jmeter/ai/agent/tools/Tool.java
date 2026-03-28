package org.qainsights.jmeter.ai.agent.tools;

import org.qainsights.jmeter.ai.agent.model.ToolResult;

import java.util.Map;

/**
 * Interface for Agent tools.
 * Tools are callable functions that the LLM can invoke to perform actions.
 */
public interface Tool {
    /**
     * Unique tool name (used for registration and calling)
     */
    String getName();

    /**
     * Tool description for the LLM
     * Should explain what the tool does and when to use it
     */
    String getDescription();

    /**
     * Tool parameter schema in JSON Schema format
     * Defines the expected parameters for the tool
     */
    String getParameterSchema();

    /**
     * Execute the tool with given parameters
     * @param parameters Tool parameters (validated if provided)
     * @return Tool execution result
     */
    ToolResult execute(Map<String, Object> parameters);

    /**
     * Validate parameters before execution
     * @param parameters Parameters to validate
     * @return Validation result
     */
    default ValidationResult validateParameters(Map<String, Object> parameters) {
        return ValidationResult.valid();
    }

    /**
     * Check if this tool requires specific parameters
     * @return true if tool has required parameters
     */
    default boolean hasRequiredParameters() {
        return false;
    }
}
