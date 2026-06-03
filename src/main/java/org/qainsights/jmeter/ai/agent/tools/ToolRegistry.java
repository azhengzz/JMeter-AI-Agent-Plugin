package org.qainsights.jmeter.ai.agent.tools;

import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.qainsights.jmeter.ai.agent.model.ToolDefinition;
import org.qainsights.jmeter.ai.agent.model.ToolEvent;
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
    private static final long DEFAULT_TIMEOUT_MS = 30000; // 30 seconds default timeout

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Executor executor;
    private final long defaultTimeoutMs;

    public ToolRegistry() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public ToolRegistry(long defaultTimeoutMs) {
        this(defaultTimeoutMs, Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "tool-executor");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public ToolRegistry(Executor executor) {
        this(DEFAULT_TIMEOUT_MS, executor);
    }

    public ToolRegistry(long defaultTimeoutMs, Executor executor) {
        this.defaultTimeoutMs = defaultTimeoutMs;
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
     * Get all tool definitions as ToolDefinition objects.
     * Used for passing to AI services that support tool calling.
     */
    public List<org.qainsights.jmeter.ai.agent.model.ToolDefinition> getToolDefinitionObjects() {
        return tools.values().stream()
                .map(tool -> org.qainsights.jmeter.ai.agent.model.ToolDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(parseJsonSchema(tool.getParameterSchema()))
                        .build())
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
        return executeWithEvent(name, parameters).result();
    }

    /**
     * Execute a tool synchronously and return result with event
     */
    public ToolExecutionResult executeWithEvent(String name, Map<String, Object> parameters) {
        long startTime = System.currentTimeMillis();
        Tool tool = tools.get(name);

        if (tool == null) {
            String error = String.format("Tool '%s' not found. Available tools: %s",
                    name, String.join(", ", getToolNames()));
            log.warn(error);
            long duration = System.currentTimeMillis() - startTime;
            return new ToolExecutionResult(
                ToolResult.error(error),
                ToolEvent.notFound(name, parameters)
            );
        }

        try {
            ToolResult result = tool.execute(parameters);
            long duration = System.currentTimeMillis() - startTime;

            // Create event based on result
            ToolEvent event;
            if (result.isSuccess()) {
                String detail = truncateDetail(result.getResult());
                event = ToolEvent.success(name, detail, duration, parameters);

                // Log tool call with arguments and result
                log.info("Tool {} executed in {}ms | Args: {} | Result: {}",
                    name, duration, formatArgumentsForLog(parameters), detail);
            } else {
                event = ToolEvent.error(name, result.getError(), duration, parameters);

                // Log tool call error with arguments
                log.warn("Tool {} failed in {}ms | Args: {} | Error: {}",
                    name, duration, formatArgumentsForLog(parameters), result.getError());
            }

            return new ToolExecutionResult(result, event);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String error = "Exception executing " + name + ": " + e.getMessage();
            log.error(error, e);
            return new ToolExecutionResult(
                ToolResult.error(error),
                ToolEvent.error(name, error, duration, parameters)
            );
        }
    }

    /**
     * Truncate detail for logging.
     * Uses ai.chat.tool.result.max.length configuration (shared with chat UI).
     */
    private String truncateDetail(String detail) {
        if (detail == null) return "";
        detail = detail.replace("\n", " ").trim();
        if (detail.isEmpty()) return "(empty)";

        // Read from configuration (shared with chat UI)
        int maxDetailLength = Integer.parseInt(
            org.qainsights.jmeter.ai.utils.AiConfig.getProperty("ai.chat.tool.result.max.length", "500"));

        if (detail.length() > maxDetailLength) return detail.substring(0, maxDetailLength) + "...(truncated)";
        return detail;
    }

    /**
     * Format arguments map for logging.
     * Uses ai.chat.tool.result.max.length configuration (shared with chat UI).
     */
    private String formatArgumentsForLog(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }

        // Read from configuration (shared with chat UI)
        int maxDetailLength = Integer.parseInt(
            org.qainsights.jmeter.ai.utils.AiConfig.getProperty("ai.chat.tool.result.max.length", "500"));

        String argsStr = parameters.toString();
        if (argsStr.length() > maxDetailLength) {
            return argsStr.substring(0, maxDetailLength) + "...(truncated)";
        }
        return argsStr;
    }

    /**
     * Record that combines ToolResult and ToolEvent
     */
    public static class ToolExecutionResult {
        private final ToolResult result;
        private final ToolEvent event;

        ToolExecutionResult(ToolResult result, ToolEvent event) {
            this.result = result;
            this.event = event;
        }

        public ToolResult result() { return result; }
        public ToolEvent event() { return event; }
    }

    /**
     * Execute a tool asynchronously
     */
    public CompletableFuture<ToolResult> executeAsync(String name, Map<String, Object> parameters) {
        return executeAsync(name, parameters, defaultTimeoutMs);
    }

    /**
     * Execute a tool asynchronously with timeout
     */
    public CompletableFuture<ToolResult> executeAsync(String name, Map<String, Object> parameters, long timeoutMs) {
        return executeAsyncWithEvent(name, parameters, timeoutMs)
                .thenApply(ToolExecutionResult::result);
    }

    /**
     * Execute a tool asynchronously with timeout, returning event as well
     */
    public CompletableFuture<ToolExecutionResult> executeAsyncWithEvent(String name, Map<String, Object> parameters, long timeoutMs) {
        // Check if tool has custom timeout
        Tool tool = tools.get(name);
        long effectiveTimeout = timeoutMs;
        if (tool != null && tool.getTimeoutMs() > 0) {
            effectiveTimeout = tool.getTimeoutMs();
        }

        final long finalTimeout = effectiveTimeout;
        return CompletableFuture.supplyAsync(() -> executeWithEvent(name, parameters), executor)
                .orTimeout(finalTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Tool execution timed out after {}ms: {}", finalTimeout, name);
                        return new ToolExecutionResult(
                            ToolResult.error("Tool execution timed out after " + finalTimeout + "ms"),
                            ToolEvent.timeout(name, finalTimeout, parameters)
                        );
                    }
                    return new ToolExecutionResult(
                        ToolResult.error("Tool execution failed: " + ex.getMessage()),
                        ToolEvent.error(name, "Tool execution failed: " + ex.getMessage(), 0, parameters)
                    );
                });
    }

    /**
     * Execute multiple tool calls asynchronously
     */
    public CompletableFuture<List<ToolResult>> executeAsync(List<ToolCall> toolCalls) {
        return executeAsyncWithEvents(toolCalls, defaultTimeoutMs)
                .thenApply(ToolBatchResult::results);
    }

    /**
     * Execute multiple tool calls asynchronously with events
     */
    public CompletableFuture<ToolBatchResult> executeAsyncWithEvents(List<ToolCall> toolCalls) {
        return executeAsyncWithEvents(toolCalls, defaultTimeoutMs);
    }

    /**
     * Execute multiple tool calls asynchronously with timeout
     * Tools are executed in priority order (higher priority first)
     */
    public CompletableFuture<List<ToolResult>> executeAsync(List<ToolCall> toolCalls, long timeoutMs) {
        return executeAsyncWithEvents(toolCalls, timeoutMs)
                .thenApply(ToolBatchResult::results);
    }

    /**
     * Execute multiple tool calls asynchronously with timeout and events
     * Tools are executed in priority order (higher priority first)
     */
    public CompletableFuture<ToolBatchResult> executeAsyncWithEvents(List<ToolCall> toolCalls, long timeoutMs) {
        // Sort tool calls by priority (highest first) while keeping track of original index
        List<IndexedToolCall> sortedCalls = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            sortedCalls.add(new IndexedToolCall(toolCalls.get(i), i));
        }
        sortedCalls.sort((a, b) -> {
            int priorityA = getPriority(a.call.getName());
            int priorityB = getPriority(b.call.getName());
            return Integer.compare(priorityB, priorityA); // Descending order
        });

        // Execute all tools
        List<CompletableFuture<IndexedToolExecutionResult>> futures = sortedCalls.stream()
                .map(item -> executeAsyncWithEvent(item.call.getName(), item.call.getArguments(), timeoutMs)
                        .thenApply(result -> new IndexedToolExecutionResult(result, item.originalIndex)))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Return results in original order
                    List<IndexedToolExecutionResult> indexedResults = futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList());
                    indexedResults.sort((a, b) -> Integer.compare(a.originalIndex, b.originalIndex));

                    List<ToolResult> results = indexedResults.stream()
                            .map(ir -> ir.executionResult.result())
                            .collect(Collectors.toList());
                    List<ToolEvent> events = indexedResults.stream()
                            .map(ir -> ir.executionResult.event())
                            .collect(Collectors.toList());

                    return new ToolBatchResult(results, events);
                });
    }

    /**
     * Batch result containing tool results and events
     */
    public static class ToolBatchResult {
        private final List<ToolResult> results;
        private final List<ToolEvent> events;

        ToolBatchResult(List<ToolResult> results, List<ToolEvent> events) {
            this.results = results;
            this.events = events;
        }

        public List<ToolResult> results() { return results; }
        public List<ToolEvent> events() { return events; }
    }

    /**
     * Get the priority of a tool by name
     */
    private int getPriority(String toolName) {
        Tool tool = tools.get(toolName);
        return tool != null ? tool.getPriority() : 0;
    }

    /**
     * Helper class to track original index of tool calls
     */
    private static class IndexedToolCall {
        final ToolCall call;
        final int originalIndex;

        IndexedToolCall(ToolCall call, int originalIndex) {
            this.call = call;
            this.originalIndex = originalIndex;
        }
    }

    /**
     * Helper class to track original index of results
     */
    private static class IndexedToolResult {
        final ToolResult result;
        final int originalIndex;

        IndexedToolResult(ToolResult result, int originalIndex) {
            this.result = result;
            this.originalIndex = originalIndex;
        }
    }

    /**
     * Helper class to track original index of execution results
     */
    private static class IndexedToolExecutionResult {
        final ToolExecutionResult executionResult;
        final int originalIndex;

        IndexedToolExecutionResult(ToolExecutionResult executionResult, int originalIndex) {
            this.executionResult = executionResult;
            this.originalIndex = originalIndex;
        }
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
     * Uses Jackson ObjectMapper for proper JSON parsing.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSchema(String schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(schema, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON schema for tool, using default: {}", e.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
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
