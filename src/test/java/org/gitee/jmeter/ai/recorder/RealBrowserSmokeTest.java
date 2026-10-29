package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.cdp.DevToolsHttp;
import org.gitee.jmeter.ai.recorder.cdp.WebSocketCdpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实浏览器冒烟（本机有 Chrome 才跑，否则跳过）：open → ARMED → 取消。
 *
 * <p>存在的意义：FakeTransport 是同步内存回放，测不出 WebSocket 传输层与真实 CDP 的
 * 契约问题——曾发生的 JDK WebSocket 需求计数缺陷（不调 {@code request(n)} 则只有第一条
 * 消息被分发）正是靠本类测试捕获的「预检过、autoAttach 超时」场景。
 */
class RealBrowserSmokeTest {

    @TempDir
    Path workspace;

    @Test
    void openArmedAndCancelAgainstRealBrowser() throws Exception {
        assumeTrue(Boolean.getBoolean("recorder.run.real.browser"),
                "real-browser smoke is opt-in: -Drecorder.run.real.browser=true");
        Path exe = findLocalBrowser();
        assumeTrue(exe != null, "no local Chrome; real-browser smoke skipped");

        RecorderConfig cfg = RecorderConfig.from(k -> switch (k) {
            case RecorderConfig.KEY_BROWSER -> exe.toString();
            default -> null;
        });
        BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, new DevToolsHttp());
        RecorderEngine engine = new RecorderEngine(cfg, new RecorderEngine.EngineListener() {
        }, workspace, "smoke", (c, ws, url) -> finder.open(ws, url),
                WebSocketCdpTransport::new, RecorderScripts.INIT_SCRIPT);

        engine.open(new RecorderEngine.OpenParams(null, Set.of()));
        // 能到 ARMED 说明：getVersion 预检 + setAutoAttach 应答 + 页面会话初始化全通
        assertEquals(RecorderEngine.State.ARMED, engine.state());
        engine.stop(); // ARMED 停止 = 取消（杀进程树收尾）
        assertEquals(RecorderEngine.State.CANCELLED, engine.state());
        awaitProfileReleased(workspace.resolve("browser-profile"));
    }

    /** 树杀后 Windows 文件句柄释放有延迟：重试删除，避免 @TempDir 清理报错。 */
    private static void awaitProfileReleased(Path profile) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (Files.exists(profile) && System.currentTimeMillis() < deadline) {
            deleteQuietly(profile);
            Thread.sleep(200);
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
