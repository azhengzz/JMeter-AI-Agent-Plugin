package org.gitee.jmeter.ai.instance;

import com.sun.net.httpserver.HttpServer;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SessionReaper} 的保守回收行为:只删失活且超 TTL 的孤立会话文件;
 * 活跃实例、当前实例、未超龄、遗留命名(jmeter-ai-chat)的文件一律保留。
 *
 * <p>TTL 以参数显式传入,与 {@code agent.session.reap.ttl.days} 配置解耦,保持测试确定性。
 */
class SessionReaperTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @TempDir
    File tmp;
    private Path sessionsDir;
    private File ipcDir;
    private HttpServer liveServer;

    @BeforeEach
    void setUp() throws IOException {
        sessionsDir = tmp.toPath().resolve("sessions");
        Files.createDirectories(sessionsDir);
        ipcDir = new File(tmp, "ipc");
    }

    @AfterEach
    void tearDown() {
        if (liveServer != null) {
            liveServer.stop(0);
        }
    }

    @Test
    void reapsStaleOrphanButKeepsActiveYoungLegacyAndSelf() throws Exception {
        long ttlMs = 7 * DAY_MS;
        String self = InstanceContext.instanceId();

        // 1) 失活 + 超 TTL 的孤立文件 → 应被回收
        File staleOrphan = createSession("400001-1700000000000", 8 * DAY_MS);
        // 2) 失活但未超 TTL → 保留(太年轻)
        File youngOrphan = createSession("400002-1700000000001", 1 * DAY_MS);
        // 3) 遗留命名 → 不匹配 {pid}-{ts} 模式,跳过
        File legacy = createSession(InstanceContext.LEGACY_SESSION_KEY, 30 * DAY_MS);
        // 4) 当前实例自身 → 恒不回收
        File selfFile = createSession(self, 30 * DAY_MS);

        // 5) 存活对端实例(真实 bind 端口 + 端口文件 instanceId 一致)→ 保留
        liveServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        liveServer.start();
        InstanceRegistry.writeInstance(ipcDir, "400003", liveServer.getAddress().getPort(),
                "tok", "127.0.0.1", "400003-1700000000002", "/plans/peer.jmx");
        File livePeer = createSession("400003-1700000000002", 30 * DAY_MS);

        int reaped = SessionReaper.reap(sessionsDir, ipcDir, ttlMs, self);

        assertEquals(1, reaped, "only the stale orphan should be reaped");
        assertFalse(Files.exists(staleOrphan.toPath()), "stale orphan must be deleted");
        assertTrue(Files.exists(youngOrphan.toPath()), "young orphan must be kept");
        assertTrue(Files.exists(legacy.toPath()), "legacy file must be kept");
        assertTrue(Files.exists(selfFile.toPath()), "self session must be kept");
        assertTrue(Files.exists(livePeer.toPath()), "active peer session must be kept");
    }

    @Test
    void missingSessionsDirIsNoOp() {
        int reaped = SessionReaper.reap(tmp.toPath().resolve("does-not-exist"), ipcDir,
                7 * DAY_MS, InstanceContext.instanceId());
        assertEquals(0, reaped);
    }

    @Test
    void pidReusedPortFileIsTreatedAsOrphan() throws Exception {
        // 端口文件存在且存活,但 instanceId 不一致(同 pid 不同 startedAt = PID 被新进程复用)→ 旧会话视为孤立
        liveServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        liveServer.start();
        InstanceRegistry.writeInstance(ipcDir, "400010", liveServer.getAddress().getPort(),
                "tok", "127.0.0.1", "400010-1700000000099", "/plans/p.jmx");
        // 旧会话文件:同 pid、不同(更早)startedAt —— 即上一次启动的残留
        File stale = createSession("400010-1700000000010", 8 * DAY_MS);

        int reaped = SessionReaper.reap(sessionsDir, ipcDir, 7 * DAY_MS, InstanceContext.instanceId());

        assertEquals(1, reaped, "PID-reused orphan (instanceId mismatch) should be reaped once over TTL");
        assertFalse(Files.exists(stale.toPath()));
    }

    /** 在 sessionsDir 下建一个空的 {name}.jsonl,并把 lastModified 置为 now - ageMs。 */
    private File createSession(String name, long ageMs) throws IOException {
        File f = sessionsDir.resolve(name + ".jsonl").toFile();
        Files.createFile(f.toPath());
        long target = Math.max(0L, System.currentTimeMillis() - ageMs);
        // ageMs 可能 > now(测试用的远未来时间戳构造的负偏移),钳到 0
        assertTrue(f.setLastModified(target), "setLastModified failed for " + name);
        return f;
    }
}
