package org.gitee.jmeter.ai.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.cdp.CdpConnection;
import org.gitee.jmeter.ai.recorder.cdp.DevToolsHttp;
import org.gitee.jmeter.ai.recorder.cdp.WebSocketCdpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实浏览器迷你端到端（opt-in：{@code -Drecorder.run.real.browser=true} 且本机有 Chrome）：
 * 本地静态站 + 本地 API，覆盖任务 2.1（注入存活/悬浮条状态切换/导航后存活）与任务 2.4 核心
 * （预备期登录不入录制流、控制面停止 + 关浏览器双通道、请求/响应配对、UI 事件、预备态
 * 登录 cookie 经基线进入产物）。驱动方式：页面级第二条 CDP 连接（不干扰引擎的 browser 级连接）。
 */
class RealBrowserRecordingE2eTest {

    @TempDir
    Path workspace;

    private HttpServer server;
    private String siteUrl;
    private String siteHost;
    private final AtomicReference<BrowserInstanceFinder.BrowserHandle> handleRef = new AtomicReference<>();
    private RecorderEngine engine;

    // ---- 本地站点 ----

    private void startSite() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if ("/api/login".equals(path)) {
                exchange.getResponseHeaders().add("Set-Cookie", "sess=abc; Path=/");
                body = "ok".getBytes(StandardCharsets.UTF_8);
            } else if ("/api/biz".equals(path) || "/api/biz2".equals(path)) {
                body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            } else if ("/second".equals(path)) {
                body = page("<button id=\"do-biz2\" onclick=\"fetch('/api/biz2')"
                        + ".then(function(r){return r.text()}).then(function(){window.__biz2Done=true})\">biz2</button>")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                body = page("<button id=\"do-login\" onclick=\"fetch('/api/login')"
                        + ".then(function(r){return r.text()}).then(function(){window.__loginDone=true})\">login</button>"
                        + "<button id=\"do-biz\" onclick=\"fetch('/api/biz')"
                        + ".then(function(r){return r.text()}).then(function(){window.__bizDone=true})\">biz</button>"
                        + "<button id=\"open-tab\" onclick=\"window.__opened=true;window.open('/second')\">open manual</button>")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type",
                    path.startsWith("/api/") ? "application/json" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        siteHost = server.getAddress().getAddress().getHostAddress();
        siteUrl = "http://" + siteHost + ":" + server.getAddress().getPort() + "/";
    }

    private static String page(String buttons) {
        return "<!doctype html><html><body>" + buttons + "</body></html>";
    }

    @AfterEach
    void stopSiteAndBrowser() {
        if (server != null) {
            server.stop(0);
        }
        if (engine != null) {
            try {
                engine.stop(); // 幂等：终态后 no-op（state!=RECORDING 且 !=ARMED 直接返回）
            } catch (Exception ignored) {
            }
        }
        BrowserInstanceFinder.BrowserHandle h = handleRef.get();
        if (h != null) {
            h.destroy();
        }
        releaseProfile();
    }

