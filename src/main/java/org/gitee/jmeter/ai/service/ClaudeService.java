package org.gitee.jmeter.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gitee.jmeter.ai.usage.AnthropicUsage;

/**
 * ClaudeService class.
 */
public class ClaudeService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private final int maxHistorySize;
    private String currentModelId;
    private final AnthropicClient client;
    private String systemPrompt;
    private boolean systemPromptInitialized = false;
    private GenerationSettings generationSettings;

    public ClaudeService() {
        this.maxHistorySize = Integer.parseInt(AiConfig.getPropertyWithFallback("claude", "max.history.size", "10"));

        String API_KEY = AiConfig.getProperty("anthropic.api.key", "YOUR_API_KEY");

        String loggingLevel = AiConfig.getProperty("anthropic.log.level", "");
        if (!loggingLevel.isEmpty()) {
            System.setProperty("ANTHROPIC_LOG", loggingLevel);
            log.info("Enabled Anthropic client logging with level: {}", loggingLevel);
        }

        AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder()
                .apiKey(API_KEY);

        String baseUrl = AiConfig.getProperty("anthropic.api.base.url", "");
        if (!baseUrl.isEmpty()) {
            clientBuilder.baseUrl(baseUrl);
            log.info("Using custom Anthropic API base URL: {}", baseUrl);
        }

        this.client = clientBuilder.build();
        this.currentModelId = AiConfig.getDefaultModel();
        this.generationSettings = GenerationSettings.fromConfig();

        this.systemPrompt = SystemPrompt.get();
        log.info("Loaded system prompt (length: {})", systemPrompt.length());
    }

    @Override
    public GenerationSettings getGenerationSettings() {
        return generationSettings;
    }

    @Override
    public void setGenerationSettings(GenerationSettings settings) {
        this.generationSettings = settings;
        log.info("Generation settings updated: {}", settings);
    }

    public AnthropicClient getClient() {
        return client;
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
        log.info("Model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return currentModelId;
    }

    public void setTemperature(float temperature) {
        if (temperature < 0 || temperature >= 1) {
            log.warn("Temperature must be between 0 and 1. Provided value: {}. Setting to default 0.7", temperature);
            generationSettings.setTemperature(0.7);
        } else {
            generationSettings.setTemperature(temperature);
            log.info("Temperature set to: {}", temperature);
        }
    }

    public float getTemperature() {
        return (float) generationSettings.getTemperature();
    }

    public long getMaxTokens() {
        return generationSettings.getMaxTokens();
    }

    public void setMaxTokens(long maxTokens) {
        generationSettings.setMaxTokens((int) maxTokens);
        log.info("Max tokens set to: {}", maxTokens);
    }

    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
        log.info("Reset system prompt initialization flag");
    }

    public String sendMessage(String message) {
        log.info("Sending message to Claude: {}", message);
        return generateResponse(java.util.Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        try {
            log.info("Generating response for conversation with {} messages", conversation.size());

            if (currentModelId == null || currentModelId.isEmpty()) {
                currentModelId = "claude-3-sonnet-20240229";
                log.warn("No model was set, defaulting to: {}", currentModelId);
            }

            double temperature = generationSettings.getTemperature();
            long maxTokens = generationSettings.getMaxTokens();
            String reasoningEffort = generationSettings.getReasoningEffort();

            if (temperature < 0 || temperature > 1) {
                temperature = 0.7;
                log.warn("Invalid temperature value, defaulting to: 0.7");
            }

            log.info("Generating response using model: {} and temperature: {}", currentModelId, temperature);

            boolean isFirstMessage = !systemPromptInitialized;
            if (isFirstMessage) {
                log.info("Using system prompt (first 100 chars): {}",
                        systemPrompt.substring(0, Math.min(100, systemPrompt.length())));
                systemPromptInitialized = true;
            } else {
                log.info("Using previously initialized conversation with system prompt");
            }

            List<String> limitedConversation = conversation;
            if (conversation.size() > maxHistorySize) {
                limitedConversation = conversation.subList(conversation.size() - maxHistorySize, conversation.size());
                log.info("Limiting conversation to last {} messages", limitedConversation.size());
            }

            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .maxTokens(maxTokens)
                    .model(currentModelId);

            int budgetTokens = mapReasoningEffortToBudget(reasoningEffort);
            if (budgetTokens > 0) {
                paramsBuilder.enabledThinking(budgetTokens);
                if (maxTokens < budgetTokens + 1) {
                    paramsBuilder.maxTokens(budgetTokens + 1000);
                }
            } else {
                paramsBuilder.temperature(temperature);
            }

            if (isFirstMessage) {
                paramsBuilder.system(systemPrompt);
                log.info("Including system prompt in request (length: {})", systemPrompt.length());
            } else {
                log.info("Skipping system prompt to save tokens (already sent in previous messages)");
            }

            for (int i = 0; i < limitedConversation.size(); i++) {
                String msg = limitedConversation.get(i);
                if (i % 2 == 0) {
                    paramsBuilder.addUserMessage(msg);
                } else {
                    paramsBuilder.addAssistantMessage(msg);
                }
            }

            MessageCreateParams params = paramsBuilder.build();
            log.info("Request parameters: maxTokens={}, temperature={}, model={}, messagesCount={}",
                    params.maxTokens(), params.temperature(), params.model(),
                    limitedConversation.size());

            Message message = client.messages().create(params);

            log.info(message.content().toString());

            String responseText = String.valueOf(message.content().get(0).text().get().text());

            long inputTokens = 0;
            long outputTokens = 0;
            try {
                var usage = message.usage();
                if (usage != null) {
                    inputTokens = usage.inputTokens();
                    outputTokens = usage.outputTokens();
                }
            } catch (Exception e) {
                log.warn("Could not extract real usage from response, using estimates: {}", e.getMessage());
                inputTokens = estimateTokens(String.join(" ", limitedConversation));
                outputTokens = estimateTokens(responseText);
            }

            try {
                AnthropicUsage.getInstance().recordUsage(
                        message,
                        currentModelId,
                        inputTokens,
                        outputTokens);
                log.info("Recorded token usage: {} input, {} output", inputTokens, outputTokens);
            } catch (Exception e) {
                log.error("Failed to record token usage", e);
            }

            return responseText;
        } catch (Exception e) {
            log.error("Error generating response", e);
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    private int mapReasoningEffortToBudget(String effort) {
        if (effort == null || effort.equalsIgnoreCase("none") || effort.equalsIgnoreCase("null")) {
            return 0;
        }
        return switch (effort.toLowerCase()) {
            case "low" -> 4096;
            case "medium" -> 10000;
            case "high" -> 32000;
            default -> 10000;
        };
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private String extractUserFriendlyErrorMessage(Exception e) {
        String errorMessage = e.getMessage();

        if (errorMessage != null && errorMessage.contains("credit balance is too low")) {
            return "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.";
        }

        if (errorMessage != null && errorMessage.contains("invalid_api_key")) {
            return "Invalid API key. Please check your API key and try again.";
        }

        if (errorMessage != null && errorMessage.contains("rate_limit_exceeded")) {
            return "Rate limit exceeded. Please try again later.";
        }

        if (errorMessage != null && errorMessage.contains("model_not_found")) {
            return "The selected model was not found. Please select a different model.";
        }

        if (errorMessage != null && errorMessage.contains("context_length_exceeded")) {
            return "The conversation is too long. Please start a new conversation.";
        }

        if (errorMessage != null) {
            if (errorMessage.contains("AnthropicError")) {
                int messageStart = errorMessage.indexOf("message=");
                if (messageStart != -1) {
                    int messageEnd = errorMessage.indexOf("}", messageStart);
                    if (messageEnd != -1) {
                        return errorMessage.substring(messageStart + 8, messageEnd);
                    }
                }
            }
        }

        return "An error occurred while communicating with the Anthropic API. Please try again later.";
    }

    public String generateResponse(List<String> conversation, String model) {
        log.info("Generating response with specified model: {}", model);

        String originalModel = this.currentModelId;

        try {
            this.currentModelId = model;
            return generateResponse(conversation);
        } finally {
            this.currentModelId = originalModel;
            log.info("Restored original model: {}", originalModel);
        }
    }

    public String getName() {
        return "Anthropic Claude";
    }

    @Override
    public boolean supportsToolCalling() {
        return currentModelId != null && (
                currentModelId.startsWith("claude-3") ||
                currentModelId.startsWith("claude-sonnet") ||
                currentModelId.startsWith("claude-opus") ||
                currentModelId.startsWith("claude-haiku")
        );
    }

    @Override
    public boolean supportsStreaming() {
        return supportsToolCalling();
    }

    @Override
    public void generateResponseStreaming(List<String> conversation, java.util.function.Consumer<String> chunkConsumer) {
        log.info("Using fallback streaming implementation (non-streaming API)");

        try {
            String response = generateResponse(conversation);
            String[] chunks = response.split("(?<=\\s)|(?<=\\n)");
            for (String chunk : chunks) {
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in streaming response", e);
            chunkConsumer.accept("Error: " + extractUserFriendlyErrorMessage(e));
        }
    }

    @Override
    public LLMResponse generateResponseWithTools(List<org.gitee.jmeter.ai.agent.model.Message> messages, List<ToolDefinition> tools) {
        log.info("Tool calling requested - using fallback to text generation");
        String text = generateResponse(convertToStringList(messages));

        Map<String, Integer> usageMap = java.util.Map.of();
        try {
            long[] tokens = AnthropicUsage.getInstance().getLastRecordedUsage();
            if (tokens[0] > 0 || tokens[1] > 0) {
                usageMap = java.util.Map.of("prompt_tokens", (int) tokens[0], "completion_tokens", (int) tokens[1]);
            }
        } catch (Exception e) {
            log.debug("Could not extract usage from usage tracker", e);
        }

        return LLMResponse.builder()
                .content(text)
                .finishReason("stop")
                .usage(usageMap)
                .build();
    }

    @Override
    public LLMResponse generateResponseWithTools(List<org.gitee.jmeter.ai.agent.model.Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
        GenerationSettings original = this.generationSettings;
        String originalModel = this.currentModelId;

        try {
            if (options != null) {
                this.generationSettings = new GenerationSettings(
                    options.getTemperature() != null ? options.getTemperature() : original.getTemperature(),
                    options.getMaxTokens() != null ? options.getMaxTokens() : original.getMaxTokens(),
                    options.getReasoningEffort() != null ? options.getReasoningEffort() : original.getReasoningEffort()
                );
                if (options.getModel() != null) this.currentModelId = options.getModel();
            }
            return generateResponseWithTools(messages, tools);
        } finally {
            this.generationSettings = original;
            this.currentModelId = originalModel;
        }
    }

    private List<String> convertToStringList(List<org.gitee.jmeter.ai.agent.model.Message> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != org.gitee.jmeter.ai.agent.model.Message.Role.SYSTEM && m.getRole() != org.gitee.jmeter.ai.agent.model.Message.Role.TOOL)
                .map(org.gitee.jmeter.ai.agent.model.Message::getContent)
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }
}
