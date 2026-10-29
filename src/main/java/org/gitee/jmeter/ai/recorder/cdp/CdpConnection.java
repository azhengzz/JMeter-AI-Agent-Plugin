package org.gitee.jmeter.ai.recorder.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gitee.jmeter.ai.recorder.RecorderJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CDP 消息层：递增 id + pending-future 表 + 按 {@code sessionId} 多路复用分发。
 *
 * <p>消息形态：命令 {@code {id, method, params?, sessionId?}}；响应 {@code {id, result|error}}；
 * 事件 {@code {method, params?, sessionId?}}（无 id）。
 */
public final class CdpConnection implements CdpTransport.Listener {
    private static final Logger log = LoggerFactory.getLogger(CdpConnection.class);

    private final CdpTransport transport;
    private final AtomicLong idGen = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile EventListener eventListener;

    public interface EventListener {
        void onEvent(String method, JsonNode params, String sessionId);

        void onTransportClosed();
    }

    public CdpConnection(CdpTransport transport) {
        this.transport = transport;
        transport.setListener(this);
    }

    public void connect(String wsUrl) throws Exception {
        transport.connect(wsUrl);
    }

    /** 发送命令（sessionId 为 null = browser 级）。返回 result 节点；error 时 future 异常完成。 */
    public CompletableFuture<JsonNode> send(String method, Map<String, Object> params, String sessionId) {
        ObjectNode msg = RecorderJson.MAPPER.createObjectNode();
        long id = idGen.incrementAndGet();
        msg.put("id", id);
        msg.put("method", method);
        if (params != null && !params.isEmpty()) {
            msg.set("params", RecorderJson.MAPPER.valueToTree(params));
        }
        if (sessionId != null) {
            msg.put("sessionId", sessionId);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            transport.send(msg.toString());
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    public CompletableFuture<JsonNode> send(String method) {
        return send(method, null, null);
    }

    public CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
        return send(method, params, null);
    }

    public boolean isTransportOpen() {
        return transport.isOpen();
    }

    public void close() {
        transport.close();
    }

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    // ---- CdpTransport.Listener ----

    @Override
    public void onText(String message) {
        JsonNode node;
        try {
            node = RecorderJson.parse(message);
        } catch (Exception e) {
            log.warn("Unparseable CDP message dropped: {}", e.getMessage());
            return;
        }
        if (node.has("id")) {
            long id = node.path("id").asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                return;
            }
            if (node.has("error")) {
                future.completeExceptionally(new CdpCommandException(
                        node.path("error").path("code").asInt(),
                        node.path("error").path("message").asText("unknown")));
            } else {
                future.complete(node.path("result"));
            }
            return;
        }
        String method = node.path("method").asText(null);
        if (method != null) {
            EventListener l = eventListener;
            if (l != null) {
                l.onEvent(method, node.path("params"), node.has("sessionId") ? node.get("sessionId").asText() : null);
            }
        }
    }

    @Override
    public void onClosed() {
        for (Map.Entry<Long, CompletableFuture<JsonNode>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(new IllegalStateException("CDP connection closed"));
            pending.remove(e.getKey());
        }
        EventListener l = eventListener;
        if (l != null) {
            l.onTransportClosed();
        }
    }

    /** CDP error 响应。 */
    public static final class CdpCommandException extends RuntimeException {
        public final int code;

        public CdpCommandException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
