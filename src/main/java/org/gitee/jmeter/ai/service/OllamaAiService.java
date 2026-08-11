package org.gitee.jmeter.ai.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import io.github.ollama4j.models.request.ThinkMode;
import io.github.ollama4j.utils.Options;
import io.github.ollama4j.utils.OptionsBuilder;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.chat.OllamaChatToolCalls;
import io.github.ollama4j.tools.OllamaToolCallsFunction;
import io.github.ollama4j.tools.Tools;

// Ollama Help https://ollama4j.github.io/ollama4j/intro
public class OllamaAiService implements AiService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaAiService.class);
    private final Ollama ollamaClient;
    private String model;
    private final String host;
    private final boolean isThinkingModeEnabled;
    private final ThinkMode thinkingMode;
    private final long requestTimeoutSeconds;
    private final String systemPrompt;
    private GenerationSettings generationSettings;

    public OllamaAiService() {
        this.host = buildHost(
                AiConfig.getProperty("ollama.host", "http://localhost"),
                AiConfig.getProperty("ollama.port", "11434"));

        this.model = AiConfig.getDefaultModel();
        this.generationSettings = GenerationSettings.fromConfig();
        this.isThinkingModeEnabled = AiConfig.getProperty("ollama.thinking.mode", "DISABLED").equalsIgnoreCase("enabled");

        // Resolve thinking level: per-provider override > global reasoning.effort > hardcoded default
        String thinkingLevelStr = AiConfig.getProperty("ollama.thinking.level", null);
        if (thinkingLevelStr != null && !thinkingLevelStr.isEmpty()) {
            this.thinkingMode = parseThinkingMode(thinkingLevelStr);
        } else {
            this.thinkingMode = mapReasoningEffortToThinkMode(
                    AiConfig.getProperty("jmeter.ai.reasoning.effort", "medium"));
        }

        this.requestTimeoutSeconds = parseTimeout(AiConfig.getProperty("ollama.request.timeout.seconds", "120"));
        this.ollamaClient = new Ollama(this.host);
        this.ollamaClient.setRequestTimeoutSeconds(this.requestTimeoutSeconds);
        // Load system prompt using centralized utility
        this.systemPrompt = SystemPrompt.get();

        logger.info("Initialized Ollama service with host: {}, model: {}, thinking mode: {}, timeout: {}s",
                this.host, this.model, this.isThinkingModeEnabled ? this.thinkingMode : "DISABLED", this.requestTimeoutSeconds);
    }

    private static String buildHost(String hostValue, String portValue) {
        if (hostValue == null || hostValue.isEmpty()) {
            return "http://localhost:11434";
        }
        if (!portValue.isEmpty() && !hostValue.matches(".*:\\d+/?$")) {
            hostValue = hostValue.endsWith("/") ? hostValue.substring(0, hostValue.length() - 1) : hostValue;
            return hostValue + ":" + portValue;
        }
        return hostValue;
    }

    private static ThinkMode parseThinkingMode(String value) {
        try {
            return ThinkMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid thinking level: '{}'. Setting to default MEDIUM", value);
            return ThinkMode.MEDIUM;
        }
    }

    private static ThinkMode mapReasoningEffortToThinkMode(String effort) {
        return switch (effort.toLowerCase()) {
            case "low" -> ThinkMode.LOW;
            case "medium" -> ThinkMode.MEDIUM;
            case "high" -> ThinkMode.HIGH;
            default -> ThinkMode.MEDIUM;
        };
    }

    private static long parseTimeout(String value) {
        try {
            long timeout = Long.parseLong(value);
            if (timeout <= 0) {
                logger.warn("Request timeout must be positive. Provided value: {}. Setting to default 120s", timeout);
                return 120L;
            }
            return timeout;
        } catch (NumberFormatException e) {
            logger.warn("Invalid request timeout value: '{}'. Setting to default 120s", value);
            return 120L;
        }
    }

    public boolean isReachable() {
        try {
            return this.ollamaClient.ping();
        } catch (Exception e) {
            logger.error("Ollama is not reachable at {}", this.host);
            return false;
        }
    }

    public boolean isValidModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isEmpty()) {
            return false;
        }
        try {
            List<Model> models = this.ollamaClient.listModels();
            for (Model m : models) {
                if (m.getName().equals(configuredModel)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logger.error("Model is not valid", e);
            return false;
        }
    }

    public List<Model> listModels() {
        try {
            return this.ollamaClient.listModels();
        } catch (Exception e) {
            logger.error("Error listing models", e);
            return new ArrayList<>();
        }
    }

    @Override
    public String getName() {
        return "Ollama";
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public String generateResponse(List<String> messages) {
        return generateResponse(messages, this.systemPrompt);
    }

    public void setModel(String modelId) {
        this.model = modelId;
        logger.info("Ollama Model set to: {}", modelId);
    }


   public boolean isThinkingModeValid() {
       return this.thinkingMode == ThinkMode.LOW || this.thinkingMode == ThinkMode.MEDIUM || this.thinkingMode == ThinkMode.HIGH;
   }

    @Override
    public String generateResponse(List<String> messages, String systemPrompt) {

        OllamaChatRequest request = OllamaChatRequest.builder();
        OllamaChatResult result = null;

        if (!isValidModel(this.model)) {
            logger.warn("Configured model '{}' is not available. Using default or failing.", this.model);
        }

        if (isThinkingModeEnabled && !isThinkingModeValid()) {
            logger.warn("Thinking mode is enabled but thinking level '{}' is not valid. Disabling thinking mode.", this.thinkingMode);
        }

        try {
            request = buildOllamaChatRequest(request);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                request.withMessage(OllamaChatMessageRole.SYSTEM, systemPrompt);
            } else {
                request.withMessage(OllamaChatMessageRole.SYSTEM, SystemPrompt.getDefault());
            }

            for (int i = 0; i < messages.size(); i++) {
                String msg = messages.get(i);
                if (msg == null || msg.isEmpty()) continue;
                if (i % 2 == 0) {
                    request.withMessage(OllamaChatMessageRole.USER, msg);
                } else {
                    request.withMessage(OllamaChatMessageRole.ASSISTANT, msg);
                }
            }

            result = ollamaClient.chat(request, null);
            return result.getResponseModel().getMessage().getResponse();

        } catch (Exception e) {
            logger.error("Error generating response from Ollama", e);
            return "Error generating response: " + e.getMessage();
        }
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools,
                                                 LlmCallOptions options) {
        // System prompt comes only from the messages (ContextBuilder includes a SYSTEM
        // message), so the request is built without the separate systemPrompt injection
        // used by the text path — avoiding a doubled system prompt.
        String originalModel = this.model;
        try {
            if (options != null && options.getModel() != null && !options.getModel().isEmpty()) {
                this.model = options.getModel();
            }

            OllamaChatRequest request = buildOllamaChatRequest(OllamaChatRequest.builder());
            request = request.withTools(mapTools(tools)).withUseTools(true);
            // Tool execution stays with the Agent loop: do not let ollama4j auto-execute
            // or internally retry tool calls.
            ollamaClient.setMaxChatToolCallRetries(0);

            for (Message msg : messages) {
                request = appendMessage(request, msg);
            }

            OllamaChatResult result = ollamaClient.chat(request, null);
            return buildLLMResponse(result.getResponseModel().getMessage());

        } catch (Exception e) {
            logger.error("Error generating tool-calling response from Ollama", e);
            return LLMResponse.error("Ollama tool-calling error: " + e.getMessage());
        } finally {
            this.model = originalModel;
        }
    }

    /**
     * Map an Ollama response message to our {@link LLMResponse}. Package-private so the
     * tool-call/text branching can be unit-tested without a live Ollama instance.
     */
    static LLMResponse buildLLMResponse(OllamaChatMessage respMsg) {
        List<OllamaChatToolCalls> respToolCalls = respMsg.getToolCalls();

        if (respToolCalls != null && !respToolCalls.isEmpty()) {
            List<ToolCall> mapped = new ArrayList<>();
            for (OllamaChatToolCalls tc : respToolCalls) {
                OllamaToolCallsFunction fn = tc.getFunction();
                String name = fn != null ? fn.getName() : null;
                Map<String, Object> args = fn != null ? fn.getArguments() : Map.of();
                mapped.add(new ToolCall(tc.getId(), name, args));
            }
            return LLMResponse.builder()
                    .content(respMsg.getResponse())
                    .toolCalls(mapped)
                    .finishReason("tool_calls")
                    .build();
        }
        return LLMResponse.text(respMsg.getResponse());
    }

    /**
     * Map our {@link ToolDefinition} list to ollama4j {@link Tools.Tool}s.
     * Parameters are mapped best-effort: only top-level properties translate to the
     * flat {@link Tools.Property} model; nested schemas degrade to a generic object.
     */
    static List<Tools.Tool> mapTools(List<ToolDefinition> tools) {
        List<Tools.Tool> out = new ArrayList<>();
        if (tools == null) return out;
        for (ToolDefinition td : tools) {
            Tools.ToolSpec spec = Tools.ToolSpec.builder()
                    .name(td.getName())
                    .description(td.getDescription() != null ? td.getDescription() : "")
                    .parameters(mapParameters(td.getParameters()))
                    .build();
            out.add(Tools.Tool.builder().toolSpec(spec).type("function").build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Tools.Parameters mapParameters(Map<String, Object> params) {
        Map<String, Tools.Property> propertyMap = new HashMap<>();
        if (params == null) {
            return Tools.Parameters.of(propertyMap);
        }
        Object propertiesObj = params.get("properties");
        if (propertiesObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) propertiesObj).entrySet()) {
                propertyMap.put(e.getKey(), mapProperty(e.getValue()));
            }
        }
        Tools.Parameters parameters = Tools.Parameters.of(propertyMap);
        Object requiredObj = params.get("required");
        if (requiredObj instanceof List) {
            List<String> reqList = new ArrayList<>();
            for (Object o : (List<?>) requiredObj) {
                if (o != null) reqList.add(o.toString());
            }
            parameters.setRequired(reqList);
        }
        return parameters;
    }

    @SuppressWarnings("unchecked")
    private static Tools.Property mapProperty(Object propObj) {
        Tools.Property.PropertyBuilder b = Tools.Property.builder();
        if (propObj instanceof Map) {
            Map<String, Object> propMap = (Map<String, Object>) propObj;
            Object type = propMap.get("type");
            if (type != null) b.type(type.toString());
            Object desc = propMap.get("description");
            if (desc != null) b.description(desc.toString());
            Object enumObj = propMap.get("enum");
            if (enumObj instanceof List) {
                List<String> enumVals = new ArrayList<>();
                for (Object o : (List<?>) enumObj) {
                    if (o != null) enumVals.add(o.toString());
                }
                b.enumValues(enumVals);
            }
        }
        return b.build();
    }

    /**
     * Append a {@link Message} to the request, preserving role, prior assistant
     * tool calls, and tool results for multi-turn round-trips.
     */
    static OllamaChatRequest appendMessage(OllamaChatRequest request, Message msg) {
        String content = msg.getContent() != null ? msg.getContent() : "";
        switch (msg.getRole()) {
            case SYSTEM:
                return request.withMessage(OllamaChatMessageRole.SYSTEM, content);
            case USER:
                return request.withMessage(OllamaChatMessageRole.USER, content);
            case ASSISTANT:
                if (msg.hasToolCalls()) {
                    return request.withMessage(OllamaChatMessageRole.ASSISTANT, content, mapToolCalls(msg.getToolCalls()));
                }
                return request.withMessage(OllamaChatMessageRole.ASSISTANT, content);
            case TOOL:
                return request.withMessage(OllamaChatMessageRole.TOOL, content);
            default:
                return request.withMessage(OllamaChatMessageRole.USER, content);
        }
    }

    static List<OllamaChatToolCalls> mapToolCalls(List<ToolCall> toolCalls) {
        List<OllamaChatToolCalls> out = new ArrayList<>();
        if (toolCalls == null) return out;
        for (ToolCall tc : toolCalls) {
            out.add(new OllamaChatToolCalls(tc.getId(),
                    new OllamaToolCallsFunction(tc.getName(), tc.getArguments())));
        }
        return out;
    }

    private OllamaChatRequest buildOllamaChatRequest(OllamaChatRequest request) {
        float temperature = (float) generationSettings.getTemperature();

        if(isThinkingModeEnabled && isThinkingModeValid()) {
            return request.withThinking(this.thinkingMode)
                    .withOptions(new OptionsBuilder().setTemperature(temperature).build())
                    .withModel(this.model).build();
        }
        else {
            return request.withThinking(ThinkMode.DISABLED)
                    .withOptions(new OptionsBuilder().setTemperature(temperature).build())
                    .withModel(this.model).build();
        }
    }

    @Override
    public GenerationSettings getGenerationSettings() {
        return generationSettings;
    }

    @Override
    public void setGenerationSettings(GenerationSettings settings) {
        this.generationSettings = settings;
        logger.info("Generation settings updated: {}", settings);
    }
}
