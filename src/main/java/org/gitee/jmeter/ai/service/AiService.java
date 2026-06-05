package org.gitee.jmeter.ai.service;

import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI Service interface for LLM providers.
 * Supports text generation, tool calling, and streaming responses.
 */
public interface AiService {
    String generateResponse(List<String> conversation);
    String generateResponse(List<String> conversation, String model);
    String getName();

    /**
     * Get the generation settings (default parameters) for this provider.
     */
    GenerationSettings getGenerationSettings();

    /**
     * Update the generation settings for this provider.
     */
    void setGenerationSettings(GenerationSettings settings);

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
     * Generate response with tool calling support and per-call overrides.
     * Overrides that are null fall back to the service's configured defaults.
     */
    default LLMResponse generateResponseWithTools(
            List<Message> messages,
            List<ToolDefinition> tools,
            LlmCallOptions options) {
        return generateResponseWithTools(messages, tools);
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
     * Generate response with forced tool choice and per-call overrides.
     */
    default LLMResponse generateResponseWithForcedTool(
            List<Message> messages,
            List<ToolDefinition> tools,
            String forcedToolName,
            LlmCallOptions options) {
        return generateResponseWithForcedTool(messages, tools, forcedToolName);
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

    /**
     * Check if this service supports streaming responses
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Generate streaming response.
     * The consumer is called for each chunk of the response.
     *
     * @param conversation The conversation history
     *param chunkConsumer Consumer for response chunks
     */
    default void generateResponseStreaming(List<String> conversation, Consumer<String> chunkConsumer) {
        // Default implementation falls back to non-streaming
        String response = generateResponse(conversation);
        chunkConsumer.accept(response);
    }

    /**
     * Generate streaming response with tool calling support.
     *
     * @param messages List of messages with roles
     * @param tools List of available tools
     * @param chunkConsumer Consumer for response chunks
     * @return LLM response including potential tool calls
     */
    default LLMResponse generateResponseStreamingWithTools(
            List<Message> messages,
            List<ToolDefinition> tools,
            Consumer<String> chunkConsumer) {
        // Default implementation falls back to non-streaming
        return generateResponseWithTools(messages, tools);
    }
}