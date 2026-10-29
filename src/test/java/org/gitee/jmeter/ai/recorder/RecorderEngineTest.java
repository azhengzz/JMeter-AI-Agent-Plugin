package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务 1.4/1.5：状态机全迁移、幂等收官、双通道停止的 cookie 数据流语义、
 * 过滤规则、ARMED 零采集。全栈走 FakeTransport（应答内联同步，确定性断言）。
 */
class RecorderEngineTest {

    @TempDir
    Path workspace;

    private FakeTransport transport;
    private final List<String> stateLog = new CopyOnWriteArrayList<>();
    private final CountDownLatch finalizedLatch = new CountDownLatch(1);
    private volatile String cancelledReason;
    private final AtomicInteger cookieCalls = new AtomicInteger();

    private RecorderEngine newEngine(Function<String, String> props) {
        RecorderConfig cfg = RecorderConfig.from(props);
        transport = new FakeTransport();
        transport.responder = msg -> {
            String method = msg.path("method").asText("");
            long id = msg.path("id").asLong();
            if ("Storage.getCookies".equals(method)) {
                cookieCalls.incrementAndGet();
                return Optional.of("{\"id\":" + id
                        + ",\"result\":{\"cookies\":[{\"name\":\"sess\",\"value\":\"v1\",\"domain\":\"example.com\"}]}}");
            }
            if ("Network.getResponseBody".equals(method)) {
                return Optional.of("{\"id\":" + id
                        + ",\"result\":{\"body\":\"HELLO-BODY\",\"base64Encoded\":false}}");
            }
            return Optional.of("{\"id\":" + id + ",\"result\":{}}");
        };
        RecorderEngine.BrowserProvider provider = (c, ws, url) -> new BrowserInstanceFinder.BrowserHandle() {
            @Override
            public String wsUrl() {
                return "ws://fake";
            }

            @Override
            public boolean own() {
                return true;
            }

            @Override
            public String fallbackReason() {
                return null;
            }

            @Override
            public void destroy() {
            }
        };
        RecorderEngine engine = new RecorderEngine(cfg, new RecorderEngine.EngineListener() {
            @Override
            public void onState(RecorderEngine.State from, RecorderEngine.State to) {
                stateLog.add(from + "->" + to);
            }

            @Override
            public void onFinalized(RecorderEngine.Status status) {
                finalizedLatch.countDown();
            }

            @Override
            public void onCancelled(String reason) {
                cancelledReason = reason;
            }
        }, workspace, "test-inst", provider, () -> transport, "// init");
        return engine;
    }

    // ---- CDP 事件注入 helpers ----

    private void attachPage(String sessionId) {
        transport.receive("{\"method\":\"Target.attachedToTarget\",\"sessionId\":\"B\",\"params\":{"
                + "\"sessionId\":\"" + sessionId + "\",\"targetInfo\":{\"type\":\"page\",\"url\":\"about:blank\"}}}");
        // 隔离世界上下文就绪（引擎的状态/计数推送在隔离世界中求值）
        transport.receive("{\"method\":\"Runtime.executionContextCreated\",\"sessionId\":\""
                + sessionId + "\",\"params\":{\"context\":{\"id\":7,\"name\":\"__jaiRecWorld\","
                + "\"auxData\":{\"frameId\":\"F1\",\"isDefault\":false}}}}");
    }

    private void requestWillBeSent(String rid, String type, String url, String postData) {
        StringBuilder req = new StringBuilder();
        req.append("\"request\":{\"method\":\"").append(postData == null ? "GET" : "POST").append("\",")
                .append("\"url\":\"").append(url).append("\",\"headers\":{\"accept\":\"*/*\"}");
        if (postData != null) {
            req.append(",\"postData\":\"").append(postData).append("\"");
        }
        req.append("}");
        transport.receive("{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"" + rid + "\",\"type\":\"" + type + "\"," + req + "}}");
    }

    private void responseReceived(String rid, int status, String url, String setCookie) {
        String headers = setCookie == null ? "{}"
                : ("{\"set-cookie\":\"" + setCookie + "\"}");
        transport.receive("{\"method\":\"Network.responseReceived\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"" + rid + "\","
                + "\"response\":{\"status\":" + status + ",\"url\":\"" + url + "\",\"headers\":" + headers + "}}}");
    }

    private void loadingFailed(String rid, String errorText) {
        transport.receive("{\"method\":\"Network.loadingFailed\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"" + rid + "\",\"errorText\":\"" + errorText + "\"}}");
    }

