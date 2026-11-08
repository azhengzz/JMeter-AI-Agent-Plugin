package org.gitee.jmeter.ai.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.Usage;
import com.anthropic.core.http.StreamResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.StreamCloseRegistry;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.gitee.jmeter.ai.utils.VersionUtils;
import org.gitee.jmeter.ai.usage.AnthropicUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClaudeService class.
 */
public class ClaudeService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private String currentModelId;
    private final AnthropicClient client;
    private String systemPrompt;
    private boolean systemPromptInitialized = false;
    private GenerationSettings generationSettings;

    public ClaudeService() {
        String API_KEY = AiConfig.getAnthropicApiKey();

        String loggingLevel = AiConfig.getAnthropicLogLevel();
        if (!loggingLevel.isEmpty()) {
            System.setProperty("ANTHROPIC_LOG", loggingLevel);
            log.info("Enabled Anthropic client logging with level: {}", loggingLevel);
        }

        AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder()
                .apiKey(API_KEY)
                .putHeader("User-Agent", VersionUtils.userAgent());

        String baseUrl = AiConfig.getAnthropicApiBaseUrl();
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

    /** 常见服务端错误的用户可读文案（无匹配时原样返回服务端消息）。 */
    private static String friendlyClaudeError(String errorMessage) {
        if (errorMessage == null) {
            return "An error occurred while communicating with the Anthropic API. Please try again later.";
        }
        if (errorMessage.contains("credit balance is too low")) {
            return "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.";
        }
        if (errorMessage.contains("invalid_api_key")) {
            return "Invalid API key. Please check your API key and try again.";
        }
        if (errorMessage.contains("rate_limit_exceeded")) {
            return "Rate limit exceeded. Please try again later.";
        }
        if (errorMessage.contains("model_not_found")) {
            return "The selected model was not found. Please select a different model.";
        }
        if (errorMessage.contains("context_length_exceeded")) {
            return "The conversation is too long. Please start a new conversation.";
        }
        return errorMessage;
    }

    public String getName() {
        return "Anthropic Claude";
    }

    @Override
    public boolean supportsToolCalling() {
        // Tool calling is a backend capability, not a property of the model id. Every
        // Claude model since Claude 3 supports it, so this is unconditional — matching
        // OpenAICompatibleProvider, which also returns
        // true and trusts the API to reject an unsupported model. A model/prefix check
        // here has repeatedly broken the agent (provider-prefix bug ×2, the
        // claude-fable-* family-list gap), because AgentRunner's pre-flight guard treats
        // a false return as fatal. Prefix stripping is AiServiceFactory.bareModelName's
        // job; this method must not re-validate the id.
        return true;
    }

    @Override
    public LLMResponse generateResponseWithTools(List<org.gitee.jmeter.ai.agent.model.Message> messages, List<ToolDefinition> tools) {
        return callWithTools(messages, tools, null);
    }

    @Override
    public LLMResponse generateResponseWithForcedTool(List<org.gitee.jmeter.ai.agent.model.Message> messages,
                                                      List<ToolDefinition> tools, String forcedToolName) {
        return callWithTools(messages, tools, forcedToolName);
    }

    @Override
    public boolean supportsForcedToolChoice() {
        return supportsToolCalling();
    }

    /**
     * Shared tool-calling request path for auto and forced tool choice. When
     * {@code forcedToolName} is non-null, Anthropic {@code tool_choice} forces that tool —
     * used by MemoryConsolidator's {@code save_memory} consolidation so the call can't be
     * skipped (previously the forced-tool default threw and the fallback never fired).
     */
    private LLMResponse callWithTools(List<org.gitee.jmeter.ai.agent.model.Message> messages,
                                      List<ToolDefinition> tools, String forcedToolName) {
        log.info("Generating response with tools using model: {}, {} tools{}",
                currentModelId,
                tools != null ? tools.size() : 0,
                forcedToolName != null ? ", forced=" + forcedToolName : "");
        try {
            Message response = client.messages().create(buildToolCallParams(messages, tools, forcedToolName));
            return toLLMResponse(response, currentModelId);
        } catch (Exception e) {
            log.error("Error generating response with tools", e);
            // 与流式路径同构的错误分类（429/5xx/网络/超时可重试、4xx 按类别不可
            // 重试）：流式静默回退落穿到这里的失败不得丢失可重试分类
            return classifyClaudeError(e);
        }
    }

    /**
     * Assemble {@link MessageCreateParams} for a tool-calling turn: max-tokens/model,
     * thinking/temperature (mutually exclusive), top-level system, mapped messages, tool
     * definitions, and an optional forced {@code tool_choice}. Extracted for testability.
     */
    MessageCreateParams buildToolCallParams(List<org.gitee.jmeter.ai.agent.model.Message> messages,
                                            List<ToolDefinition> tools, String forcedToolName) {
        if (currentModelId == null || currentModelId.isEmpty()) {
            currentModelId = "claude-3-sonnet-20240229";
            log.warn("No model was set, defaulting to: {}", currentModelId);
        }

        double temperature = generationSettings.getTemperature();
        long maxTokens = generationSettings.getMaxTokens();
        String reasoningEffort = generationSettings.getReasoningEffort();

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

        String system = extractSystem(messages);
        if (system != null && !system.isEmpty()) {
            paramsBuilder.system(system);
        }

        addMessages(paramsBuilder, messages);

        if (tools != null) {
            for (ToolDefinition td : tools) {
                paramsBuilder.addTool(buildTool(td));
            }
        }

        if (forcedToolName != null) {
            paramsBuilder.toolChoice(ToolChoice.ofTool(
                    ToolChoiceTool.builder().name(forcedToolName).build()));
        }

        return paramsBuilder.build();
    }

    /**
     * Build an Anthropic SDK {@link Tool} from a {@link ToolDefinition}, reusing its
     * JSON-schema parameters as the tool's input schema.
     */
    private Tool buildTool(ToolDefinition td) {
        return Tool.builder()
                .name(td.getName())
                .description(td.getDescription() != null ? td.getDescription() : "")
                .inputSchema(buildInputSchema(td.getParameters()))
                .build();
    }

    @SuppressWarnings("unchecked")
    Tool.InputSchema buildInputSchema(Map<String, Object> parameters) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();

        if (parameters == null || parameters.isEmpty()) {
            return schemaBuilder.type(JsonValue.from("object")).build();
        }

        Object type = parameters.get("type");
        schemaBuilder.type(JsonValue.from(type != null ? type : "object"));

        Object props = parameters.get("properties");
        if (props instanceof Map) {
            Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) props).entrySet()) {
                propsBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
            schemaBuilder.properties(propsBuilder.build());
        }

        Object required = parameters.get("required");
        if (required instanceof List) {
            schemaBuilder.required((List<String>) required);
        }

        return schemaBuilder.build();
    }

    /**
     * Map our message list onto Anthropic message params. SYSTEM messages are
     * skipped here (consumed by {@link #extractSystem}); TOOL results are coalesced into
     * single USER messages to keep user/assistant turns strictly alternating.
     */
    void addMessages(MessageCreateParams.Builder builder,
                             List<org.gitee.jmeter.ai.agent.model.Message> messages) {
        List<ContentBlockParam> pendingToolResults = new ArrayList<>();
        for (org.gitee.jmeter.ai.agent.model.Message m : messages) {
            switch (m.getRole()) {
                case SYSTEM:
                    break;
                case USER:
                    flushToolResults(builder, pendingToolResults);
                    builder.addUserMessage(m.getContent() != null ? m.getContent() : "");
                    break;
                case ASSISTANT:
                    flushToolResults(builder, pendingToolResults);
                    builder.addAssistantMessageOfBlockParams(buildAssistantBlocks(m));
                    break;
                case TOOL:
                    pendingToolResults.add(buildToolResultBlock(m));
                    break;
            }
        }
        flushToolResults(builder, pendingToolResults);
    }

    private void flushToolResults(MessageCreateParams.Builder builder, List<ContentBlockParam> results) {
        if (results.isEmpty()) {
            return;
        }
        builder.addUserMessageOfBlockParams(new ArrayList<>(results));
        results.clear();
    }

    List<ContentBlockParam> buildAssistantBlocks(org.gitee.jmeter.ai.agent.model.Message m) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (m.getContent() != null && !m.getContent().isEmpty()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(m.getContent()).build()));
        }
        if (m.hasToolCalls()) {
            for (ToolCall tc : m.getToolCalls()) {
                ToolUseBlockParam.Input.Builder inputBuilder = ToolUseBlockParam.Input.builder();
                if (tc.getArguments() != null) {
                    for (Map.Entry<String, Object> entry : tc.getArguments().entrySet()) {
                        inputBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
                    }
                }
                blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(tc.getId())
                        .name(tc.getName())
                        .input(inputBuilder.build())
                        .build()));
            }
        }
        return blocks;
    }

    ContentBlockParam buildToolResultBlock(org.gitee.jmeter.ai.agent.model.Message m) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(m.getToolCallId())
                .content(m.getContent() != null ? m.getContent() : "")
                .build());
    }

    /**
     * Concatenate SYSTEM-role message content for the top-level {@code system} param.
     * Re-derived on every call, so the system prompt is never dropped on later turns.
     */
    String extractSystem(List<org.gitee.jmeter.ai.agent.model.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (org.gitee.jmeter.ai.agent.model.Message m : messages) {
            if (m.getRole() == org.gitee.jmeter.ai.agent.model.Message.Role.SYSTEM
                    && m.getContent() != null && !m.getContent().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(m.getContent());
            }
        }
        return sb.toString();
    }

    /**
     * Convert an Anthropic SDK {@link Message} into our {@link LLMResponse}: tool_use
     * blocks become {@link ToolCall}s, text/thinking blocks become content/reasoning.
     */
    LLMResponse toLLMResponse(Message message, String modelId) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (ContentBlock block : message.content()) {
            if (block.isToolUse()) {
                ToolUseBlock tu = block.asToolUse();
                Map<String, Object> args;
                try {
                    args = tu._input().convert(new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse tool_use input for '{}': {}", tu.name(), e.getMessage());
                    args = Collections.emptyMap();
                }
                toolCalls.add(new ToolCall(tu.id(), tu.name(), args));
            } else if (block.isText()) {
                content.append(block.asText().text());
            } else if (block.isThinking()) {
                reasoning.append(block.asThinking().thinking());
            }
        }

        long inputTokens = 0;
        long outputTokens = 0;
        try {
            Usage usage = message.usage();
            if (usage != null) {
                inputTokens = usage.inputTokens();
                outputTokens = usage.outputTokens();
            }
        } catch (Exception e) {
            log.warn("Could not extract usage from response: {}", e.getMessage());
        }
        Map<String, Integer> usageMap = Map.of(
                "prompt_tokens", (int) inputTokens,
                "completion_tokens", (int) outputTokens);

        try {
            AnthropicUsage.getInstance().recordUsage(message, modelId, inputTokens, outputTokens);
        } catch (Exception e) {
            log.error("Failed to record token usage", e);
        }

        LLMResponse.Builder builder = LLMResponse.builder().usage(usageMap);
        if (content.length() > 0) {
            builder.content(content.toString());
        }
        if (reasoning.length() > 0) {
            builder.reasoningContent(reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            builder.toolCalls(toolCalls).finishReason("tool_calls");
        } else {
            builder.finishReason(mapStopReason(message.stopReason()));
        }
        return builder.build();
    }

    String mapStopReason(Optional<StopReason> stopReason) {
        if (stopReason == null || stopReason.isEmpty()) {
            return "stop";
        }
        try {
            StopReason.Known known = stopReason.get().known();
            return switch (known) {
                case MAX_TOKENS -> "length";
                default -> "stop";
            };
        } catch (Exception e) {
            log.debug("Unknown stop reason, defaulting to stop", e);
            return "stop";
        }
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
            // 流式分流：携带 delta 消费者（唯一流式闸门）时走原生流式路径；
            // 强制工具调用（记忆整合）与无 options 的直连调用保持非流式。
            if (options != null && options.wantsStreaming()) {
                return callWithToolsStreaming(messages, tools, options);
            }
            return generateResponseWithTools(messages, tools);
        } finally {
            this.generationSettings = original;
            this.currentModelId = originalModel;
        }
    }

    // ---- 流式路径 ----

    /** 流式专用 client：SDK 内部自动重试禁用（maxRetries 0），重试预算由 AgentRunner 外层环独控。 */
    private volatile AnthropicClient streamingClient;
    private String streamingClientApiKey;
    private String streamingClientBaseUrl;

    private AnthropicClient streamingClient() {
        String apiKey = AiConfig.getAnthropicApiKey();
        String baseUrl = AiConfig.getAnthropicApiBaseUrl();
        AnthropicClient local = streamingClient;
        if (local == null || !apiKey.equals(streamingClientApiKey) || !baseUrl.equals(streamingClientBaseUrl)) {
            synchronized (this) {
                if (streamingClient == null || !apiKey.equals(streamingClientApiKey)
                        || !baseUrl.equals(streamingClientBaseUrl)) {
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                            .apiKey(apiKey)
                            .putHeader("User-Agent", VersionUtils.userAgent())
                            .maxRetries(0);
                    if (!baseUrl.isEmpty()) {
                        builder.baseUrl(baseUrl);
                    }
                    streamingClient = builder.build();
                    streamingClientApiKey = apiKey;
                    streamingClientBaseUrl = baseUrl;
                }
                local = streamingClient;
            }
        }
        return local;
    }

    /** 中止标记：abort 谓词在事件之间命中时跳出流读取。 */
    static final class StreamingAbortException extends RuntimeException {
        StreamingAbortException() { super("aborted between events"); }
    }

    /** 流式聚合结果。 */
    static final class ClaudeStreamAggregation {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final java.util.TreeMap<Long, ClaudeToolBuffer> toolBuffers = new java.util.TreeMap<>();
        Message startMessage;
        Optional<StopReason> stopReason = Optional.empty();
        boolean sawMessageStop;
        long inputTokens;
        long outputTokens;
        /** 流中是否真实携带 usage（message_start/message_delta）：缺失不得以 0/0 冒充。 */
        boolean usageSeen;
    }

    /** 按 content block index 拼合的 tool_use 缓冲（block start 给 id/name，input_json_delta 追加参数）。 */
    static final class ClaudeToolBuffer {
        String id;
        String name;
        final StringBuilder partialJson = new StringBuilder();
    }

    private LLMResponse callWithToolsStreaming(List<org.gitee.jmeter.ai.agent.model.Message> messages,
                                               List<ToolDefinition> tools, LlmCallOptions options) {
        log.info("Generating streaming response with tools using model: {}, {} tools", currentModelId,
                tools != null ? tools.size() : 0);
        boolean[] emittedAnyDelta = {false};
        LlmCallOptions tracked = trackDeltaEmission(options, emittedAnyDelta);
        MessageCreateParams params = buildToolCallParams(messages, tools, null);
        StreamCloseRegistry.Registration closeRegistration = null;
        try {
            ClaudeStreamAggregation agg;
            try (StreamResponse<RawMessageStreamEvent> streamResponse =
                         streamingClient().messages().createStreaming(params)) {
                if (tracked.getStreamCloseRegistry() != null) {
                    closeRegistration = tracked.getStreamCloseRegistry().register(streamResponse::close);
                }
                agg = aggregateClaudeStream(streamResponse.stream(), tracked);
            } finally {
                if (closeRegistration != null) {
                    closeRegistration.close();
                }
            }

            if (!agg.sawMessageStop) {
                // 流结束但缺 message_stop：按可重试网络失败处理，截断内容不得作为成功结果返回；
                // 零增量时静默回退非流式（不支持流式协商的兼容服务不应让调用失败）
                log.warn("Anthropic stream ended without message_stop (truncated connection?)");
                if (!emittedAnyDelta[0]) {
                    return callWithTools(messages, tools, null);
                }
                return LLMResponse.error("Anthropic stream ended without message_stop (connection truncated)",
                        true, null, "eof");
            }

            List<ToolCall> toolCalls = buildToolCallsFromClaudeBuffers(agg.toolBuffers);
            // 流中缺失 usage 时不得以 0/0 冒充（会清零上下文用量指示）：空 map
            // 表示"未收到"，消费端（onUsage 只对非空发射）保持前值
            Map<String, Integer> usageMap = agg.usageSeen
                    ? Map.of("prompt_tokens", (int) agg.inputTokens,
                             "completion_tokens", (int) agg.outputTokens)
                    : Map.of();
            if (agg.usageSeen) {
                try {
                    AnthropicUsage.getInstance().recordUsage(agg.startMessage, currentModelId,
                            agg.inputTokens, agg.outputTokens);
                } catch (Exception e) {
                    log.error("Failed to record token usage", e);
                }
            }

            LLMResponse.Builder builder = LLMResponse.builder().usage(usageMap).streamed(true);
            if (agg.content.length() > 0) {
                builder.content(agg.content.toString());
            }
            if (agg.reasoning.length() > 0) {
                builder.reasoningContent(agg.reasoning.toString());
            }
            if (!toolCalls.isEmpty()) {
                builder.toolCalls(toolCalls).finishReason("tool_calls");
            } else {
                builder.finishReason(mapStopReason(agg.stopReason));
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Error generating streaming response with tools", e);
            // 零增量、非中止/中断、且非定性客户端错误（401/403/404/429）的失败：
            // 静默回退非流式（不支持流式的兼容网关不应让整个调用失败，也不得
            // 把完整结果伪装成流式）。定性错误不回退——换非流式重发既浪费请求
            // 也可能掩盖配置错误（与 OpenAICompatibleProvider 同口径）
            LLMResponse classified = classifyClaudeError(e);
            String kind = classified.getErrorKind();
            if (!emittedAnyDelta[0] && !"aborted".equals(kind) && !"interrupted".equals(kind)
                    && !isDefinitiveClientError(classified)) {
                log.warn("Streaming failed before any delta; silently falling back to non-streaming");
                return callWithTools(messages, tools, null);
            }
            return classified;
        }
    }

    /** 定性客户端错误（鉴权 401/403、模型不存在 404、限额 429）：非流式重试同样必败或应走重试环。 */
    private static boolean isDefinitiveClientError(LLMResponse classified) {
        Integer code = classified.getStatusCode();
        if (code == null) {
            return false;
        }
        return code == 401 || code == 403 || code == 404 || code == 429;
    }

    /** 包装消费者记录"是否交付过任何 delta"（静默回退资格判定）。 */
    private static LlmCallOptions trackDeltaEmission(LlmCallOptions options, boolean[] emittedAnyDelta) {
        StreamDeltaListener raw = options.getStreamDeltaListener();
        return LlmCallOptions.builder()
                .model(options.getModel())
                .temperature(options.getTemperature())
                .maxTokens(options.getMaxTokens())
                .reasoningEffort(options.getReasoningEffort())
                .abortCheck(options.getAbortCheck())
                .streamCloseRegistry(options.getStreamCloseRegistry())
                .streamDeltaListener(new StreamDeltaListener() {
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
     * 聚合 Anthropic 流事件并向 listener 逐片发射原始增量。message_start 提取输入
     * token、message_delta 提取 stop_reason 与输出 token；text/thinking 增量双通道
     * 发射；input_json_delta 仅内部拼合不发射。
     */
    static ClaudeStreamAggregation aggregateClaudeStream(java.util.stream.Stream<RawMessageStreamEvent> events,
                                                         LlmCallOptions options) {
        StreamDeltaListener listener = options != null ? options.getStreamDeltaListener() : null;
        java.util.function.BooleanSupplier abortCheck = options != null ? options.getAbortCheck() : null;
        ClaudeStreamAggregation agg = new ClaudeStreamAggregation();
        java.util.Iterator<RawMessageStreamEvent> it = events.iterator();
        while (it.hasNext()) {
            RawMessageStreamEvent event = it.next();
            if (abortCheck != null && abortCheck.getAsBoolean()) {
                throw new StreamingAbortException();
            }
            if (event.isMessageStart()) {
                agg.startMessage = event.asMessageStart().message();
                try {
                    Usage usage = agg.startMessage.usage();
                    if (usage != null) {
                        agg.inputTokens = usage.inputTokens();
                        agg.usageSeen = true;
                    }
                } catch (Exception e) {
                    log.debug("Could not extract input tokens from message_start", e);
                }
            } else if (event.isContentBlockStart()) {
                var block = event.asContentBlockStart().contentBlock();
                var toolUse = block.toolUse();
                if (toolUse != null && toolUse.isPresent() && toolUse.get() != null) {
                    ClaudeToolBuffer buf = agg.toolBuffers.computeIfAbsent(
                            event.asContentBlockStart().index(), k -> new ClaudeToolBuffer());
                    buf.id = toolUse.get().id();
                    buf.name = toolUse.get().name();
                }
            } else if (event.isContentBlockDelta()) {
                var delta = event.asContentBlockDelta().delta();
                var text = delta.text();
                if (text != null && text.isPresent() && text.get() != null && !text.get().text().isEmpty()) {
                    agg.content.append(text.get().text());
                    if (listener != null) {
                        listener.onContentDelta(text.get().text());
                    }
                }
                var thinking = delta.thinking();
                if (thinking != null && thinking.isPresent() && thinking.get() != null
                        && !thinking.get().thinking().isEmpty()) {
                    agg.reasoning.append(thinking.get().thinking());
                    if (listener != null) {
                        listener.onReasoningDelta(thinking.get().thinking());
                    }
                }
                var inputJson = delta.inputJson();
                if (inputJson != null && inputJson.isPresent() && inputJson.get() != null) {
                    ClaudeToolBuffer buf = agg.toolBuffers.computeIfAbsent(
                            event.asContentBlockDelta().index(), k -> new ClaudeToolBuffer());
                    buf.partialJson.append(inputJson.get().partialJson());
                }
            } else if (event.isMessageDelta()) {
                var messageDelta = event.asMessageDelta();
                agg.stopReason = messageDelta.delta().stopReason();
                try {
                    var usage = messageDelta.usage();
                    if (usage != null) {
                        agg.outputTokens = usage.outputTokens();
                        agg.usageSeen = true;
                    }
                } catch (Exception e) {
                    log.debug("Could not extract output tokens from message_delta", e);
                }
            } else if (event.isMessageStop()) {
                agg.sawMessageStop = true;
            }
        }
        return agg;
    }

    /** 把 tool_use 分片缓冲解析为完整工具调用（空参数归 {}，JSON 解析失败跳过并记日志）。 */
    static List<ToolCall> buildToolCallsFromClaudeBuffers(java.util.TreeMap<Long, ClaudeToolBuffer> buffers) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<Long, ClaudeToolBuffer> entry : buffers.entrySet()) {
            ClaudeToolBuffer buf = entry.getValue();
            if (buf.name == null || buf.name.isEmpty()) {
                log.warn("Skipping streamed tool_use block without a name (index={})", entry.getKey());
                continue;
            }
            String raw = buf.partialJson.length() > 0 ? buf.partialJson.toString() : "{}";
            Map<String, Object> args;
            try {
                args = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        raw, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.error("Failed to parse streamed tool_use input for '{}': rawInput=[{}]", buf.name, raw, e);
                continue;
            }
            String id = buf.id != null && !buf.id.isEmpty() ? buf.id : "toolu_stream_" + entry.getKey();
            toolCalls.add(new ToolCall(id, buf.name, args));
        }
        return toolCalls;
    }

    /** 流式错误分类（SocketTimeout 先于中断判别；429/5xx 可重试；4xx 按类别不可重试）。 */
    static LLMResponse classifyClaudeError(Throwable e) {
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
        if (e instanceof AnthropicServiceException) {
            AnthropicServiceException se = (AnthropicServiceException) e;
            int code = se.statusCode();
            String message = "Error calling Claude (HTTP " + code + "): " + friendlyClaudeError(se.getMessage());
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
        return LLMResponse.error("Error calling Claude (streaming): " + e.getMessage(), false, null, "unknown");
    }

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
}
