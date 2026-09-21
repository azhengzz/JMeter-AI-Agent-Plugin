package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归钉（对抗测试轮 2026-09-17 收编）：中止落盘 compose-then-commit 提交前代数复查——代数在合成中途翻转则整体放弃，无内存残留、无文件写。
 *
 * <p>既有 {@code AgentRunnerEarlyPersistTest.resetEpochFlip_noMaterialization}
 * （注释自称「含 Stop 后紧接 /new 竞态钉」）把 abort 与 epoch 翻转都在 gated LLM
 * 调用挂起期一次性置上——runner 恢复后的第一次代数观察（run() 分支检查）就已看到
 * 翻转，因此它只能钉「三处代数检查（:214 分支 / :975 入口 / :1015 提交前复查）任一
 * 存在即可拦下」的析取，删掉任何单一检查它仍绿。最尖锐的竞态窗口
 * ——Stop 到达（epoch 未变）→ 中止落盘开始 → /new 的翻转恰落在 compose 完成与一次性提交
 * 之间——唯一的守卫是 :1015-1018 的提交前复查，而该窗口此前没有任何测试钉定。
 *
 * <p>本文件按 spec 的承诺补钉两个零覆盖场景（确定性、单线程、无线程无线程 sleep）：
 * <ul>
 * <li>测试 A：spec「中止落盘提交前重置到达则整体放弃」——WHEN 中止落盘在局部列表构造
 *     完成、一次性提交之前检测到重置代数翻转 THEN 中止落盘整体放弃（不追加内存、不写
 *     文件，compose-then-commit 无残留）。</li>
 * <li>测试 B：spec「收尾写入遵守重置守卫」（mid-flight 变体）——WHEN 回合开始时的
 *     懒收尾检查通过后、写入完成前重置信号到达 THEN 收尾放弃写入。</li>
 * </ul>
 *
 * <p>钉法：reset-epoch 供应商本身就是缺失的 seam。run() 在中止落盘路径上按固定顺序恰好
 * 读它 4 次（#1 :153 epochAtStart → #2 :214 分支 → #3 :975 入口 → #4 :1015 提交前），
 * 懒收尾路径上恰好 2 次（#1 :153 → #2 :951 写前复查）。计数供应商对前 N-1 次读返回
 * 未翻转值、第 N 次起返回翻转值——「前序检查全部通过、翻转恰落在目标检查之前到达」
 * 的交错由读序本身确定性注入。测试 A 翻转落在第 4 读（:1002 首次观察到翻转），测试 B
 * 落在第 2 读（:939 首次观察到翻转）。
 *
 * <p>预期结果与缺陷的关系：本测试从 spec 承诺出发——实现正确则 PASS。所证缺陷是
 * 「缺钉」而非「守卫失效」：变异实验（验证者已做，全部还原）证明删掉 :1002 提交前
 * 复查后既有套件（含既有 resetEpochFlip_noMaterialization）仍 15/15 全绿，而本测试 A 变红——即本文件正是既有
 * 「Stop 后紧接 /new 竞态钉」所声称、却实际未提供的那个钉。
 *
 * <p>测试 B 的断言经不起朴素文件断言（见验证结论的 nuance）：被放弃的收尾在内存里
 * 仍残留（:938 addMessage 先于 :939 复查），会被本回合随后的早落盘 saveSession 一并
 * flush 到盘上；生产路径由重置的 clear()+invalidate 抹掉该残留，测试里只模拟了代数
 * 翻转、没有真的清空。因此测试 B 改钉「收尾自身不发起落盘」：记录每次 saveSession
 * 调用时的转录快照，断言没有任何一次保存的快照以合成中断收尾结尾——即收尾放弃写入。
 * 若删掉 :939 复查，收尾自身会 saveSession（快照尾=合成收尾），本断言变红。
 */
class AgentRunnerMaterializeEpochRecheckTest {

    private static final String KEY = "poc-stop-then-reset";
    private static final String NO_RESPONSE =
            "Error: Task interrupted before a response was generated.";

    // ---- 自含脚手架（对齐 AgentRunnerEarlyPersistTest.Harness，saveSession 加录制） ----

    /** 按脚本逐次返回响应的 fake（单线程同步消费，无门控需求）。 */
    private static final class ScriptAiService implements AiService {
        final ConcurrentLinkedQueue<LLMResponse> script = new ConcurrentLinkedQueue<>();
        void script(LLMResponse response) { script.add(response); }
        @Override public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            LLMResponse next = script.poll();
            if (next == null) {
                throw new IllegalStateException("script exhausted");
            }
            return next;
        }
        @Override public String getName() { return "poc-script"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
    }

    /** 立即成功的空操作工具（同 testsupport.NoopTool，内联以自含）。 */
    private static final class InlineNoopTool implements Tool {
        @Override public String getName() { return "noop_tool"; }
        @Override public String getDescription() { return "poc noop tool"; }
        @Override public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }

