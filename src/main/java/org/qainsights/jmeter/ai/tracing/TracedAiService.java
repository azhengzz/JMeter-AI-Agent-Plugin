package org.qainsights.jmeter.ai.tracing;

import org.qainsights.jmeter.ai.agent.model.LLMResponse;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;
import org.qainsights.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wrapper for AiService that automatically traces calls to LangSmith.
 */
public class TracedAiService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(TracedAiService.class);

    private final AiService delegate;
    private final LangSmithClient tracer;

    private TracedAiService(AiService delegate, LangSmithClient tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    public static AiService wrap(AiService service) {
        if (!LangSmithClient.getInstance().isEnabled()) {
            log.debug("LangSmith tracing is disabled, returning unwrapped service");
            return service;
        }
        return new TracedAiService(service, LangSmithClient.getInstance());
    }

    public static AiService wrap(AiService service, LangSmithClient tracer) {
        return new TracedAiService(service, tracer);
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, null);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        String effectiveModel = model != null ? model : "default";
        String runName = delegate.getName() + ":" + effectiveModel;

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("conversation", conversation);
        inputs.put("message_count", conversation.size());

        List<String> tags = buildTags(effectiveModel);
        LangSmithClient.LLMRun run = tracer.createRun(runName, inputs, tags);

        try {
            long startTime = System.currentTimeMillis();
            String response = delegate.generateResponse(conversation, model);
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("response", response);
            outputs.put("duration_ms", duration);

            // Estimated tokens (text-only mode has no actual usage)
            int inputTokens = conversation.stream().mapToInt(String::length).sum() / 4;
            int outputTokens = response.length() / 4;
            outputs.put("estimated_input_tokens", inputTokens);
            outputs.put("estimated_output_tokens", outputTokens);

            run.complete(outputs);
            return response;

        } catch (Exception e) {
            log.error("Error in generateResponse for {}", runName, e);
            run.error(e.getMessage());
            throw e;
        }
    }

    @Override
    public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
        String runName = delegate.getName() + ":tools:" + tools.size();
        String modelName = delegate.getName();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", formatMessages(messages));
        inputs.put("tools", formatTools(tools));
        inputs.put("tool_count", tools.size());
        inputs.put("message_count", messages.size());

        List<String> tags = buildTags(modelName);
        LangSmithClient.LLMRun run = tracer.createRun(runName, inputs, tags);

        try {
            LLMResponse response = delegate.generateResponseWithTools(messages, tools);

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("content", response.getContent());
            outputs.put("finish_reason", response.getFinishReason());
            outputs.put("has_tool_calls", response.hasToolCalls());

            // Actual token usage from API
            Map<String, Integer> usage = response.getUsage();
            if (usage != null && !usage.isEmpty()) {
                outputs.put("prompt_tokens", usage.getOrDefault("prompt_tokens", 0));
                outputs.put("completion_tokens", usage.getOrDefault("completion_tokens", 0));
                outputs.put("total_tokens",
                    usage.getOrDefault("prompt_tokens", 0) + usage.getOrDefault("completion_tokens", 0));
            }

            // Tool call details (name + arguments, not just count)
            if (response.hasToolCalls()) {
                List<Map<String, Object>> toolCallDetails = response.getToolCalls().stream()
                    .map(tc -> {
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("id", tc.getId());
                        detail.put("name", tc.getName());
                        detail.put("arguments", tc.getArguments());
                        return detail;
                    })
                    .collect(Collectors.toList());
                outputs.put("tool_calls", toolCallDetails);
            }

            run.complete(outputs);
            return response;

        } catch (Exception e) {
            log.error("Error in generateResponseWithTools for {}", runName, e);
            run.error(e.getMessage());
            throw e;
        }
    }

    @Override
    public LLMResponse generateResponseWithForcedTool(List<Message> messages, List<ToolDefinition> tools, String forcedToolName) {
        String runName = delegate.getName() + ":forced:" + forcedToolName;
        String modelName = delegate.getName();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", formatMessages(messages));
        inputs.put("tools", formatTools(tools));
        inputs.put("forced_tool", forcedToolName);

        List<String> tags = buildTags(modelName);
        LangSmithClient.LLMRun run = tracer.createRun(runName, inputs, tags);

        try {
            LLMResponse response = delegate.generateResponseWithForcedTool(messages, tools, forcedToolName);

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("content", response.getContent());
            outputs.put("finish_reason", response.getFinishReason());
            outputs.put("has_tool_calls", response.hasToolCalls());

            // Actual token usage from API
            Map<String, Integer> usage = response.getUsage();
            if (usage != null && !usage.isEmpty()) {
                outputs.put("prompt_tokens", usage.getOrDefault("prompt_tokens", 0));
                outputs.put("completion_tokens", usage.getOrDefault("completion_tokens", 0));
                outputs.put("total_tokens",
                    usage.getOrDefault("prompt_tokens", 0) + usage.getOrDefault("completion_tokens", 0));
            }

            if (response.hasToolCalls()) {
                outputs.put("tool_calls", response.getToolCalls().stream()
                    .map(tc -> {
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("id", tc.getId());
                        detail.put("name", tc.getName());
                        detail.put("arguments", tc.getArguments());
                        return detail;
                    })
                    .collect(Collectors.toList()));
            }

            run.complete(outputs);
            return response;

        } catch (Exception e) {
            log.error("Error in generateResponseWithForcedTool for {}", runName, e);
            run.error(e.getMessage());
            throw e;
        }
    }

    @Override
    public boolean supportsToolCalling() {
        return delegate.supportsToolCalling();
    }

    @Override
    public boolean supportsForcedToolChoice() {
        return delegate.supportsForcedToolChoice();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    // --- Helper methods ---

    private List<String> buildTags(String modelName) {
        List<String> tags = new ArrayList<>();
        tags.add("jmeter-agent");
        tags.add("service:" + delegate.getName());
        if (modelName != null && !modelName.equals("default")) {
            tags.add("model:" + modelName);
        }
        return tags;
    }

    private List<Map<String, Object>> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("role", m.getRole().name().toLowerCase());
                    msg.put("content", m.getContent() != null ? m.getContent() : "");

                    if (m.hasToolCalls()) {
                        List<Map<String, Object>> toolCalls = new ArrayList<>();
                        for (var tc : m.getToolCalls()) {
                            Map<String, Object> toolCall = new HashMap<>();
                            toolCall.put("id", tc.getId());
                            toolCall.put("type", "function");
                            Map<String, Object> function = new HashMap<>();
                            function.put("name", tc.getName());
                            function.put("arguments", tc.getArguments());
                            toolCall.put("function", function);
                            toolCalls.add(toolCall);
                        }
                        msg.put("tool_calls", toolCalls);
                    }

                    if (m.getRole() == Message.Role.TOOL) {
                        if (m.getToolCallId() != null) {
                            msg.put("tool_call_id", m.getToolCallId());
                        }
                        // Include tool name for easier identification
                        if (m.getToolName() != null) {
                            msg.put("tool_name", m.getToolName());
                        }
                    }

                    return msg;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> formatTools(List<ToolDefinition> tools) {
        return tools.stream()
                .map(t -> {
                    Map<String, Object> tool = new HashMap<>();
                    tool.put("name", t.getName());
                    tool.put("description", t.getDescription());
                    if (t.getParameters() != null) {
                        tool.put("parameters", t.getParameters());
                    }
                    return tool;
                })
                .collect(Collectors.toList());
    }
}
