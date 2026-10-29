package org.gitee.jmeter.ai.recorder.cdp;

import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 1.1：编解码、id 关联、sessionId 路由、断连时 pending 失败。 */
class CdpConnectionTest {

    @Test
    void commandResultCorrelatesById() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responder = msg -> Optional.empty(); // 手动应答
        CdpConnection c = new CdpConnection(t);
        t.connect("ws://fake");

        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> f = c.send("Network.enable");
        long id = t.sent.get(0).path("id").asLong();
        t.receive("{\"id\":" + id + ",\"result\":{\"ok\":1}}");
        assertEquals(1, f.get(2, TimeUnit.SECONDS).path("ok").asInt());
    }

    @Test
    void errorReplyCompletesExceptionally() {
        FakeTransport t = new FakeTransport();
        t.responder = msg -> Optional.empty(); // 手动应答
        CdpConnection c = new CdpConnection(t);
        t.connect("ws://fake");

        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> f = c.send("Bad.Command");
        long id = t.sent.get(0).path("id").asLong();
        t.receive(FakeTransport.errorReply(id, "not found"));

        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof CdpConnection.CdpCommandException);
        assertEquals("not found", e.getCause().getMessage());
    }

    @Test
    void eventRoutesWithSessionId() {
        FakeTransport t = new FakeTransport();
        CdpConnection c = new CdpConnection(t);
        t.connect("ws://fake");

        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> sessionId = new AtomicReference<>();
        c.setEventListener(new CdpConnection.EventListener() {
            @Override
            public void onEvent(String m, com.fasterxml.jackson.databind.JsonNode params, String sid) {
                method.set(m);
                sessionId.set(sid);
            }

            @Override
            public void onTransportClosed() {
            }
        });
        t.receive("{\"method\":\"Network.requestWillBeSent\",\"params\":{},\"sessionId\":\"S1\"}");
        assertEquals("Network.requestWillBeSent", method.get());
        assertEquals("S1", sessionId.get());
    }

    @Test
    void transportCloseFailsPendingAndNotifies() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responder = msg -> Optional.empty();
        CdpConnection c = new CdpConnection(t);
        t.connect("ws://fake");

        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> f1 = c.send("A");
        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> f2 = c.send("B");
        AtomicReference<Boolean> closed = new AtomicReference<>(false);
        c.setEventListener(new CdpConnection.EventListener() {
            @Override
            public void onEvent(String m, com.fasterxml.jackson.databind.JsonNode p, String s) {
            }

            @Override
            public void onTransportClosed() {
                closed.set(true);
            }
        });

        t.fireClose();
        assertTrue(closed.get());
        assertThrows(ExecutionException.class, () -> f1.get(1, TimeUnit.MILLISECONDS));
        assertThrows(ExecutionException.class, () -> f2.get(1, TimeUnit.MILLISECONDS));
    }

    @Test
    void sendCarriesParamsAndSessionId() {
        FakeTransport t = new FakeTransport();
        CdpConnection c = new CdpConnection(t);
        t.connect("ws://fake");

        c.send("Network.getResponseBody", Map.of("requestId", "R1"), "S9");
        var msg = t.sent.get(0);
        assertEquals("Network.getResponseBody", msg.path("method").asText());
        assertEquals("R1", msg.path("params").path("requestId").asText());
        assertEquals("S9", msg.path("sessionId").asText());
    }
}
