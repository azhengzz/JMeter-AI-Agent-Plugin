package org.qainsights.jmeter.ai.agent.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Message model for Agent Loop communication.
 * Represents messages in different roles: SYSTEM, USER, ASSISTANT, TOOL
 */
public class Message {
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String reasoningContent;
    private final LocalDateTime timestamp;
    private final Map<String, Object> metadata;

    private Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId,
                    String reasoningContent, Map<String, Object> metadata) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.toolCallId = toolCallId;
        this.reasoningContent = reasoningContent;
        this.timestamp = LocalDateTime.now();
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    /**
     * Create a system message
     */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null, null);
    }

    /**
     * Create a user message
     */
    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null, null);
    }

    /**
     * Create an assistant message with optional tool calls
     */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, toolCalls, null, null, null);
    }

    /**
     * Create an assistant message with optional tool calls and reasoning content
     */
    public static Message assistant(String content, List<ToolCall> toolCalls, String reasoningContent) {
        return new Message(Role.ASSISTANT, content, toolCalls, null, reasoningContent, null);
    }

    /**
     * Create an assistant message with only content (no tool calls)
     */
    public static Message assistant(String content) {
        return assistant(content, null);
    }

    /**
     * Create a tool result message
     */
    public static Message tool(String toolCallId, String toolName, String content) {
        return new Message(Role.TOOL, content, null, toolCallId, null, Collections.singletonMap("toolName", toolName));
    }

    // Getters
    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public boolean hasReasoningContent() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public String getToolName() {
        if (metadata != null && metadata.containsKey("toolName")) {
            return String.valueOf(metadata.get("toolName"));
        }
        return null;
    }

    @Override
    public String toString() {
        return "Message{" +
                "role=" + role +
                ", content='" + (content != null ? truncate(content, 50) : "null") + '\'' +
                ", hasToolCalls=" + hasToolCalls() +
                ", hasReasoning=" + hasReasoningContent() +
                ", timestamp=" + timestamp +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...(truncated)";
    }

    /**
     * Create a message builder for complex message construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder pattern for Message construction.
     */
    public static class Builder {
        private Role role;
        private String content;
        private List<ToolCall> toolCalls;
        private String toolCallId;
        private String reasoningContent;
        private Map<String, Object> metadata;

        public Builder role(Role role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Message build() {
            return new Message(role, content, toolCalls, toolCallId, reasoningContent, metadata);
        }
    }
}
