package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 进程内可复用的 IPC 客户端:对指定实例(host/port/token)经 loopback HTTP 投递请求。
 *
 * <p><b>JMeter-free</b> —— 只依赖 JDK {@link HttpClient}、Jackson 与 {@code ipc.protocol.*},
 * 供主插件内的跨实例委派工具({@code DelegateToInstanceTool})与 CLI({@code JmeterCli})共用同一传输。
 * 实例发现(port/token 解析)由调用方经 {@link InstanceRegistry} 完成,本类只负责传输。
 *
 * <p>{@link HttpClient} 为共享单例(线程安全、可重用);{@code IpcClient} 实例本身轻量(仅存 host/port/token)。
 */
public final class IpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String host;
    private final int port;
    private final String token;

    public IpcClient(String host, int port, String token) {
        this.host = (host == null || host.isEmpty()) ? "127.0.0.1" : host;
        this.port = port;
        this.token = token;
    }

    /**
     * POST 一个 {@link IpcRequest}(JSON),返回原始 HTTP 响应。供需要 HTTP 状态码的调用方(CLI)使用。
     */
    public HttpResponse<String> post(String endpoint, IpcRequest req, long timeoutMs) throws Exception {
        String body = MAPPER.writeValueAsString(req);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-IPC-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 投递 agent 消息(POST /agent),阻塞至目标 Agent 回合完成或超时。返回解析后的 {@link IpcResponse}。
     *
     * @param message   委派给目标实例 Agent 的自然语言指令
     * @param session   目标会话键;null/空 则用目标实例默认会话(其 instanceId)
     * @param timeoutMs 阻塞超时(复用 {@code jmeter.ai.ipc.agent.timeout.ms});超时由调用方据此取消目标活动任务
     */
    public IpcResponse postAgent(String message, String session, long timeoutMs) throws Exception {
        return postAgent(message, session, timeoutMs, false);
    }

    /**
     * 投递 agent 消息(POST /agent)并声明来源:跨实例委派传 {@code delegated=true},
     * 接收侧在该回合内禁止再次委派(深度 1 硬阻断,防 A↔B ping-pong);CLI 直连传 false。
     */
    public IpcResponse postAgent(String message, String session, long timeoutMs, boolean delegated) throws Exception {
        IpcRequest req = new IpcRequest();
        req.setOp("agent");
        req.setMessage(message);
        if (session != null && !session.isEmpty()) {
            req.setSession(session);
        }
        req.setDelegated(delegated);
        HttpResponse<String> resp = post("/agent", req, timeoutMs);
        return MAPPER.readValue(resp.body(), IpcResponse.class);
    }
}
