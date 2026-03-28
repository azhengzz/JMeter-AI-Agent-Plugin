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

    /**
     * Get tool execution priority.
     * Higher values = higher priority. Default is 0.
     * Tools with higher priority are executed first when using concurrent execution.
     *
     * @return Tool priority (0-100, where 100 is highest priority)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Get tool execution timeout in milliseconds.
     * Returns 0 to use the default timeout from ToolRegistry.
     *
     * @return Timeout in milliseconds, or 0 for default
     */
    default long getTimeoutMs() {
        return 0;
    }
}
