package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.convert.RecordingModel;
import org.gitee.jmeter.ai.recorder.convert.SkeletonConverter;
import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两轮评审修复的回归测试：
 * A 截断表单体、B 宽限窗响应体、C 基线竞态、D 隔离世界控制通道、E 字节截断、F setup 失败、
 * G 悬浮条计数、H 请求体补拉、I 文件名去重，
 * 以及第二轮新增：ExtraInfo Set-Cookie（P0-2）、基线 cookie 域名过滤（P2-10）、
 * navigate 域名过滤（P2-9）、UI 载荷上限（P3-2）、base64 对齐截断（P3-5）。
 */
class RecorderHardeningTest {

    @TempDir
    Path workspace;

    private FakeTransport transport;

    private RecorderEngine newEngine(Function<String, String> props) {
        RecorderConfig cfg = RecorderConfig.from(props);
        transport = new FakeTransport();
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
        return new RecorderEngine(cfg, new RecorderEngine.EngineListener() {
        }, workspace, "hard-inst", provider, () -> transport, "// init");
    }

    private void attachPage() {
        transport.receive(json(Map.of("method", "Target.attachedToTarget", "params", Map.of(
                "sessionId", "S1", "targetInfo", Map.of("type", "page", "url", "about:blank")))));
        transport.receive(json(Map.of("method", "Runtime.executionContextCreated", "sessionId", "S1",
                "params", Map.of("context", Map.of(
                        "id", 7, "name", "__jaiRecWorld",
                        "auxData", Map.of("frameId", "F1", "isDefault", false))))));
    }

