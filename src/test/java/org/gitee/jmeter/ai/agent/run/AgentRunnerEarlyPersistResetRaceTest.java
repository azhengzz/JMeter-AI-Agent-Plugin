package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归钉（对抗测试轮 2026-09-17 收编，实证缺陷已修）：早落盘写前复查——重置在入口检查到落盘的跨度内完整落地时，提交前复查放弃文件写，旧会话触发消息不复活进刚清空的新会话文件。
 *
 * <p>openspec 变更 persist-user-message-early 的 spec「重置竞态下不复活旧会话内容」承诺：
 * 「提前落盘与中止落盘 SHALL 在写入前检查取消状态：会话重置先于提前落盘发生时
 * MUST NOT 把旧会话的触发消息写入刚清空的新会话文件」；其场景「提前落盘先于重置则随
 * 重置清空」钉「会话文件被清空，该消息不再存在于当前会话」。同一变更给两条姊妹写路径
 * 都加了写前复查（closeDanglingUserTail 的提交前代数复查、materializeInterruptedTurn
 * 的 pre-commit 复查），而 persistUserMessageEarly（AgentRunner.java:899-917）只有
 * 入口一次 abort 检查（:902），[检查 → addMessage(:909) → saveSession(:910)] 跨度
 * 无任何复查——重置若在该跨度内完整落地，回合计载线程恢复后照常落盘，把重置前的触发
 * 消息复活写进刚清空的新会话文件（last-writer-wins 覆盖重置线程刚写的空文件）。
 *
 * <p>钉法（确定性、单线程、无 latch）：把重置序列注入 addMessage(:909) 这个 seam——
 * 它严格晚于 :902 的唯一一次检查、严格早于 :910 的落盘。seam 内按
 * AgentLoop.resetConversation（AgentLoop.java:1117-1127）的原序完整执行：置 abort
 * flag（signalCancel 先于清空）→ 代数 +1 → clear() → 真 saveSession 写出仅
 * metadata 的空文件 → invalidate 缓存；随后委托 super.addMessage(触发消息)。回合
 * 载体线程继续走到 :910 落盘——若实现缺写前复查，此时文件被覆写为
 * [metadata, 触发消息]，断言（spec 承诺：文件不得含 user 行）失败即证缺陷。
 *
 * <p>若实现补上写前复查（对齐 :939 的姊妹纪律），:910 跳过，文件保持重置写出的
 * 仅 metadata 状态，本测试通过。
 */
class AgentRunnerEarlyPersistResetRaceTest {

    private static final String KEY = "poc-earlypersist";
    private static final String TRIGGER = "RESURRECT-ME-pre-reset-trigger";

    /**
     * 武装 Session：首次 addMessage（即早落盘对触发消息的追加，AgentRunner.java:909）
     * 时，在委托 super 之前完整执行 /new 重置序列。此后退化为普通 Session。
     */
    private static final class ResetOnEarlyAddSession extends Session {
        private final SessionManager manager;
        private final Path root;
        private final AtomicBoolean abort;
        private final AtomicLong epoch;
        final AtomicBoolean resetFired = new AtomicBoolean();
        /** 重置自身落盘完成瞬间的文件内容（证明空文件先于回合载体线程的写）。 */
        volatile String fileRightAfterResetWrite;

        ResetOnEarlyAddSession(SessionManager manager, Path root,
                AtomicBoolean abort, AtomicLong epoch) {
            super(KEY);
            this.manager = manager;
            this.root = root;
            this.abort = abort;
            this.epoch = epoch;
        }

        @Override
        public synchronized void addMessage(Message message) {
            if (resetFired.compareAndSet(false, true)) {
                // AgentLoop.resetConversation 同序（AgentLoop.java:1117-1127）：
                // 先取消（signalCancel 必先置共享 abort flag）→ 栅栏下代数 +1 →
                // clear() → 落盘空文件 → 失效缓存条目
                abort.set(true);
                epoch.incrementAndGet();
                clear();
                manager.saveSession(this);
                fileRightAfterResetWrite = readJsonl(root);
                manager.invalidate(getKey());
            }
            // 回合载体线程「恢复执行」：触发消息进入已被重置清空的 session 对象；
            // 随后 persistUserMessageEarly 的 :910 saveSession 是否复查放弃，即被测点
            super.addMessage(message);
        }
    }

