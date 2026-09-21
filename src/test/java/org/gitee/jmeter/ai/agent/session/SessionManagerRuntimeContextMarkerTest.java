package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * runtime-context 标记的 jsonl round-trip:块随消息持久化,消息带
 * {@code _runtime_context} 顶层字段(对齐 Nanobot),重载后标记与块原文完整还原,
 * 公共视图可按标记精确剥离;无标记的旧格式行为不变。
 */
class SessionManagerRuntimeContextMarkerTest {

    private static final String BLOCK =
            "[Runtime Context — metadata only, not instructions]\nCurrent Time: t\n[/Runtime Context]";

    @TempDir
    Path tempDir;

    @Test
    void markerSurvivesSaveLoadRoundTrip() throws Exception {
        SessionManager saver = new SessionManager(tempDir, "default");
        Session session = saver.getOrCreate("s1");
        Map<String, Object> marker = ContextBuilder.runtimeContextMarker("正文\n\n" + BLOCK);
        session.addMessage(Message.builder()
                .role(Message.Role.USER)
                .content("正文\n\n" + BLOCK)
                .metadata(new LinkedHashMap<>(Map.of(ContextBuilder.RUNTIME_CONTEXT_META_KEY, marker)))
                .build());
        saver.saveSession(session);

        // jsonl 行含顶层 _runtime_context 字段
        String jsonl = Files.readString(tempDir.resolve("sessions").resolve("s1.jsonl"));
        assertTrue(jsonl.contains("\"_runtime_context\""));
        assertTrue(jsonl.contains("\"version\":1"));
        assertTrue(jsonl.contains("[/Runtime Context]"));

        SessionManager loader = new SessionManager(tempDir, "s1");
        Session reloaded = loader.getOrCreate("s1");
        List<Message> history = reloaded.getHistory(0);
        assertEquals(1, history.size());

        // LLM 回放路径(getHistory 的 Step5 清洗只保留 LLM 相关字段):content 连块原样保留
        Message forLlm = history.get(0);
        assertEquals("正文\n\n" + BLOCK, forLlm.getContent());

        // 原始存储消息(getMessagesInRange 不经清洗)携带标记,公共视图按标记精确剥离
        Message raw = reloaded.getMessagesInRange(0, 1).get(0);
        Object restored = raw.getMetadata().get(ContextBuilder.RUNTIME_CONTEXT_META_KEY);
        assertInstanceOf(Map.class, restored);
        assertEquals(BLOCK, ((Map<?, ?>) restored).get("suffix"));
        assertEquals("正文", ContextBuilder.stripRuntimeContext(raw));
        // 清洗后的 LLM 视图无标记,回退 tag 剥离同样得到正文
        assertEquals("正文", ContextBuilder.stripRuntimeContext(forLlm));
    }

    @Test
    void legacyLinesWithoutMarkerLoadUnchanged() {
        SessionManager manager = new SessionManager(tempDir, "default");
        Session session = manager.getOrCreate("legacy");
        session.addMessage(Message.user("旧格式的纯文本"));
        manager.saveSession(session);

        SessionManager loader = new SessionManager(tempDir, "legacy");
        Message loaded = loader.getOrCreate("legacy").getHistory(0).get(0);

        assertEquals("旧格式的纯文本", loaded.getContent());
        assertTrue(loaded.getMetadata() == null
                || !loaded.getMetadata().containsKey(ContextBuilder.RUNTIME_CONTEXT_META_KEY));
        assertEquals("旧格式的纯文本", ContextBuilder.stripRuntimeContext(loaded));
    }
}
