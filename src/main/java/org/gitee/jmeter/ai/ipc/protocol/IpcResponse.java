package org.gitee.jmeter.ai.ipc.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * IPC 响应信封,server 与 CLI 共用。纯 POJO,零 jmeter 依赖。
 *
 * <p>镜像 {@code ToolResult} 的 {@code success/content/error},并附带 Agent 路由的元信息
 * ({@code toolsUsed/iterations/errorMessage})与耗时。{@code ToolResult}/{@code AgentResponse}
 * 到本类的转换由 {@code IpcServer} 负责,以保持协议层零依赖。
 *
 * <p>取消/超时载荷(可选,仅 409/504 分支填充):{@code cancelled} 标记回合未跑完即被取消,
 * {@code cancelReason} 区分终止途径({@code cancelled_by_target_user}=目标实例用户 STOP,
 * {@code timeout}=对端等待超时自取消),{@code partialContent} 携带截至终止已产生的助手部分内容。
 * 三者均按 {@code NON_DEFAULT}/{@code NON_NULL} 省略——正常成功响应序列化形状与旧版本逐字节一致。
 * {@link JsonIgnoreProperties} 与 {@code IpcRequest} 同理:容忍未来新增字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpcResponse {

    /** {@link #cancelReason}:目标实例用户点击 STOP 终止。 */
    public static final String CANCEL_REASON_USER_STOP = "cancelled_by_target_user";
    /** {@link #cancelReason}:对端等待超时,目标实例按超时机制自取消。 */
    public static final String CANCEL_REASON_TIMEOUT = "timeout";

    private boolean success;
    private String content;
    private String error;
    private long durationMs;
    private List<String> toolsUsed;
    private Integer iterations;
    private String errorMessage;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean cancelled;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cancelReason;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String partialContent;

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

    /** 回合是否未跑完即被取消(人工 STOP 或超时自取消);正常响应恒 false 且不序列化。 */
    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /** 终止途径:{@code cancelled_by_target_user} / {@code timeout};未取消时为 null。 */
    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    /** 截至终止已产生的助手部分内容(尽力而为,截断上限由 server 侧裁定);未取消时为 null。 */
    public String getPartialContent() {
        return partialContent;
    }

    public void setPartialContent(String partialContent) {
        this.partialContent = partialContent;
    }
}
