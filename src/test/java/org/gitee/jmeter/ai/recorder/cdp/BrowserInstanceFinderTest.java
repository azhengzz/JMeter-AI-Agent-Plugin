package org.gitee.jmeter.ai.recorder.cdp;

import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 1.3：专用实例启动参数、DevToolsActivePort 解析与可读报错（attach 腿已移除）。 */
class BrowserInstanceFinderTest {

    private static RecorderConfig config(Map<String, String> overrides) {
        return RecorderConfig.from(overrides::get);
    }

    /** 恒不可达的探活 fake。 */
    private static final Prober DEAD_PROBER = ep -> Optional.empty();

    @Test
    void launchArgsOrderAndUrl() {
        Path exe = Path.of("C:/b/chrome.exe");
        List<String> args = BrowserInstanceFinder.launchArgs(exe, Path.of("D:/p"), "https://x");
        assertEquals(exe.toString(), args.get(0));
        assertEquals("--remote-debugging-port=0", args.get(1));
        assertTrue(args.get(2).startsWith("--user-data-dir="));
        assertEquals("--no-first-run", args.get(3));
        assertEquals("--no-default-browser-check", args.get(4));
        assertEquals("https://x", args.get(5));
        // 无起始 URL：五个参数
        assertEquals(5, BrowserInstanceFinder.launchArgs(Path.of("b"), Path.of("p"), null).size());
    }

