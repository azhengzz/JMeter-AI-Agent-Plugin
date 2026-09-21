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
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * persist-early + 中止落盘 + 悬空尾懒收尾的契约测试（openspec 变更
 * persist-user-message-early）。
 *
 * <p>驱经 {@link AgentRunner#run}（同步直调或独立线程配 GatedScriptAiService 挂起钉
 * 子），真实 {@link ContextBuilder}（临时 workspace）+ 真实 {@link SessionManager}
 * （临时目录）+ 真实 {@link MemoryConsolidator}；epoch 供应商接 AtomicLong 支撑
 * 测试内「翻代数」（模拟 /new 重置）。
 */
class AgentRunnerEarlyPersistTest {

    private static final String KEY = "ep-test";
    private static final String NO_RESPONSE =
            "Error: Task interrupted before a response was generated.";
    private static final String TOOL_INTERRUPTED =
            "Error: Task interrupted before this tool finished.";

    /** 记录每次 LLM 请求消息列表的可脚本化 fake（组合 GatedScriptAiService）。 */
    private static final class RecordingScriptAiService implements AiService {
        final GatedScriptAiService delegate = new GatedScriptAiService();
        final List<List<Message>> requests = new CopyOnWriteArrayList<>();

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            requests.add(messages);
            return delegate.generateResponseWithTools(messages, tools, options);
        }
        @Override public String getName() { return "recording-script"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
    }

    /** 在指定回调处执行动作（置 abort / 翻 epoch / 抛异常）并记录事件序。 */
    private static final class InterceptorHook implements AgentHook {
        enum At { BEFORE_EXECUTE_TOOLS, ON_USAGE, BEFORE_ITERATION_THROW }
        final List<String> events = new ArrayList<>();
        private final At at;
        private final Runnable action;
        InterceptorHook(At at, Runnable action) { this.at = at; this.action = action; }

        private void fire(At where) {
            if (where == at) {
                action.run();
            }
        }
        @Override public void beforeIteration(AgentHookContext ctx) {
            events.add("beforeIteration:" + ctx.getCurrentIteration());
            if (at == At.BEFORE_ITERATION_THROW) {
                action.run(); // 抛异常路径：记录后即抛，不追加其他事件
            }
        }
        @Override public void afterIteration(AgentHookContext ctx) {
            events.add("afterIteration:" + ctx.getCurrentIteration());
        }
        @Override public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext ctx) {
            events.add("beforeExecuteTools");
            fire(At.BEFORE_EXECUTE_TOOLS);
        }
        @Override public void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext ctx) {
            events.add("afterExecuteTools");
        }
        @Override public void onError(Throwable error, AgentHookContext ctx) {
            events.add("onError");
        }
        @Override public void onIntermediateResponse(String content, AgentHookContext ctx) {
            events.add("onIntermediateResponse:" + content);
        }
        @Override public void onUsage(Map<String, Integer> usage, AgentHookContext ctx) {
            events.add("onUsage");
            fire(At.ON_USAGE);
        }
        @Override public String finalizeContent(String content, AgentHookContext ctx) {
            events.add("finalizeContent:" + content);
            return content;
        }
    }

    /** 同一临时根上的全套真实构件（sessions/memory 共享，epoch 支撑翻代数）。 */
    private static final class Harness {
        final Path root;
        final SessionManager sessions;
        final RecordingScriptAiService ai = new RecordingScriptAiService();
        final ToolRegistry tools = new ToolRegistry();
        final AgentRunner runner;
        final AtomicLong epoch = new AtomicLong();

        Harness(Path root) throws Exception {
            this.root = root;
            MemoryStore memory = new MemoryStore(root);
            this.sessions = new SessionManager(root, KEY);
            ContextBuilder context = new ContextBuilder(memory, root);
            tools.register(new NoopTool());
            this.runner = new AgentRunner(
                tools, new MemoryConsolidator(memory, ai, sessions, context, tools),
                context, sessions, ai, 40, 16000, 30000);
        }

        AgentRunSpec.Builder spec(String userMessage, AtomicBoolean abort) {
            return AgentRunSpec.builder()
                .sessionKey(KEY)
                .userMessage(userMessage)
                .abortFlag(abort)
                .resetEpochSupplier(epoch::get);
        }

        Session session() { return sessions.getOrCreate(KEY); }
    }

    @TempDir
    Path tempDir;

    private Harness newHarness() throws Exception {
        Path root = Files.createTempDirectory(tempDir, "ws");
        return new Harness(root);
    }

    private static String jsonl(Path root) throws Exception {
        return Files.readString(root.resolve("sessions").resolve(KEY + ".jsonl"));
    }

    private static List<String> roles(Session session) {
        return session.getMessages().stream().map(m -> m.getRole().name()).toList();
    }

    /** 在独立线程上跑 run()，等 gated LLM 调用进入后执行 during，再放行并收拢结果。 */
    private static AgentRunResult runGated(Harness h, AgentRunSpec spec,
            GatedScriptAiService.GatedCall call, Runnable during) throws Exception {
        AtomicReference<AgentRunResult> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            result.set(h.runner.run(spec));
            done.countDown();
        });
        t.start();
        assertTrue(call.entered.await(10, TimeUnit.SECONDS), "LLM 调用未进入");
        during.run();
        call.release.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "回合未收尾");
        t.join(10_000);
        return result.get();
    }

    // ---- 正常完成：触发消息恰好一条、LLM 请求恰一次 ----

    @Test
    void completedTurn_persistsTriggerOnce_noDuplicate() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL"));

        AgentRunResult r = h.runner.run(h.spec("Q1", abort).build());

        assertTrue(r.isSuccess());
        Session session = h.session();
        assertEquals(List.of("USER", "ASSISTANT"), roles(session), "user + final，无重复触发");
        assertEquals("Q1", org.gitee.jmeter.ai.agent.context.ContextBuilder
                .stripRuntimeContext(session.getMessages().get(0)));
        String raw = jsonl(h.root);
        assertTrue(raw.contains("\"_runtime_context\""), "触发消息带 runtime-context 标记");
        assertEquals(1, raw.split("\"role\":\"user\"", -1).length - 1, "jsonl 恰一条 user 行");

        // 钉：LLM 请求中触发文本恰好出现一次（早落盘不产生历史副本）
        List<Message> request = h.ai.requests.get(0);
        long occurrences = request.stream()
                .filter(m -> m.getRole() == Message.Role.USER)
                .filter(m -> m.getContent().contains("Q1"))
                .count();
        assertEquals(1, occurrences);
    }

    // ---- 早落盘的「早」字钉：LLM 循环运行中触发消息已在盘上 ----

    @Test
    void triggerOnDiskWhileLlmCallInFlight() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        GatedScriptAiService.GatedCall call = h.ai.delegate.scriptGated(LLMResponse.text("FINAL"));

        // during 在 LLM 调用挂起期间执行——此刻回合未结束，触发消息必须已在 jsonl
        java.util.concurrent.atomic.AtomicReference<String> fileDuringLlm = new java.util.concurrent.atomic.AtomicReference<>();
        runGated(h, h.spec("Q1", abort).build(), call, () ->
                fileDuringLlm.updateAndGet(v -> {
                    try { return jsonl(h.root); } catch (Exception e) { return "READ-FAIL"; }
                }));

        String during = fileDuringLlm.get();
        assertNotNull(during);
        assertTrue(during.contains("Q1"),
                "LLM 调用进行中触发消息应已早落盘（spec「首次 LLM 调用前消息已在会话文件中」），实际：" + during);
        assertTrue(during.contains("\"_runtime_context\""));
    }

    // ---- 首调中被取消：user + 合成 assistant 收尾 ----

    @Test
    void abortDuringFirstLlm_persistsUserAndSyntheticCloser() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        GatedScriptAiService.GatedCall call = h.ai.delegate.scriptGated(LLMResponse.text("FINAL"));

        AgentRunResult r = runGated(h, h.spec("Q1", abort).build(), call, () -> abort.set(true));

        Session session = h.session();
        assertEquals(List.of("USER", "ASSISTANT"), roles(session));
        Message closer = session.getMessages().get(1);
        assertEquals(NO_RESPONSE, closer.getContent());
        assertEquals(Boolean.TRUE, closer.getMetadata().get(
                org.gitee.jmeter.ai.agent.context.ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
        assertTrue(jsonl(h.root).contains("\"_recovery_interrupted\":true"));
    }

    // ---- 工具结果前被取消：真实 assistant + 每悬空 call 合成 tool 结果 ----

    @Test
    void abortBeforeToolResults_realAssistantPlusSyntheticToolResult() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.withToolCalls(
            List.of(new ToolCall("call-1", "noop_tool", Map.of())), "t1"));
        InterceptorHook hook = new InterceptorHook(
            InterceptorHook.At.BEFORE_EXECUTE_TOOLS, () -> abort.set(true));

        AgentRunResult r = h.runner.run(h.spec("Q1", abort).hook(hook).build());

        Session session = h.session();
        assertEquals(List.of("USER", "ASSISTANT", "TOOL"), roles(session));
        Message assistant = session.getMessages().get(1);
        assertEquals("t1", assistant.getContent());
        assertFalse(assistant.getMetadata().containsKey(
                org.gitee.jmeter.ai.agent.context.ContextBuilder.RECOVERY_INTERRUPTED_META_KEY),
                "真实消息不带标记");
        Message tool = session.getMessages().get(2);
        assertEquals(TOOL_INTERRUPTED, tool.getContent());
        assertEquals("call-1", tool.getToolCallId());
        assertEquals("noop_tool", tool.getToolName());
        assertEquals(Boolean.TRUE, tool.getMetadata().get(
                org.gitee.jmeter.ai.agent.context.ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));
    }

    // ---- 终答已产出但守卫拦截：全真实、无合成 ----

    @Test
    void abortAfterFinalResponse_materializesAllReal_noSynthetics() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL"));
        // onUsage 发生在「LLM 后 abort 检查之后、终答 append 之前」——终答分支无 abort
        // 检查，assistant 照常 append，run() 收尾时中止信号已置位 → 走中止落盘
        InterceptorHook hook = new InterceptorHook(InterceptorHook.At.ON_USAGE, () -> abort.set(true));

        h.runner.run(h.spec("Q1", abort).hook(hook).build());

        Session session = h.session();
        assertEquals(List.of("USER", "ASSISTANT"), roles(session));
        assertEquals("FINAL", session.getMessages().get(1).getContent());
        assertFalse(jsonl(h.root).contains("_recovery_interrupted"), "全真实，无合成消息");
    }

    // ---- 已消费注入后被取消：注入 user 保留 + USER 尾收尾 ----

    @Test
    void injectionConsumedThenAbort_materializesInjectionWithCloser() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.withToolCalls(
            List.of(new ToolCall("call-1", "noop_tool", Map.of())), "t1"));
        GatedScriptAiService.GatedCall call2 = h.ai.delegate.scriptGated(LLMResponse.text("FINAL"));
        java.util.function.Function<Integer, List<org.gitee.jmeter.ai.agent.turn.InjectionItem>> inj =
            limit -> List.of(new org.gitee.jmeter.ai.agent.turn.InjectionItem("inj-a", false));

        AgentRunResult r = runGated(h,
            h.spec("Q1", abort).injectionCallback(inj).build(),
            call2, () -> abort.set(true));

        Session session = h.session();
        assertEquals(List.of("USER", "ASSISTANT", "TOOL", "USER", "ASSISTANT"), roles(session));
        // 已消费注入 user 随中止落盘保留，落盘尾为 USER ⇒ 合成收尾
        String injected = session.getMessages().get(3).getContent();
        assertTrue(injected.startsWith("inj-a"), injected);
        Message closer = session.getMessages().get(4);
        assertEquals(NO_RESPONSE, closer.getContent());
    }

    // ---- 重置（epoch 翻转，含 Stop 后紧接 /new 竞态钉）：不做中止落盘 ----

    @Test
    void resetEpochFlip_noMaterialization() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        GatedScriptAiService.GatedCall call = h.ai.delegate.scriptGated(LLMResponse.text("FINAL"));

        runGated(h, h.spec("Q1", abort).build(), call, () -> {
            abort.set(true);       // Stop（或直接重置取消）
            h.epoch.incrementAndGet(); // /new：markConversationReset 翻代数
        });

        assertEquals(List.of("USER"), roles(h.session()),
                "epoch 已翻：中止落盘放弃，仅剩早落盘触发消息");
        assertFalse(jsonl(h.root).contains("_recovery_interrupted"));
    }

    // ---- 回合起点前预置中止：早落盘与中止落盘均不发生 ----

    @Test
    void presetAbort_nothingPersisted() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        abort.set(true);
        h.ai.delegate.script(LLMResponse.text("FINAL"));

        h.runner.run(h.spec("Q1", abort).build());

        assertEquals(0, h.session().getMessageCount(), "回合未开跑：零痕迹是正确语义");
    }

    // ---- 非持久化回合：无会话文件写 ----

    @Test
    void persistSessionFalse_noSessionWrites() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL"));

        h.runner.run(h.spec("Q1", abort).persistSession(false).build());

        assertFalse(Files.exists(h.root.resolve("sessions").resolve(KEY + ".jsonl")),
                "子代理/临时回合不产生会话文件");
    }

    // ---- 中止落盘结果的 provider 合法性：无孤儿 tool、无悬空 tool_calls ----

    @Test
    void materializedHistoryIsProviderLegal() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.withToolCalls(
            List.of(new ToolCall("call-1", "noop_tool", Map.of())), "t1"));
        InterceptorHook hook = new InterceptorHook(
            InterceptorHook.At.BEFORE_EXECUTE_TOOLS, () -> abort.set(true));
        h.runner.run(h.spec("Q1", abort).hook(hook).build());

        // findLegalStart 不截肢（截肢会吃掉悬空 assistant 的 tool_call），角色序列完整
        List<Message> history = h.session().getHistory(10);
        assertEquals(List.of("USER", "ASSISTANT", "TOOL"),
                history.stream().map(m -> m.getRole().name()).toList());
        assertTrue(history.get(1).hasToolCalls());
        assertEquals("call-1", history.get(2).getToolCallId(), "悬空 call 有配对合成结果");
    }

    // ---- null-content 工具调用 assistant：空串落盘不丢 ----

    @Test
    void nullContentToolCallAssistant_persistsAsEmptyString() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.withToolCalls(
            List.of(new ToolCall("call-1", "noop_tool", Map.of())), null));
        InterceptorHook hook = new InterceptorHook(
            InterceptorHook.At.BEFORE_EXECUTE_TOOLS, () -> abort.set(true));

        h.runner.run(h.spec("Q1", abort).hook(hook).build());

        Session session = h.session();
        assertEquals(List.of("USER", "ASSISTANT", "TOOL"), roles(session),
                "null-content assistant 以空串落盘，不丢——丢弃会让合成 tool 结果成孤儿");
        assertEquals("", session.getMessages().get(1).getContent());
    }

    // ---- 崩溃悬空尾：下回合懒收尾闭合，LLM 请求无连续 user ----

    @Test
    void crashDanglingTail_closedByLazyCloserNextTurn() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort1 = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL1"));
        h.runner.run(h.spec("Q1", abort1).build());
        // 模拟崩溃遗留：直接追加一条无收尾的 user 并落盘（不走中止落盘）
        h.session().addMessage(Message.user("DANGLING"));
        h.sessions.saveSession(h.session());

        // 重启模拟：全新 SessionManager 从盘加载；新回合开始时懒收尾闭合
        Harness h2 = new Harness(h.root);
        AtomicBoolean abort2 = new AtomicBoolean();
        h2.ai.delegate.script(LLMResponse.text("FINAL2"));
        h2.runner.run(h2.spec("Q2", abort2).build());

        Session session = h2.session();
        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT", "USER", "ASSISTANT"),
                roles(session),
                "Q1/FINAL1 + DANGLING/收尾 + Q2/FINAL2——DANGLING 后紧跟合成收尾");
        Message closer = session.getMessages().get(3);
        assertEquals(NO_RESPONSE, closer.getContent());
        assertEquals(Boolean.TRUE, closer.getMetadata().get(
                org.gitee.jmeter.ai.agent.context.ContextBuilder.RECOVERY_INTERRUPTED_META_KEY));

        // 联合钉（历史快照时序 + 懒收尾）：本回合 LLM 请求无相邻两条 user
        List<Message> request = h2.ai.requests.get(0);
        for (int i = 1; i < request.size(); i++) {
            assertFalse(request.get(i - 1).getRole() == Message.Role.USER
                            && request.get(i).getRole() == Message.Role.USER,
                    "LLM 请求出现连续 user：" + request.stream()
                            .map(m -> m.getRole().name()).toList());
        }
    }

    // ---- LLM 错误回合 USER 尾（既有砖化源）：下回合闭合 ----

    @Test
    void llmErrorTurn_userTailClosedNextTurn() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort1 = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.error("boom"));
        AgentRunResult r1 = h.runner.run(h.spec("Q1", abort1).build());
        assertTrue(r1.isSuccess(), "错误回合 success=true（错误串经 finalizeContent 出结果）");
        assertEquals(List.of("USER"), roles(h.session()),
                "错误响应不产生 assistant 行——既有连续 user 砖化源");

        AtomicBoolean abort2 = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL2"));
        h.runner.run(h.spec("Q2", abort2).build());

        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT"), roles(h.session()),
                "Q1 后紧跟懒收尾，Q2 不再与 Q1 相邻");
        assertEquals(NO_RESPONSE, h.session().getMessages().get(1).getContent());
    }

    // ---- drain6 收尾抽干 USER 尾（既有砖化源）：下回合闭合 ----

    @Test
    void drain6UserTail_closedNextTurn() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort1 = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.withToolCalls(
            List.of(new ToolCall("call-1", "noop_tool", Map.of())), "t1"));
        // inj1 空批 → 迭代 1 后落穿；maxIterations=1 → 收尾块 drain6 抽到 late-inj（USER 尾）
        java.util.List<java.util.List<String>> rounds =
                new java.util.concurrent.CopyOnWriteArrayList<>(
                        List.of(List.of(), List.of("late-inj")));
        java.util.concurrent.atomic.AtomicInteger invocations = new java.util.concurrent.atomic.AtomicInteger();
        h.runner.run(h.spec("Q1", abort1)
                .maxIterations(1)
                .injectionCallback(limit -> {
                    int idx = invocations.getAndIncrement();
                    return idx < rounds.size() ? rounds.get(idx).stream()
                            .map(t -> new org.gitee.jmeter.ai.agent.turn.InjectionItem(t, false))
                            .toList() : List.of();
                })
                .build());
        assertEquals(List.of("USER", "ASSISTANT", "TOOL", "USER"), roles(h.session()),
                "maxIterations + drain6：回合正常完成但以注入 user 结尾");

        AtomicBoolean abort2 = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL2"));
        h.runner.run(h.spec("Q2", abort2).build());

        assertEquals(NO_RESPONSE, h.session().getMessages().get(4).getContent(),
                "drain6 USER 尾由懒收尾闭合");
    }

    // ---- 内部异常中止：中止落盘 + hook 零新增发射 ----

    @Test
    void exceptionPath_materializes_hookZeroNewEmissions() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        InterceptorHook hook = new InterceptorHook(InterceptorHook.At.BEFORE_ITERATION_THROW,
                () -> { throw new RuntimeException("hook-boom"); });

        AgentRunResult r = h.runner.run(h.spec("Q1", abort).hook(hook).build());

        assertFalse(r.isSuccess());
        assertEquals("hook-boom", r.getErrorMessage());
        assertEquals("exception", r.getStopReason());
        assertEquals(List.of("beforeIteration:1"), hook.events,
                "hook 零新增发射：抛出点之前的事件序与现状一致，无 onError");
        assertEquals(List.of("USER", "ASSISTANT"), roles(h.session()),
                "早落盘 user + 异常中止落盘的收尾");
        assertEquals(NO_RESPONSE, h.session().getMessages().get(1).getContent());
    }

    // ---- 懒收尾守卫：中止信号已置则跳过收尾 ----

    @Test
    void lazyCloser_presetAbort_skipsClose() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort1 = new AtomicBoolean();
        h.ai.delegate.script(LLMResponse.text("FINAL1"));
        h.runner.run(h.spec("Q1", abort1).build());
        h.session().addMessage(Message.user("DANGLING"));
        h.sessions.saveSession(h.session());
        int before = h.session().getMessageCount();

        AtomicBoolean abort2 = new AtomicBoolean();
        abort2.set(true); // 重置进行中（signalCancel 先置 flag 再 clear）
        h.ai.delegate.script(LLMResponse.text("FINAL2"));
        h.runner.run(h.spec("Q2", abort2).build());

        assertEquals(before, h.session().getMessageCount(),
                "中止信号已置：懒收尾放弃，不追加合成行");
        assertEquals("DANGLING", h.session().getMessages().get(before - 1).getContent());
    }
}
