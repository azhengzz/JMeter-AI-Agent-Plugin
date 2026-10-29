package org.gitee.jmeter.ai.recorder.cdp;

import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 录制浏览器提供者：新开专用实例（{@code --remote-debugging-port=0} + DevToolsActivePort
 * 轮询；持久 profile 先取 {@link ProfileClaim} 跨进程占用声明，被并行录制实例占用时
 * 秒级回退临时目录并记录原因——不 spawn 进对方 user-data-dir，杜绝 Chrome 单例转交）。
 *
 * <p>attach 模式（cdp.url 显式附着 / 本机扫描附着既有浏览器）已按用户要求移除——
 * Chrome 136+ 在默认用户配置目录下静默忽略调试端口参数，附着日常浏览器大多不可行，
 * 专用实例（持久 profile 保留登录态）是唯一路径。
 */
public final class BrowserInstanceFinder {
    private static final Logger log = LoggerFactory.getLogger(BrowserInstanceFinder.class);
    /** 冷启动（全新 profile 建目录 + 首跑向导 + 杀软扫描）下端口文件与端点就绪都可能滞后——10s 联合轮询。 */
    private static final long ENDPOINT_WAIT_MS = 10000;

    /** 浏览器实例句柄：own=true 收官时销毁进程。 */
    public interface BrowserHandle {
        String wsUrl();

        boolean own();

        /** 持久 profile 被占用回退临时目录等原因，无则 null。 */
        String fallbackReason();

        void destroy();
    }

    /**
     * 实例启动缝：把「spawn 进程 + 端点就绪等待」从 launch 编排中隔离出来。
     * 真实实现 {@link SpawnLauncher}；默认套件注入 fake 钉编排不变式
     * （被拒不 spawn / catch 必释放声明 / 回退原因分类 / destroy 杀放次序）。
     */
    interface InstanceLauncher {
        Launched launch(Path exe, Path profile, String startUrl) throws IOException;

        /** 启动结果：进程句柄 + 浏览器级 wsUrl。 */
        record Launched(Process process, String wsUrl) {
        }
    }

    private final RecorderConfig config;
    private final InstanceLauncher launcher;

    public BrowserInstanceFinder(RecorderConfig config, Prober prober) {
        this(config, new SpawnLauncher(prober));
    }

    /** 测试装配：注入启动缝（默认缝 = 真实 spawn + 端点轮询）。 */
    BrowserInstanceFinder(RecorderConfig config, InstanceLauncher launcher) {
        this.config = config;
        this.launcher = launcher;
    }

    /** 打开录制浏览器：新开专用实例；浏览器路径/启动失败抛带配置指引的 IOException。 */
    public BrowserHandle open(Path workspace, String startUrl) throws IOException {
        return launch(workspace, startUrl);
    }

    /** 新开专用实例。 */
    public BrowserHandle launch(Path workspace, String startUrl) throws IOException {
        Path exe = resolveExecutable();
        Path profile = config.resolveProfileDir(workspace);
        ProfileClaim claim = null;
        boolean denied = false;
        try {
            claim = ProfileClaim.tryAcquire(profile);
            if (claim == null) {
                // 另一录制实例正在使用该 profile——绝不 spawn 进对方单例域（会被 Chrome
                // 单例转交、起始页开进对方窗口），直接抛出走临时目录回退
                denied = true;
                throw new IOException("persistent profile is held by another recording instance ("
                        + profile + ")");
            }
            return handle(launcher.launch(exe, profile, startUrl), null, claim, null);
        } catch (IOException first) {
            if (claim != null) {
                claim.release();
            }
            // 持久 profile 首启失败（被占/端点超时/启动失败）→ 临时目录重试一次；
            // 回退原因如实分类，防把「锁 IO 故障/冷启动超时」误标成「被占用」误导排障
            Path temp = Files.createTempDirectory("jai-rec-profile");
            log.warn("Launch with persistent profile failed ({}), retrying with temp profile {}",
                    first.getMessage(), temp);
            String reason = (denied ? "persistent profile occupied: " : "persistent profile launch failed: ")
                    + first.getMessage();
            try {
                return handle(launcher.launch(exe, temp, startUrl), reason, null, temp);
            } catch (IOException second) {
                throw new IOException("无法启动录制浏览器（" + config.browser + "）："
                        + second.getMessage() + "。请检查 jmeter.ai.recorder.browser 配置"
                        + "（可取值 chrome / 可执行文件绝对路径）", second);
            }
        }
    }

    /** 真实启动缝实现：spawn chrome + DevToolsActivePort 联合轮询。 */
    private static final class SpawnLauncher implements InstanceLauncher {
        private final Prober prober;

        SpawnLauncher(Prober prober) {
            this.prober = prober;
        }