    private void releaseProfile() {
        Path profile = workspace.resolve("browser-profile");
        long deadline = System.currentTimeMillis() + 8000;
        while (Files.exists(profile) && System.currentTimeMillis() < deadline) {
            deleteQuietly(profile);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ---- 引擎与页面驱动 ----

    private void assumeEnabled() {
        assumeTrue(Boolean.getBoolean("recorder.run.real.browser"),
                "real-browser e2e is opt-in: -Drecorder.run.real.browser=true");
        assumeTrue(findLocalBrowser() != null, "no local Chrome; real-browser e2e skipped");
    }

    private RecorderEngine newEngine() {
        RecorderConfig cfg = RecorderConfig.from(k -> switch (k) {
            case RecorderConfig.KEY_BROWSER -> findLocalBrowser().toString();
            default -> null;
        });
        BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, new DevToolsHttp());
        RecorderEngine.BrowserProvider provider = (c, ws, url) -> {
            BrowserInstanceFinder.BrowserHandle h = finder.open(ws, url);
            handleRef.set(h);
            return h;
        };
        engine = new RecorderEngine(cfg, new RecorderEngine.EngineListener() {
        }, workspace, "e2e", provider, WebSocketCdpTransport::new, RecorderScripts.INIT_SCRIPT);
        return engine;
    }

    /** 页面级第二条 CDP 连接（引擎连 browser 端点，本驱动连 page 端点，互不干扰）。 */
    private static final class PageDriver implements AutoCloseable {
        final CdpConnection connection;

        PageDriver(String wsUrl) throws Exception {
            connection = new CdpConnection(new WebSocketCdpTransport());
            connection.setEventListener(new CdpConnection.EventListener() {
                @Override public void onEvent(String method, JsonNode params, String sessionId) { }
                @Override public void onTransportClosed() { }
            });
            connection.connect(wsUrl);
            connection.send("Runtime.enable").get(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        /** 主世界求值（页面函数/DOM 驱动；returnByValue 解包为 Java 标量）。 */
        Object eval(String expression) throws Exception {
            JsonNode res = connection.send("Runtime.evaluate",
                    Map.of("expression", expression, "returnByValue", true), null)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            JsonNode v = res.path("result").path("value");
            if (v.isMissingNode() || v.isNull()) {
                return null;
            }
            if (v.isBoolean()) {
                return v.asBoolean();
            }
            if (v.isTextual()) {
                return v.asText();
            }
            if (v.isNumber()) {
                return v.asDouble();
            }
            return v.toString();
        }

        @Override
        public void close() {
            connection.close();
        }

        /** 可信点击（Input.dispatchMouseEvent）：合成 .click() 的 isTrusted=false，被注入脚本正确忽略。 */
        void mouseClick(String center) throws Exception {
            String[] xy = center.split(",");
            Map<String, Object> common = Map.of("x", Double.parseDouble(xy[0]),
                    "y", Double.parseDouble(xy[1]), "button", "left", "clickCount", 1);
            Map<String, Object> pressed = new java.util.HashMap<>(common);
            pressed.put("type", "mousePressed");
            Map<String, Object> released = new java.util.HashMap<>(common);
            released.put("type", "mouseReleased");
            connection.send("Input.dispatchMouseEvent", pressed, null).get(5, java.util.concurrent.TimeUnit.SECONDS);
            connection.send("Input.dispatchMouseEvent", released, null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        /** 轮询表达式直至返回 "x,y"（元素中心），然后可信点击。 */
        void awaitCenterAndClick(String centerExpression, String what) throws Exception {
            long deadline = System.currentTimeMillis() + 15000;
            while (true) {
                Object v = eval(centerExpression);
                if (v instanceof String s && s.contains(",")) {
                    mouseClick(s);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("Timed out waiting for: " + what + " (last=" + v + ")");
                }
                Thread.sleep(150);
            }
        }
    }

    /** /json/list 里轮询目标页（url 前缀匹配）。 */
    private JsonNode awaitPageTarget(String urlPrefix) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        long deadline = System.currentTimeMillis() + 15000;
        String lastList = "";
        while (System.currentTimeMillis() < deadline) {
            String body = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + debugPort() + "/json/list")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            lastList = body;
            for (JsonNode t : RecorderJson.parse(body)) {
                if ("page".equals(t.path("type").asText()) && t.path("url").asText().startsWith(urlPrefix)) {
                    return t;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("page target not found: " + urlPrefix + "\n/json/list=" + lastList);
    }

    private int debugPort() {
        var m = java.util.regex.Pattern.compile("ws://127\\.0\\.0\\.1:(\\d+)/").matcher(handleRef.get().wsUrl());
        assertTrue(m.find(), "wsUrl 缺端口: " + handleRef.get().wsUrl());
        return Integer.parseInt(m.group(1));
    }

    /** 复用生产候选清单（含 LOCALAPPDATA 用户级安装），防测试与生产漂移。 */
    private static Path findLocalBrowser() {
        for (String c : BrowserInstanceFinder.candidatePaths("chrome", System::getenv)) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    // ---- 等待与断言 helpers ----

    private static void awaitUntil(BooleanSupplier condition, long timeoutMs, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(100);
        }
    }

    private static void awaitTrue(PageDriver driver, String expression, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!Boolean.TRUE.equals(driver.eval(expression))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(150);
        }
    }

    /** 悬浮胶囊中心坐标（fixed 定位 + 「开始/停止录制」文案；DOM 跨世界共享，主世界可寻）。 */
    private static final String PILL_CENTER =
            "(function(){var ds=document.querySelectorAll('div');for(var i=0;i<ds.length;i++){"
                    + "var d=ds[i];if(d.style&&d.style.position==='fixed'){var t=d.textContent||'';"
                    + "if(t.indexOf('开始')>=0||t.indexOf('停止')>=0){var r=d.getBoundingClientRect();"
                    + "return Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2);}}}})()";

    private static final String PILL_TEXT =
            "(function(){var ds=document.querySelectorAll('div');for(var i=0;i<ds.length;i++){"
                    + "var d=ds[i];if(d.style&&d.style.position==='fixed'){var t=d.textContent||'';"
                    + "if(t.indexOf('开始')>=0||t.indexOf('停止')>=0)return t;}}return '';})()";

    private static String btnCenter(String id) {
        return "(function(){var b=document.getElementById('" + id + "');if(!b)return null;"
                + "var r=b.getBoundingClientRect();"
                + "return Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2)})()";
    }

    // ---- 用例 ----

    /** 2.1 + 2.4 主链路：注入存活 → 预备期登录（不入流）→ 胶囊开始 → 业务与 UI 事件 → 导航存活 → 控制面停止。 */
    @Test
    void controlPlaneStopCapturesBusinessFlowOnly() throws Exception {
        assumeEnabled();
        startSite();
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(siteUrl, Set.of(siteHost)));
        awaitUntil(() -> e.state() == RecorderEngine.State.ARMED, 15000, "ARMED");

        JsonNode target = awaitPageTarget(siteUrl);
        try (PageDriver driver = new PageDriver(target.path("webSocketDebuggerUrl").asText())) {
            awaitUntil(() -> {
                try {
                    Object t = driver.eval(PILL_TEXT);
                    return t != null && t.toString().contains("开始");
                } catch (Exception ex) {
                    return false;
                }
            }, 15000, "悬浮胶囊注入存活（armed 态文案）");

            // 预备期「登录」：请求发出且完成，但处于 ARMED——不得入录制流
            driver.awaitCenterAndClick(btnCenter("do-login"), "登录按钮");
            awaitTrue(driver, "window.__loginDone===true", "预备期登录完成");

            // 胶囊点击「开始录制」→ binding → 引擎 RECORDING，且状态推送回胶囊
            driver.awaitCenterAndClick(PILL_CENTER, "胶囊点击开始录制");
            awaitUntil(() -> e.state() == RecorderEngine.State.RECORDING, 10000, "RECORDING");
            awaitUntil(() -> {
                try {
                    Object t = driver.eval(PILL_TEXT);
                    return t != null && t.toString().contains("停止");
                } catch (Exception ex) {
                    return false;
                }
            }, 10000, "胶囊状态切换为录制中");

            // 录制期业务请求 + UI 点击事件（可信点击才会被注入脚本记为 UI 事件）
            driver.awaitCenterAndClick(btnCenter("do-biz"), "业务按钮");
            awaitTrue(driver, "window.__bizDone===true", "业务请求完成");

            // 整页导航后胶囊存活且状态重推（新文档 = 新隔离世界）
            driver.eval("location.href='" + siteUrl + "second'");
            awaitPageTarget(siteUrl + "second");
            awaitUntil(() -> {
                try {
                    String t = (String) driver.eval(PILL_TEXT);
                    return t != null && t.contains("停止");
                } catch (Exception ex) {
                    return false;
                }
            }, 15000, "导航后胶囊重注入且保持录制态");
            driver.awaitCenterAndClick(btnCenter("do-biz2"), "第二页业务按钮");
            awaitTrue(driver, "window.__biz2Done===true", "第二页业务请求完成");
            Thread.sleep(400); // 响应事件落盘余量

            e.stop(); // 控制面停止通道
        }
        awaitUntil(() -> e.state() == RecorderEngine.State.DONE, 15000, "DONE");

        Path dir = e.status().dir();
        String events = Files.readString(dir.resolve("events.jsonl"));
        assertTrue(events.contains("/api/biz"), "业务请求入流:\n" + events);
        assertTrue(events.contains("/api/biz2"), "第二页业务请求入流");
        assertFalse(events.contains("/api/login"), "预备期登录请求不得入流:\n" + events);
        assertFalse(events.contains("do-login"), "预备期 UI 事件不得入流");
        assertTrue(events.contains("\"type\":\"request\"") && events.contains("\"type\":\"response\""),
                "请求/响应配对落盘");
        // UI 事件落盘 type 取载荷 ev（click/change/submit），选择器进 data.selector
        assertTrue(events.contains("\"type\":\"click\"") && events.contains("do-biz"),
                "UI 点击事件入流:\n" + events);

        String cookies = Files.readString(dir.resolve("cookies.json"));
        assertTrue(cookies.contains("sess") && cookies.contains("abc"),
                "预备期登录 cookie 经基线进入产物:\n" + cookies);
    }

    /** 2.4 第二停止通道：录制态直接杀浏览器进程树 → WS onClose 自动收官，已录数据完整。 */
    @Test
    void browserCloseChannelAutoFinalizes() throws Exception {
        assumeEnabled();
        startSite();
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(siteUrl, Set.of(siteHost)));
        awaitUntil(() -> e.state() == RecorderEngine.State.ARMED, 15000, "ARMED");

        JsonNode target = awaitPageTarget(siteUrl);
        PageDriver driver = new PageDriver(target.path("webSocketDebuggerUrl").asText());
        driver.awaitCenterAndClick(PILL_CENTER, "胶囊点击开始录制");
        awaitUntil(() -> e.state() == RecorderEngine.State.RECORDING, 10000, "RECORDING");
        driver.awaitCenterAndClick(btnCenter("do-biz"), "业务按钮");
        awaitTrue(driver, "window.__bizDone===true", "业务请求完成");
        Thread.sleep(400);

        driver.close();       // 先撤驱动连接
        handleRef.get().destroy(); // 杀浏览器进程树 → 引擎 WS onClose → 自动收官
        awaitUntil(() -> e.state() == RecorderEngine.State.DONE, 15000, "关浏览器通道 DONE");

        Path dir = e.status().dir();
        String events = Files.readString(dir.resolve("events.jsonl"));
        assertTrue(events.contains("/api/biz"), "关窗通道已录数据完整:\n" + events);
        assertTrue(Files.exists(dir.resolve("cookies.json")), "关窗通道仍落 cookie 产物（基线）");
    }

    /**
     * 录制中经 window.open 新开的标签页不得被 waitForDebuggerOnStart 挂死：
     * 复现 osc.gitee.work「帮助手册开新标签空白、一直 loading」问题——新标签 attach 后
     * 若 setup 链任一命令不应答，Runtime.run 永不发出，renderer 停在调试暂停态。
     */
    @Test
    void newTabOpenedDuringRecordingLoadsFully() throws Exception {
        assumeEnabled();
        startSite();
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(siteUrl, Set.of(siteHost)));
        awaitUntil(() -> e.state() == RecorderEngine.State.ARMED, 15000, "ARMED");

        JsonNode target = awaitPageTarget(siteUrl);
        try (PageDriver driver = new PageDriver(target.path("webSocketDebuggerUrl").asText())) {
            driver.awaitCenterAndClick(PILL_CENTER, "胶囊点击开始录制");
            awaitUntil(() -> e.state() == RecorderEngine.State.RECORDING, 10000, "RECORDING");

            // 可信点击（window.open 需用户手势放行）；开新标签瞬间浏览器响应可能迟滞，
            // dispatch 应答超时不算失败——以页面标志位确认点击已送达
            long deadline = System.currentTimeMillis() + 15000;
            String center = null;
            while (center == null && System.currentTimeMillis() < deadline) {
                Object v = driver.eval(btnCenter("open-tab"));
                if (v instanceof String s && s.contains(",")) {
                    center = s;
                } else {
                    Thread.sleep(150);
                }
            }
            assertTrue(center != null, "找到开新标签按钮");
            try {
                driver.mouseClick(center);
            } catch (Exception dispatchTimeoutIgnored) {
                // 新标签开启时 Input 应答可能 >5s；以 __opened 为准
            }
            awaitTrue(driver, "window.__opened===true", "window.open 已执行");
            JsonNode second = awaitPageTarget(siteUrl + "second");
            try (PageDriver d2 = new PageDriver(second.path("webSocketDebuggerUrl").asText())) {
                awaitUntil(() -> {
                    try {
                        Object rs = d2.eval("document.readyState");
                        Object len = d2.eval("document.body ? document.body.innerHTML.length : -1");
                        return "complete".equals(rs) && len instanceof Double d && d > 10;
                    } catch (Exception ex) {
                        return false;
                    }
                }, 15000, "新标签页加载完成（非空白、不卡 loading）");
            }
            Thread.sleep(400);
            e.stop();
        }
        awaitUntil(() -> e.state() == RecorderEngine.State.DONE, 15000, "DONE");
    }
}
