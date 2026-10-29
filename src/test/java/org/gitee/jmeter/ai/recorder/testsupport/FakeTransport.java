package org.gitee.jmeter.ai.recorder.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.gitee.jmeter.ai.recorder.RecorderJson;
import org.gitee.jmeter.ai.recorder.cdp.CdpTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 测试用 CDP 传输 fake：send 即时回放应答（同步内联，全栈测试确定性化）。
 */
public final class FakeTransport implements CdpTransport {
    public final List<JsonNode> sent = Collections.synchronizedList(new ArrayList<>());
    private volatile Listener listener;
    private volatile boolean open;

    /** 应答器：入参为命令消息，返回完整应答 JSON；empty = 不应答。默认回空 result。 */
    public volatile Function<JsonNode, Optional<String>> responder =
            msg -> Optional.of("{\"id\":" + msg.path("id").asLong() + ",\"result\":{}}");

    /**
     * 异步应答钩子：设置后优先于同步 responder——测试用它模拟「应答晚于状态迁移」的竞态
     * （生产 WebSocket 的应答到达时间不受 send 控制）。回调拿到应答消费者，自行决定何时投递。
     */
    public volatile java.util.function.BiConsumer<JsonNode, java.util.function.Consumer<String>> asyncResponder;

    @Override
    public void connect(String wsUrl) {
        open = true;
    }

    @Override
    public void send(String text) {
        JsonNode msg = RecorderJson.parse(text);
        sent.add(msg);
        java.util.function.BiConsumer<JsonNode, java.util.function.Consumer<String>> async = asyncResponder;
        if (async != null) {
            async.accept(msg, reply -> {
                if (listener != null) {
                    listener.onText(reply);
                }
            });
            return;
        }
        Optional<String> reply = responder.apply(msg);
        if (reply.isPresent() && listener != null) {
            listener.onText(reply.get());
        }
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 注入一条事件/应答（同步回调侦听器）。 */
    public void receive(String json) {
        if (listener != null) {
            listener.onText(json);
        }
    }

    /** 模拟浏览器关闭：传输断开。 */
    public void fireClose() {
        open = false;
        if (listener != null) {
            listener.onClosed();
        }
    }

    public List<String> sentMethods() {
        List<String> methods = new ArrayList<>();
        synchronized (sent) {
            sent.forEach(n -> methods.add(n.path("method").asText("?" + n.path("id").asLong())));
        }
        return methods;
    }

    /** 构造 error 应答 JSON。 */
    public static String errorReply(long id, String message) {
        return "{\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":\"" + message + "\"}}";
    }
}
