package org.qainsights.jmeter.ai.tracing;

import org.qainsights.jmeter.ai.agent.model.LLMResponse;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wrapper for AiService that automatically traces calls to LangSmith.
 *
 * Usage:
 * <pre>
 * AiService originalService = ...;
 * AiService tracedService = TracedAiService.wrap(originalService);
 * </pre>
 */
public class TracedAiService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(TracedAiService.class);

    private final AiService delegate;
    private final LangSmithClient tracer;

    private TracedAiService(AiService delegate, LangSmithClient tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    /**
     * Wrap an AiService with LangSmith tracing.
     *
     * @param service The service to wrap
     * @return A wrapped service that traces all calls
     */
    public static AiService wrap(AiService service) {
        if (!LangSmithClient.getInstance().isEnabled()) {
            log.debug("LangSmith tracing is disabled, returning unwrapped service");
            return service;
        }
        return new TracedAiService(service, LangSmithClient.getInstance());
    }

    /**
     * Wrap an AiService with custom tracer.
     */
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
        inputs.put("model", effectiveModel);
        inputs.put("message_count", conversation.size());

        // Extract and include system prompt in trace
        String systemPrompt = getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            inputs.put("system_prompt", systemPrompt);
            log.debug("Including system prompt in trace (length: {})", systemPrompt.length());
        }

        LangSmithClient.LLMRun run = tracer.createRun(runName, inputs);

        try {
            long startTime = System.currentTimeMillis();
            String response = delegate.generateResponse(conversation, model);
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("response", response);
            outputs.put("response_length", response.length());
            outputs.put("duration_ms", duration);

            // Estimate token count (rough approximation: 1 token ≈ 4 characters)
            int inputTokens = conversation.stream()
                    .mapToInt(String::length)
                    .sum() / 4;
            // Include system prompt in token estimation
            if (systemPrompt != null) {
                inputTokens += systemPrompt.length() / 4;
            }
            int outputTokens = response.length() / 4;
            outputs.put("estimated_input_tokens", inputTokens);
            outputs.put("estimated_output_tokens", outputTokens);
            outputs.put("estimated_total_tokens", inputTokens + outputTokens);

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
        String runName = delegate.getName() + ":tools";

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", formatMessages(messages));
        inputs.put("tools", formatTools(tools));
        inputs.put("tool_count", tools.size());

        // Extract and include system prompt in trace
        String systemPrompt = getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            inputs.put("system_prompt", systemPrompt);
            log.debug("Including system prompt in tool trace (length: {})", systemPrompt.length());
        }

        LangSmithClient.LLMRun run = tracer.createRun(runName, inputs);

        try {
            LLMResponse response = delegate.generateResponseWithTools(messages, tools);

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("content", response.getContent());
            outputs.put("has_tool_calls", response.hasToolCalls());

            if (response.hasToolCalls()) {
                outputs.put("tool_calls", response.getToolCalls().size());
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

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", formatMessages(messages));
        inputs.put("tools", formatTools(tools));
        inputs.put("forced_tool", forcedToolName);

        LangSmithClient.LLMRun run = tracer.createRun(runName, inputs);

        try {
            LLMResponse response = delegate.generateResponseWithForcedTool(messages, tools, forcedToolName);

            Map<String, Object> outputs = new HashMap<>();
            outputs.put("content", response.getContent());
            outputs.put("has_tool_calls", response.hasToolCalls());

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

    // Helper methods

    private List<Map<String, Object>> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("role", m.getRole().name());
                    msg.put("content", m.getContent() != null ? m.getContent() : "");

                    // Add tool_calls for assistant messages (Nanobot-style format)
                    if (m.hasToolCalls()) {
                        List<Map<String, Object>> toolCalls = new ArrayList<>();
                        for (org.qainsights.jmeter.ai.agent.model.ToolCall tc : m.getToolCalls()) {
                            Map<String, Object> toolCall = new HashMap<>();
                            toolCall.put("id", tc.getId());
                            toolCall.put("type", "function");

                            Map<String, Object> function = new HashMap<>();
                            function.put("name", tc.getName());
                            function.put("arguments", convertArgumentsToJson(tc.getArguments()));
                            toolCall.put("function", function);

                            toolCalls.add(toolCall);
                        }
                        msg.put("tool_calls", toolCalls);
                    }

                    // Add tool_call_id for tool result messages
                    if (m.getRole() == Message.Role.TOOL && m.getToolCallId() != null) {
                        msg.put("tool_call_id", m.getToolCallId());
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
                    // Include parameters schema for better debugging
                    if (t.getParameters() != null) {
                        tool.put("parameters", t.getParameters());
                    }
                    return tool;
                })
                .collect(Collectors.toList());
    }

    /**
     * Convert ToolCall arguments to JSON string.
     * Nanobot format: arguments as JSON string
     */
    private String convertArgumentsToJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(arguments);
        } catch (Exception e) {
            log.warn("Failed to convert tool arguments to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Extract the system prompt from the delegate service.
     * Uses the centralized SystemPrompt utility.
     */
    private String getSystemPrompt() {
        return SystemPrompt.get();
    }
}