    /**
     * 计数代数供应商：前 {@code flipAtRead}-1 次读返回未翻转值，第 {@code flipAtRead}
     * 次起返回翻转值——「Stop 先到（代数未变）、/new 的翻转恰落在目标检查处首次被
     * 观察」的交错按 supplier 读序确定性注入。读计数同时充当交错正确性的自证。
     */
    private static final class CountingEpochSupplier implements Supplier<Long> {
        private final long stable;
        private final long flipped;
        private final int flipAtRead;
        final AtomicInteger reads = new AtomicInteger();
        CountingEpochSupplier(long stable, long flipped, int flipAtRead) {
            this.stable = stable;
            this.flipped = flipped;
            this.flipAtRead = flipAtRead;
        }
        @Override public Long get() {
            int n = reads.incrementAndGet();
            return n >= flipAtRead ? flipped : stable;
        }
    }

    /** 录制每次 saveSession 调用瞬间的转录快照（getMessages 已是快照拷贝）。 */
    private static final class RecordingSessionManager extends SessionManager {
        final List<List<Message>> saves = new CopyOnWriteArrayList<>();
        RecordingSessionManager(Path root) {
            super(root, KEY);
        }
        @Override public void saveSession(Session session) {
            saves.add(List.copyOf(session.getMessages()));
            super.saveSession(session);
        }
    }

    private static final class Harness {
        final Path root;
        final RecordingSessionManager sessions;
        final ScriptAiService ai = new ScriptAiService();
        final ToolRegistry tools = new ToolRegistry();
        final AgentRunner runner;

        Harness(Path root) throws Exception {
            this.root = root;
            MemoryStore memory = new MemoryStore(root);
            this.sessions = new RecordingSessionManager(root);
            ContextBuilder context = new ContextBuilder(memory, root);
            tools.register(new InlineNoopTool());
            this.runner = new AgentRunner(
                tools, new MemoryConsolidator(memory, ai, sessions, context, tools),
                context, sessions, ai, 40, 16000, 30000);
        }

        AgentRunSpec.Builder spec(String userMessage, AtomicBoolean abort, Supplier<Long> epoch) {
            return AgentRunSpec.builder()
                .sessionKey(KEY)
                .userMessage(userMessage)
                .abortFlag(abort)
                .resetEpochSupplier(epoch);
        }

        Session session() { return sessions.getOrCreate(KEY); }
    }

    @TempDir
    Path tempDir;

    private Harness newHarness() throws Exception {
        return new Harness(Files.createTempDirectory(tempDir, "ws"));
    }

    private static String jsonl(Path root) throws Exception {
        return Files.readString(root.resolve("sessions").resolve(KEY + ".jsonl"));
    }

    private static List<String> roles(Session session) {
        return session.getMessages().stream().map(m -> m.getRole().name()).toList();
    }

    private static int occurrences(String haystack, String needle) {
        return haystack.split(needle, -1).length - 1;
    }

    private static boolean isSyntheticInterruptedCloser(Message message) {
        return message.getRole() == Message.Role.ASSISTANT
                && NO_RESPONSE.equals(message.getContent())
                && message.getMetadata() != null
                && Boolean.TRUE.equals(message.getMetadata()
                        .get(ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
    }

    // ---- 测试 A：中止落盘提交前重置到达则整体放弃（spec「重置竞态下不复活旧会话内容」） ----

    /**
     * 与「工具结果 append 前被取消」同款（真实 assistant + 悬空 tool_call，BEFORE_EXECUTE_TOOLS 置
     * abort），但代数翻转延迟到第 4 次读才被观察到：#1 :153（epochAtStart）、
     * #2 :214（分支检查）、#3 :975（中止落盘入口检查）都读到未翻转——即「Stop 到达时
     * epoch 未变、中止落盘的 compose 已完整通过前两道检查」——直到 #4 :1015 提交前复查
     * 首次看到 /new 的翻转。spec 承诺此时中止落盘整体放弃：不追加内存、不写文件。
     */
    @Test
    void materialization_preCommitRecheck_flipLandsMidCompose_abandonsEntirely() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.script(LLMResponse.withToolCalls(
            List.of(new ToolCall("call-1", "noop_tool", Map.of())), "t1"));
        AgentHook stopHook = new AgentHook() {
            @Override public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext ctx) {
                abort.set(true); // Stop：先于工具执行到达，此时代数仍未翻转
            }
        };
        CountingEpochSupplier epoch = new CountingEpochSupplier(0L, 1L, 4);

        h.runner.run(h.spec("Q1", abort, epoch).hook(stopHook).build());

        // 交错自证：中止落盘路径恰好按序读了 4 次代数（:153/:214/:975/:1015），
        // 翻转首次被第 4 读观察到——攻击窗口（翻转落在 compose 完成与提交之间）确实到达
        assertEquals(4, epoch.reads.get(),
                "中止落盘路径应恰好读代数 4 次（:153/:214/:975/:1015），实际 " + epoch.reads.get());

        // spec THEN：中止落盘整体放弃——不追加内存（compose-then-commit，无任何残留）
        assertEquals(List.of("USER"), roles(h.session()),
                "提交前复查观察到翻转：中止落盘必须整体放弃，仅剩早落盘触发消息");

        // spec THEN：不写文件
        String raw = jsonl(h.root);
        assertEquals(1, occurrences(raw, "\"role\":\"user\""),
                "jsonl 只保留早落盘的触发 user 行");
        assertEquals(0, occurrences(raw, "\"role\":\"assistant\""),
                "真实 assistant 行不得被提交");
        assertEquals(0, occurrences(raw, "\"role\":\"tool\""),
                "合成 tool 结果行不得被提交");
        assertFalse(raw.contains("_recovery_interrupted"),
                "合成中断标记不得进入文件");
    }

