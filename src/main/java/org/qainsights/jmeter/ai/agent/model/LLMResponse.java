package org.qainsights.jmeter.ai.agent.model;

import java.util.Collections;
import java.util.List;

/**
 * Response from the LLM provider.
 * Contains content, finish reason, tool calls, and optional reasoning content.
 */
public class LLMResponse {
    private final String content;
    private final String finishReason;
    private final List<ToolCall> toolCalls;
    private final boolean hasToolCalls;
    private final String reasoningContent;
    private final String errorMessage;

    private LLMResponse(Builder builder) {
        this.content = builder.content;
        this.finishReason = builder.finishReason != null ? builder.finishReason : "unknown";
        this.toolCalls = builder.toolCalls != null ? builder.toolCalls : Collections.emptyList();
        this.hasToolCalls = !this.toolCalls.isEmpty();
        this.reasoningContent = builder.reasoningContent;
        this.errorMessage = builder.errorMessage;
    }

    public String getContent() {
        return content;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return hasToolCalls;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public boolean isError() {
        return "error".equals(finishReason) || errorMessage != null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isLength() {
        return "length".equals(finishReason);
    }

    public boolean isStop() {
        return "stop".equals(finishReason);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String content;
        private String finishReason;
        private List<ToolCall> toolCalls;
        private String reasoningContent;
        private String errorMessage;

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            this.finishReason = "error";
            return this;
        }

        public LLMResponse build() {
            return new LLMResponse(this);
        }
    }

    /**
     * Create an error response
     */
    public static LLMResponse error(String errorMessage) {
        return builder()
                .content(null)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a text-only response
     */
    public static LLMResponse text(String content) {
        return builder()
                .content(content)
                .finishReason("stop")
                .build();
    }

    /**
     * Create a response with tool calls
     */
    public static LLMResponse withToolCalls(List<ToolCall> toolCalls, String content) {
        return builder()
                .content(content)
                .toolCalls(toolCalls)
                .finishReason("tool_calls")
                .build();
    }

    @Override
    public String toString() {
        return "LLMResponse{" +
                "content='" + (content != null ? truncate(content, 50) : "null") + '\'' +
                ", finishReason='" + finishReason + '\'' +
                ", hasToolCalls=" + hasToolCalls +
                ", toolCallCount=" + toolCalls.size() +
                ", isError=" + isError() +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }
}
