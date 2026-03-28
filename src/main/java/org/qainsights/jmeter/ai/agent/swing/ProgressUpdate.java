package org.qainsights.jmeter.ai.agent.swing;

/**
 * Progress update from Agent Loop execution.
 * Used to communicate progress from background thread to UI.
 */
public class ProgressUpdate {
    private final String message;
    private final Type type;
    private final long timestamp;

    public ProgressUpdate(String message, Type type) {
        this.message = message;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
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

    public enum Type {
        /** General progress update */
        PROGRESS,
        /** Tool execution notification */
        TOOL_CALL,
        /** Thinking/reasoning content */
        THINKING,
        /** Error message */
        ERROR
    }

    /**
     * Create a progress update
     */
    public static ProgressUpdate progress(String message) {
        return new ProgressUpdate(message, Type.PROGRESS);
    }

    /**
     * Create a tool call update
     */
    public static ProgressUpdate toolCall(String message) {
        return new ProgressUpdate(message, Type.TOOL_CALL);
    }

    /**
     * Create a thinking update
     */
    public static ProgressUpdate thinking(String message) {
        return new ProgressUpdate(message, Type.THINKING);
    }

    /**
     * Create an error update
     */
    public static ProgressUpdate error(String message) {
        return new ProgressUpdate(message, Type.ERROR);
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
