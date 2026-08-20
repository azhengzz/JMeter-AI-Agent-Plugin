package org.gitee.jmeter.ai.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 启动期一次性、best-effort 把遗留的全局会话文件 {@code jmeter-ai-chat.jsonl} 归档进共享
 * {@code HISTORY.md},使既有用户上下文在切换到每实例会话后仍对其他实例可见。
 *
 * <p>仅当每实例会话启用({@code agent.session.per-instance=true})时执行;关闭时该文件仍是
 * 活跃会话,不做迁移。归档后<b>保留原文件</b>(不删),以一个标记文件
 * {@code memory/.legacy-session-migrated} 防止重复归档。所有异常吞掉(不影响启动)。
 */
public final class LegacySessionMigrator {
    private static final Logger log = LoggerFactory.getLogger(LegacySessionMigrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String LEGACY_FILE = InstanceContext.LEGACY_SESSION_KEY + ".jsonl";
    private static final String MARKER = ".legacy-session-migrated";

    private LegacySessionMigrator() {
    }

    /**
     * 尝试迁移遗留会话。幂等、best-effort:无遗留文件/已迁移/每实例会话关闭时均为 no-op。
     */
    public static void migrate() {
        if (!AiConfig.isSessionPerInstance()) {
            return; // 旧键仍在用,不迁移
        }
        try {
            Path workspace = WorkspacePaths.resolveWorkspace();
            Path legacy = workspace.resolve("sessions").resolve(LEGACY_FILE);
            if (!Files.exists(legacy)) {
                return;
            }
            MemoryStore store = new MemoryStore(workspace);
            Path marker = store.getMemoryDir().resolve(MARKER);
            if (Files.exists(marker)) {
                return; // 已归档
            }
            String entry = buildLegacyEntry(legacy);
            if (entry == null) {
                // 空会话:也打标记,避免每次启动重复读空文件。
                Files.createFile(marker);
                return;
            }
            store.appendHistory(entry);
            Files.createFile(marker);
            log.info("Legacy session archived into HISTORY.md ({} messages, original kept)",
                    LEGACY_FILE);
        } catch (Exception e) {
            log.warn("Legacy session migration skipped (best-effort): {}", e.getMessage());
        }
    }

    private static String buildLegacyEntry(Path legacy) throws Exception {
        int count = 0;
        StringBuilder body = new StringBuilder();
        try (BufferedReader r = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                if (node.has("_type") && "metadata".equals(node.get("_type").asText())) {
                    continue;
                }
                String role = node.has("role") ? node.get("role").asText() : "user";
                String content = (node.has("content") && !node.get("content").isNull())
                        ? node.get("content").asText() : "";
                if (content.isEmpty()) {
                    continue;
                }
                count++;
                body.append("[").append(role.toUpperCase()).append("] ")
                        .append(truncate(content, 300)).append("\n");
            }
        }
        if (count == 0) {
            return null;
        }
        return "[" + LocalDateTime.now().format(TS) + "] [LEGACY] migrated " + count
                + " messages from legacy global session " + LEGACY_FILE + "\n" + body;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
