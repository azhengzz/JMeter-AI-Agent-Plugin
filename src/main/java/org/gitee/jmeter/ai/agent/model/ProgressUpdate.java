package org.gitee.jmeter.ai.agent.model;

import java.util.Map;

/**
 * Progress update from Agent Loop execution.
 * Used to communicate typed progress from the agent loop to the UI.
 */
public class ProgressUpdate {
    private final String message;
    private final Type type;
    private final long timestamp;
    private final Object payload;

    public ProgressUpdate(String message, Type type) {
        this(message, type, null);
    }

    public ProgressUpdate(String message, Type type, Object payload) {
        this.message = message;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.payload = payload;
    }

    public String getMessage() {
        return message;
    }

    public Type getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Optional structured payload. For TOOL_CALL type this carries a ToolEvent.
     */
    public Object getPayload() {
        return payload;
    }

    public enum Type {
        /** General progress update */
        PROGRESS,
        /**
         * 工具调用开始（载荷 = {@code List<ToolCall>}，本批即将执行）。先于执行
         * 发射——GUI 据此先行呈现运行态工具行，完成态经 {@link #TOOL_CALL}
         * 原位收敛；message 恒空（只认结构化载荷）。
         */
        TOOL_STARTED,
        /** Tool execution result (payload = ToolEvent) or tool hint (payload = null) */
        TOOL_CALL,
        /** Thinking/reasoning content */
        THINKING,
        /** Error message */
        ERROR,
        /** Intermediate AI response before injection continues (content = response text) */
        INTERMEDIATE_RESPONSE,
        /**
         * Token usage from the latest LLM call (payload = Map&lt;String, Integer&gt; with
         * prompt_tokens / completion_tokens). Faces the context-usage indicator, not the
         * chat text rendering domain: message is always empty and consumers that render
         * text or manage the loading indicator must skip this type.
         */
        USAGE,
        /**
         * 流式流段开始（载荷 = StreamDelta，attemptSeq>1 表示同段重试将整段替换）。
         * 懒发射：不早于该段首个增量。
         */
        STREAM_STARTED,
        /** 流式正文通道累计文本（载荷 = StreamDelta，幂等整段渲染）。 */
        CONTENT_DELTA,
        /** 流式思考通道累计文本（载荷 = StreamDelta，幂等整段渲染）。 */
        REASONING_DELTA,
        /** 流式流段终态（载荷 = StreamDelta，含 SUCCESS/FAILED/ABORTED 与两通道最终累计）。 */
        STREAM_ENDED
    }

    public static ProgressUpdate progress(String message) {
        return new ProgressUpdate(message, Type.PROGRESS);
    }

    public static ProgressUpdate toolCall(String hint) {
        return new ProgressUpdate(hint, Type.TOOL_CALL);
    }

    /** 工具调用开始（载荷 = 本批 ToolCall 列表；message 恒空）。 */
    public static ProgressUpdate toolStart(java.util.List<ToolCall> toolCalls) {
        return new ProgressUpdate("", Type.TOOL_STARTED, toolCalls);
    }

    public static ProgressUpdate toolCall(ToolEvent event) {
        return new ProgressUpdate(formatToolEvent(event), Type.TOOL_CALL, event);
    }

    public static ProgressUpdate thinking(String message) {
        return new ProgressUpdate(message, Type.THINKING);
    }

    public static ProgressUpdate error(String message) {
        return new ProgressUpdate(message, Type.ERROR);
    }

    public static ProgressUpdate intermediateResponse(String content) {
        return new ProgressUpdate(content, Type.INTERMEDIATE_RESPONSE);
    }

    public static ProgressUpdate usage(Map<String, Integer> usage) {
        return new ProgressUpdate("", Type.USAGE, usage);
    }

    /** 流段事件（载荷 = StreamDelta）；message 恒空——流式事件只认结构化载荷，不渲染文本行。 */
    public static ProgressUpdate stream(StreamDelta delta) {
        Type type = switch (delta.getKind()) {
            case STREAM_STARTED -> Type.STREAM_STARTED;
            case CONTENT_DELTA -> Type.CONTENT_DELTA;
            case REASONING_DELTA -> Type.REASONING_DELTA;
            case STREAM_ENDED -> Type.STREAM_ENDED;
        };
        return new ProgressUpdate("", type, delta);
    }

    private static String formatToolEvent(ToolEvent event) {
        String statusIcon = switch (event.getStatus()) {
            case OK -> "✓";
            case ERROR -> "✗";
            case TIMEOUT -> "⏱";
            case NOT_FOUND -> "?";
        };
        return statusIcon + " " + event.getToolName() + " [" + event.getDurationMs() + "ms]";
    }

    @Override
    public String toString() {
        return "ProgressUpdate{" +
                "type=" + type +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
