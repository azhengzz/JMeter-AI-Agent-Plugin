package org.qainsights.jmeter.ai.agent.model;

/**
 * Result of tool execution.
 * Contains success status, content, or error message.
 */
public class ToolResult {
    private final boolean success;
    private final String content;
    private final String error;

    private ToolResult(boolean success, String content, String error) {
        this.success = success;
        this.content = content;
        this.error = error;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public String getError() {
        return error;
    }

    /**
     * Create a successful result with content
     */
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null);
    }

    /**
     * Create an error result with error message
     */
    public static ToolResult error(String error) {
        return new ToolResult(false, null, error);
    }

    /**
     * Get the result content (either success content or error message)
     */
    public String getResult() {
        return success ? content : error;
    }

    @Override
    public String toString() {
        return "ToolResult{" +
                "success=" + success +
                ", content='" + (content != null ? truncate(content, 50) : "null") + '\'' +
                ", error='" + (error != null ? truncate(error, 50) : "null") + '\'' +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }
}