    private void uiBinding(String ev) {
        transport.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"__jaiRec\",\"payload\":\"{\\\"t\\\":\\\"ui\\\",\\\"ev\\\":\\\"" + ev
                + "\\\",\\\"url\\\":\\\"https://example.com/page\\\",\\\"tag\\\":\\\"button\\\"}\"}}");
    }

    private Path eventsFile(RecorderEngine engine) {
        return engine.status().dir().resolve("events.jsonl");
    }

    private List<RecordedEvent> readEvents(RecorderEngine engine) throws Exception {
        return RecordingStore.readEvents(eventsFile(engine));
    }

    // ---- 状态机 ----

    @Test
    void armedCapturesNothingUntilExplicitBegin() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        assertEquals(RecorderEngine.State.ARMED, engine.state());
        attachPage("S1");

        // 预备态推入请求/UI 事件：零落盘（D12）
        requestWillBeSent("R0", "XHR", "https://example.com/login", null);
        uiBinding("click");
        assertEquals(0, readEvents(engine).size());

        engine.begin();
        assertEquals(RecorderEngine.State.RECORDING, engine.state());
        requestWillBeSent("R1", "XHR", "https://example.com/api/list", null);
        List<RecordedEvent> events = readEvents(engine);
        assertEquals(1, events.size());
        assertEquals("request", events.get(0).type());
        engine.stop();
    }

    @Test
    void happyPathControlStopRefreshesCookiesAndPersistsArtifacts() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        int baselineCalls = cookieCalls.get(); // begin 基线已取（内联完成）
        assertTrue(baselineCalls >= 1);

        requestWillBeSent("R1", "XHR", "https://example.com/api/orders", null);
        responseReceived("R1", 200, "https://example.com/api/orders", "sid=abc; Path=/; Domain=example.com");
        uiBinding("click");
        engine.stop();

        assertEquals(RecorderEngine.State.DONE, engine.state());
        assertTrue(finalizedLatch.await(1, TimeUnit.SECONDS));
        // 控制面停止 → cookie 刷新（基线 + 刷新 = 至少 2 次调用）
        assertTrue(cookieCalls.get() >= 2, "cookie snapshot calls=" + cookieCalls.get());

        List<String> types = readEvents(engine).stream().map(RecordedEvent::type).toList();
        assertEquals(List.of("request", "response", "responseBody", "click"), types);

        var cookies = RecorderJson.MAPPER.readTree(
                engine.status().dir().resolve("cookies.json").toFile());
        assertEquals("sess", cookies.path("refresh").get(0).path("name").asText());
        assertEquals(1, cookies.path("setCookies").size());
        var summary = RecorderJson.MAPPER.readTree(
                engine.status().dir().resolve("summary.json").toFile());
        assertEquals("completed", summary.path("result").asText());
        assertEquals(1, summary.path("requests").asLong());
    }

    @Test
    void closeBrowserChannelFinalizesWithBaselineOnly() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        int callsAfterBegin = cookieCalls.get();
        requestWillBeSent("R1", "XHR", "https://example.com/api/a", null);

        transport.fireClose(); // 关浏览器：连接已断，无刷新快照（收官转后台执行器）

        assertTrue(finalizedLatch.await(2, TimeUnit.SECONDS), "close channel must finalize");
        assertEquals(RecorderEngine.State.DONE, engine.state());
        assertEquals(callsAfterBegin, cookieCalls.get(), "close channel must not refresh cookies");
        // 基线仍在产物里（D14：①基线 ∪ ②Set-Cookie 闭环；refresh 为 null）
        var cookies = RecorderJson.MAPPER.readTree(
                engine.status().dir().resolve("cookies.json").toFile());
        assertEquals("sess", cookies.path("baseline").get(0).path("name").asText());
        assertTrue(cookies.path("refresh").isNull());
        assertTrue(readEvents(engine).size() >= 1);
    }

    @Test
    void finalizeIsSingleFlight() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        requestWillBeSent("R1", "XHR", "https://example.com/api/a", null);
        engine.stop();
        engine.stop(); // 幂等：第二次静默
        assertEquals(1, stateLog.stream().filter(s -> s.endsWith("->FINALIZING")).count());
        assertEquals(RecorderEngine.State.DONE, engine.state());
    }

    @Test
    void stopInArmedCancelsWithoutArtifacts() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        Path dir = engine.status().dir();
        engine.stop();
        assertEquals(RecorderEngine.State.CANCELLED, engine.state());
        assertNotNull(cancelledReason);
        assertFalse(Files.exists(dir), "cancelled session must leave no artifacts");
    }

    @Test
    void browserCloseInArmedCancels() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        transport.fireClose(); // 取消转后台执行器——轮询终态
        long deadline = System.currentTimeMillis() + 2000;
        while (engine.state() == RecorderEngine.State.ARMED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(RecorderEngine.State.CANCELLED, engine.state());
    }

    @Test
    void secondOpenRejectedWhileSessionActive() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        assertThrows(IllegalStateException.class,
                () -> engine.open(new RecorderEngine.OpenParams(null, Set.of())));
    }

    @Test
    void allPagesClosedFinalizesAfterDelayedRecheck() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        requestWillBeSent("R1", "XHR", "https://example.com/api/a", null);
        transport.receive("{\"method\":\"Target.detachedFromTarget\",\"params\":{\"sessionId\":\"S1\"}}");
        assertTrue(finalizedLatch.await(2, TimeUnit.SECONDS), "800ms recheck should finalize");
        assertEquals(RecorderEngine.State.DONE, engine.state());
    }

    // ---- 过滤规则（1.5） ----

    @Test
    void whitelistAndDomainDrops() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of("example.com")));
        attachPage("S1");
        engine.begin();

        requestWillBeSent("R1", "Script", "https://example.com/app.js", null);   // 白名单外
        requestWillBeSent("R2", "XHR", "https://cdn.other.io/lib", null);        // 域名外
        responseReceived("R2", 200, "https://cdn.other.io/lib", null);           // 连带丢弃
        requestWillBeSent("R3", "Fetch", "https://api.example.com/v1/q", null);  // 子域命中
        engine.stop();

        List<RecordedEvent> events = readEvents(engine);
        assertEquals(1, events.size());
        assertEquals("R3", events.get(0).data().get("requestId"));
        // 白名单外/域名外请求的响应连带丢弃（无孤儿 response）
        assertEquals(1, events.stream().filter(e -> e.type().equals("request")).count());
    }

    @Test
    void documentNavigationEmitsNavigateOnly() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        requestWillBeSent("R1", "Document", "https://example.com/home", null);
        engine.stop();
        List<RecordedEvent> events = readEvents(engine);
        assertEquals(1, events.size());
        assertEquals("navigate", events.get(0).type());
    }

    @Test
    void documentCapturedAsRequestWhenWhitelisted() throws Exception {
        // 勾选 Document 后导航请求须录成采样器（navigate 上下文语义保留），且仍受域名约束
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of("example.com"),
                Set.of("xhr", "fetch", "document"), null));
        attachPage("S1");
        engine.begin();
        requestWillBeSent("R1", "Document", "https://example.com/home", null);
        requestWillBeSent("R2", "Document", "https://other.io/x", null);
        engine.stop();
        List<RecordedEvent> events = readEvents(engine);
        assertEquals(2, events.size(), "域名外的 Document 连 navigate 也不发");
        assertEquals("navigate", events.get(0).type());
        assertEquals("request", events.get(1).type());
        assertEquals("Document", events.get(1).data().get("resourceType"));
        assertEquals("R1", events.get(1).data().get("requestId"));
    }

    @Test
    void postDataTruncationFlag() throws Exception {
        RecorderEngine engine = newEngine(
                k -> "jmeter.ai.recorder.max.body.bytes".equals(k) ? "10" : null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        requestWillBeSent("R1", "XHR", "https://example.com/api/a", "0123456789ABCDEFGHIJ");
        engine.stop();
        RecordedEvent e = readEvents(engine).get(0);
        assertEquals(10, ((String) e.data().get("postData")).length());
        assertEquals(Boolean.TRUE, e.data().get("postDataTruncated"));
    }

    @Test
    void loadingFailedCarriesUrl() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        engine.begin();
        requestWillBeSent("R1", "XHR", "https://example.com/api/a", null);
        loadingFailed("R1", "net::ERR_FAILED");
        engine.stop();
        RecordedEvent e = readEvents(engine).stream()
                .filter(ev -> ev.type().equals("requestfailed")).findFirst().orElseThrow();
        assertEquals("https://example.com/api/a", e.data().get("url"));
        assertEquals("net::ERR_FAILED", e.data().get("errorText"));
    }

    @Test
    void bindingControlChannelDrivesBeginAndStop() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        // 评审 P0-1：控制 binding 只存在于隔离世界（页面主世界不可见不可调）——指令免令牌
        ctrlBinding("begin");
        awaitState(engine, RecorderEngine.State.RECORDING, 2000);
        requestWillBeSent("R1", "XHR", "https://example.com/api/a", null);
        ctrlBinding("stop");
        awaitState(engine, RecorderEngine.State.DONE, 3000);
        assertEquals(1, readEvents(engine).size());
    }

    private void ctrlBinding(String action) {
        // 整条 binding 消息用 Jackson 构造，避免 Java 转义地雷
        var payload = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createObjectNode()
                .put("t", "ctrl").put("a", action).toString();
        var msg = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createObjectNode()
                .put("method", "Runtime.bindingCalled").put("sessionId", "S1");
        msg.putObject("params").put("name", "__jaiRec").put("payload", payload);
        transport.receive(msg.toString());
    }
    private static void awaitState(RecorderEngine e, RecorderEngine.State expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (e.state() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, e.state());
    }
}
