package org.qainsights.jmeter.ai.agent.model;

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
        /** Tool execution result (payload = ToolEvent) or tool hint (payload = null) */
        TOOL_CALL,
        /** Thinking/reasoning content */
        THINKING,
        /** Error message */
        ERROR
    }

    public static ProgressUpdate progress(String message) {
        return new ProgressUpdate(message, Type.PROGRESS);
    }

    public static ProgressUpdate toolCall(String hint) {
        return new ProgressUpdate(hint, Type.TOOL_CALL);
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
