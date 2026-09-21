package org.gitee.jmeter.ai.ipc.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * IPC 请求信封,server 与 CLI 共用。纯 POJO,零 jmeter 依赖。
 *
 * <p>op 取值:
 * <ul>
 *   <li>{@code "tool"} —— 调用白名单内的工具,需填 {@link #tool} + {@link #params}</li>
 *   <li>{@code "agent"} —— 把 {@link #message} 推给 AgentLoop,可选 {@link #session}(默认目标实例的 instanceId)</li>
 *   <li>{@code "health"} —— 健康检查(由 GET /health 处理,通常不发 body)</li>
 * </ul>
 *
 * <p>鉴权通过 HTTP header {@code X-IPC-Token} 传递,不在本信封里。
 *
 * <p>{@link JsonIgnoreProperties} 容忍未知字段,保证信封向后兼容地演进。
 * {@code delegated} 用 {@link JsonInclude#NON_DEFAULT} 让 {@code false} 不上线——
 * 否则新→旧版本混布(lib/ext 旧 jar 残留)时,旧端 strict Jackson
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=true})会把每个普通请求 400。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpcRequest {
    private String op;
    private String tool;
    private Map<String, Object> params;
    private String message;
    private String session;
    private String id;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean delegated;

    public IpcRequest() {
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * 本请求是否来自另一实例 {@code delegate_to_instance} 的委派。
     * 接收侧据此在该 Agent 回合内置 {@code DelegationGuard},回合内再委派直接报错
     * (深度 1 硬阻断,防 A↔B 互相委派 ping-pong 互卡到超时)。CLI 直连对话不设(false)。
     */
    public boolean isDelegated() {
        return delegated;
    }

    public void setDelegated(boolean delegated) {
        this.delegated = delegated;
    }
}
