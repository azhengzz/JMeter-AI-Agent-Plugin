package org.gitee.jmeter.ai.agent.tools.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DelegateToInstanceTool} 的目标寻址矩阵(无/单/多目标/自委派)。
 *
 * <p>用真实 loopback {@link HttpServer} 模拟存活对端实例,把它们的端口文件写入一个临时 jmeter home
 * 下的 ipc 目录。{@link InstanceRegistry#listInstances} 对每个实例做 TCP 探活——真实 bind 的端口
 * connect 成功即视为存活(TCP 路径先于 PID 路径),故可给对端任意互异 pid 以区分文件名。
 */
class DelegateToInstanceToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    File tmp;
    private final List<HttpServer> servers = new ArrayList<>();
    private File ipcDir;
    private String prevHome;

    @BeforeEach
    void setUp() throws IOException {
        // 让工具的 ipcDir() 指向临时 home:InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()))
        prevHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tmp.getAbsolutePath());
        ipcDir = InstanceRegistry.ipcDir(tmp);
        InstanceContext.init(); // 稳定 self instanceId
    }

    @AfterEach
    void tearDown() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
        if (prevHome != null) {
            JMeterUtils.setJMeterHome(prevHome);
        } else {
            JMeterUtils.setJMeterHome("");
        }
    }

    @Test
    void noIdentifierReturnsErrorBeforeAnyLookup() {
        ToolResult r = new DelegateToInstanceTool().execute(Map.of("task", "do something"));
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
        assertTrue(r.getError().toLowerCase().contains("instanceid") || r.getError().contains("jmxPath"),
                "should name the missing identifier: " + r.getError());
    }

    @Test
    void unknownInstanceIdReturnsError() {
        // 注册表为空(无对端)→ 按 instanceId 过滤必然落空
        ToolResult r = new DelegateToInstanceTool().execute(Map.of(
                "task", "x", "instanceId", "does-not-exist-999"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("No live peer"), r.getError());
    }

    @Test
    void singleJmxMatchDelegatesAndReturnsPeerReply() throws Exception {
        LivePeer peer = createPeer("200001", "peer-A", "/plans/x.jmx", 1_000L, "reply-A");

        ToolResult r = new DelegateToInstanceTool().execute(Map.of(
                "task", "analyze", "jmxPath", "/plans/x.jmx"));

        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("reply-A"), "must surface the peer agent's reply");
        assertTrue(r.getContent().contains("peer-A"), "must name the target instanceId");
        assertEquals(1, peer.hits, "the matching peer's server must have been hit exactly once");
    }

    @Test
    void multipleJmxHoldersPicksMostRecentlyStarted() throws Exception {
        LivePeer older = createPeer("300001", "peer-old", "/plans/shared.jmx", 1_000L, "reply-old");
        LivePeer newer = createPeer("300002", "peer-new", "/plans/shared.jmx", 2_000L, "reply-new");

        ToolResult r = new DelegateToInstanceTool().execute(Map.of(
                "task", "analyze", "jmxPath", "/plans/shared.jmx"));

        assertTrue(r.isSuccess(), r.getError());
        // 6.5: 多实例持同 jmx → 按 startedAt 降序取最 reciente
        assertTrue(r.getContent().contains("reply-new"), "must delegate to the most-recently-started peer");
        assertTrue(r.getContent().contains("2 peers"), "must note how many peers held the jmx: " + r.getContent());
        assertEquals(1, newer.hits);
        assertEquals(0, older.hits);
    }

    @Test
    void delegatingToSelfIsRejected() throws Exception {
        // 把"自身"写成存活实例(instanceId = 当前 instanceId),委派给自己必须被拒
        createPeer(InstanceRegistry.currentPid(), InstanceContext.instanceId(), null, 1_000L, "should-not-happen");

        ToolResult r = new DelegateToInstanceTool().execute(Map.of(
                "task", "x", "instanceId", InstanceContext.instanceId()));

        assertFalse(r.isSuccess());
        assertTrue(r.getError().toLowerCase().contains("yourself"), r.getError());
    }

    // ---- helpers ----

    /** 建一个存活对端:绑定 loopback 端口、写端口文件、(按需)覆盖 startedAt 以确定多择排序。 */
    private LivePeer createPeer(String pid, String instanceId, String jmxPath, long startedAt, String replyContent)
            throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        LivePeer peer = new LivePeer(s);
        s.createContext("/agent", ex -> reply(ex, replyContent, peer));
        s.start();
        int port = s.getAddress().getPort();
        InstanceRegistry.writeInstance(ipcDir, pid, port, "peer-tok", "127.0.0.1", instanceId,
                jmxPath == null ? "" : jmxPath);
        // writeInstance 把 startedAt 设为 now;测试需要确定性排序,故读改写回指定值
        File pf = InstanceRegistry.portFile(ipcDir, pid);
        InstanceInfo info = MAPPER.readValue(pf, InstanceInfo.class);
        info.setStartedAt(startedAt);
        MAPPER.writeValue(pf, info);
        servers.add(s);
        return peer;
    }

    private void reply(HttpExchange ex, String content, LivePeer peer) throws IOException {
        peer.hits++;
        org.gitee.jmeter.ai.ipc.protocol.IpcResponse resp = new org.gitee.jmeter.ai.ipc.protocol.IpcResponse();
        resp.setSuccess(true);
        resp.setContent(content);
        byte[] out = MAPPER.writeValueAsBytes(resp);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static final class LivePeer {
        final HttpServer server;
        int hits;

        LivePeer(HttpServer server) {
            this.server = server;
        }
    }
}
