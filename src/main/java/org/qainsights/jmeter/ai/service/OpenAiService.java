package org.qainsights.jmeter.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.SystemPrompt;
import org.qainsights.jmeter.ai.usage.OpenAiUsage;
import org.qainsights.jmeter.ai.agent.model.LLMResponse;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private OpenAIClient client;
    private boolean systemPromptInitialized = false;

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;
    private final String reasoningEffort;

    // Provider prefixes that use OpenAI-compatible API
    private static final String[] OPENAI_COMPATIBLE_PROVIDERS = {
        "openai", "deepseek", "zhipu", "moonshot", "minimax"
    };

    public OpenAiService() {
        // Use global defaults with per-provider override fallback
        this.maxHistorySize = Integer.parseInt(AiConfig.getPropertyWithFallback("openai", "max.history.size", "10"));
        this.currentModelId = AiConfig.getDefaultModel();
        this.temperature = Float.parseFloat(AiConfig.getPropertyWithFallback("openai", "temperature", "0.7"));
        this.maxTokens = Long.parseLong(AiConfig.getPropertyWithFallback("openai", "max.tokens", "4096"));
        this.reasoningEffort = AiConfig.getPropertyWithFallback("openai", "reasoning.effort", "medium");

        // Initialize client with default (openai) configuration
        initializeClient("openai");

        // Load system prompt using centralized utility
        this.systemPrompt = SystemPrompt.get();
        log.info("Loaded system prompt (length: {})", systemPrompt.length());
        log.info("System prompt (first 100 chars): {}",
                systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
    }

    /**
     * Initialize the OpenAI client with configuration for a specific provider.
     */
    private void initializeClient(String provider) {
        String apiKey = getConfigValue(provider, "api.key", "");
        String baseUrl = getConfigValue(provider, "api.base.url", "");
        String loggingLevel = getConfigValue(provider, "log.level", "");
        String maskedApiKey = apiKey.isEmpty() ? "(empty)" : (apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey);

        log.info("=== Initializing OpenAI Client for Provider: {} ===", provider);
        log.info("API Key: {}", maskedApiKey);
        log.info("Base URL: {}", baseUrl.isEmpty() ? "(default OpenAI)" : baseUrl);
        log.info("Logging Level: {}", loggingLevel.isEmpty() ? "(default)" : loggingLevel);

        if (!loggingLevel.isEmpty()) {
            System.setProperty("OPENAI_LOG", loggingLevel);
            log.info("Enabled OpenAI client logging with level: {} for provider: {}", loggingLevel, provider);
        }

        // Build the client with optional custom base URL
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey);

        if (!baseUrl.isEmpty()) {
            clientBuilder.baseUrl(baseUrl);
            log.info("Set custom base URL for provider {}: {}", provider, baseUrl);
        } else {
            log.info("Using default OpenAI API endpoint for provider: {}", provider);
        }

        this.client = clientBuilder.build();

        log.info("OpenAI Client created successfully for provider: {}", provider);
        log.info("===========================================");

        // Update the client in the OpenAiUsage singleton
        try {
            OpenAiUsage.getInstance().setClient(this.client);
            log.info("Updated OpenAI client in OpenAiUsage for provider: {}", provider);
        } catch (Exception e) {
            log.error("Failed to set OpenAI client in OpenAiUsage", e);
        }
    }

    /**
     * Get configuration value for a specific provider.
     * Tries provider-specific key first, then falls back to generic openai key.
     */
    private String getConfigValue(String provider, String key, String defaultValue) {
        // Try provider-specific configuration first (e.g., "minimax.api.key")
        String providerKey = provider + "." + key;
        String value = AiConfig.getProperty(providerKey, null);
        if (value != null && !value.isEmpty()) {
            log.debug("Using provider-specific config: {} = {}", providerKey, value);
            return value;
        }

        // Fall back to generic openai configuration (e.g., "openai.api.key")
        String genericKey = "openai." + key;
        value = AiConfig.getProperty(genericKey, defaultValue);
        log.debug("Using generic config: {} = {}", genericKey, value);
        return value;
    }

    /**
     * Extract the provider prefix from a model ID.
     * For example, "minimax:MiniMax-M2.7" returns "minimax".
     * Returns "openai" if no provider prefix is found.
     */
    private String extractProvider(String modelId) {
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            String provider = parts[0];
            // Check if it's a known OpenAI-compatible provider
            for (String knownProvider : OPENAI_COMPATIBLE_PROVIDERS) {
                if (knownProvider.equals(provider)) {
                    return provider;
                }
            }
        }
        return "openai"; // Default provider
    }

    /**
     * Extract just the model name without provider prefix.
     * For example, "minimax:MiniMax-M2.7" returns "MiniMax-M2.7".
     * Returns the original modelId if no provider prefix is found.
     */
    private String extractModelName(String modelId) {
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            return parts[1];  // Return the part after the colon
        }
        return modelId;  // No prefix, return as-is
    }

    public OpenAIClient getClient() {
        return client;
    }

    public void setModel(String modelId) {
        // IMPORTANT: Extract providers BEFORE updating currentModelId
        String newProvider = extractProvider(modelId);
        String currentProvider = extractProvider(this.currentModelId);

        // Check if we need to reinitialize the client for a different provider
        if (!newProvider.equals(currentProvider)) {
            log.info("=== Provider change detected ===");
            log.info("Old provider: {}", currentProvider);
            log.info("New provider: {}", newProvider);
            log.info("Reinitializing client for provider: {}", newProvider);
            initializeClient(newProvider);
            log.info("============================");
        }

        // Update currentModelId AFTER provider comparison
        this.currentModelId = modelId;

        log.info("Model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return currentModelId;
    }

    public void setTemperature(float temperature) {
        if (temperature < 0 || temperature >= 1) {
            log.warn("Temperature must be between 0 and 1. Provided value: {}. Setting to default 0.7", temperature);
            this.temperature = 0.7f;
        } else {
            this.temperature = temperature;
            log.info("Temperature set to: {}", temperature);
        }
    }

    public float getTemperature() {
        return temperature;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
        log.info("Max tokens set to: {}", maxTokens);
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    /**
     * Resets the system prompt initialization flag.
     * This should be called when starting a new conversation.
     */
    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
        log.info("Reset system prompt initialization flag");
    }

    public String sendMessage(String message) {
        log.info("Sending message to OpenAI: {}", message);
        return generateResponse(java.util.Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        try {
            log.info("Generating response for conversation with {} messages", conversation.size());

            // Ensure a model is set
            if (currentModelId == null || currentModelId.isEmpty()) {
                currentModelId = "gpt-4o";
                log.warn("No model was set, defaulting to: {}", currentModelId);
            }

            // Debug: Log current API configuration
            String provider = extractProvider(currentModelId);
            String apiKey = getConfigValue(provider, "api.key", "");
            String baseUrl = getConfigValue(provider, "api.base.url", "");
            String maskedApiKey = apiKey.isEmpty() ? "(empty)" : (apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey);

            log.info("=== OpenAI Service Configuration ===");
            log.info("Provider: {}", provider);
            log.info("Model ID: {}", currentModelId);
            log.info("API Key: {}", maskedApiKey);
            log.info("Base URL: {}", baseUrl.isEmpty() ? "(default)" : baseUrl);
            log.info("====================================");

            // Ensure a temperature is set
            if (temperature < 0 || temperature > 1) {
                temperature = 0.7f;
                log.warn("Invalid temperature value ({}), defaulting to: {}", temperature, 0.7f);
            }

            // Log which model is being used for this conversation
            log.info("Generating response using model: {} and temperature: {}", currentModelId, temperature);

            // Extract just the model name (without provider prefix) for the API request
            String modelNameForApi = extractModelName(currentModelId);

            // Check if this is the first message in a conversation based on
            // systemPromptInitialized flag
            boolean isFirstMessage = !systemPromptInitialized;
            if (isFirstMessage) {
                log.info("Using system prompt (first 100 chars): {}",
                        systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
                systemPromptInitialized = true;
            } else {
                log.info("Using previously initialized conversation with system prompt");
            }

            // Limit conversation history to last 10 messages to avoid token limits
            List<String> limitedConversation = conversation;
            if (conversation.size() > maxHistorySize) {
                limitedConversation = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
                log.info("Limiting conversation to last {} messages", limitedConversation.size());
            }

            // Create a fresh builder for parameters following the working example
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .maxCompletionTokens(maxTokens)
                    .temperature(temperature)
                    .model(modelNameForApi);  // Use model name without prefix

            // Apply reasoning effort for reasoning-capable models
            ReasoningEffort effort = toReasoningEffort(reasoningEffort);
            if (effort != null) {
                paramsBuilder.reasoningEffort(effort);
            }

            // Always include the system prompt
            paramsBuilder.addSystemMessage(systemPrompt);
            log.info("Including system prompt in request (length: {})", systemPrompt.length());

            // Limit conversation history to last maxHistorySize messages to avoid token limits
            List<String> limitedHistory;
            if (conversation.size() > maxHistorySize) {
                limitedHistory = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
                log.info("Limiting conversation to last {} messages", limitedHistory.size());
            } else {
                limitedHistory = new java.util.ArrayList<>(conversation);
            }

            // Debug log the conversation array
            log.info("Conversation size: {}", limitedHistory.size());

            // Log the conversation for debugging
            for (int i = 0; i < limitedHistory.size(); i++) {
                log.info("Message[{}]: {}", i, limitedHistory.get(i));
            }

            if (limitedHistory.isEmpty()) {
                log.warn("Conversation is empty, using default message");
                paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
            } else {
                // Process the conversation history
                // We'll assume the conversation alternates between user and assistant messages
                // with the first message being from the user
                for (int i = 0; i < limitedHistory.size(); i++) {
                    String msg = limitedHistory.get(i);
                    if (msg == null || msg.isEmpty()) {
                        log.warn("Skipping empty message at position {}", i);
                        continue;
                    }

                    if (i % 2 == 0) {
                        // User messages (even indices: 0, 2, 4...)
                        paramsBuilder.addUserMessage(msg);
                        log.info("Added user message {}: {}", i,
                                msg.substring(0, Math.min(50, msg.length())));
                    } else {
                        // Assistant messages (odd indices: 1, 3, 5...)
                        // Use addAssistantMessage(String) method from OpenAI SDK 4.x
                        paramsBuilder.addAssistantMessage(msg);
                        log.info("Added assistant message {}: {}", i,
                                msg.substring(0, Math.min(50, msg.length())));
                    }
                }
            }

            // Build the parameters and create the chat completion
            ChatCompletionCreateParams params = paramsBuilder.build();
            log.info("Request parameters: maxTokens={}, temperature={}, model={}, messagesCount={}",
                    params.maxCompletionTokens(), params.temperature(), params.model(),
                    conversation.size());

            // Debug log the messages in the request
            log.info("Request messages: {}", params.messages());

            ChatCompletion chatCompletion = client.chat().completions().create(params);

            log.info("Chat completions {}", chatCompletion);

            // Record usage data if available
            try {
                OpenAiUsage.getInstance().recordUsage(chatCompletion, currentModelId);
                log.info("Recorded token usage for model: {}", currentModelId);
            } catch (Exception ex) {
                log.error("Failed to record token usage", ex);
            }

            // Extract the response content using SDK methods
            String responseContent;
            try {
                // Get the first choice
                ChatCompletion.Choice choice = chatCompletion.choices().get(0);

                // Extract the content from the message
                // The SDK provides methods to access the message and its content
                responseContent = choice.message().content().orElse("No content available");
            } catch (Exception ex) {
                log.error("Error extracting content using SDK methods", ex);

                // Fallback to using toString() if SDK methods fail
                String choiceStr = chatCompletion.choices().get(0).toString();

                // Extract just the actual content text
                int contentStart = choiceStr.indexOf("content=");
                if (contentStart > 0) {
                    contentStart += 8; // Move past "content="

                    // Find the end of the content (before refusal or annotations)
                    int contentEnd = choiceStr.indexOf(", refusal=", contentStart);
                    if (contentEnd < 0) {
                        contentEnd = choiceStr.indexOf(", annotations=", contentStart);
                    }
                    if (contentEnd < 0) {
                        contentEnd = choiceStr.indexOf("}", contentStart);
                    }

                    if (contentEnd > contentStart) {
                        responseContent = choiceStr.substring(contentStart, contentEnd);
                    } else {
                        responseContent = choiceStr.substring(contentStart);
                    }
                } else {
                    responseContent = choiceStr;
                }
            }

            return responseContent;
        } catch (Exception e) {
            log.error("Error generating response", e);

            // Extract and format error message for better readability
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    /**
     * Generates a response from the AI using the specified model.
     * 
     * @param conversation The conversation history
     * @param model        The specific model to use for this request
     * @return The AI's response
     */
    public String generateResponse(List<String> conversation, String model) {
        log.info("Generating response with specified model: {}", model);

        // Store current model
        String originalModel = this.currentModelId;

        try {
            // Set the specified model
            this.currentModelId = model;

            // Generate the response using the specified model
            return generateResponse(conversation);
        } finally {
            // Restore the original model
            this.currentModelId = originalModel;
            log.info("Restored original model: {}", originalModel);
        }
    }

    /**
     * Extracts a user-friendly error message from an exception
     *
     * @param e The exception to extract the error message from
     * @return A user-friendly error message
     */
    private String extractUserFriendlyErrorMessage(Exception e) {
        String errorMessage = e.getMessage();

        // Check for JSON parsing error (likely HTML response)
        if (e instanceof com.openai.errors.OpenAIInvalidDataException) {
            Throwable cause = e.getCause();
            if (cause instanceof com.fasterxml.jackson.core.JsonParseException) {
                log.error("JSON parsing error - API likely returned HTML instead of JSON. " +
                         "This usually means: 1) Wrong API endpoint, 2) Invalid API key, or 3) API authentication failed");
                return "API Error: Invalid response format. This usually means the API endpoint is incorrect, " +
                       "the API key is invalid, or authentication failed. Please check your configuration.";
            }
        }

        // Check for credit balance error
        if (errorMessage != null && errorMessage.contains("insufficient_quota")) {
            return "Your credit balance is too low to access the OpenAI API. Please check your billing information.";
        }

        // Check for API key error
        if (errorMessage != null && errorMessage.contains("invalid_api_key")) {
            return "Invalid API key. Please check your API key and try again.";
        }

        // Check for rate limit error
        if (errorMessage != null && errorMessage.contains("rate_limit_exceeded")) {
            return "Rate limit exceeded. Please try again later.";
        }

        // Check for model not found error
        if (errorMessage != null && errorMessage.contains("model_not_found")) {
            return "The selected model was not found. Please select a different model.";
        }

        // Check for context length error
        if (errorMessage != null && errorMessage.contains("context_length_exceeded")) {
            return "The conversation is too long. Please start a new conversation.";
        }

        // For other errors, provide a cleaner message
        if (errorMessage != null) {
            // Try to extract a more readable message
            if (errorMessage.contains("OpenAIError")) {
                // Try to extract the message field from the error JSON
                int messageStart = errorMessage.indexOf("message=");
                if (messageStart != -1) {
                    int messageEnd = errorMessage.indexOf("}", messageStart);
                    if (messageEnd != -1) {
                        return errorMessage.substring(messageStart + 8, messageEnd);
                    }
                }
            }
        }

        // If we couldn't extract a specific error message, return a generic one
        return "An error occurred while communicating with the OpenAI API. Please try again later.";
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
        return doGenerateWithTools(messages, tools, null);
    }

    @Override
    public LLMResponse generateResponseWithForcedTool(List<Message> messages, List<ToolDefinition> tools, String forcedToolName) {
        log.info("Forced tool calling: {}", forcedToolName);
        try {
            LLMResponse response = doGenerateWithTools(messages, tools, forcedToolName);
            if (response.isError() && isToolChoiceUnsupported(response.getErrorMessage())) {
                log.warn("Forced tool_choice unsupported, retrying with auto");
                return doGenerateWithTools(messages, tools, null);
            }
            return response;
        } catch (Exception e) {
            if (isToolChoiceUnsupported(e)) {
                log.warn("Forced tool_choice unsupported, retrying with auto");
                return doGenerateWithTools(messages, tools, null);
            }
            return LLMResponse.error("Error in forced tool calling: " + e.getMessage());
        }
    }

    private boolean isToolChoiceUnsupported(Throwable e) {
        if (e == null) return false;
        String msg = (e.getMessage() != null ? e.getMessage() : "").toLowerCase();
        return msg.contains("tool_choice") || msg.contains("does not support")
                || msg.contains("should be [\"none\", \"auto\"]");
    }

    private boolean isToolChoiceUnsupported(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("tool_choice") || lower.contains("does not support")
                || lower.contains("should be [\"none\", \"auto\"]");
    }

    private LLMResponse doGenerateWithTools(List<Message> messages, List<ToolDefinition> tools, String forcedToolName) {
        log.info("Generating response: {} tools, forcedTool={}", tools != null ? tools.size() : 0, forcedToolName);

        String modelNameForApi = extractModelName(currentModelId);

        try {
            // 使用 SDK 的 Builder 构建 request
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(modelNameForApi)
                    .maxCompletionTokens(maxTokens)
                    .temperature((double) temperature);

            // Apply reasoning effort for reasoning-capable models
            ReasoningEffort effort = toReasoningEffort(reasoningEffort);
            if (effort != null) {
                paramsBuilder.reasoningEffort(effort);
            }

            // 添加系统提示词
            boolean systemPromptAdded = false;
            for (Message msg : messages) {
                if (msg.getRole() == Message.Role.SYSTEM && msg.getContent() != null && !msg.getContent().isEmpty()) {
                    paramsBuilder.addSystemMessage(msg.getContent());
                    systemPromptAdded = true;
                }
            }

            // 如果没有系统提示词，添加默认系统提示词
            if (!systemPromptAdded && !systemPromptInitialized && systemPrompt != null && !systemPrompt.isEmpty()) {
                paramsBuilder.addSystemMessage(systemPrompt);
                systemPromptInitialized = true;
            }

            // 转换消息格式
            for (Message msg : messages) {
                switch (msg.getRole()) {
                    case SYSTEM -> {
                        // Already handled above, skip
                        continue;
                    }
                    case USER -> {
                        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                            paramsBuilder.addUserMessage(msg.getContent());
                        }
                        break;
                    }
                    case ASSISTANT -> {
                        if (msg.hasToolCalls()) {
                            // Build assistant message with tool calls
                            ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                                    ChatCompletionAssistantMessageParam.builder();

                            // Set content if present
                            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                                assistantBuilder.content(msg.getContent());
                            }

                            // Add tool calls
                            for (ToolCall tc : msg.getToolCalls()) {
                                ChatCompletionMessageFunctionToolCall toolCall =
                                        ChatCompletionMessageFunctionToolCall.builder()
                                                .id(tc.getId())
                                                .function(
                                                        com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall.Function
                                                                .builder()
                                                                .name(tc.getName())
                                                                .arguments(new ObjectMapper().writeValueAsString(tc.getArguments()))
                                                                .build()
                                                )
                                                .build();
                                assistantBuilder.addToolCall(toolCall);
                            }

                            paramsBuilder.addMessage(assistantBuilder.build());
                        } else {
                            // Regular assistant message
                            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                                paramsBuilder.addAssistantMessage(msg.getContent());
                            }
                        }
                        break;
                    }
                    case TOOL -> {
                        // Tool result message
                        ChatCompletionToolMessageParam toolMessage =
                                ChatCompletionToolMessageParam.builder()
                                        .toolCallId(msg.getToolCallId())
                                        .content(msg.getContent() != null ? msg.getContent() : "")
                                        .build();
                        paramsBuilder.addMessage(toolMessage);
                        break;
                    }
                }
            }

            // 添加 tools - 使用 SDK 的 addFunctionTool 方法
            if (tools != null && !tools.isEmpty()) {
                for (ToolDefinition tool : tools) {
                    // Build FunctionParameters from the Map
                    FunctionParameters.Builder functionParamsBuilder = FunctionParameters.builder();
                    if (tool.getParameters() != null) {
                        // Convert Map<String, Object> to JsonValue format
                        Map<String, Object> paramMap = tool.getParameters();
                        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
                            functionParamsBuilder.putAdditionalProperty(entry.getKey(),
                                    convertToJsonValue(entry.getValue()));
                        }
                    }

                    // Build FunctionDefinition
                    FunctionDefinition functionDef = FunctionDefinition.builder()
                            .name(tool.getName())
                            .description(tool.getDescription() != null ? tool.getDescription() : "")
                            .parameters(functionParamsBuilder.build())
                            .build();

                    // Add the tool using SDK method
                    paramsBuilder.addFunctionTool(functionDef);
                }
            }

            // 构建并发送请求
            if (forcedToolName != null) {
                paramsBuilder.toolChoice(
                    com.openai.models.chat.completions.ChatCompletionNamedToolChoice.builder()
                        .type(com.openai.core.JsonValue.from("function"))
                        .function(com.openai.models.chat.completions.ChatCompletionNamedToolChoice.Function.builder()
                            .name(forcedToolName)
                            .build())
                        .build()
                );
            }
            ChatCompletionCreateParams params = paramsBuilder.build();
            log.info("Sending request to OpenAI with {} tools", tools != null ? tools.size() : 0);

            ChatCompletion chatCompletion = client.chat().completions().create(params);
            log.info("Received response from OpenAI");

            // 记录使用量（供 @usage 命令使用）
            try {
                OpenAiUsage.getInstance().recordUsage(chatCompletion, currentModelId);
            } catch (Exception ex) {
                log.error("Failed to record token usage", ex);
            }

            // Extract usage directly from ChatCompletion for LLMResponse
            long extractedPTokens = 0;
            long extractedCTokens = 0;
            try {
                var usageOpt = chatCompletion.usage();
                if (usageOpt != null && usageOpt.isPresent()) {
                    var usage = usageOpt.get();
                    extractedPTokens = usage.promptTokens();
                    extractedCTokens = usage.completionTokens();
                    log.info("Usage from API response: promptTokens={}, completionTokens={}, totalTokens={}",
                            extractedPTokens, extractedCTokens, usage.totalTokens());
                } else {
                    log.debug("No usage data in ChatCompletion response");
                }
            } catch (Exception e) {
                log.warn("Could not extract usage from ChatCompletion: {}", e.getMessage());
            }

            Map<String, Integer> usageMap;
            if (extractedPTokens > 0 || extractedCTokens > 0) {
                usageMap = java.util.Map.of("prompt_tokens", (int) extractedPTokens,
                        "completion_tokens", (int) extractedCTokens);
            } else {
                usageMap = java.util.Map.of();
                log.debug("Usage extraction returned 0/0 for model: {}", currentModelId);
            }

            // 解析响应
            // 检查 choices 是否为 null 或空
            var choices = chatCompletion.choices();
            if (choices == null || choices.isEmpty()) {
                log.warn("API returned null or empty choices");
                try {
                    String rawResponse = chatCompletion.toString();
                    log.error("Raw API response: {}", rawResponse);
                    return LLMResponse.error("API returned null or empty choices. Response: " + rawResponse.substring(0, Math.min(200, rawResponse.length())));
                } catch (Exception e) {
                    log.error("Failed to read raw response", e);
                    return LLMResponse.error("API returned null or empty choices. Check API status and configuration.");
                }
            }

            ChatCompletion.Choice choice = choices.get(0);
            String content = choice.message().content().orElse(null);
            String finishReason = choice.finishReason() != null ? choice.finishReason().toString() : "unknown";

            // 提取 tool calls
            List<ToolCall> toolCalls = new ArrayList<>();
            var toolCallsOpt = choice.message().toolCalls();
            if (toolCallsOpt.isPresent() && !toolCallsOpt.get().isEmpty()) {
                for (var toolCall : toolCallsOpt.get()) {
                    // toolCall.function() returns Optional<ChatCompletionMessageFunctionToolCall>
                    var functionToolCallOpt = toolCall.function();
                    if (functionToolCallOpt.isPresent()) {
                        var functionToolCall = functionToolCallOpt.get();
                        // functionToolCall.function() returns the Function object
                        var function = functionToolCall.function();
                        try {
                            Map<String, Object> arguments = new ObjectMapper().readValue(
                                    function.arguments(),
                                    new TypeReference<Map<String, Object>>() {}
                            );
                            // Get ID from the functionToolCall
                            toolCalls.add(new ToolCall(functionToolCall.id(), function.name(), arguments));
                        } catch (JsonProcessingException e) {
                            log.error("Failed to parse tool arguments for {}", function.name(), e);
                        }
                    }
                }
            }

            // 构建响应
            LLMResponse.Builder responseBuilder = LLMResponse.builder()
                    .content(content)
                    .finishReason(finishReason)
                    .usage(usageMap);

            if (!toolCalls.isEmpty()) {
                responseBuilder.toolCalls(toolCalls);
            }

            return responseBuilder.build();

        } catch (Exception e) {
            log.error("Error in generateResponseWithTools", e);
            return LLMResponse.error("Error calling LLM: " + e.getMessage());
        }
    }

    /**
     * Convert a Java object to a JsonValue for the OpenAI SDK.
     * Simply delegates to JsonValue.from() which handles all conversions.
     */
    private com.openai.core.JsonValue convertToJsonValue(Object value) {
        return com.openai.core.JsonValue.from(value);
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public boolean supportsForcedToolChoice() {
        return true;
    }

    public String getName() {
        return "OpenAI";
    }

    private static ReasoningEffort toReasoningEffort(String effort) {
        if (effort == null || effort.equalsIgnoreCase("none") || effort.equalsIgnoreCase("null")) {
            return null;
        }
        return switch (effort.toLowerCase()) {
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            case "high" -> ReasoningEffort.HIGH;
            default -> ReasoningEffort.MEDIUM;
        };
    }
}
