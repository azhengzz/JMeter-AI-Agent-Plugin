package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.session.Session;

import java.time.Instant;
import java.util.*;

/**
 * Result of an agent run.
 * Encapsulates the outcome and metadata of agent execution.
 */
public class AgentRunResult {

    private final String runId;
    private final String content;
    private final List<String> toolsUsed;
    private final int iterationCount;
    private final boolean success;
    private final String errorMessage;
    private final Instant startTime;
    private final Instant endTime;
    private final Session session;
    private final Map<String, Object> metadata;
    private final List<ToolEvent> toolEvents;
    private final List<Message> currentMessages;

    private AgentRunResult(Builder builder) {
        this.runId = builder.runId;
        this.content = builder.content;
        this.toolsUsed = builder.toolsUsed != null ? builder.toolsUsed : Collections.emptyList();
        this.iterationCount = builder.iterationCount;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.session = builder.session;
        this.metadata = builder.metadata != null ? builder.metadata : Collections.emptyMap();
        this.toolEvents = builder.toolEvents != null ? builder.toolEvents : Collections.emptyList();
        this.currentMessages = builder.currentMessages;
    }

    public String getRunId() { return runId; }
    public String getContent() { return content; }
    public List<String> getToolsUsed() { return toolsUsed; }
    public int getIterationCount() { return iterationCount; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public long getDurationMs() { return endTime.toEpochMilli() - startTime.toEpochMilli(); }
    public Session getSession() { return session; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<ToolEvent> getToolEvents() { return toolEvents; }
    public List<Message> getCurrentMessages() { return currentMessages; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String runId;
        private String content;
        private List<String> toolsUsed;
        private int iterationCount;
        private boolean success = true;
        private String errorMessage;
        private Instant startTime;
        private Instant endTime;
        private Session session;
        private Map<String, Object> metadata;
        private List<ToolEvent> toolEvents;
        private List<Message> currentMessages;

        public Builder runId(String runId) { this.runId = runId; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder toolsUsed(List<String> tools) { this.toolsUsed = tools; return this; }
        public Builder iterationCount(int count) { this.iterationCount = count; return this; }
        public Builder success(boolean success) { this.success = success; return this; }
        public Builder errorMessage(String error) { this.errorMessage = error; return this; }
        public Builder startTime(Instant time) { this.startTime = time; return this; }
        public Builder endTime(Instant time) { this.endTime = time; return this; }
        public Builder session(Session session) { this.session = session; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder toolEvents(List<ToolEvent> events) { this.toolEvents = events; return this; }
        public Builder currentMessages(List<Message> currentMessages) { this.currentMessages = currentMessages; return this; }

        public AgentRunResult build() {
            return new AgentRunResult(this);
        }
    }

    /**
     * Convert to legacy AgentResponse for backward compatibility.
     */
    public org.gitee.jmeter.ai.agent.model.AgentResponse toAgentResponse() {
        if (success) {
            return org.gitee.jmeter.ai.agent.model.AgentResponse.success(
                content, toolsUsed, iterationCount, toolEvents
            );
        } else {
            return org.gitee.jmeter.ai.agent.model.AgentResponse.error(errorMessage);
        }
    }
}
