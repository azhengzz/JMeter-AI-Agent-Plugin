package org.gitee.jmeter.ai.agent.model;

import java.util.Collections;
import java.util.List;

/**
 * Final response from the Agent Loop.
 * Contains the generated content, tools used, and updated session.
 */
public class AgentResponse {
    private final String content;
    private final List<String> toolsUsed;
    private final int iterationCount;
    private final boolean success;
    private final String errorMessage;
    private final List<ToolEvent> toolEvents;

    private AgentResponse(Builder builder) {
        this.content = builder.content;
        this.toolsUsed = builder.toolsUsed != null ? builder.toolsUsed : Collections.emptyList();
        this.iterationCount = builder.iterationCount;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.toolEvents = builder.toolEvents != null ? builder.toolEvents : Collections.emptyList();
    }

    public String getContent() {
        return content;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<ToolEvent> getToolEvents() {
        return toolEvents;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String content;
        private List<String> toolsUsed;
        private int iterationCount;
        private boolean success = true;
        private String errorMessage;
        private List<ToolEvent> toolEvents;

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder toolsUsed(List<String> toolsUsed) {
            this.toolsUsed = toolsUsed;
            return this;
        }

        public Builder iterationCount(int iterationCount) {
            this.iterationCount = iterationCount;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            this.success = false;
            return this;
        }

        public Builder toolEvents(List<ToolEvent> toolEvents) {
            this.toolEvents = toolEvents;
            return this;
        }

        public AgentResponse build() {
            return new AgentResponse(this);
        }
    }

    /**
     * Create a successful response
     */
    public static AgentResponse success(String content) {
        return builder()
                .content(content)
                .build();
    }

    /**
     * Create a successful response with tools used
     */
    public static AgentResponse success(String content, List<String> toolsUsed, int iterationCount) {
        return builder()
                .content(content)
                .toolsUsed(toolsUsed)
                .iterationCount(iterationCount)
                .build();
    }

    /**
     * Create a successful response with tools used and tool events
     */
    public static AgentResponse success(String content, List<String> toolsUsed, int iterationCount, List<ToolEvent> toolEvents) {
        return builder()
                .content(content)
                .toolsUsed(toolsUsed)
                .iterationCount(iterationCount)
                .toolEvents(toolEvents)
                .build();
    }

    /**
     * Create an error response
     */
    public static AgentResponse error(String errorMessage) {
        return builder()
                .errorMessage(errorMessage)
                .build();
    }

    @Override
    public String toString() {
        return "AgentResponse{" +
                "success=" + success +
                ", content='" + (content != null ? truncate(content, 50) : "null") + '\'' +
                ", toolsUsed=" + toolsUsed +
                ", iterationCount=" + iterationCount +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...(truncated)";
    }
}