        @Override
        public Launched launch(Path exe, Path profile, String startUrl) throws IOException {
            Files.createDirectories(profile);
            // 清掉上次运行残留的 DevToolsActivePort——它指向的可能是已被杀掉（或僵尸化）的旧实例，
            // 残留文件会让端口探测「秒过」并连上僵尸端点（新实例因同 profile 单例转交根本没起来）
            Files.deleteIfExists(profile.resolve("DevToolsActivePort"));
            List<String> cmd = launchArgs(exe, profile, startUrl);
            Process process;
            try {
                process = new ProcessBuilder(cmd).start();
            } catch (IOException e) {
                throw new IOException("browser process start failed: " + e.getMessage(), e);
            }
            Path portFile = profile.resolve("DevToolsActivePort");
            String wsUrl = waitForDevToolsWs(portFile, prober, ENDPOINT_WAIT_MS, process);
            if (wsUrl == null) {
                // 树杀而非只杀启动器：只杀启动器会留浏览器子进程带 profile 锁成僵尸，
                // 占用声明随后被释放，本 JVM 此后每次开局都被僵尸单例转交、10s 超时再回退
                killProcessTree(process);
                if (!process.isAlive()) {
                    throw new IOException("浏览器进程在 DevTools 端点就绪前退出（profile: " + profile
                            + "）。常见于该 user-data-dir 已有实例（垂死/僵尸）被 Chrome 单例转交、"
                            + "或浏览器启动即崩溃——若刚有录制收官请稍候重试；持续失败请用"
                            + "任务管理器清理残留 chrome 进程");
                }
                throw new IOException("DevTools 端点在 " + ENDPOINT_WAIT_MS + "ms 内未就绪（profile: "
                        + profile + "）。冷启动过慢或调试端口被组策略禁用"
                        + "（DevToolsRemoteDebuggingAllowed），请重试或检查浏览器策略");
            }
            log.info("Launched recording browser: {} (profile {}, endpoint {})", exe.getFileName(),
                    profile.getFileName(), wsUrl);
            return new Launched(process, wsUrl);
        }
    }

