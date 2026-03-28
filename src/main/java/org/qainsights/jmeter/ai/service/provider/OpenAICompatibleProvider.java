package org.qainsights.jmeter.ai.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.qainsights.jmeter.ai.agent.model.LLMResponse;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified OpenAI-compatible provider for all Chinese LLM providers.
 * Uses the openai-java SDK with custom base URLs.
 */
public class OpenAICompatibleProvider implements AiService {
    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleProvider.class);

    private final String providerName;
    private final OpenAIClient client;
    private final Map<String, Map<String, Object>> modelOverrides;
    private final ProviderSpec spec;
    private final boolean useRawHttpClientOnly;
    private final String apiKey;
    private final String baseUrl;

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;
    private boolean systemPromptInitialized = false;

    public OpenAICompatibleProvider(ProviderSpec spec) {
        this.providerName = spec.getName();
        this.spec = spec;
        this.modelOverrides = spec.getModelOverrides();
        this.useRawHttpClientOnly = spec.isRawHttpClientOnly();

        // Get API key from properties
        this.apiKey = AiConfig.getProperty(spec.getEnvKey(), "");
        if (apiKey.isEmpty()) {
            log.warn("No API key configured for provider: {}", spec.getEnvKey());
        }

        // Build the client with provider-specific base URL
        this.baseUrl = AiConfig.getProperty(spec.getName() + ".api.base.url", spec.getDefaultApiBase());
        log.info("Creating OpenAI-compatible provider: {} with base URL: {} (raw HTTP only: {})",
                providerName, baseUrl, useRawHttpClientOnly);

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.isEmpty() ? "no-key" : apiKey);

        if (!baseUrl.isEmpty()) {
            clientBuilder.baseUrl(baseUrl);
        }

        this.client = clientBuilder.build();

        // Initialize configuration
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty(providerName + ".max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty(providerName + ".default.model", getDefaultModelForProvider(providerName));
        this.temperature = Float.parseFloat(AiConfig.getProperty(providerName + ".temperature", "0.7"));
        // Load system prompt using centralized utility
        this.systemPrompt = SystemPrompt.get();
        this.maxTokens = Long.parseLong(AiConfig.getProperty(providerName + ".max.tokens", "4096"));

        log.info("Initialized {} provider with model: {}", providerName, currentModelId);
    }

    private static String getDefaultModelForProvider(String providerName) {
        return switch (providerName) {
            case "deepseek" -> "deepseek-chat";
            case "zhipu" -> "glm-4-plus";
            case "moonshot" -> "kimi-latest";
            case "minimax" -> "abab6.5s-chat";
            default -> "gpt-4o";
        };
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, null);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        String effectiveModel = model != null ? model : currentModelId;

        // Check if this provider requires raw HTTP client (for API compatibility issues)
        if (useRawHttpClientOnly) {
            log.info("Using raw HTTP client for {} (incompatible API response format)", providerName);
            return makeRawHttpRequest(conversation, effectiveModel);
        }

        // Use OpenAI SDK for compatible providers
        try {
            return makeSdkRequest(conversation, effectiveModel);
        } catch (Exception e) {
            log.error("Error generating response from {}", providerName, e);
            return "Error: " + extractErrorMessage(e);
        }
    }

    /**
     * Make request using OpenAI SDK (for compatible providers).
     */
    private String makeSdkRequest(List<String> conversation, String model) {
        String effectiveModel = model != null ? model : currentModelId;
        Map<String, Object> params = buildChatParams(effectiveModel);

        log.info("Generating response for {} with model: {}", providerName, effectiveModel);

        // Create parameters builder
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .maxCompletionTokens((Long) params.getOrDefault("max_tokens", 4096L))
                .temperature((Double) params.getOrDefault("temperature", 0.7))
                .model(effectiveModel);

        // Add system prompt
        if (!systemPromptInitialized) {
            paramsBuilder.addSystemMessage(systemPrompt);
            systemPromptInitialized = true;
        }

        // Process conversation history
        List<String> limitedHistory = limitConversation(conversation);
        for (int i = 0; i < limitedHistory.size(); i++) {
            String msg = limitedHistory.get(i);
            if (msg == null || msg.isEmpty()) continue;

            if (i % 2 == 0) {
                paramsBuilder.addUserMessage(msg);
            } else {
                // Assistant messages (odd indices: 1, 3, 5...)
                // Use addAssistantMessage(String) method from OpenAI SDK 4.x
                paramsBuilder.addAssistantMessage(msg);
            }
        }

        // Create completion
        ChatCompletionCreateParams requestParams = paramsBuilder.build();
        ChatCompletion chatCompletion = client.chat().completions().create(requestParams);

        // Extract response content
        return chatCompletion.choices().get(0).message().content().orElse("No content available");
    }

    /**
     * Make request using raw HTTP client (for incompatible providers).
     * This handles providers like MiniMax that return extra fields not recognized by OpenAI SDK.
     */
    private String makeRawHttpRequest(List<String> conversation, String model) {
        try {
            if (apiKey.isEmpty()) {
                return "Error: No API key configured for " + providerName;
            }

            // Build the request body
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);

            // Build messages array
            List<Map<String, String>> messages = new ArrayList<>();

            // Add system prompt
            if (!systemPromptInitialized) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
                systemPromptInitialized = true;
            }

            // Add conversation
            List<String> limitedHistory = limitConversation(conversation);
            for (int i = 0; i < limitedHistory.size(); i++) {
                String msg = limitedHistory.get(i);
                if (msg == null || msg.isEmpty()) continue;

                if (i % 2 == 0) {
                    messages.add(Map.of("role", "user", "content", msg));
                } else {
                    messages.add(Map.of("role", "assistant", "content", msg));
                }
            }

            requestBody.put("messages", messages);

            String jsonBody = mapper.writeValueAsString(requestBody);
            log.info("Raw HTTP request to {} with model: {}, body length: {}", baseUrl, model, jsonBody.length());

            // Create HTTP client
            HttpClient httpClient = HttpClient.newHttpClient();

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            HttpRequest httpRequest = requestBuilder.build();

            // Send request
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Raw HTTP request failed with status {}: {}", response.statusCode(), response.body());
                return "Error: API returned status " + response.statusCode() + " - " + response.body();
            }

            // Parse response, ignoring unknown fields
            return parseResponseIgnoringUnknownFields(response.body());

        } catch (Exception e) {
            log.error("Raw HTTP request failed", e);
            return "Error: Request failed - " + e.getMessage();
        }
    }

    /**
     * Parse JSON response, ignoring unknown fields.
     */
    private String parseResponseIgnoringUnknownFields(String jsonResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            // Configure to ignore unknown properties
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            JsonNode root = mapper.readTree(jsonResponse);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null) {
                        return content.asText();
                    }
                }
            }

            return "Error: Could not extract content from response";
        } catch (Exception e) {
            log.error("Failed to parse response JSON", e);
            return "Error: Failed to parse response - " + e.getMessage();
        }
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
        // TODO: Implement full tool calling support with openai-java SDK
        // For now, convert to simple text-based response
        log.info("Tool calling requested for {} - using fallback to text generation", providerName);
        List<String> conversation = convertToStringList(messages);
        String response = generateResponse(conversation);
        return LLMResponse.text(response);
    }

    @Override
    public LLMResponse generateResponseWithForcedTool(List<Message> messages, List<ToolDefinition> tools, String forcedToolName) {
        // TODO: Implement forced tool calling
        log.info("Forced tool calling requested for {} - using fallback to text generation", providerName);
        List<String> conversation = convertToStringList(messages);
        String response = generateResponse(conversation);
        return LLMResponse.text(response);
    }

    @Override
    public boolean supportsToolCalling() {
        // Most OpenAI-compatible providers support tool calling
        // TODO: Return true once implemented
        return false;
    }

    @Override
    public boolean supportsForcedToolChoice() {
        // TODO: Return true for providers that support it
        return false;
    }

    @Override
    public String getName() {
        return providerName;
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
        log.info("Model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return currentModelId;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
        log.info("Temperature set to: {}", temperature);
    }

    public float getTemperature() {
        return temperature;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
    }

    // Private helper methods

    private Map<String, Object> buildChatParams(String model) {
        Map<String, Object> params = new HashMap<>();
        params.put("temperature", (double) temperature);
        params.put("max_tokens", maxTokens);

        // Apply model-specific overrides
        Map<String, Object> overrides = modelOverrides.get(model);
        if (overrides != null) {
            log.info("Applying model overrides for {}: {}", model, overrides);
            params.putAll(overrides);
        }

        return params;
    }

    private List<String> limitConversation(List<String> conversation) {
        if (conversation.size() <= maxHistorySize) {
            return new ArrayList<>(conversation);
        }
        return conversation.subList(conversation.size() - maxHistorySize, conversation.size());
    }

    /**
     * Convert Message list to String list for legacy API
     */
    private List<String> convertToStringList(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != Message.Role.SYSTEM && m.getRole() != Message.Role.TOOL)
                .map(Message::getContent)
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }

    private String extractErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "Unknown error from " + providerName;
        }

        // Extract user-friendly error messages
        if (message.contains("insufficient_quota") || message.contains("balance")) {
            return "Credit balance is too low. Please check your billing information.";
        }
        if (message.contains("invalid_api_key") || message.contains("authentication")) {
            return "Invalid API key. Please check your API key.";
        }
        if (message.contains("rate_limit") || message.contains("too many requests")) {
            return "Rate limit exceeded. Please try again later.";
        }
        if (message.contains("model_not_found")) {
            return "The selected model was not found.";
        }
        if (message.contains("context_length")) {
            return "The conversation is too long. Please start a new conversation.";
        }

        // Return a cleaned up version of the error message
        return message.split("\\n")[0];
    }
}
