package org.qainsights.jmeter.ai.service;

import org.qainsights.jmeter.ai.agent.model.LLMResponse;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;

import java.util.List;

public interface AiService {
    String generateResponse(List<String> conversation);
    String generateResponse(List<String> conversation, String model);
    String getName();

    /**
     * Generate response with tool calling support.
     * This method allows the LLM to call tools during generation.
     *
     * @param messages List of messages (with roles and content)
     * @param tools List of available tools
     * @return LLM response including potential tool calls
     */
    default LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
        // Default implementation for backward compatibility
        throw new UnsupportedOperationException(
            "Tool calling not supported by " + getName() + ". " +
            "Please use a service that supports function calling."
        );
    }

    /**
     * Generate response with forced tool choice.
     * This method forces the LLM to call a specific tool.
     *
     * @param messages List of messages (with roles and content)
     * @param tools List of available tools
     * @param forcedToolName Name of the tool to force call
     * @return LLM response with the forced tool call
     */
    default LLMResponse generateResponseWithForcedTool(
            List<Message> messages,
            List<ToolDefinition> tools,
            String forcedToolName) {
        throw new UnsupportedOperationException(
            "Forced tool calling not supported by " + getName() + ". " +
            "Please use a service that supports function calling."
        );
    }

    /**
     * Check if this service supports tool calling
     */
    default boolean supportsToolCalling() {
        return false;
    }

    /**
     * Check if this service supports forced tool choice
     */
    default boolean supportsForcedToolChoice() {
        return false;
    }
}