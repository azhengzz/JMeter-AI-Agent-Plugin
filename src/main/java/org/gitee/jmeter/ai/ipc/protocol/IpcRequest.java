package org.gitee.jmeter.ai.ipc.protocol;

import java.util.Map;

/**
 * IPC 请求信封,server 与 CLI 共用。纯 POJO,零 jmeter 依赖。
 *
 * <p>op 取值:
 * <ul>
 *   <li>{@code "tool"} —— 调用白名单内的工具,需填 {@link #tool} + {@link #params}</li>
 *   <li>{@code "agent"} —— 把 {@link #message} 推给 AgentLoop,可选 {@link #session}(默认 jmeter-ai-chat)</li>
 *   <li>{@code "health"} —— 健康检查(由 GET /health 处理,通常不发 body)</li>
 * </ul>
 *
 * <p>鉴权通过 HTTP header {@code X-IPC-Token} 传递,不在本信封里。
 */
public class IpcRequest {
    private String op;
    private String tool;
    private Map<String, Object> params;
    private String message;
    private String session;
    private String id;

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
}
