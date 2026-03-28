package org.qainsights.jmeter.ai.agent.tools;

import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Registry for managing and executing tools.
 * Thread-safe tool registration and execution.
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Executor executor;

    public ToolRegistry() {
        this(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "tool-executor");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public ToolRegistry(Executor executor) {
        this.executor = executor;
    }

    /**
     * Register a tool
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        Objects.requireNonNull(tool.getName(), "Tool name cannot be null");

        tools.put(tool.getName(), tool);
        log.info("Registered tool: {}", tool.getName());
    }

    /**
     * Unregister a tool by name
     */
    public void unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            log.info("Unregistered tool: {}", name);
        }
    }

    /**
     * Get a tool by name
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * Check if a tool is registered
     */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /**
     * Get all registered tool names
     */
    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * Get the number of registered tools
     */
    public int size() {
        return tools.size();
    }

    /**
     * Check if registry is empty
     */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * Get all tool definitions in OpenAI format
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return tools.values().stream()
                .map(this::toOpenAIToolDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get all tool definitions in Anthropic format
     */
    public List<Map<String, Object>> getAnthropicToolDefinitions() {
        return tools.values().stream()
                .map(this::toAnthropicToolDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Execute a tool synchronously
     */
    public ToolResult execute(String name, Map<String, Object> parameters) {
        Tool tool = tools.get(name);
        if (tool == null) {
            String error = String.format("Tool '%s' not found. Available tools: %s",
                    name, String.join(", ", getToolNames()));
            log.warn(error);
            return ToolResult.error(error);
        }

        return tool.execute(parameters);
    }

    /**
     * Execute a tool asynchronously
     */
    public CompletableFuture<ToolResult> executeAsync(String name, Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> execute(name, parameters), executor);
    }

    /**
     * Execute multiple tool calls asynchronously
     */
    public CompletableFuture<List<ToolResult>> executeAsync(List<ToolCall> toolCalls) {
        List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
                .map(call -> executeAsync(call.getName(), call.getArguments()))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    private Map<String, Object> toOpenAIToolDefinition(Tool tool) {
        try {
            return Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.getName(),
                            "description", tool.getDescription(),
                            "parameters", parseJsonSchema(tool.getParameterSchema())
                    )
            );
        } catch (Exception e) {
            log.error("Error converting tool {} to OpenAI format", tool.getName(), e);
            return Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.getName(),
                            "description", tool.getDescription(),
                            "parameters", Map.of("type", "object", "properties", Map.of())
                    )
            );
        }
    }

    private Map<String, Object> toAnthropicToolDefinition(Tool tool) {
        try {
            return Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "input_schema", parseJsonSchema(tool.getParameterSchema())
            );
        } catch (Exception e) {
            log.error("Error converting tool {} to Anthropic format", tool.getName(), e);
            return Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "input_schema", Map.of("type", "object", "properties", Map.of())
            );
        }
    }

    /**
     * Parse JSON Schema string to Map
     * Simple implementation - in production use a proper JSON parser
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSchema(String schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        // For now, return a basic schema
        // TODO: Use Jackson or Gson for proper JSON parsing
        return Map.of("type", "object", "properties", Map.of());
    }

    /**
     * Clear all registered tools
     */
    public void clear() {
        tools.clear();
        log.info("Cleared all tools from registry");
    }

    @Override
    public String toString() {
        return "ToolRegistry{" +
                "toolCount=" + tools.size() +
                ", tools=" + getToolNames() +
                '}';
    }
}
