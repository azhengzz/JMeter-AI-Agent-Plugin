package org.gitee.jmeter.ai.agent.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response from the LLM provider.
 * Contains content, finish reason, tool calls, usage, and optional reasoning content.
 * Aligned with Nanobot's LLMResponse which carries a usage dict.
 */
public class LLMResponse {
    private final String content;
    private final String finishReason;
    private final List<ToolCall> toolCalls;
    private final boolean hasToolCalls;
    private final String reasoningContent;
    private final String errorMessage;
    private final Map<String, Integer> usage;
    private final boolean streamed;
    private final boolean retryable;
    private final Integer statusCode;
    private final String errorKind;

    private LLMResponse(Builder builder) {
        this.content = builder.content;
        this.finishReason = builder.finishReason != null ? builder.finishReason : "unknown";
        this.toolCalls = builder.toolCalls != null ? builder.toolCalls : Collections.emptyList();
        this.hasToolCalls = !this.toolCalls.isEmpty();
        this.reasoningContent = builder.reasoningContent;
        this.errorMessage = builder.errorMessage;
        this.usage = builder.usage != null ? Collections.unmodifiableMap(new HashMap<>(builder.usage)) : Collections.emptyMap();
        this.streamed = builder.streamed;
        this.retryable = builder.retryable;
        this.statusCode = builder.statusCode;
        this.errorKind = builder.errorKind;
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

    /** Token usage from the LLM API response (prompt_tokens, completion_tokens). */
    public Map<String, Integer> getUsage() {
        return usage;
    }

    /**
     * Copy with the streamed flag overridden — the runner ORs the sticky
     * "delivered deltas this logical call" mark (across attempts and fallbacks)
     * onto the final response so suppression keys off a single fact source.
     */
    public LLMResponse withStreamed(boolean streamed) {
        Builder copy = builder()
                .content(content)
                .finishReason(finishReason)
                .toolCalls(toolCalls)
                .reasoningContent(reasoningContent)
                .usage(usage)
                .streamed(streamed)
                .retryable(retryable)
                .statusCode(statusCode)
                .errorKind(errorKind);
        // errorMessage(null) 会无条件把 finishReason 毒化为 "error"——仅真实错误才走该 setter
        if (errorMessage != null) {
            copy.errorMessage(errorMessage);
        }
        return copy.build();
    }

    public boolean isLength() {
        return "length".equals(finishReason);
    }

    /**
     * Whether this response's text was already delivered to consumers as
     * streaming deltas (provider streaming path). The runner ORs this across
     * attempts of one logical call, so a later non-streaming attempt that
     * succeeds after an earlier attempt already emitted deltas still reports
     * true — suppression and the streamed-final marker key off this.
     */
    public boolean isStreamed() {
        return streamed;
    }

    /** Whether a failed (error) response is eligible for the runner's retry loop. */
    public boolean isRetryable() {
        return retryable;
    }

    /** HTTP status code of the underlying provider failure, when known. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** Error category of the underlying provider failure (e.g. network, timeout, auth, rate_limit, eof). */
    public String getErrorKind() {
        return errorKind;
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
        private Map<String, Integer> usage;
        private boolean streamed;
        private boolean retryable;
        private Integer statusCode;
        private String errorKind;

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

        public Builder usage(Map<String, Integer> usage) {
            this.usage = usage;
            return this;
        }

        public Builder streamed(boolean streamed) {
            this.streamed = streamed;
            return this;
        }

        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        public Builder statusCode(Integer statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder errorKind(String errorKind) {
            this.errorKind = errorKind;
            return this;
        }

        public LLMResponse build() {
            return new LLMResponse(this);
        }
    }

    /** Create an error response. */
    public static LLMResponse error(String errorMessage) {
        return builder()
                .content(null)
                .errorMessage(errorMessage)
                .build();
    }

    /** Create a classified error response (retry classification for the runner's retry loop). */
    public static LLMResponse error(String errorMessage, boolean retryable, Integer statusCode, String errorKind) {
        return builder()
                .content(null)
                .errorMessage(errorMessage)
                .retryable(retryable)
                .statusCode(statusCode)
                .errorKind(errorKind)
                .build();
    }

    /** Create a text-only response. */
    public static LLMResponse text(String content) {
        return builder()
                .content(content)
                .finishReason("stop")
                .build();
    }

    /** Create a response with tool calls. */
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
                ", usage=" + usage +
                ", isError=" + isError() +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...(truncated)";
    }
}
