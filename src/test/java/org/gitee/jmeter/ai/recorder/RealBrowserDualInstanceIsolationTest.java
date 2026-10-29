package org.gitee.jmeter.ai.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.cdp.DevToolsHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 双实例并行录制隔离（opt-in：{@code -Drecorder.run.real.browser=true} 且本机有 Chrome）。
 *
 * <p>隔离的第一性约束：Chrome 以 user-data-dir 为单例域——同一 user-data-dir 同一时刻
 * 只能存活一个浏览器进程，第二次启动会被单例转交进已有实例（起始 URL 在对方窗口打开、
 * 新进程退出）。并行录制者靠持久 profile 上的跨进程占用声明（FileLock）仲裁：
 * 先到者用持久 profile（登录态保留），后被占用者<b>不 spawn 进对方单例域</b>、
 * 秒级回退临时目录。本用例模拟共享 workspace 下两个 JMeter 实例先后/同时打开录制浏览器，断言：
 * ① 后开者的起始页不出现在先开者浏览器（无单例转交）、且确实落在自己的浏览器里；
 * ② 先开者用持久 profile（无回退）、后开者回退临时目录（回退原因含占用语义）；
 * ③ 近同时武装时端点互异、至多一方用持久 profile（双方都回退可由双并发冷启动超时触发，非仲裁失败）。
 */
class RealBrowserDualInstanceIsolationTest {

    @TempDir
    Path workspace;

    private HttpServer server;
    private String markerUrl;
    private final List<BrowserInstanceFinder.BrowserHandle> handles = new java.util.ArrayList<>();

    @AfterEach
    void stopSiteAndBrowsers() {
        if (server != null) {
            server.stop(0);
        }
        for (BrowserInstanceFinder.BrowserHandle h : handles) {
            try {
                h.destroy();
            } catch (Exception ignored) {
            }
        }
        releaseProfile();
    }

    /** 错峰开局：A 已持占用声明连上浏览器，B 再开——B 必须回退临时目录且不进 A 的单例域。 */
    @Test
    void staggeredSecondInstanceGetsOwnBrowser() throws Exception {
        assumeEnabled();
        startMarkerSite();
        BrowserInstanceFinder finderA = newFinder();
        BrowserInstanceFinder finderB = newFinder();

        BrowserInstanceFinder.BrowserHandle a = track(finderA.open(workspace, null));
        BrowserInstanceFinder.BrowserHandle b = track(finderB.open(workspace, markerUrl));

        assertNull(a.fallbackReason(), "A 应使用持久 profile，却回退了: " + a.fallbackReason());
        assertNotNull(b.fallbackReason(), "B 在 A 持有占用声明时应回退临时目录，却拿到了持久 profile");
        assertTrue(b.fallbackReason().contains("occupied") || b.fallbackReason().contains("held"),
                "B 的回退原因应为占用语义，实际: " + b.fallbackReason());
        assertNotEquals(a.wsUrl(), b.wsUrl(), "两个实例连上了同一浏览器端点");

        // B 的起始页若被 Chrome 同 user-data-dir 单例转交，会作为新标签出现在 A 的浏览器里
        Optional<String> hijacked = findTarget(debugPort(a), markerUrl, 3000);
        assertTrue(hijacked.isEmpty(),
                "B 的起始页被单例转交进了 A 的浏览器（双实例共享同一浏览器）, 命中 target: " + hijacked.orElse(""));
        // 正向闭环：B 的起始页确实加载进了 B 自己的浏览器（防起始 URL 静默丢弃的空转绿）
        assertTrue(findTarget(debugPort(b), markerUrl, 5000).isPresent(),
                "B 的起始页未出现在 B 自己的浏览器里（起始 URL 丢失?）");
    }

    /** 近同时开局：占用声明必须仲裁出恰好一方持久、一方回退，端点互异。 */
    @Test
    void concurrentLaunchesGetDistinctEndpoints() throws Exception {
        assumeEnabled();
        startMarkerSite();
        BrowserInstanceFinder finderA = newFinder();
        BrowserInstanceFinder finderB = newFinder();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BrowserInstanceFinder.BrowserHandle> fa =
                    pool.submit(() -> finderA.open(workspace, null));
            Future<BrowserInstanceFinder.BrowserHandle> fb =
                    pool.submit(() -> finderB.open(workspace, markerUrl));

            BrowserInstanceFinder.BrowserHandle a = track(fa.get(60, java.util.concurrent.TimeUnit.SECONDS));
            BrowserInstanceFinder.BrowserHandle b = track(fb.get(60, java.util.concurrent.TimeUnit.SECONDS));
            assertNotEquals(a.wsUrl(), b.wsUrl(),
                    "双实例连上同一浏览器端点（互相录进对方流量）: A=" + a.wsUrl() + " B=" + b.wsUrl());

            // 仲裁不变式 = 至多一方持久（双方临时回退可由双并发冷启动 >10s 触发，非仲裁失败）
            int persistent = (a.fallbackReason() == null ? 1 : 0) + (b.fallbackReason() == null ? 1 : 0);
            assertTrue(persistent <= 1, "占用声明必须保证至多一方持久 profile（实际持久方数=" + persistent
                    + ", fallbackA=" + a.fallbackReason() + ", fallbackB=" + b.fallbackReason() + "）");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 装配与工具 ----

    private void assumeEnabled() {
        assumeTrue(Boolean.getBoolean("recorder.run.real.browser"),
                "dual-instance isolation is opt-in: -Drecorder.run.real.browser=true");
        assumeTrue(findLocalBrowser() != null, "no local Chrome; dual-instance isolation skipped");
    }

    private BrowserInstanceFinder newFinder() {
        RecorderConfig cfg = RecorderConfig.from(k -> switch (k) {
            case RecorderConfig.KEY_BROWSER -> findLocalBrowser().toString();
            default -> null;
        });
        return new BrowserInstanceFinder(cfg, new DevToolsHttp());
    }

    private BrowserInstanceFinder.BrowserHandle track(BrowserInstanceFinder.BrowserHandle h) {
        handles.add(h);
        return h;
    }

    /** B 的起始页：本地一站一页，URL 即转交指纹。 */
    private void startMarkerSite() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dual-b", exchange -> {
            byte[] body = "<!doctype html><html><body>dual-b</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        markerUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/dual-b";
    }

    /** 轮询指定浏览器的 /json/list，窗口内出现 url 前缀匹配的 page target 即返回其 url。 */
    private static Optional<String> findTarget(int debugPort, String urlPrefix, long windowMs) throws Exception {
        long deadline = System.currentTimeMillis() + windowMs;
        Optional<String> found = Optional.empty();
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            String body = java.net.http.HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + debugPort + "/json/list")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).body();
            for (JsonNode t : RecorderJson.parse(body)) {
                if ("page".equals(t.path("type").asText()) && t.path("url").asText().startsWith(urlPrefix)) {
                    return Optional.of(t.path("url").asText());
                }
            }
            Thread.sleep(200);
        }
        return found;
    }

    private static int debugPort(BrowserInstanceFinder.BrowserHandle h) {
        var m = java.util.regex.Pattern.compile("ws://127\\.0\\.0\\.1:(\\d+)/").matcher(h.wsUrl());
        assertTrue(m.find(), "wsUrl 缺端口: " + h.wsUrl());
        return Integer.parseInt(m.group(1));
    }

    /** 树杀后 Windows 文件句柄释放有延迟：重试删除，避免 @TempDir 清理报错。 */
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
}