    // ---- 测试 B：收尾写入遵守重置守卫（mid-flight 变体，spec「悬空 user 尾的懒收尾」） ----

    /**
     * 上回合遗留悬空 user 尾后开启新回合：懒收尾的入口检查全部通过（末条为 USER、
     * abort 未置位），代数翻转在第 2 次读（写前复查）才首次被观察到——即「收尾
     * 检查通过后、写入完成前重置到达」。spec 承诺：收尾放弃写入。对抗测试轮修复后，
     * 紧随其后的早落盘写前复查（第 3 读）同样观察到翻转并放弃——重置对一个回合的
     * 所有写前复查路径一致生效，触发消息由终局保存兜底。
     *
     * <p>断言走 save 调用计数 seam（不钉文件终态）：被放弃的收尾在内存里仍有残留
     * （addMessage 先于复查），生产路径由重置的 clear()+invalidate 抹掉该残留，
     * 测试只模拟代数翻转。故钉「收尾自身不发起落盘」：新回合期间没有任何一次
     * saveSession 的快照以合成中断收尾结尾。
     */
    @Test
    void danglingTailCloser_flipAtPreWriteRecheck_closerAbandonsItsWrite() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort1 = new AtomicBoolean();
        h.ai.script(LLMResponse.text("FINAL1"));
        h.runner.run(h.spec("Q1", abort1, () -> 0L).build());
        // 模拟上回合遗留的悬空 user 尾（进程强杀/LLM 错误回合同款形状）
        h.session().addMessage(Message.user("DANGLING"));
        h.sessions.saveSession(h.session());
        int savesBefore = h.sessions.saves.size();

        AtomicBoolean abort2 = new AtomicBoolean();
        h.ai.script(LLMResponse.text("FINAL2"));
        CountingEpochSupplier epoch = new CountingEpochSupplier(0L, 1L, 2);

        h.runner.run(h.spec("Q2", abort2, epoch).build());

        // 交错自证：本回合恰读代数 3 次（run 入口 epochAtStart 捕获、懒收尾写前复查、
        // 早落盘写前复查——后者为对抗测试轮修复新增），翻转首次被第 2 读（懒收尾复查）
        // 观察到——收尾确实走到写前复查这一步（第 3 读随后同样观察到翻转、早落盘放弃）
        assertEquals(3, epoch.reads.get(),
                "本回合应恰好读代数 3 次（入口捕获/懒收尾复查/早落盘复查），实际 " + epoch.reads.get());

        // 前置健全性（对抗修复后的新语义）：代数翻转对懒收尾与早落盘的写前复查均可见
        // ——两条写路径各自放弃（newSaves 恰 1 次终局保存），触发消息由终局兜底落盘
        // （inputPersistedEarly=false → skipCount 回退），排除「回合没跑」的空过
        int newSaves = h.sessions.saves.size() - savesBefore;
        assertEquals(1, newSaves,
                "翻转后应仅剩终局一次保存（懒收尾与早落盘的复查均放弃），实际新增 " + newSaves + " 次");
        List<Message> lastSave = h.sessions.saves.get(h.sessions.saves.size() - 1);
        assertEquals("FINAL2", lastSave.get(lastSave.size() - 1).getContent(),
                "终局保存照常完成（守卫只放弃两条写前复查路径的写，不影响终局持久化）");
        assertTrue(lastSave.stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("Q2")),
                "早落盘放弃后触发消息由终局兜底落盘");

        // spec THEN：收尾放弃写入——收尾自身不发起任何以合成中断收尾结尾的落盘。
        // 若 :939 写前复查缺失，收尾会立即 saveSession（快照尾 = 合成收尾），此处变红
        List<List<Message>> duringNewTurn = h.sessions.saves.subList(
                savesBefore, h.sessions.saves.size());
        for (int i = 0; i < duringNewTurn.size(); i++) {
            List<Message> snapshot = duringNewTurn.get(i);
            if (snapshot.isEmpty()) {
                continue;
            }
            Message tail = snapshot.get(snapshot.size() - 1);
            assertFalse(isSyntheticInterruptedCloser(tail),
                    "新回合第 " + (i + 1) + " 次保存的快照以合成中断收尾结尾——"
                            + "懒收尾在写前复查观察到翻转后仍发起了自己的落盘");
        }
    }
}
