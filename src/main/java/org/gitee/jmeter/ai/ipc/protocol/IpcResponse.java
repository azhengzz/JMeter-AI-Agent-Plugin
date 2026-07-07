package org.gitee.jmeter.ai.ipc.protocol;

import java.util.List;

/**
 * IPC 响应信封,server 与 CLI 共用。纯 POJO,零 jmeter 依赖。
 *
 * <p>镜像 {@code ToolResult} 的 {@code success/content/error},并附带 Agent 路由的元信息
 * ({@code toolsUsed/iterations/errorMessage})与耗时。{@code ToolResult}/{@code AgentResponse}
 * 到本类的转换由 {@code IpcServer} 负责,以保持协议层零依赖。
 */
public class IpcResponse {
    private boolean success;
    private String content;
    private String error;
    private long durationMs;
    private List<String> toolsUsed;
    private Integer iterations;
    private String errorMessage;

    public IpcResponse() {
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public List<String> getToolsUsed() {
        return toolsUsed;
    }

    public void setToolsUsed(List<String> toolsUsed) {
        this.toolsUsed = toolsUsed;
    }

    public Integer getIterations() {
        return iterations;
    }

    public void setIterations(Integer iterations) {
        this.iterations = iterations;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
