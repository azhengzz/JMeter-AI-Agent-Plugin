package org.gitee.jmeter.ai.recorder.cdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 JDK {@code java.net.http.WebSocket} 的 CDP 传输（零第三方依赖路线的落点）。
 *
 * <p><b>关键的 JDK 契约</b>：{@code java.net.http.WebSocket} 采用**显式需求计数**
 * （demand）——新建连接初始 demand = 1，每收到一条消息递减，侦听器必须调用
 * {@code websocket.request(n)} 才会继续接收。不调用则<b>只有第一条消息会被分发</b>，
 * 表现为「Browser.getVersion 预检秒回、之后所有命令静默超时」（实测 JDK 17 + Chrome 156，
 * .NET 客户端同协议同端点完全正常）。故在 onOpen 一次性请求无上限需求。
 *
 * <p>发送为异步投递（不 join）：消息顺序由 WebSocket 内部 FIFO 保证，且侦听回调线程
 * 会经 CdpClient 走到发送路径，回调内阻塞等待发送完成属于自锁风险。
 */
public final class WebSocketCdpTransport implements CdpTransport {
    private static final Logger log = LoggerFactory.getLogger(WebSocketCdpTransport.class);

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean open = new AtomicBoolean(false);
    private volatile WebSocket ws;
    private volatile Listener listener;

    @Override
    public void connect(String wsUrl) throws Exception {
        StringBuilder fragment = new StringBuilder();
        WebSocket w = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket websocket) {
                        // 一次性请求无上限需求：否则 JDK 只分发第一条消息（见类注释）
                        websocket.request(Long.MAX_VALUE);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket websocket, CharSequence data, boolean last) {
                        fragment.append(data);
                        if (last) {
                            String full = fragment.toString();
                            fragment.setLength(0);
                            Listener l = listener;
                            if (l != null) {
                                try {
                                    l.onText(full);
                                } catch (Exception e) {
                                    log.warn("CDP listener error: {}", e.getMessage());
                                }
                            }
                        }
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket websocket, int statusCode, String reason) {
                        handleClosed();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket websocket, Throwable error) {
                        log.warn("CDP websocket error: {}", String.valueOf(error.getMessage()));
                        handleClosed();
                    }
                }).join();
        this.ws = w;
        this.open.set(true);
    }

    @Override
    public void send(String text) {
        WebSocket w = ws;
        if (w == null || !open.get()) {
            throw new IllegalStateException("CDP transport not connected");
        }
        try {
            // 异步投递：绝不在此 join——侦听回调线程会经 CdpClient 走到这里，
            // 阻塞等发送完成 = JDK WebSocket 死锁（见类注释）
            w.sendText(text, true).whenComplete((v, err) -> {
                if (err != null) {
                    log.debug("CDP send failed: {}", String.valueOf(err.getMessage()));
                    handleClosed();
                }
            });
        } catch (Exception e) {
            handleClosed();
            throw new IllegalStateException("CDP send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            WebSocket w = ws;
            if (w != null) {
                try {
                    w.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
                } catch (Exception ignored) {
                    // 关闭失败按断连处理
                }
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void handleClosed() {
        if (open.compareAndSet(true, false)) {
            Listener l = listener;
            if (l != null) {
                try {
                    l.onClosed();
                } catch (Exception e) {
                    log.warn("CDP onClosed listener error: {}", e.getMessage());
                }
            }
        }
    }
}