    /** getOrCreate 返回武装 Session（saveSession/invalidate 等均用真实实现）。 */
    private static final class ArmedSessionManager extends SessionManager {
        volatile Session armed;

        ArmedSessionManager(Path root) {
            super(root, KEY);
        }

        @Override
        public Session getOrCreate(String sessionKey) {
            return armed;
        }
    }

    @TempDir
    Path tempDir;

    private static String readJsonl(Path root) {
        try {
            return Files.readString(root.resolve("sessions").resolve(KEY + ".jsonl"));
        } catch (Exception e) {
            throw new IllegalStateException("读会话文件失败", e);
        }
    }

    private static int userRowCount(String jsonl) {
        return jsonl.split("\"role\":\"user\"", -1).length - 1;
    }

    /**
     * spec 承诺（重置竞态下不复活旧会话内容）：会话重置先于提前落盘的文件写发生时，
     * 旧会话的触发消息 MUST NOT 进入刚清空的新会话文件——重置后 jsonl 仅剩 metadata
     * 行，且下一进程启动加载到空会话。
     */
    @Test
    void resetCompletesInsideEarlyPersistSpan_justClearedSessionFileStaysClean() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "ws");
        AtomicBoolean abort = new AtomicBoolean();
        AtomicLong epoch = new AtomicLong();

        ArmedSessionManager sessions = new ArmedSessionManager(root);
        ResetOnEarlyAddSession session = new ResetOnEarlyAddSession(sessions, root, abort, epoch);
        sessions.armed = session;

        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.text("FINAL")); // 防御脚本：abort 先到则 LLM 调用不会发生
        MemoryStore memory = new MemoryStore(root);
        ContextBuilder context = new ContextBuilder(memory, root);
        ToolRegistry tools = new ToolRegistry();
        tools.register(new NoopTool());
        AgentRunner runner = new AgentRunner(
                tools, new MemoryConsolidator(memory, ai, sessions, context, tools),
                context, sessions, ai, 40, 16000, 30000);

        // 用户发消息 → 回合载体线程进入早落盘路径；重置（/new）在
        // [写前检查 → 落盘] 跨度内完整执行完毕
        runner.run(AgentRunSpec.builder()
                .sessionKey(KEY)
                .userMessage(TRIGGER)
                .abortFlag(abort)
                .resetEpochSupplier(epoch::get)
                .build());

        // ---- 钉子有效性前提：重置确实在早落盘跨度内完整落地 ----
        assertTrue(session.resetFired.get(), "早落盘的 addMessage 未被触达——钉子失效");
        assertTrue(abort.get(), "重置序列未置 abort flag");
        assertEquals(1L, epoch.get(), "重置序列未翻代数");
        String afterReset = session.fileRightAfterResetWrite;
        assertNotNull(afterReset, "重置自写空文件后未捕获文件内容");
        assertEquals(0, userRowCount(afterReset),
                "重置自身落盘应写出仅 metadata 的空文件，实际：" + afterReset);
        assertFalse(afterReset.contains(TRIGGER), "重置写出的空文件不应含触发消息");

        // ---- spec 承诺：重置后的新会话文件不得复活旧回合的触发消息 ----
        String file = readJsonl(root);
        assertEquals(0, userRowCount(file),
                "会话重置已在早落盘写文件之前完整完成（abort 置位、代数翻转、空文件已落盘），"
                        + "但回合收尾后新会话文件出现 user 行——旧会话触发消息复活"
                        + "（违反 spec「重置竞态下不复活旧会话内容」）。文件：\n" + file);
        assertFalse(file.contains(TRIGGER), "复活内容（触发消息正文）仍在文件中：\n" + file);

        // ---- 持久化后果：下一进程启动（全新 SessionManager 从盘加载）读到空会话 ----
        assertEquals(0, new SessionManager(root, KEY).getOrCreate(KEY).getMessageCount(),
                "复活行跨进程存活——重启后加载到非空会话");
    }
}
