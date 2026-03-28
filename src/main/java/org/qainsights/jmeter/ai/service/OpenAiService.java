package org.qainsights.jmeter.ai.service;

import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.SystemPrompt;
import org.qainsights.jmeter.ai.usage.OpenAiUsage;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private OpenAIClient client;
    private boolean systemPromptInitialized = false;

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;

    // Provider prefixes that use OpenAI-compatible API
    private static final String[] OPENAI_COMPATIBLE_PROVIDERS = {
        "openai", "deepseek", "zhipu", "moonshot", "minimax"
    };

    public OpenAiService() {
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("openai.max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty("openai.default.model", "gpt-4o");
        this.temperature = Float.parseFloat(AiConfig.getProperty("openai.temperature", "0.7"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("openai.max.tokens", "4096"));

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

    public String getName() {
        return "OpenAI";
    }
}
