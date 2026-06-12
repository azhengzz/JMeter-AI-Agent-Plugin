package org.gitee.jmeter.ai.service.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
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

    // Maps thinking_style -> extra_body builder (mirrors Nanobot's _THINKING_STYLE_MAP).
    // Each builder takes a boolean (thinkingEnabled) and returns the dict to merge into extra_body.
    private static final Map<String, java.util.function.Function<Boolean, Map<String, Object>>> THINKING_STYLE_MAP = Map.of(
            "thinking_type", on -> Map.of("thinking", Map.of("type", on ? "enabled" : "disabled")),
            "enable_thinking", on -> Map.of("enable_thinking", on),
            "reasoning_split", on -> Map.of("reasoning_split", on)
    );

    private final String providerName;
    private final OpenAIClient client;
    private final Map<String, Map<String, Object>> modelOverrides;
    private final ProviderSpec spec;
    private final boolean useRawHttpClientOnly;
    private final String apiKey;
    private final String baseUrl;

    private final int maxHistorySize;
    private String currentModelId;
    private String systemPrompt;
    private GenerationSettings generationSettings;
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

        // Initialize configuration with global defaults fallback
        this.maxHistorySize = Integer.parseInt(AiConfig.getPropertyWithFallback(providerName, "max.history.size", "10"));
        this.currentModelId = AiConfig.getDefaultModel();
        this.generationSettings = GenerationSettings.fromConfig();
        // Load system prompt using centralized utility
        this.systemPrompt = SystemPrompt.get();

        log.info("Initialized {} provider with model: {}", providerName, currentModelId);
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
        String modelName = stripProviderPrefix(effectiveModel);
        Map<String, Object> params = buildChatParams(modelName);

        log.info("Generating response for {} with model: {}", providerName, modelName);

        // Create parameters builder
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .maxCompletionTokens((Long) params.getOrDefault("max_tokens", 4096L))
                .temperature((Double) params.getOrDefault("temperature", 0.7))
                .model(modelName);

        // 两种方式表达"深度思考"：SDK 的 reasoningEffort 和原生的 thinking extra_body 参数。
        // 对支持原生 thinking 的模型（如 kimi-k2.5/k2.6），跳过 reasoningEffort，
        // 由后续 THINKING_STYLE_MAP 写入 thinking 参数，避免 Moonshot API 因两参数冲突而报错。
        ReasoningEffort effort = toReasoningEffort(generationSettings.getReasoningEffort());
        boolean modelSupportsThinking = spec != null && spec.supportsThinking(modelName);
        if (effort != null && !modelSupportsThinking) {
            paramsBuilder.reasoningEffort(effort);
        }

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
        log.info("[{}] Request params: {}", providerName, summarizeParams(requestParams));
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

            String modelName = stripProviderPrefix(model);

            // Build the request body
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("temperature", generationSettings.getTemperature());
            requestBody.put("max_tokens", generationSettings.getMaxTokens());

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
            log.info("Raw HTTP request to {} with model: {}, body length: {}", baseUrl, modelName, jsonBody.length());

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
        return doGenerateWithTools(messages, tools, null, null);
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
        return doGenerateWithTools(messages, tools, null, options);
    }

    @Override
    public LLMResponse generateResponseWithForcedTool(List<Message> messages, List<ToolDefinition> tools, String forcedToolName) {
        log.info("Forced tool calling for {}: {}", providerName, forcedToolName);
        try {
            LLMResponse response = doGenerateWithTools(messages, tools, forcedToolName, null);
            if (response.isError() && isToolChoiceUnsupported(response.getErrorMessage())) {
                log.warn("Forced tool_choice unsupported by {}", providerName);
                return response;
            }
            return response;
        } catch (Exception e) {
            if (isToolChoiceUnsupported(e)) {
                log.warn("Forced tool_choice unsupported by {}", providerName);
                return LLMResponse.error(e.getMessage());
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

    /**
     * Check if an exception was caused by thread interruption (e.g. OkHttp InterruptedIOException).
     */
    private boolean isCausedByInterrupt(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.io.InterruptedIOException
                    || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isToolChoiceUnsupported(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("tool_choice") || lower.contains("does not support")
                || lower.contains("should be [\"none\", \"auto\"]");
    }

    /**
     * Core implementation: generates response with optional forced tool choice.
     * @param forcedToolName if non-null, forces the LLM to call this specific tool
     */
    private LLMResponse doGenerateWithTools(List<Message> messages, List<ToolDefinition> tools, String forcedToolName, LlmCallOptions options) {
        log.info("Generating response for {}: {} tools, forcedTool={}", providerName,
                tools != null ? tools.size() : 0, forcedToolName);

        // Resolve per-call overrides (fallback to generation settings when null)
        String effectiveModel = (options != null && options.getModel() != null) ? options.getModel() : this.currentModelId;
        double effectiveTemperature = (options != null && options.getTemperature() != null) ? options.getTemperature() : this.generationSettings.getTemperature();
        long effectiveMaxTokens = (options != null && options.getMaxTokens() != null) ? options.getMaxTokens().longValue() : this.generationSettings.getMaxTokens();
        String effectiveReasoningEffort = (options != null && options.getReasoningEffort() != null) ? options.getReasoningEffort() : this.generationSettings.getReasoningEffort();

        String modelName = stripProviderPrefix(effectiveModel);
        boolean modelSupportsThinking = spec != null && spec.supportsThinking(modelName);

        // Apply model-specific overrides (e.g. kimi-k2.5/k2.6 require temperature=1.0)
        Map<String, Object> overrides = modelOverrides.get(modelName);
        if (overrides != null) {
            Object tempOverride = overrides.get("temperature");
            if (tempOverride instanceof Number) {
                effectiveTemperature = ((Number) tempOverride).doubleValue();
            }
        }

        try {
            // 使用 SDK 的 Builder 构建 request
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(modelName)
                    .maxCompletionTokens(effectiveMaxTokens)
                    .temperature(effectiveTemperature);

            // Apply reasoning effort — skip for thinking models that use native thinking param
            // (Moonshot rejects requests with both reasoning_effort and native thinking param)
            ReasoningEffort effort = toReasoningEffort(effectiveReasoningEffort);
            if (effort != null && !modelSupportsThinking) {
                paramsBuilder.reasoningEffort(effort);
            }

            // Determine if thinking mode is active (mirrors Nanobot's thinking_active logic).
            // Only true when the provider has a thinking_style AND model supports it AND reasoning_effort is not none/minimal.
            String semanticEffort = toSemanticEffort(effectiveReasoningEffort);
            boolean thinkingActive = spec != null
                    && spec.getThinkingStyle() != null && !spec.getThinkingStyle().isEmpty()
                    && modelSupportsThinking
                    && effectiveReasoningEffort != null
                    && semanticEffort != null
                    && !"none".equals(semanticEffort) && !"minimal".equals(semanticEffort);

            // Provider-specific thinking parameters (mirrors Nanobot's _THINKING_STYLE_MAP).
            // Only sent when reasoning_effort is explicitly configured so that
            // the provider default is preserved otherwise.
            if (spec != null && spec.getThinkingStyle() != null && !spec.getThinkingStyle().isEmpty()
                    && effectiveReasoningEffort != null && modelSupportsThinking) {
                boolean thinkingEnabled = !"none".equals(semanticEffort) && !"minimal".equals(semanticEffort);
                java.util.function.Function<Boolean, Map<String, Object>> styleBuilder =
                        THINKING_STYLE_MAP.get(spec.getThinkingStyle());
                if (styleBuilder != null) {
                    Map<String, Object> extra = styleBuilder.apply(thinkingEnabled);
                    if (extra != null && !extra.isEmpty()) {
                        for (Map.Entry<String, Object> entry : extra.entrySet()) {
                            paramsBuilder.putAdditionalBodyProperty(entry.getKey(),
                                    com.openai.core.JsonValue.from(entry.getValue()));
                        }
                    }
                }
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
                        // Build assistant message param (unified for both tool-call and text cases)
                        ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                                ChatCompletionAssistantMessageParam.builder();

                        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                            assistantBuilder.content(msg.getContent());
                        }

                        // Pass back reasoning_content for thinking-mode providers (e.g., DeepSeek).
                        // If the message already has reasoning_content from a previous turn, pass it
                        // back intact. Otherwise, when thinking is active, backfill an empty string
                        // to satisfy providers that require the field on all assistant messages.
                        if (msg.hasReasoningContent()) {
                            assistantBuilder.putAdditionalProperty("reasoning_content",
                                    com.openai.core.JsonValue.from(msg.getReasoningContent()));
                        } else if (thinkingActive) {
                            assistantBuilder.putAdditionalProperty("reasoning_content",
                                    com.openai.core.JsonValue.from(""));
                        }

                        if (msg.hasToolCalls()) {
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
                        }

                        paramsBuilder.addMessage(assistantBuilder.build());
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
            log.info("[{}] Request params: {}, thinkingActive={}, thinkingStyle={}",
                    providerName, summarizeParams(params), thinkingActive,
                    spec != null ? spec.getThinkingStyle() : "none");

            ChatCompletion chatCompletion = client.chat().completions().create(params);
            log.info("Received response from {}, chatCompletion object type: {}",
                    providerName, chatCompletion.getClass().getName());

            // Extract usage directly from ChatCompletion for LLMResponse
            long extractedPTokens = 0;
            long extractedCTokens = 0;
            try {
                var usageOpt = chatCompletion.usage();
                if (usageOpt != null && usageOpt.isPresent()) {
                    var usage = usageOpt.get();
                    extractedPTokens = usage.promptTokens();
                    extractedCTokens = usage.completionTokens();
                    log.info("Usage from {} API: promptTokens={}, completionTokens={}, totalTokens={}",
                            providerName, extractedPTokens, extractedCTokens, usage.totalTokens());
                } else {
                    log.debug("No usage data in {} ChatCompletion response", providerName);
                }
            } catch (Exception e) {
                log.warn("Could not extract usage from {} ChatCompletion: {}", providerName, e.getMessage());
            }

            java.util.Map<String, Integer> usageMap;
            if (extractedPTokens > 0 || extractedCTokens > 0) {
                usageMap = java.util.Map.of("prompt_tokens", (int) extractedPTokens,
                        "completion_tokens", (int) extractedCTokens);
            } else {
                usageMap = java.util.Map.of();
                log.debug("Usage extraction returned 0/0 for provider: {}", providerName);
            }

            // 解析响应
            // 检查 choices 是否为 null 或空 - 需要捕获异常因为 openai-java SDK 的 choices() 方法在字段为 null 时会抛出异常
            List<ChatCompletion.Choice> choices;
            try {
                choices = chatCompletion.choices();
            } catch (com.openai.errors.OpenAIInvalidDataException e) {
                log.error("API returned null choices field for {}. This usually indicates an API error or invalid response.", providerName, e);
                // 尝试读取原始响应以获取更多信息
                try {
                    String rawResponse = chatCompletion.toString();
                    log.error("Raw API response from {}: {}", providerName, rawResponse);
                    return LLMResponse.error("API returned invalid response (null choices). Response: " +
                            rawResponse.substring(0, Math.min(200, rawResponse.length())) +
                            ". Check API key, quota, or service status.");
                } catch (Exception ex) {
                    log.error("Failed to read raw response", ex);
                    return LLMResponse.error("API returned invalid response (null choices). Check API key, quota, or service status.");
                }
            }

            if (choices.isEmpty()) {
                log.warn("API returned empty choices for {}", providerName);
                return LLMResponse.error("API returned empty choices. Check API status and configuration.");
            }

            ChatCompletion.Choice choice = choices.get(0);
            String content = choice.message().content().orElse(null);
            String finishReason = choice.finishReason() != null ? choice.finishReason().toString() : "unknown";

            // Extract reasoning_content from additional properties (DeepSeek reasoning models)
            String reasoningContent = null;
            Map<String, com.openai.core.JsonValue> additionalProps = choice.message()._additionalProperties();
            if (additionalProps != null && additionalProps.containsKey("reasoning_content")) {
                com.openai.core.JsonValue reasoningValue = additionalProps.get("reasoning_content");
                if (reasoningValue != null) {
                    try {
                        reasoningContent = reasoningValue.convert(String.class);
                        log.debug("Extracted reasoning_content from {} response (length: {})", providerName, reasoningContent.length());
                    } catch (Exception e) {
                        log.debug("Could not convert reasoning_content to String: {}", e.getMessage());
                    }
                }
            }

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
                    .reasoningContent(reasoningContent)
                    .usage(usageMap);

            if (!toolCalls.isEmpty()) {
                responseBuilder.toolCalls(toolCalls);
            }

            return responseBuilder.build();

        } catch (Exception e) {
            // Thread interrupt can surface as InterruptedException or wrapped in OkHttp's InterruptedIOException
            if (isCausedByInterrupt(e)) {
                Thread.currentThread().interrupt();
                log.info("LLM request interrupted for {} (agent stopped)", providerName);
                return LLMResponse.error("Interrupted");
            }
            // tool_choice unsupported is expected for some models (e.g. deepseek-reasoner), log as WARN
            if (isToolChoiceUnsupported(e)) {
                log.warn("tool_choice unsupported by {}: {}", providerName, e.getMessage());
            } else {
                log.error("Error in generateResponseWithTools for {}", providerName, e);
            }
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

    @Override
    public GenerationSettings getGenerationSettings() {
        return generationSettings;
    }

    @Override
    public void setGenerationSettings(GenerationSettings settings) {
        this.generationSettings = settings;
        log.info("Generation settings updated for {}: {}", providerName, settings);
    }

    public void setTemperature(float temperature) {
        generationSettings.setTemperature(temperature);
        log.info("Temperature set to: {}", temperature);
    }

    public float getTemperature() {
        return (float) generationSettings.getTemperature();
    }

    public void setMaxTokens(long maxTokens) {
        generationSettings.setMaxTokens((int) maxTokens);
    }

    public long getMaxTokens() {
        return generationSettings.getMaxTokens();
    }

    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
    }

    /**
     * Build a log-friendly summary of request params (excludes messages).
     */
    /**
     * 通过反射遍历请求参数对象的所有 getter 方法，自动输出所有字段值（排除 messages）。
     */
    private String summarizeParams(ChatCompletionCreateParams p) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("model", String.valueOf(p.model()));
        m.put("temperature", opt(p.temperature()));
        m.put("maxCompletionTokens", opt(p.maxCompletionTokens()));
        m.put("topP", opt(p.topP()));
        m.put("frequencyPenalty", opt(p.frequencyPenalty()));
        m.put("presencePenalty", opt(p.presencePenalty()));
        m.put("reasoningEffort", opt(p.reasoningEffort()));
        m.put("n", opt(p.n()));
        m.put("seed", opt(p.seed()));
        m.put("stop", opt(p.stop()));
        m.put("toolChoice", opt(p.toolChoice()));
        m.put("parallelToolCalls", opt(p.parallelToolCalls()));
        m.put("responseFormat", opt(p.responseFormat()));
        m.put("serviceTier", opt(p.serviceTier()));
        m.put("user", opt(p.user()));
        m.put("store", opt(p.store()));
        m.put("logprobs", opt(p.logprobs()));
        m.put("topLogprobs", opt(p.topLogprobs()));
        p.tools().ifPresentOrElse(v -> m.put("tools", v.size() + " items"), () -> m.put("tools", ""));
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(", ");
            sb.append(k).append("=").append(v);
        });
        sb.append("}");
        return sb.toString();
    }

    private String opt(java.util.Optional<?> o) {
        return o.map(Object::toString).orElse("");
    }

    // Private helper methods

    private Map<String, Object> buildChatParams(String model) {
        Map<String, Object> params = new HashMap<>();
        params.put("temperature", generationSettings.getTemperature());
        params.put("max_tokens", (long) generationSettings.getMaxTokens());

        // Apply model-specific overrides
        Map<String, Object> overrides = modelOverrides.get(model);
        if (overrides != null) {
            log.info("Applying model overrides for {}: {}", model, overrides);
            params.putAll(overrides);
        }

        return params;
    }

    /**
     * Strip the provider prefix from a model ID.
     * e.g., "minimax:MiniMax-M2.7" -> "MiniMax-M2.7"
     */
    private String stripProviderPrefix(String modelId) {
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            if (parts.length == 2) {
                return parts[1];
            }
        }
        return modelId;
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

    /** Normalize reasoning_effort into a semantic form (mirrors Nanobot). */
    private static String toSemanticEffort(String effort) {
        if (effort == null) return null;
        String normalized = effort.toLowerCase();
        if ("minimum".equals(normalized)) normalized = "minimal";
        return normalized;
    }
}
