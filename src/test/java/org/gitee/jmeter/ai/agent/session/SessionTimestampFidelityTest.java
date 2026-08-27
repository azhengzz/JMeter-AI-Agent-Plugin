package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * jsonl 时间戳保真回归测试：Message.Builder 曾无 timestamp 透传——落盘 rebuild
 * （AgentRunner.saveMessagesToSession）与加载 rebuild（SessionManager.jsonToMessage）
 * 都把 timestamp 洗成 rebuild 时刻，导致同回合消息在 ~1ms 时钟粒度下共享同一读数、
 * 重启后全部历史被洗成加载时刻，jsonl 时间戳失去消息时序语义。
 */
class SessionTimestampFidelityTest {

    @Test
    void timestampSurvivesRoundTrip(@TempDir Path tempDir) {
        String key = "ts-fidelity";
        LocalDateTime produced = LocalDateTime.of(2026, 8, 26, 10, 0, 0, 123456700);

        SessionManager saver = new SessionManager(tempDir, key);
        Session session = saver.getOrCreate(key);
        session.addMessage(Message.builder()
                .role(Message.Role.USER)
                .content("hello")
                .timestamp(produced)
                .build());
        saver.saveSession(session);

        // 全新 manager 模拟重启加载（loadSessions 只认 focusSessionKey 的文件）
        SessionManager reloader = new SessionManager(tempDir, key);
        Session loaded = reloader.getOrCreate(key);

        assertEquals(1, loaded.getMessageCount());
        assertEquals(produced, loaded.getMessages().get(0).getTimestamp(),
                "jsonl round-trip 后 timestamp 应保持消息原时刻，而非落盘/加载时刻");
    }

    @Test
    void legacyLineWithoutParseableTimestampFallsBackToNow(@TempDir Path tempDir) throws Exception {
        String key = "ts-legacy";
        Path sessionsDir = tempDir.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Files.write(sessionsDir.resolve(key + ".jsonl"), (
                "{\"_type\":\"metadata\",\"key\":\"ts-legacy\","
                        + "\"created_at\":\"2026-08-26T10:00:00\",\"updated_at\":\"2026-08-26T10:00:00\","
                        + "\"metadata\":{},\"last_consolidated\":0}\n"
                        + "{\"role\":\"user\",\"content\":\"legacy\",\"timestamp\":\"not-a-date\"}\n"
        ).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        SessionManager manager = new SessionManager(tempDir, key);
        Session loaded = manager.getOrCreate(key);

        assertEquals(1, loaded.getMessageCount(), "坏 timestamp 不得导致整条消息解析失败");
        assertEquals("legacy", loaded.getMessages().get(0).getContent());
        assertNotNull(loaded.getMessages().get(0).getTimestamp(),
                "坏 timestamp 应回退到加载时刻（历史行为），而非 null");
    }
}