    private static String json(Object o) {
        try {
            return RecorderJson.MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void request(String rid, String url, String postData, String contentType, boolean hasPostData) {
        Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("method", "POST");
        req.put("url", url);
        req.put("headers", Map.of("Content-Type", contentType));
        if (postData != null) {
            req.put("postData", postData);
        } else if (hasPostData) {
            req.put("hasPostData", true);
        }
        transport.receive(json(Map.of("method", "Network.requestWillBeSent", "sessionId", "S1",
                "params", Map.of("requestId", rid, "type", "XHR", "request", req))));
    }

    private void response(String rid, String url) {
        transport.receive(json(Map.of("method", "Network.responseReceived", "sessionId", "S1",
                "params", Map.of("requestId", rid, "response", Map.of(
                        "status", 200, "url", url, "headers", Map.of())))));
    }

    private void ctrlBinding(String action) {
        String payload = json(Map.of("t", "ctrl", "a", action));
        transport.receive(json(Map.of("method", "Runtime.bindingCalled", "sessionId", "S1",
                "params", Map.of("name", "__jaiRec", "payload", payload))));
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

    // ---- A：截断的 urlencoded 表单体不得击穿转换器 ----

    @Test
    void truncatedUrlEncodedBodySurvivesConversion() throws Exception {
        RecorderEngine engine = newEngine(
                k -> "jmeter.ai.recorder.max.body.bytes".equals(k) ? "12" : null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/login", "a=1&tok=%E4%B8%AD%E6%96%87",
                "application/x-www-form-urlencoded", false);
        engine.stop();
        assertEquals(RecorderEngine.State.DONE, engine.state());

        RecordingModel model = RecordingModel.load(engine.status().dir());
        var result = new SkeletonConverter().convert(model);
        assertEquals(1, result.samplerCount());
        ElementDefProbe sampler = ElementDefProbe.of(result);
        assertTrue(sampler.arguments.size() >= 1);
        assertEquals("a", sampler.arguments.get(0).get("name"));
    }

    record ElementDefProbe(List<Map<String, String>> arguments) {
        @SuppressWarnings("unchecked")
        static ElementDefProbe of(SkeletonConverter.ConversionResult r) {
            var sampler = r.tree().get(0).children().get(0).children().stream()
                    .filter(c -> c.elementType().equals("httpsampler")).findFirst().orElseThrow();
            return new ElementDefProbe((List<Map<String, String>>) sampler.props().getOrDefault(
                    "arguments", List.of()));
        }
    }

    // ---- B：收官宽限窗内到达的响应体必须落盘 ----

    @Test
    void graceWindowBodyFetchIsAccepted() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        transport.asyncResponder = (msg, deliver) -> {
            String method = msg.path("method").asText("");
            long id = msg.path("id").asLong();
            if ("Network.getResponseBody".equals(method)) {
                CompletableFuture.runAsync(() -> deliver.accept(
                                "{\"id\":" + id + ",\"result\":{\"body\":\"LATE\",\"base64Encoded\":false}}"),
                        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
            } else if ("Storage.getCookies".equals(method)) {
                deliver.accept("{\"id\":" + id
                        + ",\"result\":{\"cookies\":[{\"name\":\"sess\",\"value\":\"v\"}]}}");
            } else {
                deliver.accept("{\"id\":" + id + ",\"result\":{}}");
            }
        };
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", "b=1", "application/json", false);
        response("R1", "https://a.io/x");
        engine.stop();
        assertEquals(RecorderEngine.State.DONE, engine.state());
        List<String> types = RecordingStore.readEvents(engine.status().dir().resolve("events.jsonl"))
                .stream().map(e -> e.type()).toList();
        assertTrue(types.contains("responseBody"), "grace-window body must be persisted: " + types);
    }

    // ---- C：cookie 基线慢应答不丢 ----

    @Test
    void slowBaselineReplyAwaitedAtFinalize() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        AtomicInteger cookieCalls = new AtomicInteger();
        transport.asyncResponder = (msg, deliver) -> {
            String method = msg.path("method").asText("");
            long id = msg.path("id").asLong();
            if ("Storage.getCookies".equals(method) && cookieCalls.incrementAndGet() == 1) {
                CompletableFuture.runAsync(() -> deliver.accept(
                                "{\"id\":" + id
                                        + ",\"result\":{\"cookies\":[{\"name\":\"sess\",\"value\":\"baseline-v\"}]}}"),
                        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
            } else if ("Storage.getCookies".equals(method)) {
                deliver.accept("{\"id\":" + id + ",\"result\":{\"cookies\":[]}}");
            } else {
                deliver.accept("{\"id\":" + id + ",\"result\":{}}");
            }
        };
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", null, "application/json", false);
        engine.stop();
        assertEquals(RecorderEngine.State.DONE, engine.state());
        var cookies = RecorderJson.MAPPER.readTree(engine.status().dir().resolve("cookies.json").toFile());
        assertEquals("sess", cookies.path("baseline").get(0).path("name").asText());
    }

    @Test
    void baselineNeverArrivesFinalizeStillCompletes() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        transport.asyncResponder = (msg, deliver) -> {
            if ("Storage.getCookies".equals(msg.path("method").asText())) {
                return; // 永不回：finalize 必须在 1.5s 上限内自行收敛
            }
            deliver.accept("{\"id\":" + msg.path("id").asLong() + ",\"result\":{}}");
        };
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", null, "application/json", false);
        long start = System.currentTimeMillis();
        engine.stop();
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(RecorderEngine.State.DONE, engine.state());
        assertTrue(elapsed < 5000, "finalize must converge within bounded wait, took " + elapsed + "ms");
        var cookies = RecorderJson.MAPPER.readTree(engine.status().dir().resolve("cookies.json").toFile());
        assertTrue(cookies.path("baseline").isNull());
    }

    // ---- D：控制通道由隔离世界承载（评审 P0-1；页面主世界不可见不可调） ----

    @Test
    void controlBindingDrivesStateMachineWithoutToken() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        ctrlBinding("begin");
        awaitState(engine, RecorderEngine.State.RECORDING, 2000);
        request("R1", "https://a.io/x", null, "application/json", false);
        ctrlBinding("stop");
        awaitState(engine, RecorderEngine.State.DONE, 3000);
    }

    // ---- E：按字节截断 ----

    @Test
    void truncationCountsBytesNotChars() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of(), 10);
        EventFilter.Truncated t = f.truncateBody("中文中文中文");
        assertTrue(t.truncated());
        assertTrue(t.value().getBytes(StandardCharsets.UTF_8).length <= 10,
                "byte cap must hold: " + t.value().getBytes(StandardCharsets.UTF_8).length);
        assertFalse(f.truncateBody("短").truncated());
    }

    @Test
    void engineEmitsByteCappedPostData() throws Exception {
        RecorderEngine engine = newEngine(
                k -> "jmeter.ai.recorder.max.body.bytes".equals(k) ? "10" : null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", "中文中文中文", "application/json", false);
        engine.stop();
        RecordingModel model = RecordingModel.load(engine.status().dir());
        String postData = model.requests.get(0).postData();
        assertTrue(postData.getBytes(StandardCharsets.UTF_8).length <= 10);
        assertTrue(model.requests.get(0).postDataTruncated());
    }

    // ---- F：setAutoAttach 被拒 → open 报可读错误 ----

    @Test
    void setupFailureYieldsReadableOpenError() {
        RecorderEngine engine = newEngine(k -> null);
        transport.responder = msg -> {
            if ("Target.setAutoAttach".equals(msg.path("method").asText())) {
                return java.util.Optional.of(
                        FakeTransport.errorReply(msg.path("id").asLong(), "auto-attach denied"));
            }
            return java.util.Optional.of("{\"id\":" + msg.path("id").asLong() + ",\"result\":{}}");
        };
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> engine.open(new RecorderEngine.OpenParams(null, Set.of())));
        assertTrue(e.getMessage().contains("setAutoAttach"), e.getMessage());
        assertEquals(RecorderEngine.State.IDLE, engine.state());
    }

    // ---- G：悬浮条计数广播 ----

    @Test
    void requestCountBroadcastToPill() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", null, "application/json", false);
        synchronized (transport.sent) {
            boolean found = transport.sent.stream().anyMatch(n -> n.path("params").path("expression")
                    .asText("").contains("'recording',1"));
            assertTrue(found, "pill count broadcast (recording,1) missing");
        }
        engine.stop();
    }

    // ---- H：过大 POST 体走 getRequestPostData 补拉 ----

    @Test
    void oversizedPostDataFetchedViaCommand() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        transport.responder = msg -> {
            String method = msg.path("method").asText("");
            long id = msg.path("id").asLong();
            if ("Network.getRequestPostData".equals(method)) {
                return java.util.Optional.of("{\"id\":" + id
                        + ",\"result\":{\"postData\":\"user=tom&pwd=x\"}}");
            }
            return java.util.Optional.of("{\"id\":" + id + ",\"result\":{}}");
        };
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/login", null, "application/x-www-form-urlencoded", true);
        engine.stop();
        List<String> types = RecordingStore.readEvents(engine.status().dir().resolve("events.jsonl"))
                .stream().map(e -> e.type()).toList();
        assertEquals(List.of("request", "requestPostData"), types);
        RecordingModel model = RecordingModel.load(engine.status().dir());
        assertEquals("user=tom&pwd=x", model.requests.get(0).postData());
        var result = new SkeletonConverter().convert(model);
        assertEquals(2, ElementDefProbe.of(result).arguments.size());
    }

    // ---- I：同秒另存文件名去重 ----

    @Test
    void uniquePathAppendsSuffixOnCollision(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("recording-x.jmx");
        assertEquals(base, RecorderRuntime.uniquePath(base));
        java.nio.file.Files.writeString(base, "first");
        Path second = RecorderRuntime.uniquePath(base);
        assertEquals(dir.resolve("recording-x-1.jmx"), second);
        java.nio.file.Files.writeString(second, "second");
        assertEquals(dir.resolve("recording-x-2.jmx"), RecorderRuntime.uniquePath(base));
    }

    // ---- 新 P1-4：退出钩子先落 cookies/summary 再关 store ----

    @Test
    void shutdownHookWritesArtifactsBeforeClosingStore() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", null, "application/json", false);
        java.lang.reflect.Method m = RecorderEngine.class.getDeclaredMethod("shutdownOnce");
        m.setAccessible(true);
        m.invoke(engine); // 模拟 JVM 退出钩子
        Path dir = engine.status().dir();
        assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("cookies.json")),
                "shutdown hook must persist cookies.json before closing the store");
        var summary = RecorderJson.MAPPER.readTree(dir.resolve("summary.json").toFile());
        assertEquals("interrupted", summary.path("result").asText());
    }

    // ---- OOPIF：跨域 iframe target 也 enable Network 并采集其请求 ----

    @Test
    void oopifIframeNetworkCaptured() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        transport.receive(json(Map.of("method", "Target.attachedToTarget", "params", Map.of(
                "sessionId", "IF1", "targetInfo", Map.of("type", "iframe", "url", "https://pay.example")))));
        boolean iframeNetworkEnabled;
        synchronized (transport.sent) {
            iframeNetworkEnabled = transport.sent.stream().anyMatch(n ->
                    "Network.enable".equals(n.path("method").asText())
                            && "IF1".equals(n.path("sessionId").asText()));
        }
        assertTrue(iframeNetworkEnabled, "OOPIF target must get Network.enable");
        engine.begin();
        transport.receive(json(Map.of("method", "Network.requestWillBeSent", "sessionId", "IF1",
                "params", Map.of("requestId", "R9", "type", "XHR",
                        "request", Map.of("method", "POST", "url", "https://pay.example/api",
                                "headers", Map.of())))));
        engine.stop();
        RecordingModel model = RecordingModel.load(engine.status().dir());
        assertEquals(1, model.requests.size());
        assertEquals("https://pay.example/api", model.requests.get(0).url());
    }

    // ---- 新 P0-2：ExtraInfo 的 Set-Cookie 在关窗通道下仍进产物 ----

    @Test
    void extraInfoSetCookiesCapturedOnCloseChannel() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", "b=1", "application/json", false);
        // Chrome 裁剪 responseReceived.headers 的 Set-Cookie；原始头在 ExtraInfo（多值以 \n 连接）
        transport.receive(json(Map.of("method", "Network.responseReceivedExtraInfo", "sessionId", "S1",
                "params", Map.of("requestId", "R1",
                        "headers", Map.of("Set-cookie", "alpha=1; Path=/\nbeta=2; Path=/; HttpOnly")))));
        response("R1", "https://a.io/x");
        transport.fireClose(); // 关窗通道：无 refresh，setCookies 是唯一补充源
        long deadline = System.currentTimeMillis() + 3000;
        while (engine.state() != RecorderEngine.State.DONE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(RecorderEngine.State.DONE, engine.state());
        var cookies = RecorderJson.MAPPER.readTree(engine.status().dir().resolve("cookies.json").toFile());
        assertEquals(2, cookies.path("setCookies").size());

        RecordingModel model = RecordingModel.load(engine.status().dir());
        var result = new SkeletonConverter().convert(model);
        var cm = result.tree().get(0).children().get(0).children().stream()
                .filter(c -> c.elementType().equals("cookiemanager")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> merged = (List<Map<String, Object>>) cm.props().get("cookies");
        assertEquals(2, merged.size());
    }

    // ---- 新 P2-9：navigate 事件受只采域名约束 ----

    @Test
    void navigateRespectsDomainFilter() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of("example.com")));
        attachPage();
        engine.begin();
        transport.receive(json(Map.of("method", "Network.requestWillBeSent", "sessionId", "S1",
                "params", Map.of("requestId", "D1", "type", "Document",
                        "request", Map.of("method", "GET", "url", "https://other.io/private?token=x",
                                "headers", Map.of())))));
        transport.receive(json(Map.of("method", "Network.requestWillBeSent", "sessionId", "S1",
                "params", Map.of("requestId", "D2", "type", "Document",
                        "request", Map.of("method", "GET", "url", "https://example.com/home",
                                "headers", Map.of())))));
        engine.stop();
        List<String> navUrls = RecordingStore.readEvents(engine.status().dir().resolve("events.jsonl"))
                .stream().filter(e -> e.type().equals("navigate"))
                .map(e -> String.valueOf(e.data().get("url"))).toList();
        assertEquals(List.of("https://example.com/home"), navUrls);
    }

    // ---- 新 P2-10：基线 cookie 按只采域名过滤 ----

    @Test
    void baselineCookiesFilteredByIncludeDomains() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        transport.responder = msg -> {
            long id = msg.path("id").asLong();
            if ("Storage.getCookies".equals(msg.path("method").asText())) {
                return java.util.Optional.of("{\"id\":" + id + ",\"result\":{\"cookies\":["
                        + "{\"name\":\"keep\",\"value\":\"1\",\"domain\":\".example.com\"},"
                        + "{\"name\":\"drop\",\"value\":\"2\",\"domain\":\"other.io\"}]}}");
            }
            return java.util.Optional.of("{\"id\":" + id + ",\"result\":{}}");
        };
        engine.open(new RecorderEngine.OpenParams(null, Set.of("example.com")));
        attachPage();
        engine.begin();
        request("R1", "https://example.com/x", null, "application/json", false);
        engine.stop();
        var cookies = RecorderJson.MAPPER.readTree(engine.status().dir().resolve("cookies.json").toFile());
        assertEquals(1, cookies.path("baseline").size());
        assertEquals("keep", cookies.path("baseline").get(0).path("name").asText());
    }

    // ---- 新 P3-2：UI 回传载荷服务端截断 ----

    @Test
    void uiPayloadCappedServerSide() throws Exception {
        RecorderEngine engine = newEngine(k -> null);
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        String payload = json(Map.of("t", "ui", "ev", "click", "url", "https://a/",
                "text", "A".repeat(5000), "selector", "B".repeat(5000)));
        transport.receive(json(Map.of("method", "Runtime.bindingCalled", "sessionId", "S1",
                "params", Map.of("name", "__jaiRec", "payload", payload))));
        engine.stop();
        var click = RecordingStore.readEvents(engine.status().dir().resolve("events.jsonl"))
                .stream().filter(e -> e.type().equals("click")).findFirst().orElseThrow();
        assertEquals(200, String.valueOf(click.data().get("text")).length());
        assertEquals(300, String.valueOf(click.data().get("selector")).length());
    }

    // ---- 新 P3-5：base64 载荷按 4 字符对齐截断 ----

    @Test
    void base64BodyTruncationAligned() throws Exception {
        RecorderEngine engine = newEngine(
                k -> "jmeter.ai.recorder.max.body.bytes".equals(k) ? "10" : null);
        transport.responder = msg -> {
            long id = msg.path("id").asLong();
            if ("Network.getResponseBody".equals(msg.path("method").asText())) {
                return java.util.Optional.of("{\"id\":" + id + ",\"result\":{\"body\":\""
                        + "QUJDREVGR0hJSktMTU5PUA==\",\"base64Encoded\":true}}");
            }
            return java.util.Optional.of("{\"id\":" + id + ",\"result\":{}}");
        };
        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        engine.begin();
        request("R1", "https://a.io/x", "b=1", "application/json", false);
        response("R1", "https://a.io/x");
        engine.stop();
        var body = RecordingStore.readEvents(engine.status().dir().resolve("events.jsonl"))
                .stream().filter(e -> e.type().equals("responseBody")).findFirst().orElseThrow();
        String value = String.valueOf(body.data().get("body"));
        assertEquals(0, value.length() % 4, "base64 must stay 4-char aligned: " + value);
        assertEquals(Boolean.TRUE, body.data().get("bodyTruncated"));
    }
}
