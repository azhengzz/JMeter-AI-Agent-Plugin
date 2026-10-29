package org.gitee.jmeter.ai.recorder.cdp;

import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 1.2：命令封装与每页会话初始化序列（enable → 注入 → run）。 */
class CdpClientTest {

    private static final String SCRIPT = "// init";

    private static class RecordingCallbacks implements CdpClient.Callbacks {
        final AtomicInteger attaches = new AtomicInteger();
        final AtomicInteger detaches = new AtomicInteger();
        final AtomicReference<String> bindingPayload = new AtomicReference<>();
        final AtomicReference<String> connectionClosed = new AtomicReference<>("no");

        @Override
        public void onPageAttached(int pageId, String sessionId) {
            attaches.incrementAndGet();
        }

        @Override
        public void onPageDetached(String sessionId) {
            detaches.incrementAndGet();
        }

        @Override
        public void onBindingCalled(int pageId, String sessionId, String payload) {
            bindingPayload.set(payload);
        }

        @Override
        public void onConnectionClosed() {
            connectionClosed.set("yes");
        }
    }

    @Test
    void setupSendsPreflightThenAutoAttachWithWaitForDebugger() throws Exception {
        FakeTransport t = new FakeTransport();
        CdpConnection conn = new CdpConnection(t);
        CdpClient client = new CdpClient(conn, new RecordingCallbacks(), SCRIPT);
        t.connect("ws://fake");
        client.setup().get();
        // 预检 getVersion 在前（WS 沉默 vs autoAttach 特定无应答的差分诊断）
        assertEquals("Browser.getVersion", t.sent.get(0).path("method").asText());
        var msg = t.sent.get(1);
        assertEquals("Target.setAutoAttach", msg.path("method").asText());
        assertTrue(msg.path("params").path("autoAttach").asBoolean());
        assertTrue(msg.path("params").path("waitForDebuggerOnStart").asBoolean());
        assertTrue(msg.path("params").path("flatten").asBoolean());
    }

    @Test
    void pageAttachRunsFullSessionSetupSequence() {
        FakeTransport t = new FakeTransport();
        CdpConnection conn = new CdpConnection(t);
        RecordingCallbacks cb = new RecordingCallbacks();
        CdpClient client = new CdpClient(conn, cb, SCRIPT);
        t.connect("ws://fake");

        t.receive("{\"method\":\"Target.attachedToTarget\",\"params\":{"
                + "\"sessionId\":\"S1\",\"targetInfo\":{\"type\":\"page\",\"url\":\"about:blank\"}}}");

        assertEquals(1, cb.attaches.get());
        // 顺序：runIfWaitingForDebugger（attach 即放行——暂停态上装配命令不应答会互锁）
        // → Runtime.enable → Page.enable → Network.enable → addBinding → 注入
        List<String> after = t.sentMethods();
        int run = after.indexOf("Runtime.runIfWaitingForDebugger");
        int enable = after.indexOf("Runtime.enable");
        int page = after.indexOf("Page.enable");
        int network = after.indexOf("Network.enable");
        int binding = after.indexOf("Runtime.addBinding");
        int inject = after.indexOf("Page.addScriptToEvaluateOnNewDocument");
        assertTrue(run >= 0 && enable > run && page > enable && network > page
                && binding > network && inject > binding, "unexpected order: " + after);

        var bindingMsg = t.sent.stream()
                .filter(n -> "Runtime.addBinding".equals(n.path("method").asText())).findFirst().orElseThrow();
        assertEquals(CdpClient.BINDING_NAME, bindingMsg.path("params").path("name").asText());
        // 评审 P0-1：binding 只绑定在隔离世界，页面主世界不可见不可调
        assertEquals(CdpClient.WORLD_NAME,
                bindingMsg.path("params").path("executionContextName").asText());
        var injectMsg = t.sent.stream()
                .filter(n -> "Page.addScriptToEvaluateOnNewDocument".equals(n.path("method").asText()))
                .findFirst().orElseThrow();
        assertEquals(SCRIPT, injectMsg.path("params").path("source").asText());
        // 评审 P1-5：既有文档也要注入（worldName + runImmediately）
        assertEquals(CdpClient.WORLD_NAME, injectMsg.path("params").path("worldName").asText());
        assertTrue(injectMsg.path("params").path("runImmediately").asBoolean());
        assertEquals(1, client.attachedPageCount());
    }

    @Test
    void nonPageTargetsIgnored() {
        FakeTransport t = new FakeTransport();
        CdpConnection conn = new CdpConnection(t);
        RecordingCallbacks cb = new RecordingCallbacks();
        new CdpClient(conn, cb, SCRIPT);
        t.connect("ws://fake");

        t.receive("{\"method\":\"Target.attachedToTarget\",\"params\":{"
                + "\"sessionId\":\"S2\",\"targetInfo\":{\"type\":\"iframe\",\"url\":\"x\"}}}");
        assertEquals(0, cb.attaches.get());
    }

    @Test
    void bindingPayloadForwarded() {
        FakeTransport t = new FakeTransport();
        CdpConnection conn = new CdpConnection(t);
        RecordingCallbacks cb = new RecordingCallbacks();
        new CdpClient(conn, cb, SCRIPT);
        t.connect("ws://fake");

        t.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"" + CdpClient.BINDING_NAME + "\",\"payload\":\"{\\\"t\\\":\\\"ui\\\",\\\"ev\\\":\\\"click\\\"}\"}}");
        assertEquals("{\"t\":\"ui\",\"ev\":\"click\"}", cb.bindingPayload.get());
    }

    @Test
    void getCookiesFallsBackToNetworkGetAllCookies() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responder = msg -> {
            if ("Storage.getCookies".equals(msg.path("method").asText())) {
                return Optional.of(FakeTransport.errorReply(msg.path("id").asLong(), "deprecated path"));
            }
            return Optional.of("{\"id\":" + msg.path("id").asLong() + ",\"result\":{\"cookies\":[]}}");
        };
        CdpConnection conn = new CdpConnection(t);
        CdpClient client = new CdpClient(conn, new RecordingCallbacks(), SCRIPT);
        t.connect("ws://fake");

        var result = client.getCookies().get();
        assertTrue(result.has("cookies"));
        assertTrue(t.sentMethods().contains("Network.getAllCookies"));
    }

    @Test
    void transportClosePropagatesToCallbacks() {
        FakeTransport t = new FakeTransport();
        CdpConnection conn = new CdpConnection(t);
        RecordingCallbacks cb = new RecordingCallbacks();
        new CdpClient(conn, cb, SCRIPT);
        t.connect("ws://fake");
        t.fireClose();
        assertEquals("yes", cb.connectionClosed.get());
        assertFalse(conn.isTransportOpen());
    }
}
