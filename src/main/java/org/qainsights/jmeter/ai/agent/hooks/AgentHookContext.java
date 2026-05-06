package org.qainsights.jmeter.ai.agent.hooks;

import org.qainsights.jmeter.ai.agent.model.LLMResponse;
import org.qainsights.jmeter.ai.agent.model.ToolEvent;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.session.Session;

import java.time.Instant;
import java.util.*;

/**
 * Context object passed to AgentHook methods.
 * Contains state information about the current agent run.
 */
public class AgentHookContext {

    private final String runId;
    private final Session session;
    private final String userMessage;
    private final Instant startTime;

    // Mutable state
    private int currentIteration;
    private int totalIterations;
    private List<String> toolsUsed;
    private Map<String, Object> metadata;

    // Latest results
    private LLMResponse lastLlmResponse;
    private List<ToolResult> lastToolResults;

    // Token usage tracking
    private Map<String, Integer> usage;
    private String stopReason;
    private String error;

    // Tool execution events
    private List<ToolEvent> toolEvents;

    public AgentHookContext(String runId, Session session, String userMessage) {
        this.runId = runId;
        this.session = session;
        this.userMessage = userMessage;
        this.startTime = Instant.now();
        this.currentIteration = 0;
        this.totalIterations = 0;
        this.toolsUsed = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.usage = new HashMap<>();
        this.stopReason = "unknown";
        this.toolEvents = new ArrayList<>();
    }

    // Getters
    public String getRunId() { return runId; }
    public Session getSession() { return session; }
    public String getUserMessage() { return userMessage; }
    public Instant getStartTime() { return startTime; }
    public int getCurrentIteration() { return currentIteration; }
    public int getTotalIterations() { return totalIterations; }
    public List<String> getToolsUsed() { return Collections.unmodifiableList(toolsUsed); }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public LLMResponse getLastLlmResponse() { return lastLlmResponse; }
    public List<ToolResult> getLastToolResults() { return lastToolResults; }
    public Map<String, Integer> getUsage() { return Collections.unmodifiableMap(usage); }
    public String getStopReason() { return stopReason; }
    public String getError() { return error; }
    public List<ToolEvent> getToolEvents() { return Collections.unmodifiableList(toolEvents); }

    // Setters (for internal use by AgentRunner)
    public void setCurrentIteration(int iteration) { this.currentIteration = iteration; }
    public void setTotalIterations(int iterations) { this.totalIterations = iterations; }
    public void addToolUsed(String toolName) { this.toolsUsed.add(toolName); }
    public void setLastLlmResponse(LLMResponse response) { this.lastLlmResponse = response; }
    public void setLastToolResults(List<ToolResult> results) { this.lastToolResults = results; }
    public void putMetadata(String key, Object value) { this.metadata.put(key, value); }
    public void setUsage(Map<String, Integer> usage) { this.usage = usage != null ? usage : new HashMap<>(); }
    public void setUsage(String key, int value) { this.usage.put(key, value); }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public void setError(String error) { this.error = error; }
    public void addToolEvent(ToolEvent event) { this.toolEvents.add(event); }
    public void setToolEvents(List<ToolEvent> events) { this.toolEvents = events != null ? new ArrayList<>(events) : new ArrayList<>(); }

    /**
     * Get total tokens used (prompt + completion)
     */
    public int getTotalTokens() {
        return usage.getOrDefault("prompt_tokens", 0) + usage.getOrDefault("completion_tokens", 0);
    }

    /**
     * Check if any tool execution failed
     */
    public boolean hasToolErrors() {
        return toolEvents.stream().anyMatch(ToolEvent::isError);
    }

    /**
     * Get failed tool events
     */
    public List<ToolEvent> getFailedToolEvents() {
        return toolEvents.stream()
                .filter(ToolEvent::isError)
                .toList();
    }
}
