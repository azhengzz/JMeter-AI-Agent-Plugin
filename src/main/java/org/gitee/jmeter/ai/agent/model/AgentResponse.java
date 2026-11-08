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
    /** 终答流式标记：终答正文已经增量呈现（粘滞事实源，跨尝试/回退保留）。 */
    private final boolean streamedFinal;
    /** 终答所属最终流段 id（面板据此查找原位重渲的流式卡；未流式为 null）。 */
    private final Long finalStreamId;

    private AgentResponse(Builder builder) {
        this.content = builder.content;
        this.toolsUsed = builder.toolsUsed != null ? builder.toolsUsed : Collections.emptyList();
        this.iterationCount = builder.iterationCount;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.toolEvents = builder.toolEvents != null ? builder.toolEvents : Collections.emptyList();
        this.streamedFinal = builder.streamedFinal;
        this.finalStreamId = builder.finalStreamId;
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

    /** 终答流式标记：为 true 时面板按 {@link #getFinalStreamId()} 查卡原位重渲，不新增重复卡。 */
    public boolean isStreamedFinal() {
        return streamedFinal;
    }

    public Long getFinalStreamId() {
        return finalStreamId;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Copy-all-fields builder seed（流式标记透传等派生拷贝用）。 */
    public Builder toBuilder() {
        return builder()
                .content(content)
                .toolsUsed(toolsUsed)
                .iterationCount(iterationCount)
                .success(success)
                .toolEvents(toolEvents)
                .streamedFinal(streamedFinal)
                .finalStreamId(finalStreamId);
    }

    public static class Builder {
        private String content;
        private List<String> toolsUsed;
        private int iterationCount;
        private boolean success = true;
        private String errorMessage;
        private List<ToolEvent> toolEvents;
        private boolean streamedFinal;
        private Long finalStreamId;

        public Builder streamedFinal(boolean streamedFinal) { this.streamedFinal = streamedFinal; return this; }
        public Builder finalStreamId(Long finalStreamId) { this.finalStreamId = finalStreamId; return this; }

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
