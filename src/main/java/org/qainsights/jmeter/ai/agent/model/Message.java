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
    private final LocalDateTime timestamp;
    private final Map<String, Object> metadata;

    private Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, Map<String, Object> metadata) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.toolCallId = toolCallId;
        this.timestamp = LocalDateTime.now();
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    /**
     * Create a system message
     */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null);
    }

    /**
     * Create a user message
     */
    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    /**
     * Create an assistant message with optional tool calls
     */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, toolCalls, null, null);
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
        return new Message(Role.TOOL, content, null, toolCallId, Collections.singletonMap("toolName", toolName));
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

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "Message{" +
                "role=" + role +
                ", content='" + (content != null ? truncate(content, 50) : "null") + '\'' +
                ", hasToolCalls=" + hasToolCalls() +
                ", timestamp=" + timestamp +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }
}
