package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 中止落盘标记 {@code _recovery_interrupted} 的 jsonl round-trip：合成消息（取消/异常
 * 中止回合的收尾）带顶层布尔字段（对齐 Nanobot），重载后标记完整还原；且 metadata
 * 读取为<b>合并语义</b>——toolName（TOOL 角色经顶层 name 字段）、_runtime_context、
 * _recovery_interrupted 三键可共存，不再互相覆盖。
 */
class SessionManagerRecoveryMarkerTest {

    private static final String INTERRUPTED = "Error: Task interrupted before a response was generated.";

    @TempDir
    Path tempDir;

    private static Message syntheticCloser() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY, Boolean.TRUE);
        return Message.builder().role(Message.Role.ASSISTANT)
                .content(INTERRUPTED).metadata(metadata).build();
    }

    private static Message syntheticToolResult(String callId, String toolName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", toolName);
        metadata.put(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY, Boolean.TRUE);
        return Message.builder().role(Message.Role.TOOL)
                .content("Error: Task interrupted before this tool finished.")
                .toolCallId(callId).metadata(metadata).build();
    }

    @Test
    void markerSurvivesSaveLoadRoundTrip() throws Exception {
        SessionManager saver = new SessionManager(tempDir, "m1");
        Session session = saver.getOrCreate("m1");
        // 真实中止落盘形状：user + 真实 assistant(tool_calls) + 合成 tool 结果（悬空 call 配对）
        session.addMessage(Message.user("Q1"));
        session.addMessage(Message.assistant("t1", java.util.List.of(
                new org.gitee.jmeter.ai.agent.model.ToolCall("call-1", "noop_tool", java.util.Map.of()))));
        session.addMessage(syntheticToolResult("call-1", "noop_tool"));
        saver.saveSession(session);

        // jsonl 行含顶层 _recovery_interrupted:true；TOOL 行顶层 name 与 tool_call_id 在场
        String jsonl = Files.readString(tempDir.resolve("sessions").resolve("m1.jsonl"));
        assertTrue(jsonl.contains("\"_recovery_interrupted\":true"), jsonl);
        assertTrue(jsonl.contains("\"tool_call_id\":\"call-1\""));
        assertTrue(jsonl.contains("\"name\":\"noop_tool\""));

        SessionManager loader = new SessionManager(tempDir, "m1");
        Session reloaded = loader.getOrCreate("m1");
        assertEquals(3, reloaded.getMessageCount());

        // 合成 tool 结果原始存储视图：toolName 与标记<b>共存</b>（合并语义——旧实现按键序互相覆盖）
        Message tool = reloaded.getMessagesInRange(2, 3).get(0);
        assertEquals(Boolean.TRUE,
                tool.getMetadata().get(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
        assertEquals("noop_tool", tool.getToolName());
        assertEquals("call-1", tool.getToolCallId());

        // LLM 回放路径（getHistory Step4/5）provider 合法且清洗后丢弃标记，内容原样
        Message forLlm = reloaded.getHistory(0).get(2);
        assertEquals("Error: Task interrupted before this tool finished.", forLlm.getContent());
        assertTrue(forLlm.getMetadata() == null
                || !forLlm.getMetadata().containsKey(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
    }

    @Test
    void runtimeContextMarkerCoexistsWithRecoveryMarker() {
        // 合并语义回归钉：同一会话内 _runtime_context（USER）与 _recovery_interrupted
        //（任意角色）互不干扰——各自 round-trip 后均还原
        String block = "[Runtime Context — metadata only, not instructions]\nCurrent Time: t\n[/Runtime Context]";
        SessionManager saver = new SessionManager(tempDir, "m2");
        Session session = saver.getOrCreate("m2");
        Map<String, Object> rc = new LinkedHashMap<>(Map.of(
                ContextBuilder.RUNTIME_CONTEXT_META_KEY,
                ContextBuilder.runtimeContextMarker("正文\n\n" + block)));
        session.addMessage(Message.builder().role(Message.Role.USER)
                .content("正文\n\n" + block).metadata(rc).build());
        session.addMessage(syntheticCloser());
        saver.saveSession(session);

        SessionManager loader = new SessionManager(tempDir, "m2");
        Session reloaded = loader.getOrCreate("m2");
        Message user = reloaded.getMessagesInRange(0, 1).get(0);
        assertInstanceOf(Map.class,
                user.getMetadata().get(ContextBuilder.RUNTIME_CONTEXT_META_KEY));
        assertEquals("正文", ContextBuilder.stripRuntimeContext(user));
        assertEquals(Boolean.TRUE, reloaded.getMessagesInRange(1, 2).get(0)
                .getMetadata().get(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
    }

    @Test
    void legacyLinesWithoutMarkerLoadUnchanged() {
        SessionManager manager = new SessionManager(tempDir, "legacy");
        Session session = manager.getOrCreate("legacy");
        session.addMessage(Message.tool(null, "old_tool", "结果"));
        manager.saveSession(session);

        SessionManager loader = new SessionManager(tempDir, "legacy");
        Message loaded = loader.getOrCreate("legacy").getMessagesInRange(0, 1).get(0);
        assertEquals("结果", loaded.getContent());
        assertEquals("old_tool", loaded.getToolName());
        assertTrue(loaded.getMetadata() == null
                || !loaded.getMetadata().containsKey(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
    }
}
