package org.gitee.jmeter.ai.service.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ReasoningEffort;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.StreamCloseRegistry;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.gitee.jmeter.ai.utils.VersionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Unified OpenAI-compatible provider for all Chinese LLM providers.
 * Uses the openai-java SDK with custom base URLs.
 */
public class OpenAICompatibleProvider implements AiService {
    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleProvider.class);

    // Maps thinking_style -> extra_body builder (mirrors Nanobot's _THINKING_STYLE_MAP).
    // Each builder takes (modelName, thinkingEnabled) and returns the dict to merge into extra_body.
    // Most styles ignore the model name; "minimax_thinking" uses it to pick M3 (adaptive) vs M2.x (enabled).
    private static final Map<String, java.util.function.BiFunction<String, Boolean, Map<String, Object>>> THINKING_STYLE_MAP = Map.of(
            "thinking_type", (model, on) -> Map.of("thinking", Map.of("type", on ? "enabled" : "disabled")),
            "enable_thinking", (model, on) -> Map.of("enable_thinking", on),
            "minimax_thinking", (model, on) -> buildMinimaxThinkingExtraBody(model, on)
    );

    /**
     * MiniMax thinking extra_body. The on/off toggle is {@code thinking.type} (NOT
     * {@code reasoning_split}, which is only an output-format toggle). The "on" value is
     * model-family dependent: M3 accepts only {@code adaptive} (sending {@code enabled} → HTTP 400);
     * M2.x accepts {@code enabled} (its {@code disabled} is silently ignored by the server — a known
     * limitation). When thinking is on, {@code reasoning_split:true} is also sent so reasoning routes
     * to {@code reasoning_content} (consumed by the display pipeline) instead of inline {@code <think>}
     * tags polluting {@code content}.
     *
     * @see <a href="https://platform.minimaxi.com/docs/api-reference/text-openai-api#thinking-控制">MiniMax thinking 控制</a>
     */
    static Map<String, Object> buildMinimaxThinkingExtraBody(String model, boolean on) {
        if (on) {
            String onType = isM3Family(model) ? "adaptive" : "enabled";
            return Map.of(
                    "thinking", Map.of("type", onType),
                    "reasoning_split", true);
        }
        return Map.of("thinking", Map.of("type", "disabled"));
    }

    /**
     * Whether a MiniMax model is in the M3 family, which requires {@code thinking.type=adaptive}
     * to enable thinking (it rejects {@code enabled} with HTTP 400). Detection is by substring on
     * the lowercased model id (e.g. "MiniMax-M3", "minimax:MiniMax-M3-Pro"). Substring (not prefix)
     * so renamed M3 ids on third-party aggregators (e.g. "acme-minimax-m3-pro") still match, and a
     * provider prefix does not affect the match (it cannot compose "minimax-m3" across the colon).
     */
    static boolean isM3Family(String model) {
        if (model == null) return false;
        return model.toLowerCase().contains("minimax-m3");
    }

    private final String providerName;
    private final OpenAIClient client;
    private final Map<String, Map<String, Object>> modelOverrides;
    private final ProviderSpec spec;
    private final String apiKey;
    private final String baseUrl;

    private String currentModelId;
    private String systemPrompt;
    private GenerationSettings generationSettings;
    private boolean systemPromptInitialized = false;

    public OpenAICompatibleProvider(ProviderSpec spec) {
        this.providerName = spec.getName();
        this.spec = spec;
        this.modelOverrides = spec.getModelOverrides();

        // Get API key from properties
        this.apiKey = AiConfig.getProperty(spec.getEnvKey(), "");
        if (apiKey.isEmpty()) {
            log.warn("No API key configured for provider: {}", spec.getEnvKey());
        }

        // Build the client with provider-specific base URL
        this.baseUrl = AiConfig.getProperty(spec.getName() + ".api.base.url", spec.getDefaultApiBase());
        log.info("Creating OpenAI-compatible provider: {} with base URL: {}",
                providerName, baseUrl);

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey.isEmpty() ? "no-key" : apiKey)
                .putHeader("User-Agent", VersionUtils.userAgent());

        if (!baseUrl.isEmpty()) {
            clientBuilder.baseUrl(baseUrl);
        }

        this.client = clientBuilder.build();

        this.currentModelId = AiConfig.getDefaultModel();
        this.generationSettings = GenerationSettings.fromConfig();
        // Load system prompt using centralized utility
        this.systemPrompt = SystemPrompt.get();

        log.info("Initialized {} provider with model: {}", providerName, currentModelId);
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
    private static boolean isCausedByInterrupt(Throwable e) {
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

            // Provider-wide thinking style (injected into extra_body for models like K2.6/K2.7).
            String effectiveStyle = spec != null ? spec.getThinkingStyle() : "";
            boolean alwaysOn = spec != null && spec.isThinkingAlwaysOn(modelName);

            // Apply reasoning effort via the SDK enum (includes MAX). Skip for models without
            // thinking support. Values a model doesn't accept (e.g. K3/GLM-5.3 with
            // minimal/medium/xhigh) pass through unchanged — the provider rejects them,
            // surfacing the misconfiguration rather than silently clamping it. Always-on
            // models only force thinking.type=enabled below; a configured "none" simply omits
            // reasoning_effort and lands on the server default (deepest, e.g. GLM-5.3 max) —
            // the README documents "none" as unsupported for these models.
            ReasoningEffort effort = toReasoningEffort(effectiveReasoningEffort);
            if (effort != null && modelSupportsThinking) {
                paramsBuilder.reasoningEffort(effort);
            }

            // Determine if thinking mode is active (mirrors Nanobot's thinking_active logic).
            // Always-on models (e.g. kimi-k2.7-code, kimi-k3) force this true so reasoning_content
            // backfill triggers correctly for multi-turn tool calls.
            boolean thinkingActive = alwaysOn || (spec != null
                    && !effectiveStyle.isEmpty()
                    && modelSupportsThinking
                    && effectiveReasoningEffort != null
                    && !"none".equalsIgnoreCase(effectiveReasoningEffort));

            // Provider-specific thinking extra_body (mirrors Nanobot's _THINKING_STYLE_MAP).
            // Only sent when reasoning_effort is explicitly configured, so the provider default
            // is preserved otherwise.
            if (spec != null && !effectiveStyle.isEmpty()
                    && effectiveReasoningEffort != null && modelSupportsThinking) {
                // Always-on models cannot receive disabled (API rejects); force enabled.
                boolean thinkingEnabled = alwaysOn || !"none".equalsIgnoreCase(effectiveReasoningEffort);
                java.util.function.BiFunction<String, Boolean, Map<String, Object>> styleBuilder =
                        THINKING_STYLE_MAP.get(effectiveStyle);
                if (styleBuilder != null) {
                    Map<String, Object> extra = styleBuilder.apply(modelName, thinkingEnabled);
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
            // 流式分流：仅当携带 delta 消费者（唯一流式闸门）且非强制工具调用时走流式路径；
            // 记忆整合器的强制工具调用与无 options 的直连调用天然保持非流式。
            if (forcedToolName == null && options != null && options.wantsStreaming()) {
                LLMResponse streamed = doGenerateStreaming(paramsBuilder, options);
                if (streamed != null) {
                    return streamed;
                }
                // null = 静默回退信号（零增量、非中止、非定性客户端错误的失败，
                // 如网关不支持流式/5xx/网络抖动）：落穿到非流式路径重发完整请求
                // ——不把完整结果切片伪装成流式，回退请求不得携带 stream_options。
                // 401/403/404/429 等定性错误不回退（见 silentFallbackOrClassify）
                paramsBuilder.streamOptions(java.util.Optional.empty());
                log.warn("[{}] streaming failed before any delta was delivered; "
                        + "silently falling back to non-streaming", providerName);
            }

            ChatCompletionCreateParams params = paramsBuilder.build();
            log.info("[{}] Request params: {}, thinkingActive={}, thinkingStyle={}",
                    providerName, summarizeParams(params), thinkingActive, effectiveStyle);

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
                            log.error("Failed to parse tool arguments for {}: rawArguments=[{}]", function.name(), function.arguments(), e);
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
            // 与流式路径同构的错误分类（429/5xx/网络/超时可重试、4xx 按类别不可
            // 重试）：流式静默回退落穿到这里的失败不得丢失可重试分类，否则一次
            // 可恢复的 500 经回退后进不了外层重试预算
            return classifyStreamingError(e);
        }
    }

    // ---- 流式路径 ----

    /** 流式专用 client：SDK 内部自动重试禁用（maxRetries 0），重试预算由 AgentRunner 外层环独控。 */
    private volatile OpenAIClient streamingClient;

    private OpenAIClient streamingClient() {
        OpenAIClient local = streamingClient;
        if (local == null) {
            synchronized (this) {
                if (streamingClient == null) {
                    OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                            .apiKey(apiKey.isEmpty() ? "no-key" : apiKey)
                            .putHeader("User-Agent", VersionUtils.userAgent())
                            .maxRetries(0);
                    if (!baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl);
                    }
                    streamingClient = builder.build();
                }
                local = streamingClient;
            }
        }
        return local;
    }

    /** 中止标记：abort 谓词在 chunk 之间命中时跳出流读取（区别于线程中断）。 */
    static final class StreamingAbortException extends RuntimeException {
        StreamingAbortException() { super("aborted between chunks"); }
    }

    /** 流式聚合结果（provider 内部累积，与事件通道增量同源）。 */
    static final class StreamAggregation {
        String content;
        String reasoning;
        String finishReason;
        final TreeMap<Integer, StreamingToolCall> toolBuffers = new TreeMap<>();
        Map<String, Integer> usage = Map.of();

        boolean sawFinishReason() { return finishReason != null; }
    }

    /** 按 index 拼合的工具调用分片缓冲（Nanobot tc_bufs 同构）。 */
    static final class StreamingToolCall {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    /**
     * 流式调用：先带 {@code stream_options.include_usage} 请求（stream:true 由 SDK 的
     * createStreaming 注入）；网关因此 400 时去掉该选项重试一次（保持流式）。
     * <b>零增量失败静默回退</b>：尚未向消费者交付任何 delta 且非中止/中断类失败时
     * 返回 {@code null}——调用方落穿非流式路径重发（支持流式与否的协商失败不
     * 应让整个调用失败，也不得把完整结果伪装成流式）。
     */
    private LLMResponse doGenerateStreaming(ChatCompletionCreateParams.Builder paramsBuilder, LlmCallOptions options) {
        boolean[] emittedAnyDelta = {false};
        LlmCallOptions tracked = trackDeltaEmission(options, emittedAnyDelta);
        try {
            LLMResponse response = drainStreaming(paramsBuilder
                    .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                    .build(), tracked);
            // 非异常失败（如提前 EOF 判可重试）：零增量同样具备静默回退资格
            if (response.isError()) {
                return silentFallbackOrClassifyResponse(response, emittedAnyDelta[0]);
            }
            return response;
        } catch (Exception e) {
            if (isStreamOptionsRejected(e)) {
                log.warn("[{}] gateway rejected stream_options/include_usage ({}); retrying streaming without it",
                        providerName, e.getMessage());
                try {
                    return drainStreaming(paramsBuilder
                            .streamOptions(java.util.Optional.empty())
                            .build(), tracked);
                } catch (Exception retry) {
                    return silentFallbackOrClassify(retry, emittedAnyDelta[0]);
                }
            }
            return silentFallbackOrClassify(e, emittedAnyDelta[0]);
        }
    }

    /** 包装消费者记录"是否交付过任何 delta"（回退资格判定）。 */
    private static LlmCallOptions trackDeltaEmission(LlmCallOptions options, boolean[] emittedAnyDelta) {
        org.gitee.jmeter.ai.agent.model.StreamDeltaListener raw = options.getStreamDeltaListener();
        return LlmCallOptions.builder()
                .model(options.getModel())
                .temperature(options.getTemperature())
                .maxTokens(options.getMaxTokens())
                .reasoningEffort(options.getReasoningEffort())
                .abortCheck(options.getAbortCheck())
                .streamCloseRegistry(options.getStreamCloseRegistry())
                .streamDeltaListener(new org.gitee.jmeter.ai.agent.model.StreamDeltaListener() {
                    @Override public void onContentDelta(String text) {
                        emittedAnyDelta[0] = true;
                        raw.onContentDelta(text);
                    }

                    @Override public void onReasoningDelta(String text) {
                        emittedAnyDelta[0] = true;
                        raw.onReasoningDelta(text);
                    }
                })
                .build();
    }

    /**
     * 零增量、非中止/中断、且非定性客户端错误 → null（静默回退信号）；否则按
     * 分类错误返回。401/403/404/429 属定性错误（鉴权/模型不存在/限额）：换
     * 非流式再发一次既浪费请求也可能掩盖配置错误——直接以分类错误回给外层
     * （429 走外层重试环，4xx 即时失败）。
     */
    private static LLMResponse silentFallbackOrClassify(Throwable e, boolean emittedAnyDelta) {
        LLMResponse classified = classifyStreamingError(e);
        if (!emittedAnyDelta && !isAbortKind(classified) && !isDefinitiveClientError(classified)) {
            return null;
        }
        return classified;
    }

    /** 同 {@link #silentFallbackOrClassify}，但输入已是分类错误响应（非异常路径）。 */
    private static LLMResponse silentFallbackOrClassifyResponse(LLMResponse error, boolean emittedAnyDelta) {
        if (!emittedAnyDelta && !isAbortKind(error) && !isDefinitiveClientError(error)) {
            return null;
        }
        return error;
    }

    private static boolean isAbortKind(LLMResponse classified) {
        String kind = classified.getErrorKind();
        return "aborted".equals(kind) || "interrupted".equals(kind);
    }

    /** 定性客户端错误（鉴权 401/403、模型不存在 404、限额 429）：非流式重试同样必败或应走重试环。 */
    private static boolean isDefinitiveClientError(LLMResponse classified) {
        Integer code = classified.getStatusCode();
        if (code == null) {
            return false;
        }
        return code == 401 || code == 403 || code == 404 || code == 429;
    }

    private LLMResponse drainStreaming(ChatCompletionCreateParams params, LlmCallOptions options) {
        log.info("[{}] Opening streaming completion (model={})", providerName, params.model());
        StreamCloseRegistry.Registration closeRegistration = null;
        StreamAggregation agg;
        try (StreamResponse<ChatCompletionChunk> streamResponse =
                     streamingClient().chat().completions().createStreaming(params)) {
            if (options.getStreamCloseRegistry() != null) {
                // 注册 close 句柄：取消路径可主动关闭停滞流（阻塞读不响应线程中断）
                closeRegistration = options.getStreamCloseRegistry().register(streamResponse::close);
            }
            agg = aggregateStream(streamResponse.stream(), options.getStreamDeltaListener(), options.getAbortCheck());
        } finally {
            if (closeRegistration != null) {
                closeRegistration.close();
            }
        }

        if (!agg.sawFinishReason()) {
            // 流结束但缺终止标记：按可重试网络失败处理，截断内容不得作为成功结果返回
            log.warn("[{}] stream ended without finish_reason (truncated connection?)", providerName);
            return LLMResponse.error("LLM stream ended without finish_reason (connection truncated)",
                    true, null, "eof");
        }

        List<ToolCall> toolCalls = buildToolCallsFromBuffers(agg.toolBuffers);
        return LLMResponse.builder()
                .content(agg.content == null || agg.content.isEmpty() ? null : agg.content)
                .finishReason(agg.finishReason)
                .reasoningContent(agg.reasoning == null || agg.reasoning.isEmpty() ? null : agg.reasoning)
                .usage(agg.usage)
                .streamed(true)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .build();
    }

    /**
     * 聚合 chunk 流并向 listener 逐片发射原始增量（不做分段/节流——那是发射器层的事）。
     * usage-only 尾包（choices 为空且 usage 非空）显式接受，不误判错误。
     */
    static StreamAggregation aggregateStream(java.util.stream.Stream<ChatCompletionChunk> chunks,
                                             StreamDeltaListener listener,
                                             java.util.function.BooleanSupplier abortCheck) {
        StreamAggregation agg = new StreamAggregation();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Iterator<ChatCompletionChunk> it = chunks.iterator();
        while (it.hasNext()) {
            ChatCompletionChunk chunk = it.next();
            if (abortCheck != null && abortCheck.getAsBoolean()) {
                throw new StreamingAbortException();
            }
            var usageOpt = chunk.usage();
            if (usageOpt != null && usageOpt.isPresent()) {
                var usage = usageOpt.get();
                agg.usage = Map.of("prompt_tokens", (int) usage.promptTokens(),
                        "completion_tokens", (int) usage.completionTokens());
            }
            List<ChatCompletionChunk.Choice> choices;
            try {
                choices = chunk.choices();
            } catch (com.openai.errors.OpenAIInvalidDataException e) {
                choices = List.of();
            }
            if (choices == null || choices.isEmpty()) {
                continue; // usage-only 尾包 / keepalive
            }
            ChatCompletionChunk.Choice choice = choices.get(0);
            var finishReasonField = choice.finishReason();
            if (finishReasonField != null && finishReasonField.isPresent()
                    && finishReasonField.get() != null) {
                agg.finishReason = finishReasonField.get().asString();
            }
            ChatCompletionChunk.Choice.Delta delta = choice.delta();
            if (delta == null) {
                continue;
            }
            var contentOpt = delta.content();
            if (contentOpt != null && contentOpt.isPresent() && contentOpt.get() != null
                    && !contentOpt.get().isEmpty()) {
                content.append(contentOpt.get());
                if (listener != null) {
                    listener.onContentDelta(contentOpt.get());
                }
            }
            String reasoningFragment = extractDeltaReasoning(delta);
            if (reasoningFragment != null && !reasoningFragment.isEmpty()) {
                reasoning.append(reasoningFragment);
                if (listener != null) {
                    listener.onReasoningDelta(reasoningFragment);
                }
            }
            var toolCallsOpt = delta.toolCalls();
            if (toolCallsOpt != null && toolCallsOpt.isPresent() && toolCallsOpt.get() != null) {
                for (var frag : toolCallsOpt.get()) {
                    mergeToolCallFragment(agg, frag);
                }
            }
        }
        agg.content = content.toString();
        agg.reasoning = reasoning.toString();
        return agg;
    }

    /** 从 delta 的 additional properties 读取 reasoning_content（DeepSeek 系思考通道）。 */
    private static String extractDeltaReasoning(ChatCompletionChunk.Choice.Delta delta) {
        try {
            Map<String, com.openai.core.JsonValue> props = delta._additionalProperties();
            if (props != null && props.containsKey("reasoning_content")) {
                com.openai.core.JsonValue value = props.get("reasoning_content");
                if (value != null) {
                    return value.convert(String.class);
                }
            }
        } catch (Exception e) {
            // 字段形态不符则按无思考增量处理
        }
        return null;
    }

    /** 按 index 拼合工具调用分片：id/name 取首个非空值，arguments 顺序追加。 */
    private static void mergeToolCallFragment(StreamAggregation agg, ChatCompletionChunk.Choice.Delta.ToolCall frag) {
        int index = (int) frag.index();
        StreamingToolCall buf = agg.toolBuffers.computeIfAbsent(index, k -> new StreamingToolCall());
        var idField = frag.id();
        if (idField != null && idField.isPresent() && idField.get() != null && !idField.get().isEmpty()) {
            buf.id = idField.get();
        }
        var fnField = frag.function();
        if (fnField != null && fnField.isPresent() && fnField.get() != null) {
            var fn = fnField.get();
            var nameField = fn.name();
            if (nameField != null && nameField.isPresent() && nameField.get() != null
                    && !nameField.get().isEmpty()) {
                buf.name = nameField.get();
            }
            var argsField = fn.arguments();
            if (argsField != null && argsField.isPresent() && argsField.get() != null) {
                buf.args.append(argsField.get());
            }
        }
    }

    /** 把分片缓冲解析为完整工具调用（参数 JSON 解析失败的分片按既有非流式口径跳过并记日志）。 */
    static List<ToolCall> buildToolCallsFromBuffers(TreeMap<Integer, StreamingToolCall> buffers) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<Integer, StreamingToolCall> entry : buffers.entrySet()) {
            StreamingToolCall buf = entry.getValue();
            if (buf.name == null || buf.name.isEmpty()) {
                log.warn("Skipping streamed tool call without a name (index={})", entry.getKey());
                continue;
            }
            String rawArgs = buf.args.length() > 0 ? buf.args.toString() : "{}";
            try {
                Map<String, Object> arguments = new ObjectMapper().readValue(
                        rawArgs, new TypeReference<Map<String, Object>>() {});
                String id = buf.id != null && !buf.id.isEmpty() ? buf.id : "call_stream_" + entry.getKey();
                toolCalls.add(new ToolCall(id, buf.name, arguments));
            } catch (JsonProcessingException e) {
                log.error("Failed to parse streamed tool arguments for {}: rawArguments=[{}]",
                        buf.name, rawArgs, e);
            }
        }
        return toolCalls;
    }

    /** 网关因 stream_options/include_usage 拒绝（400 且报文点名该选项）时判定可去参重试。 */
    static boolean isStreamOptionsRejected(Throwable e) {
        if (!(e instanceof OpenAIServiceException)) {
            return false;
        }
        OpenAIServiceException se = (OpenAIServiceException) e;
        if (se.statusCode() != 400) {
            return false;
        }
        String msg = se.getMessage() != null ? se.getMessage().toLowerCase(Locale.ROOT) : "";
        return msg.contains("stream_options") || msg.contains("include_usage");
    }

    /**
     * 流式错误分类（顺序敏感）：
     * ① abort 标记 / 线程中断 → 不可重试（"Interrupted" 语义保留，runner 特判）；
     * ② SocketTimeout 先于中断判别（它是 InterruptedIOException 子类）→ 可重试 timeout；
     * ③ 其余 IO → 可重试 network；④ SDK 服务端异常 → 429/5xx 可重试、4xx 按类别不可重试；
     * ⑤ 兜底不可重试（与现状一致：外层只重试显式可重试分类）。
     */
    static LLMResponse classifyStreamingError(Throwable e) {
        if (e instanceof StreamingAbortException) {
            return LLMResponse.error("Interrupted", false, null, "aborted");
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof java.net.SocketTimeoutException) {
            return LLMResponse.error("LLM request timed out: " + root.getMessage(), true, null, "timeout");
        }
        if (isCausedByInterrupt(e)) {
            Thread.currentThread().interrupt();
            return LLMResponse.error("Interrupted", false, null, "interrupted");
        }
        if (root instanceof java.io.IOException) {
            return LLMResponse.error("LLM network error: " + root.getMessage(), true, null, "network");
        }
        if (e instanceof OpenAIServiceException) {
            OpenAIServiceException se = (OpenAIServiceException) e;
            int code = se.statusCode();
            String message = "Error calling LLM (HTTP " + code + "): " + se.getMessage();
            if (code == 429) {
                return LLMResponse.error(message, true, code, "rate_limit");
            }
            if (code >= 500) {
                return LLMResponse.error(message, true, code, "server");
            }
            if (code == 401 || code == 403) {
                return LLMResponse.error(message, false, code, "auth");
            }
            if (code == 404) {
                return LLMResponse.error(message, false, code, "not_found");
            }
            return LLMResponse.error(message, false, code, "invalid_request");
        }
        return LLMResponse.error("Error calling LLM: " + e.getMessage(), false, null, "unknown");
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

    /**
     * Build a log-friendly summary of request params (excludes message content,
     * includes counts and serialized extraBody for debugging).
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
        m.put("messages", p.messages().size() + " items");
        p.tools().ifPresentOrElse(v -> m.put("tools", v.size() + " items"), () -> m.put("tools", ""));
        Map<String, com.openai.core.JsonValue> extraBody = p._additionalBodyProperties();
        if (extraBody != null && !extraBody.isEmpty()) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, com.openai.core.JsonValue> e : extraBody.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            json.append("}");
            m.put("extraBody", json.toString());
        }
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

    /**
     * Strip the current provider's own "provider:" prefix from a model ID.
     * e.g. for an ollama provider: "ollama:qwen3.5:2b" -> "qwen3.5:2b".
     * A bare colon that is NOT the current provider's prefix is left intact —
     * Ollama model tags themselves use ":" (e.g. "qwen3.5:2b"), which previously
     * got truncated to "2b". No global prefix whitelist is needed: each instance
     * already knows its own provider name.
     */
    private String stripProviderPrefix(String modelId) {
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            if (parts.length == 2 && parts[0].equals(this.providerName)) {
                return parts[1];
            }
        }
        return modelId;
    }

    private static ReasoningEffort toReasoningEffort(String effort) {
        if (effort == null || effort.equalsIgnoreCase("none") || effort.equalsIgnoreCase("null")) {
            return null;
        }
        return switch (effort.toLowerCase()) {
            case "minimal", "minimum" -> ReasoningEffort.MINIMAL;
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            case "high" -> ReasoningEffort.HIGH;
            case "xhigh" -> ReasoningEffort.XHIGH;
            case "max" -> ReasoningEffort.MAX;
            default -> ReasoningEffort.HIGH;
        };
    }
}