    @Test
    void stalePortFileIsRetriedUntilEndpointAlive(@TempDir Path dir) throws Exception {
        // 陈旧端口文件（上次残留死端口）+ 延迟后新端口就绪：联合轮询必须等到活的端点
        Path portFile = dir.resolve("DevToolsActivePort");
        Files.writeString(portFile, "1111\n");
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
                Files.writeString(portFile, "2222\n/devtools/browser/x\n");
                alive.set(true);
            } catch (Exception ignored) {
            }
        }, java.util.concurrent.CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS));
        String ws = BrowserInstanceFinder.waitForDevToolsWs(portFile, ep -> alive.get()
                ? Optional.of("ws://127.0.0.1:2222/devtools/browser/x")
                : Optional.empty(), 2000);
        assertEquals("ws://127.0.0.1:2222/devtools/browser/x", ws);
    }

    @Test
    void waitForDevToolsWsTimesOutGracefully(@TempDir Path dir) {
        assertNull(BrowserInstanceFinder.waitForDevToolsWs(
                dir.resolve("none"), ep -> Optional.empty(), 150));
    }

    @Test
    void devToolsEndpointPolling(@TempDir Path dir) throws Exception {
        Path portFile = dir.resolve("DevToolsActivePort");
        Files.writeString(portFile, "9222\n/devtools/browser/abc\n");
        assertEquals("ws://alive", BrowserInstanceFinder.waitForDevToolsWs(
                portFile, ep -> Optional.of("ws://alive"), 500));
        assertNull(BrowserInstanceFinder.waitForDevToolsWs(dir.resolve("none"), ep -> Optional.empty(), 100));
    }

    @Test
    void profileClaimExclusivityAndRelease(@TempDir Path dir) throws Exception {
        Path profile = dir.resolve("browser-profile");
        BrowserInstanceFinder.ProfileClaim first = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
        assertNotNull(first, "首个占用声明应成功");
        try {
            // 本测试只验同 JVM 第二 channel 的 OverlappingFileLockException 腿；
            // 生产场景的跨进程 tryLock 返 null 腿由 claimIsExclusiveAcrossProcesses 覆盖
            assertNull(BrowserInstanceFinder.ProfileClaim.tryAcquire(profile), "占用期第二个声明必须被拒");
        } finally {
            first.release();
        }
        // 释放后可重新声明（收官换新引擎的再武装路径）；幂等释放不抛
        BrowserInstanceFinder.ProfileClaim second = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
        assertNotNull(second, "释放后应可重新声明");
        second.release();
        second.release();
    }

    /**
     * 跨进程互斥的真实回归（对齐 MemoryStoreWriteLockTest 先例）：fork 子 JVM 独占声明，
     * 本进程 tryLock 必须被 OS 级 FileLock 拒绝；子进程退出后锁随进程终止自动释放、可重新声明。
     * 若锁实现回归为 JVM 内标志位等非 OS 锁，本进程 tryAcquire 会立即成功 → 断言失败。
     */
    @Test
    void claimIsExclusiveAcrossProcesses(@TempDir Path dir) throws Exception {
        Path profile = dir.resolve("browser-profile");
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"),
                BrowserInstanceFinderTest.class.getName() + "$ChildClaimHolder",
                profile.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertEquals("CHILD_READY", readChildFirstLine(child), "子 JVM 未就绪");
            assertNull(BrowserInstanceFinder.ProfileClaim.tryAcquire(profile),
                    "子 JVM 持锁期间本进程拿到了声明——OS FileLock 未跨进程互斥?");

            child.destroyForcibly();
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "子 JVM 未退出");
            // 进程退出后 OS 自动释放锁：有界重试吸收终止清理的毫秒级滞后
            BrowserInstanceFinder.ProfileClaim re = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (re == null && System.currentTimeMillis() < deadline) {
                re = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
                if (re == null) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(re, "子 JVM 退出后 5s 内应可重新声明");
            if (re != null) {
                re.release();
            }
        } finally {
            child.destroyForcibly();
        }
    }

    /** 子 JVM 占用声明器：tryAcquire 成功打 CHILD_READY 后持锁长眠；被拒打 CHILD_DENIED 退出。 */
    public static class ChildClaimHolder {
        public static void main(String[] args) throws Exception {
            BrowserInstanceFinder.ProfileClaim claim = BrowserInstanceFinder.ProfileClaim
                    .tryAcquire(java.nio.file.Path.of(args[0]));
            System.out.println(claim == null ? "CHILD_DENIED" : "CHILD_READY");
            System.out.flush();
            if (claim == null) {
                return;
            }
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** 有界读子 JVM 首行输出（EOF/IO 异常返回诊断串/null）。 */
    private static String readChildFirstLine(Process child) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> first = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(child.getInputStream()))) {
                first.set(r.readLine());
            } catch (Exception e) {
                first.set("<io-error: " + e + ">");
            }
        });
        reader.start();
        reader.join(15_000);
        return first.get();
    }

    // ---- launch 编排不变式（注入 fake 启动缝，默认套件常跑、不依赖真 Chrome） ----

    /** 编排测试装配：可执行文件桩（resolveExecutable 只查存在性，fake 缝不会真 spawn）。 */
    private static RecorderConfig stubExeConfig(Path dir) throws IOException {
        Path exe = dir.resolve("stub-chrome.exe");
        Files.writeString(exe, "stub");
        return RecorderConfig.from(k -> RecorderConfig.KEY_BROWSER.equals(k) ? exe.toString() : null);
    }

    /** 即返 fake 进程：记录 destroyForcibly/waitFor 调用序；toHandle 走基类抛错→killProcessTree 吞异常分支。 */
    private static Process fakeProcess(List<String> calls) {
        return new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
                calls.add("waitFor");
                return true;
            }
            @Override public int exitValue() { return 0; }
            @Override public boolean isAlive() { return false; }
            @Override public void destroy() { calls.add("destroy"); }
            @Override public Process destroyForcibly() { calls.add("destroyForcibly"); return this; }
        };
    }

    /** 早败门：spawn 的进程已死（单例转交秒退/崩溃）时立即失败，不等满超时预算。 */
    @Test
    void deadProcessFailsFastInsteadOfBurningTimeout(@TempDir Path dir) throws Exception {
        Path portFile = dir.resolve("DevToolsActivePort");
        Files.writeString(portFile, "9222\n/devtools/browser/x\n"); // 端点探活恒真：门必须先于端口判定
        Process dead = fakeProcess(new ArrayList<>());              // isAlive() = false
        long start = System.currentTimeMillis();
        String ws = BrowserInstanceFinder.waitForDevToolsWs(portFile,
                ep -> Optional.of("ws://alive"), 5000, dead);
        assertNull(ws, "进程已死应立即失败");
        assertTrue(System.currentTimeMillis() - start < 2000, "不应等满超时预算");
    }

    /** 临时回退目录在 destroy 后应被异步清理（带期限重试吸收 Windows 句柄滞后）。 */
    @Test
    void tempFallbackProfileIsCleanedUpOnDestroy(@TempDir Path ws) throws Exception {
        RecorderConfig cfg = stubExeConfig(ws);
        Path profile = ws.resolve("browser-profile");
        BrowserInstanceFinder.ProfileClaim held = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
        assertNotNull(held);
        List<Path> spawned = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        try {
            BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, (exe, prof, url) -> {
                spawned.add(prof);
                return new BrowserInstanceFinder.InstanceLauncher.Launched(fakeProcess(calls), "ws://fake-4");
            });
            BrowserInstanceFinder.BrowserHandle h = finder.launch(ws, null);
            assertEquals(1, spawned.size(), "恰一次临时重试");
            Path temp = spawned.get(0);
            assertTrue(Files.exists(temp), "临时回退目录已创建");
            h.destroy();
            long deadline = System.currentTimeMillis() + 8000;
            while (Files.exists(temp) && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertFalse(Files.exists(temp), "destroy 后临时目录应被异步清理");
        } finally {
            held.release();
        }
    }

    /** 被拒不 spawn：他实例持声明时，本实例不得 spawn 进持久 profile，必须临时目录回退且原因含占用语义。 */
    @Test
    void deniedLaunchSkipsPersistentProfileAndFallsBack(@TempDir Path ws) throws Exception {
        RecorderConfig cfg = stubExeConfig(ws);
        Path profile = ws.resolve("browser-profile");
        BrowserInstanceFinder.ProfileClaim held = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
        assertNotNull(held, "预持有声明应成功");

        List<Path> spawned = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, (exe, prof, url) -> {
            spawned.add(prof);
            return new BrowserInstanceFinder.InstanceLauncher.Launched(fakeProcess(calls), "ws://fake-1");
        });
        try {
            BrowserInstanceFinder.BrowserHandle h = finder.launch(ws, null);
            try {
                assertEquals(1, spawned.size(), "恰一次临时重试");
                assertNotEquals(profile, spawned.get(0), "被拒不 spawn 进被持有的持久 profile");
                assertNotNull(h.fallbackReason());
                assertTrue(h.fallbackReason().contains("occupied"),
                        "回退原因应为占用语义: " + h.fallbackReason());
            } finally {
                h.destroy();
            }
        } finally {
            held.release();
        }
    }

    /** catch 兜底必释放声明 + 非占用失败分类为 launch failed。 */
    @Test
    void failedPersistentAttemptReleasesClaimAndClassifiesReason(@TempDir Path ws) throws Exception {
        RecorderConfig cfg = stubExeConfig(ws);
        Path profile = ws.resolve("browser-profile");
        List<String> calls = new ArrayList<>();
        BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, (exe, prof, url) -> {
            if (prof.equals(profile)) {
                throw new IOException("simulated endpoint timeout");
            }
            return new BrowserInstanceFinder.InstanceLauncher.Launched(fakeProcess(calls), "ws://fake-2");
        });
        BrowserInstanceFinder.BrowserHandle h = finder.launch(ws, null);
        h.destroy();
        // catch 兜底必须释放声明：此后可重新获取
        BrowserInstanceFinder.ProfileClaim reacquired = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
        assertNotNull(reacquired, "持久尝试失败后声明应已释放（catch 漏 release 回归）");
        reacquired.release();
        assertTrue(h.fallbackReason().contains("launch failed"),
                "非占用失败应分类为 launch failed: " + h.fallbackReason());
    }

    /** destroy 次序不变式：树杀→有界等待完成前声明不得释放（用可阻塞 fake waitFor 钉死窗口）。 */
    @Test
    void destroyReleasesClaimOnlyAfterKillAndWait(@TempDir Path ws) throws Exception {
        RecorderConfig cfg = stubExeConfig(ws);
        Path profile = ws.resolve("browser-profile");
        CountDownLatch waitForEntered = new CountDownLatch(1);
        CountDownLatch finishWaitFor = new CountDownLatch(1);
        List<String> calls = new ArrayList<>();
        Process fake = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
                    throws InterruptedException {
                calls.add("waitFor");
                waitForEntered.countDown();
                finishWaitFor.await();
                return true;
            }
            @Override public int exitValue() { return 0; }
            @Override public boolean isAlive() { return false; }
            @Override public void destroy() { calls.add("destroy"); }
            @Override public Process destroyForcibly() { calls.add("destroyForcibly"); return this; }
        };
        BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, (exe, prof, url) ->
                new BrowserInstanceFinder.InstanceLauncher.Launched(fake, "ws://fake-3"));
        BrowserInstanceFinder.BrowserHandle h = finder.launch(ws, null);

        Thread destroyer = new Thread(h::destroy);
        destroyer.start();
        assertTrue(waitForEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "destroy 未走到有界等待");
        // waitFor 已进入（树杀已发起）而声明必须仍未释放——释放早于杀/等待即为次序回归
        assertNull(BrowserInstanceFinder.ProfileClaim.tryAcquire(profile),
                "声明在杀进程+有界等待完成前就被释放（destroy 次序回归）");
        finishWaitFor.countDown();
        destroyer.join(5000);
        assertFalse(destroyer.isAlive(), "destroy 线程未收尾");

        BrowserInstanceFinder.ProfileClaim after = BrowserInstanceFinder.ProfileClaim.tryAcquire(profile);
        assertNotNull(after, "destroy 完成后声明应已释放");
        after.release();
        assertEquals(List.of("destroyForcibly", "waitFor"), calls, "destroy 调用序应为树杀→有界等待");
    }

    @Test
    void resolveExecutableNotFoundReadableError(@TempDir Path ws) {
        RecorderConfig cfg = config(Map.of("jmeter.ai.recorder.browser", "Z:/nope/browser.exe"));
        BrowserInstanceFinder finder = new BrowserInstanceFinder(cfg, DEAD_PROBER);
        IOException e = assertThrows(IOException.class, () -> finder.launch(ws, null));
        assertTrue(e.getMessage().contains("jmeter.ai.recorder.browser"));
    }

    @Test
    void endpointNormalizeVariants() {
        assertEquals("http://127.0.0.1:9222/json/version", DevToolsHttp.normalize("9222"));
        assertEquals("http://127.0.0.1:9222/json/version", DevToolsHttp.normalize("127.0.0.1:9222"));
        assertEquals("http://localhost:9222/json/version", DevToolsHttp.normalize("http://localhost:9222/"));
        assertEquals("http://h:1/json/version", DevToolsHttp.normalize("http://h:1/json/version"));
    }

    @Test
    void chromeCandidatesOnlyAndUnknownBrowserRejected(@TempDir Path dir) {
        // Edge 支持已按用户要求移除：只认 chrome；其它取值按未找到处理
        java.util.Map<String, String> env = java.util.Map.of(
                "ProgramFiles", dir.toString(),
                "ProgramFiles(x86)", dir.toString(),
                "LOCALAPPDATA", dir.toString());
        List<String> paths = BrowserInstanceFinder.candidatePaths("chrome", env::get);
        assertEquals(3, paths.size());
        assertTrue(paths.get(0).endsWith("chrome.exe"));
        assertTrue(BrowserInstanceFinder.candidatePaths("edge", env::get).isEmpty());
    }
}
