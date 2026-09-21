package org.gitee.jmeter.ai.agent.tools.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReadInstanceSessionTool} 的行为矩阵:参数校验/自身排除、有界解析(角色过滤/撕裂行容忍/
 * 最近 8 条窗口)、节选渲染(Nanobot _excerpt 移植)、边界错误与存活标注。
 *
 * <p>workspace 经包私有构造注入 @TempDir;jmeter home 指向同一临时目录使 IPC 注册表默认为空
 * (not live 降级路径),存活路径用真实 loopback {@link HttpServer} + 端口文件
 * ({@code DelegateToInstanceToolTest} 同款配方)。
 */
class ReadInstanceSessionToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    File tmp;
    private final List<HttpServer> servers = new ArrayList<>();
    private String prevHome;

    @BeforeEach
    void setUp() {
        prevHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tmp.getAbsolutePath());
        InstanceContext.init(); // 稳定 self instanceId
    }

    @AfterEach
    void tearDown() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
        JMeterUtils.setJMeterHome(prevHome != null ? prevHome : "");
    }

    // ---- 1.2 参数校验与自身排除 ----

    @Test
    void missingInstanceIdIsRejected() {
        ToolResult r = newTool().execute(Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("instanceId"), r.getError());
    }

    @Test
    void matchAllQueriesAreRejected() {
        for (String q : new String[]{"*", ".*"}) {
            ToolResult r = newTool().execute(Map.of("instanceId", "peer-A", "query", q));
            assertFalse(r.isSuccess(), "query=" + q);
            assertTrue(r.getError().contains("literal substrings"), q + ": " + r.getError());
        }
    }

    @Test
    void readingOwnSessionIsRejected() {
        ToolResult r = newTool().execute(Map.of("instanceId", InstanceContext.instanceId()));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().toLowerCase().contains("own"), r.getError());
    }

    // ---- 1.5 边界与错误路径 ----

    @Test
    void missingSessionFileErrorsWithDiscoveryHint() {
        ToolResult r = newTool().execute(Map.of("instanceId", "ghost"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("list_instances"), r.getError());
    }

    @Test
    void queryWithNoMatchesReturnsClearMessage() throws IOException {
        writeSession("peer-A", List.of(metadataLine(), msgLine("user", "hello there")));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A", "query", "zzz-absent"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("No visible messages match query 'zzz-absent'"),
                r.getContent());
        assertTrue(r.getContent().contains("untrusted"), "notice must still be present");
    }

    // ---- 1.3 有界解析:角色过滤 / 撕裂行容忍 / 最近 8 条窗口 ----

    @Test
    void latestEightVisibleMessagesSkipsToolSystemAndTornLines() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(metadataLine());
        lines.add(msgLine("tool", "tool chatter"));            // tool 角色跳过
        lines.add(msgLine("system", "sys prompt"));            // system 角色跳过
        lines.add("{\"role\":\"assistant\",\"content\":null}"); // null content 跳过
        lines.add("{\"role\":\"user\",\"content\":\"{torn");   // 损坏行容忍(非 JSON)
        for (int i = 1; i <= 20; i++) {
            lines.add(msgLine(i % 2 == 0 ? "assistant" : "user",
                    i == 14 ? "msg-14 线程组配置" : "msg-" + i));
        }
        writeSession("peer-A", lines);

        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));

        assertTrue(r.isSuccess(), r.getError());
        String c = r.getContent();
        assertTrue(c.contains("20 visible messages"), c);
        assertTrue(c.contains("last updated 2026-09-13T11:00:00"), c);
        assertTrue(c.contains("(showing the latest 8 messages)"), c);
        assertTrue(c.contains("[13] user"), c);
        assertTrue(c.contains("[20] assistant"), c);
        assertTrue(c.contains("msg-13"), "最新 8 条应含第 13 条: " + c);
        assertTrue(c.contains("msg-20"), c);
        assertTrue(c.contains("msg-14 线程组配置"), c);
        assertFalse(c.contains("msg-12"), "第 12 条应滑出 8 条窗口: " + c);
        assertFalse(c.contains("msg-5"), c);
        assertFalse(c.contains("tool chatter"), c);
        assertFalse(c.contains("sys prompt"), c);
        assertTrue(c.contains("not live"), "注册表为空应降级标注 not live: " + c);
        assertTrue(c.contains("untrusted"), c);
    }

    // ---- 1.4 query 过滤 + 节选渲染 ----

    @Test
    void queryFiltersCaseInsensitivelyAndCentersExcerptOnMatch() throws IOException {
        String longNeedle = "A".repeat(6000) + "Needle" + "B".repeat(6000);
        writeSession("peer-A", List.of(
                metadataLine(),
                msgLine("user", "short hello"),
                msgLine("assistant", longNeedle),
                msgLine("user", "no match here")));

        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A", "query", "needle"));

        assertTrue(r.isSuccess(), r.getError());
        String c = r.getContent();
        assertTrue(c.contains("3 visible messages"), c);
        assertTrue(c.contains("(query: \"needle\"; 1 matching messages)"), c);
        assertTrue(c.contains("Needle"), "命中窗口必须包含命中点: " + c.substring(0, 400));
        assertTrue(c.contains("…"), "超长消息的节选必须带省略号: " + c.substring(0, 400));
        assertFalse(c.contains("short hello"), "未命中消息不得返回");
        assertFalse(c.contains("no match here"), "未命中消息不得返回");
    }

    @Test
    void excerptCompactsWhitespaceAndKeepsShortText() {
        assertEquals("a b c", ReadInstanceSessionTool.excerpt("a\n\nb\t  c ", "", 4000));
    }

    @Test
    void excerptTruncatesHeadWhenNoMatch() {
        String out = ReadInstanceSessionTool.excerpt("x".repeat(5000), "needle", 100);
        assertTrue(out.length() <= 100, "limit-1 截断 + 省略号 ≤ limit: " + out.length());
        assertTrue(out.endsWith("…"));
        assertTrue(out.startsWith("x"));
    }

    @Test
    void excerptCentersOnFirstMatch() {
        String text = "A".repeat(6000) + "needle" + "B".repeat(6000);
        String out = ReadInstanceSessionTool.excerpt(text, "needle", 1000);
        assertTrue(out.startsWith("…"), "命中点远离头部应前带省略号");
        assertTrue(out.endsWith("…"));
        assertTrue(out.contains("needle"));
        assertTrue(out.indexOf("needle") < 1000 / 2,
                "命中点应大体居中(前 1/3 起窗),而非贴头部: " + out.indexOf("needle"));
    }

    // ---- 存活标注:注册表命中 → live ----

    @Test
    void peerMarkedLiveWhenRegisteredPeerHoldsPortFile() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.start();
        servers.add(s);
        InstanceRegistry.writeInstance(InstanceRegistry.ipcDir(tmp), "410001",
                s.getAddress().getPort(), "tok", "127.0.0.1", "peer-Live", "");
        writeSession("peer-Live", List.of(metadataLine(), msgLine("user", "hello from peer")));

        ToolResult r = newTool().execute(Map.of("instanceId", "peer-Live"));

        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("(live)"), r.getContent());
        assertTrue(r.getContent().contains("hello from peer"));
    }

    // ---- helpers ----

    private ReadInstanceSessionTool newTool() {
        return new ReadInstanceSessionTool(tmp.toPath());
    }

    private void writeSession(String instanceId, List<String> lines) throws IOException {
        File sessions = new File(tmp, "sessions");
        if (!sessions.exists() && !sessions.mkdirs()) {
            throw new IOException("cannot create " + sessions);
        }
        Files.write(new File(sessions, instanceId + ".jsonl").toPath(), lines, StandardCharsets.UTF_8);
    }

    private static String metadataLine() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("_type", "metadata");
        n.put("key", "peer-A");
        n.put("created_at", "2026-09-13T10:00:00");
        n.put("updated_at", "2026-09-13T11:00:00");
        n.put("last_consolidated", 0);
        return n.toString();
    }

    private static String msgLine(String role, String content) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("role", role);
        if (content == null) {
            n.putNull("content");
        } else {
            n.put("content", content);
        }
        n.put("timestamp", "2026-09-13T10:15:30");
        return n.toString();
    }
}
