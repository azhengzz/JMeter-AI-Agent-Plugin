package org.gitee.jmeter.ai.agent.tools.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReadInstanceSessionTool} 对抗性测试:刁钻输入下要么行为正确、要么优雅降级,绝不崩溃/越界/泄漏。
 *
 * <p>覆盖攻击面:路径穿越(safeFileName 规范化)、规范化碰撞、regex 语义注入(query 必须字面)、
 * 空/仅元数据/目录/BOM/CRLF 文件、1MB 消息有界性、代理对截断、null 与非字符串参数、
 * tool 角色命中不计入、元数据乱序、query 超长防护(Nanobot max_length=500 对齐)、excerpt 模糊轰炸。
 */
class ReadInstanceSessionToolAdversarialTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    File tmp;

    @Test
    void pathTraversalInstanceIdsNeverEscapeSessionsDir() throws IOException {
        // 会话目录外放一个诱饵文件:sessions 同级的 evil.jsonl;穿越型 instanceId 若逃逸会读到它
        Files.write(new File(tmp, "evil.jsonl").toPath(),
                List.of("{\"role\":\"user\",\"content\":\"SECRET-OUTSIDE\"}"), StandardCharsets.UTF_8);
        for (String id : new String[]{"..", "../evil", "..\\evil", "../../evil", "/evil", "a/../evil"}) {
            ToolResult r = newTool().execute(Map.of("instanceId", id));
            assertFalse(r.isSuccess(), "traversal id should not resolve: " + id);
            assertTrue(r.getError().contains("No persisted session"), id + ": " + r.getError());
        }
        assertFalse(new String(Files.readAllBytes(new File(tmp, "evil.jsonl").toPath()),
                StandardCharsets.UTF_8).isEmpty(), "诱饵文件不该被动过");
    }

    @Test
    void sanitizedCollisionReadsSameFileForEquivalentInstanceIds() throws IOException {
        // "peer A"(空格)与"peer_A"、"peer/A" 规范化后同名——instanceId 真实格式为 {pid}-{ms} 不含
        // 这些字符,碰撞仅是理论 quirk;钉死行为:三者命中同一文件、同一内容
        writeSession("peer_A", List.of(metadataLine(), msgLine("user", "collide-me")));
        for (String id : new String[]{"peer_A", "peer A", "peer/A"}) {
            ToolResult r = newTool().execute(Map.of("instanceId", id));
            assertTrue(r.isSuccess(), id);
            assertTrue(r.getContent().contains("collide-me"), id);
        }
    }

    @Test
    void regexLookingQueryIsTreatedLiterally() throws IOException {
        writeSession("peer-A", List.of(metadataLine(),
                msgLine("user", "price: 100 USD"),
                msgLine("assistant", "total: 200 EUR")));
        // "100.*USD" 若被当 regex 会命中第一条;字面语义下无任何消息含该子串
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A", "query", "100.*USD"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("No visible messages match query '100.*USD'"), r.getContent());
        // 元字符以外的普通子串按字面命中
        ToolResult r2 = newTool().execute(Map.of("instanceId", "peer-A", "query", "100 USD"));
        assertTrue(r2.isSuccess());
        assertTrue(r2.getContent().contains("price: 100 USD"));
    }

    @Test
    void emptyAndMetadataOnlyFilesDegradeToZeroVisible() throws IOException {
        File sessions = new File(tmp, "sessions");
        assertTrue(sessions.mkdirs());
        Files.write(new File(sessions, "empty.jsonl").toPath(), new byte[0]);
        Files.write(new File(sessions, "meta.jsonl").toPath(),
                List.of(metadataLine()), StandardCharsets.UTF_8);

        ToolResult r1 = newTool().execute(Map.of("instanceId", "empty"));
        assertTrue(r1.isSuccess(), r1.getError());
        assertTrue(r1.getContent().contains("0 visible messages"), r1.getContent());
        assertTrue(r1.getContent().contains("No visible messages."), r1.getContent());

        ToolResult r2 = newTool().execute(Map.of("instanceId", "meta"));
        assertTrue(r2.isSuccess(), r2.getError());
        assertTrue(r2.getContent().contains("last updated 2026-09-13T11:00:00"), r2.getContent());
        assertTrue(r2.getContent().contains("No visible messages."), r2.getContent());
    }

    @Test
    void directoryInPlaceOfSessionFileGivesErrorNotCrash() throws IOException {
        File sessions = new File(tmp, "sessions");
        assertTrue(sessions.mkdirs());
        assertTrue(new File(sessions, "adir.jsonl").mkdirs());
        ToolResult r = newTool().execute(Map.of("instanceId", "adir"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("Failed to read"), r.getError());
    }

    @Test
    void oneMegabyteMessageStaysBoundedInOutput() throws IOException {
        String huge = "A".repeat(1_000_000 - 4) + "TAIL";
        writeSession("peer-A", List.of(metadataLine(), msgLine("user", huge)));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));
        assertTrue(r.isSuccess(), r.getError());
        String c = r.getContent();
        assertTrue(c.length() < 50_000, "总输出必须远小于源消息: " + c.length());
        assertFalse(c.contains("TAIL"), "头部截断不应漏出尾部哨兵");
        // 消息体那一行 ≤ 4001(4000 内容 + 换行;省略号已含在 4000 内)
        for (String line : c.split("\n")) {
            assertTrue(line.length() <= 4001, "单行越界: " + line.length());
        }
    }

    @Test
    void surrogatePairsAtTruncationBoundaryDoNotCrash() throws IOException {
        // 3000 个 emoji = 6000 个 UTF-16 单元,头部截断边界可能劈开代理对——不崩溃即可
        writeSession("peer-A", List.of(metadataLine(),
                msgLine("user", "😀".repeat(3000)),
                msgLine("assistant", "收尾")));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("收尾"));
        assertTrue(r.getContent().contains("2 visible messages"));
    }

    @Test
    void crlfJsonlParsesLikeLf() throws IOException {
        String json = metadataLine() + "\r\n" + msgLine("user", "windows 写的文件") + "\r\n";
        File sessions = new File(tmp, "sessions");
        assertTrue(sessions.mkdirs());
        Files.write(new File(sessions, "peer-A.jsonl").toPath(),
                json.getBytes(StandardCharsets.UTF_8));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("windows 写的文件"), r.getContent());
        assertTrue(r.getContent().contains("1 visible messages"), r.getContent());
    }

    @Test
    void bomAtFileStartDegradesGracefully() throws IOException {
        String json = '﻿' + metadataLine() + "\n" + msgLine("user", "bom 后的内容") + "\n";
        File sessions = new File(tmp, "sessions");
        assertTrue(sessions.mkdirs());
        Files.write(new File(sessions, "peer-A.jsonl").toPath(),
                json.getBytes(StandardCharsets.UTF_8));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));
        // 无论 BOM 行是否解析成功,完好消息必须返回、不崩溃;updatedAt 允许缺失
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("bom 后的内容"), r.getContent());
    }

    @Test
    void toolRoleMatchIsNotCountedEvenWhenQueryHitsIt() throws IOException {
        writeSession("peer-A", List.of(metadataLine(),
                msgLine("tool", "secret-in-tool-result"),
                msgLine("user", "普通提问")));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A", "query", "secret-in-tool"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("No visible messages match"), r.getContent());
        assertFalse(r.getContent().contains("secret-in-tool-result"),
                "tool 角色消息不得因 query 命中而泄漏");
    }

    @Test
    void metadataAfterMessagesStillProvidesUpdatedAt() throws IOException {
        writeSession("peer-A", List.of(msgLine("user", "乱序元数据"), metadataLine()));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("last updated 2026-09-13T11:00:00"), r.getContent());
        assertTrue(r.getContent().contains("乱序元数据"));
    }

    @Test
    void whitespaceOnlyContentIsSkippedFromVisibleCount() throws IOException {
        writeSession("peer-A", List.of(metadataLine(),
                msgLine("user", "   \t\n  "),
                msgLine("user", "实消息")));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("1 visible messages"), r.getContent());
    }

    @Test
    void nonStringAndNullParametersAreCoercedSafely() throws IOException {
        writeSession("12345", List.of(metadataLine(), msgLine("user", "数字 id 的会话")));
        Map<String, Object> numeric = new HashMap<>();
        numeric.put("instanceId", 12345);   // LLM 传整数
        ToolResult r = newTool().execute(numeric);
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("数字 id 的会话"));

        Map<String, Object> nullQuery = new HashMap<>();
        nullQuery.put("instanceId", "12345");
        nullQuery.put("query", null);       // 显式 JSON null
        ToolResult r2 = newTool().execute(nullQuery);
        assertTrue(r2.isSuccess(), r2.getError());
    }

    @Test
    void chineseQueryMatchesCjkContent() throws IOException {
        writeSession("peer-A", List.of(metadataLine(),
                msgLine("user", "线程组配置已完成"),
                msgLine("assistant", "好的")));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A", "query", "线程组"));
        assertTrue(r.isSuccess(), r.getError());
        assertTrue(r.getContent().contains("线程组配置已完成"));
        assertFalse(r.getContent().contains("好的"), "未命中消息不得返回");
    }

    @Test
    void oversizedQueryIsRejectedLikeNanobotMaxLength() throws IOException {
        // Nanobot schema query max_length=500;本工具 schema 无框架强制,超长 query 若放行,
        // 会被头部原样回显一次(无放大但无上界)——应对齐 Nanobot 拒绝
        writeSession("peer-A", List.of(metadataLine(), msgLine("user", "hello")));
        ToolResult r = newTool().execute(Map.of("instanceId", "peer-A",
                "query", "q".repeat(501)));
        if (r.isSuccess()) {
            fail("oversized query should be rejected (Nanobot max_length=500 parity): "
                    + r.getContent().length() + " chars echoed");
        }
        assertTrue(r.getError().contains("500"), r.getError());
    }

    @Test
    void excerptFuzzNeverThrowsOrExceedsBound() {
        Random rnd = new Random(42); // 固定种子,确定性
        for (int i = 0; i < 300; i++) {
            String text = randomText(rnd);
            String needle = rnd.nextBoolean() ? randomText(rnd) : "a";
            int limit = 1 + rnd.nextInt(500);
            String out = ReadInstanceSessionTool.excerpt(text, needle, limit);
            assertNotNull(out, "iter " + i);
            assertTrue(out.length() <= limit + 2,
                    "iter " + i + ": len " + out.length() + " > limit+2 " + (limit + 2));
        }
    }

    // ---- helpers ----

    private ReadInstanceSessionTool newTool() {
        return new ReadInstanceSessionTool(tmp.toPath());
    }

    private void writeSession(String fileNameStem, List<String> lines) throws IOException {
        File sessions = new File(tmp, "sessions");
        if (!sessions.exists() && !sessions.mkdirs()) {
            throw new IOException("cannot create " + sessions);
        }
        Files.write(new File(sessions, fileNameStem + ".jsonl").toPath(), lines, StandardCharsets.UTF_8);
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
        n.put("content", content);
        n.put("timestamp", "2026-09-13T10:15:30");
        return n.toString();
    }

    private static String randomText(Random r) {
        int n = r.nextInt(600);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            switch (r.nextInt(5)) {
                case 0 -> sb.append((char) ('a' + r.nextInt(26)));
                case 1 -> sb.append("线程组");
                case 2 -> sb.append("😀");
                case 3 -> sb.append(r.nextBoolean() ? ' ' : '\n');
                default -> sb.append('x');
            }
        }
        return sb.toString();
    }
}
