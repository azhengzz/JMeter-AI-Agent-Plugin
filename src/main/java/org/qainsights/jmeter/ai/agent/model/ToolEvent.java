package org.qainsights.jmeter.ai.agent.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a tool execution event.
 * Tracks the outcome of each tool call for debugging and monitoring.
 */
public class ToolEvent {
    public enum Status {
        OK,
        ERROR,
        TIMEOUT,
        NOT_FOUND
    }

    private final String toolName;
    private final Status status;
    private final String detail;
    private final long durationMs;
    private final LocalDateTime timestamp;
    private final Map<String, Object> metadata;

    private ToolEvent(Builder builder) {
        this.toolName = builder.toolName;
        this.status = builder.status;
        this.detail = builder.detail;
        this.durationMs = builder.durationMs;
        this.timestamp = builder.timestamp != null ? builder.timestamp : LocalDateTime.now();
        this.metadata = builder.metadata != null ? builder.metadata : new HashMap<>();
    }

    public String getToolName() { return toolName; }
    public Status getStatus() { return status; }
    public String getDetail() { return detail; }
    public long getDurationMs() { return durationMs; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Map<String, Object> getMetadata() { return metadata; }

    public boolean isSuccess() { return status == Status.OK; }
    public boolean isError() { return status == Status.ERROR || status == Status.TIMEOUT; }

    @Override
    public String toString() {
        return String.format("ToolEvent{name='%s', status=%s, detail='%s', duration=%dms}",
                toolName, status, truncate(detail, 80), durationMs);
    }

    private String truncate(String str, int max) {
        if (str == null) return "null";
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", toolName);
        map.put("status", status.name().toLowerCase());
        map.put("detail", detail);
        map.put("duration_ms", durationMs);
        map.putAll(metadata);
        return map;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String toolName;
        private Status status = Status.OK;
        private String detail = "";
        private long durationMs;
        private LocalDateTime timestamp;
        private Map<String, Object> metadata;

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder putMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public ToolEvent build() {
            return new ToolEvent(this);
        }
    }

    /**
     * Create a success event
     */
    public static ToolEvent success(String toolName, String detail, long durationMs) {
        return builder()
                .toolName(toolName)
                .status(Status.OK)
                .detail(detail)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Create an error event
     */
    public static ToolEvent error(String toolName, String errorDetail, long durationMs) {
        return builder()
                .toolName(toolName)
                .status(Status.ERROR)
                .detail(errorDetail)
                .durationMs(durationMs)
                .build();
    }

    /**
     * Create a timeout event
     */
    public static ToolEvent timeout(String toolName, long durationMs) {
        return builder()
                .toolName(toolName)
                .status(Status.TIMEOUT)
                .detail("Tool execution timed out")
                .durationMs(durationMs)
                .build();
    }

    /**
     * Create a not found event
     */
    public static ToolEvent notFound(String toolName) {
        return builder()
                .toolName(toolName)
                .status(Status.NOT_FOUND)
                .detail("Tool not found")
                .durationMs(0)
                .build();
    }
}