    /** @param cleanupDir 非空 = 临时回退目录，destroy 时异步清理；null = 持久 profile 不清理。 */
    private static BrowserHandle handle(InstanceLauncher.Launched launched, String fallbackReason,
            ProfileClaim claim, Path cleanupDir) {
        final Process p = launched.process();
        final String ws = launched.wsUrl();
        return new BrowserHandle() {
            @Override
            public String wsUrl() {
                return ws;
            }

            @Override
            public boolean own() {
                return true;
            }

            @Override
            public String fallbackReason() {
                return fallbackReason;
            }

            @Override
            public void destroy() {
                killProcessTree(p);
                try {
                    // 树杀是异步发起（TerminateProcess 发起即返回）：有界等启动器退出
                    // （单例域随进程拆除释放）再放声明，把垂死期抢锁窗口压到等待上限内
                    p.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (claim != null) {
                    claim.release();
                }
                if (cleanupDir != null) {
                    // 临时 profile 用完即弃：异步清理（收官路径不为目录清卸滞留；
                    // Windows 垂死句柄释放有滞后，清理线程带期限重试）
                    deleteLater(cleanupDir);
                }
            }
        };
    }

    /** 后台 daemon 线程带期限重试删除（仅临时回退路径调用，低频）。 */
    private static void deleteLater(Path dir) {
        Thread cleaner = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 10_000;
            while (Files.exists(dir) && System.currentTimeMillis() < deadline) {
                deleteQuietly(dir);
                if (Files.exists(dir)) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "jai-rec-temp-profile-cleanup");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 杀整棵进程树：Windows 上 chrome 是启动器 + 子进程树，只杀启动器
     * 会让真正的浏览器子进程带 profile 锁与调试端口存活成僵尸（后续同 profile
     * 启动被单实例转交，端口探测连上僵尸端点且 CDP 不响应）。
     */
    static void killProcessTree(Process p) {
        try {
            p.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception e) {
            log.debug("descendant kill failed: {}", e.getMessage());
        }
        p.destroyForcibly();
    }

    /** 专用实例启动参数（顺序即语义：调试端口 → 独立 profile → 首跑抑制 → 起始 URL）。 */
    static List<String> launchArgs(Path exe, Path profile, String startUrl) {
        List<String> cmd = new ArrayList<>(List.of(
                exe.toString(),
                "--remote-debugging-port=0",
                "--user-data-dir=" + profile.toAbsolutePath(),
                "--no-first-run",
                "--no-default-browser-check"));
        if (startUrl != null && !startUrl.isBlank()) {
            cmd.add(startUrl);
        }
        return cmd;
    }

    /**
     * 持久 profile 的跨进程占用声明：OS 级 {@link FileLock}（持有进程退出即自动释放，
     * 无残留锁；对齐 MemoryStore.lockLongTermMemory 的 FileLock 先例）。持有期 = 本次
     * 录制浏览器生命周期（BrowserHandle.destroy 释放）；拿不到 = 另一录制实例正在
     * 使用该 user-data-dir，不得 spawn 进去（Chrome 单例域会被转交）。
     */
    static final class ProfileClaim implements AutoCloseable {
        /** profile 目录内声明文件名（Chrome 对 user-data-dir 根的无关文件不敏感）。 */
        static final String CLAIM_FILE = ".recorder-claim";

        private final FileChannel channel;
        private final FileLock lock;
        private boolean released;

        private ProfileClaim(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /** @return 声明成功；null = 被其它录制实例（或本 JVM 泄漏的旧声明）持有。 */
        static ProfileClaim tryAcquire(Path profile) throws IOException {
            Files.createDirectories(profile);
            FileChannel ch = FileChannel.open(profile.resolve(CLAIM_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lk = null;
            try {
                lk = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                // 本 JVM 另一 channel 已持有（上一场录制的声明泄漏）：视同占用，走回退
            } finally {
                if (lk == null) {
                    // 拿不到锁（他进程持有/本 JVM 重叠/锁 IO 异常向上传播）：关 channel 防句柄泄漏
                    ch.close();
                }
            }
            return lk == null ? null : new ProfileClaim(ch, lk);
        }

        /** 幂等释放（引擎收官与中止清理路径可能对同一 handle 双触发 destroy）。 */
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                lock.release();
            } catch (IOException ignored) {
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    /** 默认浏览器路径候选（env 可注入供测试；公开给真实浏览器测试复用同一清单）：仅 Chrome（Edge 支持已按用户要求移除，非 Chrome 视为未找到）。 */
    public static List<String> candidatePaths(String browser, java.util.function.Function<String, String> envLookup) {
        String pf64 = orDefault(envLookup.apply("ProgramFiles"), "C:\\Program Files");
        String pf86 = orDefault(envLookup.apply("ProgramFiles(x86)"), "C:\\Program Files (x86)");
        String lappd = orDefault(envLookup.apply("LOCALAPPDATA"), "");
        List<String> chrome = new ArrayList<>(List.of(
                pf64 + "\\Google\\Chrome\\Application\\chrome.exe",
                pf86 + "\\Google\\Chrome\\Application\\chrome.exe"));
        if (!lappd.isEmpty()) {
            chrome.add(lappd + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        return "chrome".equals(browser) ? chrome : List.of();
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private Path resolveExecutable() throws IOException {
        String b = config.browser == null ? "chrome" : config.browser.trim();
        if (b.contains("/") || b.contains("\\")) {
            Path p = Path.of(b);
            if (Files.isRegularFile(p)) {
                return p;
            }
            throw new IOException("浏览器路径不存在：" + b
                    + "。请检查 jmeter.ai.recorder.browser（可取值 chrome / 可执行文件绝对路径）");
        }
        for (String candidate : candidatePaths(b.toLowerCase(), System::getenv)) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IOException("未找到已安装的浏览器（" + b + "）。"
                + "请检查 jmeter.ai.recorder.browser（可取值 chrome / 可执行文件绝对路径）");
    }

    /**
     * 「端口文件出现 + 端点探活通过」联合轮询（冷启动两段都可能滞后）。
     * 端口文件可能是上次运行残留的陈旧端口——探活不通过就继续轮询等新文件覆盖，
     * 不会误判成功也不会因陈旧文件提前失败。
     *
     * @return 浏览器级 webSocketDebuggerUrl；超时返回 null。
     */
    static String waitForDevToolsWs(Path portFile, Prober prober, long timeoutMs) {
        return waitForDevToolsWs(portFile, prober, timeoutMs, null);
    }

    /**
     * @param process 非空时作早败门：spawn 的浏览器进程死亡（同 user-data-dir 已有
     *        实例被 Chrome 单例转交后新进程立即退出，或启动即崩溃）→ 立即返回 null
     *        不等满超时，把占用冲突的代价从整个超时预算压到一次轮询间隔。
     */
    static String waitForDevToolsWs(Path portFile, Prober prober, long timeoutMs, Process process) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive()) {
                return null;
            }
            Integer port = readPortIfPresent(portFile);
            if (port != null) {
                var ws = prober.probeWebSocketUrl("http://127.0.0.1:" + port);
                if (ws.isPresent()) {
                    return ws.get();
                }
                // 端口文件在但探活不通：陈旧文件或端点未就绪，继续轮询
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 读端口文件首行的端口号；不存在/写入中途/非数字返回 null。 */
    private static Integer readPortIfPresent(Path portFile) {
        if (!Files.isRegularFile(portFile)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).matches("\\d+")) {
                return Integer.parseInt(lines.get(0));
            }
        } catch (IOException ignored) {
            // 文件写入中途
        }
        return null;
    }
}
