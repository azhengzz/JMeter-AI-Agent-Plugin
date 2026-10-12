package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.session.GoalRuntime;
import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /goal 命令路由、goal Runtime Context 注入门控、防收工 nudge、跨回合续跑与
 * Stop 语义的 loop 级行为测试。
 */
class GoalLoopTest {

    /** Capturing wrapper: records the messages of every LLM call. */
    private static final class CapturingAiService implements AiService {
        private final GatedScriptAiService delegate = new GatedScriptAiService();
        final AtomicReference<List<Message>> lastMessages = new AtomicReference<>(List.of());
        /** 每次调用的全部 USER 消息内容（跨调用累积，供"某类触发从未发生"断言）。 */
        final List<String> allUserContents = new java.util.concurrent.CopyOnWriteArrayList<>();

        GatedScriptAiService script() {
            return delegate;
        }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools,
                                                      org.gitee.jmeter.ai.agent.model.LlmCallOptions options) {
            lastMessages.set(List.copyOf(messages));
            messages.stream()
                    .filter(m -> m.getRole() == Message.Role.USER && m.getContent() != null)
                    .forEach(m -> allUserContents.add(m.getContent()));
            return delegate.generateResponseWithTools(messages, tools, options);
        }

        @Override public String getName() { return "capturing"; }
        @Override public org.gitee.jmeter.ai.agent.model.GenerationSettings getGenerationSettings() {
            return delegate.getGenerationSettings();
        }
        @Override public void setGenerationSettings(org.gitee.jmeter.ai.agent.model.GenerationSettings s) { }
        @Override public boolean supportsToolCalling() { return true; }
    }

    private static AgentLoop newLoop(AiService ai, Path workspace, ToolRegistry registry) {
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace, "chat:main");
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory,
                new MemoryConsolidator(memory, ai, sessions, context, registry),
                context, sessions, ai);
    }

    private static ToolRegistry noopRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new org.gitee.jmeter.ai.agent.testsupport.NoopTool());
        return registry;
    }

    private static String lastUserContent(CapturingAiService ai) {
        List<Message> messages = ai.lastMessages.get();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == Message.Role.USER) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    /** Any user message of the last LLM call contains the given text. */
    private static boolean anyUserContentContains(CapturingAiService ai, String text) {
        return ai.lastMessages.get().stream()
                .anyMatch(m -> m.getRole() == Message.Role.USER
                        && m.getContent() != null && m.getContent().contains(text));
    }

    // --- /goal command routing ---

    @Test
    void goalWithoutArgsReturnsUsage() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            AgentResponse response = loop.processMessage("/goal", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("Usage: /goal"),
                    "no-arg /goal must return usage: " + response.getContent());
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void goalDuringActiveRunIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        var gated = ai.script().scriptGated(LLMResponse.text("working"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            loop.processMessage("analyze the plan", "chat:main");
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS), "run should be in flight");

            AgentResponse response = loop.processMessage("/goal build the login script", "chat:main")
                    .get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("already running"),
                    "busy /goal must be rejected with a Stop hint: " + response.getContent());

            gated.release.countDown();
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    @Test
    void goalWithArgsFlowsIntoAgentTurnWithContextBlock() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("ack"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            AgentResponse response = loop.processMessage("/goal 为登录接口建脚本", "chat:main")
                    .get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertEquals("ack", response.getContent(), "raw text must reach the LLM turn, not a command result");

            String user = lastUserContent(ai);
            assertTrue(user.contains("/goal 为登录接口建脚本"),
                    "the model must see the original /goal text");
            assertTrue(user.contains("Goal Mode Requested"),
                    "goal-start discipline must be injected into the runtime context");
            assertTrue(user.contains("[Goal Runtime Guidance — host instructions]"),
                    "goal guidance must sit in its own host-instructions wrapper");
        } finally {
            loop.shutdown();
        }
    }

    // --- goal runtime context gating ---

    /** 对齐 Nanobot 的指令/数据分离：guidance 独立 host-instructions 包裹，状态数据独立 metadata 包裹。 */
    @Test
    void buildGoalContextSeparatesGuidanceFromState() {
        GoalState goal = GoalState.create("objective text", null);
        String composite = GoalRuntime.buildGoalContext(false, goal);
        assertTrue(composite.startsWith("[Goal Runtime Guidance — host instructions]"),
                "guidance must carry its own host-instructions wrapper");
        int guidanceEnd = composite.indexOf("[/Goal Runtime Guidance]");
        int objectivePos = composite.indexOf("objective text");
        assertTrue(guidanceEnd >= 0 && objectivePos > guidanceEnd,
                "state must live outside the guidance wrapper");
        assertTrue(composite.endsWith("[/Runtime Context]"),
                "state block must be metadata-wrapped and trailing (strip contract)");

        // /goal 请求态（goal 未建成）：只有 guidance 包裹，无状态块
        String requested = GoalRuntime.buildGoalContext(true, null);
        assertTrue(requested.contains("Goal Mode Requested"));
        assertTrue(requested.endsWith("[/Goal Runtime Guidance]"),
                "request-only shape ends with the guidance wrapper");
    }

    @Test
    void activeGoalInjectsContextBlockForLocalTurnsOnly() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("local answer"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("为登录接口建脚本并验证通过", null));

            loop.processMessage("continue", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(anyUserContentContains(ai, "Active Sustained Goal"),
                    "active goal must inject for local turns");
            assertTrue(anyUserContentContains(ai, "为登录接口建脚本并验证通过"),
                    "objective must be visible");
            // guidance 包裹在前、状态数据在其后的 metadata 包裹里（指令/数据分离）。
            // 判定触发消息本体：本回合纯文本终答会触发 nudge,末条 user 消息是 nudge
            // 合成消息（只含状态行,不含 guidance 包裹）
            String trigger = ai.lastMessages.get().stream()
                    .filter(m -> m.getRole() == Message.Role.USER
                            && m.getContent() != null && m.getContent().startsWith("continue"))
                    .findFirst().orElseThrow().getContent();
            int guidanceEnd = trigger.indexOf("[/Goal Runtime Guidance]");
            assertTrue(guidanceEnd >= 0, "guidance wrapper must be present");
            assertTrue(trigger.indexOf("为登录接口建脚本并验证通过") > guidanceEnd,
                    "objective state must live in the metadata region after the guidance wrapper");

            // delegated turns must NOT carry the host session's goal context（判定本回合
            // 触发消息——历史里前序本地回合的上下文块属既有持久化行为，不在本契约内）
            ai.script().script(LLMResponse.text("delegated answer"));
            loop.processMessage("external task", "chat:main", null, TurnOrigin.IPC_DELEGATED)
                    .get(20, TimeUnit.SECONDS);
            assertFalse(lastUserContent(ai).contains("Active Sustained Goal"),
                    "delegated turns must not carry the goal block");

            // after termination the block is gone
            GoalState goal = GoalState.load(session);
            goal.markTerminal(GoalState.Status.COMPLETED, "done");
            GoalState.store(session, goal);
            ai.script().script(LLMResponse.text("post answer"));
            loop.processMessage("next question", "chat:main").get(20, TimeUnit.SECONDS);
            assertFalse(lastUserContent(ai).contains("Active Sustained Goal"),
                    "terminated goal must not inject");
        } finally {
            loop.shutdown();
        }
    }

    // --- nudge ---

    @Test
    void plainFinalWhileGoalActiveIsNudgedThenReleased() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("first try"));
        ai.script().script(LLMResponse.text("second try"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            AgentResponse response = loop.processMessage("go", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("second try"),
                    "the released final must be the model's second reply");
            assertTrue(response.getContent().contains("[Goal still active"),
                    "the released final must carry the not-terminated note");

            // the nudge message landed in the transcript
            boolean nudged = session.getMessages().stream()
                    .anyMatch(m -> m.getRole() == Message.Role.USER
                            && m.getContent() != null
                            && m.getContent().contains("active sustained goal"));
            assertTrue(nudged, "nudge message must be appended to the conversation");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void smokeBudgetExhaustedSwitchesNudgeToHandback() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("try stop"));
        ai.script().script(LLMResponse.text("handback"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("objective", null);
            goal.recordSmokeAttempt();
            goal.recordSmokeAttempt();
            goal.recordSmokeAttempt();
            GoalState.store(session, goal);

            AgentResponse response = loop.processMessage("go", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(response.getContent().contains("handback"));

            boolean handbackInjected = session.getMessages().stream()
                    .anyMatch(m -> m.getRole() == Message.Role.USER
                            && m.getContent() != null
                            && m.getContent().contains("structured handback"));
            assertTrue(handbackInjected, "budget-exhausted nudge must inject the handback instruction");
        } finally {
            loop.shutdown();
        }
    }

    // --- continuation ---

    @Test
    void iterationExhaustionContinuesGoalInANewTurn() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        Supplier<LLMResponse> alwaysTool = () -> LLMResponse.withToolCalls(
                List.of(new ToolCall("noop", "noop", Map.of())), null);
        ai.script().override = alwaysTool;
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            CompletableFuture<AgentResponse> first =
                    loop.processMessage("go", "chat:main");
            AgentResponse response = first.get(60, TimeUnit.SECONDS);
            assertTrue(response.getContent().contains("Continuing the active goal"),
                    "exhausted goal turn must not surface the max-iterations failure: "
                            + response.getContent());

            // let the continuation slice finish with plain replies (flip off the tool override)
            ai.script().override = null;
            ai.script().script(LLMResponse.text("slice final"));
            ai.script().script(LLMResponse.text("slice final"));

            assertTrue(await(() -> {
                GoalState goal = GoalState.load(loop.getSessionManager().getOrCreate("chat:main"));
                return goal != null && goal.getContinuationRounds() >= 1
                        && !loop.hasActiveRun("chat:main");
            }), "continuation slice should run and complete");

            GoalState goal = GoalState.load(loop.getSessionManager().getOrCreate("chat:main"));
            assertTrue(goal.getContinuationRounds() >= 1, "continuation rounds must be counted");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void continuationYieldsToSuccessorTurnRegisteredDuringTeardown() throws Exception {
        // 收尾窗口竞态钉定：goal 回合耗尽迭代、续跑任务入队后、执行器取出它之前，
        // 用户回合注册进同一会话槽——续跑必须让位，不得 latest-wins 击穿在跑后继的
        // 注册表条目（Stop/注入路由错位）。用另一会话的停放回合把执行器按住，
        // 制造确定性的"槽位空闲 + 续跑仍在队列"窗口
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        // 门控脚本按消费顺序入队：49 noop → goal 末迭代门控 → 占位回合门控
        for (int i = 0; i < 49; i++) {
            ai.script().script(LLMResponse.withToolCalls(
                    List.of(new ToolCall("noop_tool", "noop_tool", Map.of())), null));
        }
        var gatedFinal = ai.script().scriptGated(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", "noop_tool", Map.of())), null));
        var s2Gate = ai.script().scriptGated(LLMResponse.text("placeholder done"));
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            CompletableFuture<AgentResponse> goalTurn = loop.processMessage("go", "chat:main");
            assertTrue(gatedFinal.entered.await(30, TimeUnit.SECONDS),
                    "goal turn should reach the final iteration");

            // 占位回合（另一会话）先入队——其任务排在续跑任务之前；释放 goal 末迭代后
            // 执行器转入占位回合并停在门控上，此刻主会话槽位空闲、续跑任务仍在队列
            loop.processMessage("park", "chat:s2");
            gatedFinal.release.countDown();
            goalTurn.get(30, TimeUnit.SECONDS);
            assertTrue(s2Gate.entered.await(30, TimeUnit.SECONDS),
                    "placeholder turn should park and hold the executor");
            assertTrue(!loop.hasActiveRun("chat:main"),
                    "main slot must be free while the continuation is still queued");

            // 用户回合注册进主会话槽（任务体排在续跑任务之后）
            ai.script().script(LLMResponse.text("user reply"));
            ai.script().script(LLMResponse.text("user reply 2"));
            CompletableFuture<AgentResponse> userTurn = loop.processMessage("user msg", "chat:main");

            // 释放占位：续跑任务取出时发现槽位已被后继接管 → 让位不注册；
            // 用户回合正常跑完（goal 活跃下首个普通终答被 nudge 顶一次，放行第二条）
            s2Gate.release.countDown();
            AgentResponse response = userTurn.get(30, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("user reply 2"),
                    "the user turn must own the slot and finish normally: " + response.getContent());

            boolean continuationRan = ai.allUserContents.stream()
                    .anyMatch(c -> c.startsWith("Continue the active sustained goal"));
            assertFalse(continuationRan, "the continuation slice must yield to the successor turn");
            assertTrue(await(() -> !loop.hasActiveRun("chat:main")
                    && !loop.hasActiveRun("chat:s2")));
        } finally {
            gatedFinal.release.countDown();
            s2Gate.release.countDown();
            loop.shutdown();
        }
    }

    // --- Stop semantics ---

    @Test
    void stopLeavesGoalActiveAndNextMessageContinuesWithoutAutoContinuation() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        var gated = ai.script().scriptGated(LLMResponse.text("interrupted reply"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            CompletableFuture<AgentResponse> running = loop.processMessage("go", "chat:main");
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS));
            loop.signalCancel("chat:main");
            gated.release.countDown();
            try {
                running.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException expected) {
                // signalCancel cancels the future (NewCommandCancelTest precedent)
            }

            GoalState goal = GoalState.load(loop.getSessionManager().getOrCreate("chat:main"));
            assertTrue(goal.isActive(), "Stop must not clear the goal");
            assertEquals(0, goal.getContinuationRounds(),
                    "a stopped turn must not trigger auto-continuation");

            // the next user message continues the goal naturally
            ai.script().script(LLMResponse.text("resumed try"));
            ai.script().script(LLMResponse.text("resumed final"));
            AgentResponse response = loop.processMessage("continue the goal", "chat:main")
                    .get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("resumed final"));
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    @Test
    void stopDuringFinalIterationDoesNotScheduleContinuation() throws Exception {
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        // 49 immediate tool-call responses fill iterations 1..49; the 50th (final)
        // iteration parks in the gated call where the test lands its Stop
        for (int i = 0; i < 49; i++) {
            ai.script().script(LLMResponse.withToolCalls(
                    List.of(new ToolCall("noop", "noop", Map.of())), null));
        }
        var gated = ai.script().scriptGated(LLMResponse.withToolCalls(
                List.of(new ToolCall("noop", "noop", Map.of())), null));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            CompletableFuture<AgentResponse> running = loop.processMessage("go", "chat:main");
            assertTrue(gated.entered.await(30, TimeUnit.SECONDS),
                    "run should reach the final iteration");
            loop.signalCancel("chat:main");
            gated.release.countDown();
            try {
                running.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException expected) {
                // cancelled future per the Stop contract
            }

            assertTrue(await(() -> !loop.hasActiveRun("chat:main")),
                    "no turn should be left running");
            Thread.sleep(300); // any wrongly-queued continuation would surface here
            GoalState goal = GoalState.load(loop.getSessionManager().getOrCreate("chat:main"));
            assertEquals(0, goal.getContinuationRounds(),
                    "a Stop'd run must never be mistaken for iteration exhaustion");
            assertTrue(!loop.hasActiveRun("chat:main"),
                    "no continuation slice may start after Stop");
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    // --- /new during an active goal ---

    @Test
    void goalSurvivesNewAndInjectsIntoTheNextConversation() throws Exception {
        // 真实用户路径：goal 活跃 → /new 命令（resetConversationAny：清消息、落盘、
        // 失效缓存）→ 新会话首条 user 消息必须带 goal 复合块（guidance + 状态）。
        // 复现 2026-10-10 实测缺陷：invalidate 后 getOrCreate 新建空 Session 并覆写
        // 盘文件，goal_state 不可逆销毁
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("resumed answer"));
        ai.script().script(LLMResponse.text("resumed final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("跨会话存续的目标", null));
            loop.getSessionManager().saveSession(session);

            AgentResponse reset = loop.processMessage("/new", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(reset.isSuccess());
            assertTrue(reset.getContent().contains("New session started"));

            AgentResponse response = loop.processMessage("next conversation message", "chat:main")
                    .get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());

            String trigger = ai.lastMessages.get().stream()
                    .filter(m -> m.getRole() == Message.Role.USER
                            && m.getContent() != null
                            && m.getContent().startsWith("next conversation message"))
                    .findFirst().orElseThrow().getContent();
            assertTrue(trigger.contains("[/Goal Runtime Guidance]"),
                    "goal guidance must inject into the new conversation's first message");
            assertTrue(trigger.contains("跨会话存续的目标"),
                    "goal state must survive the session reset");
        } finally {
            loop.shutdown();
        }
    }

    /** 第 50 次迭代调用的终结工具：把 goal 标记 COMPLETED（模拟 update_goal 收尾）。 */
    private static final class CompleteGoalTool implements org.gitee.jmeter.ai.agent.tools.Tool {
        private final AgentLoop loop;
        CompleteGoalTool(AgentLoop loop) { this.loop = loop; }
        @Override public String getName() { return "complete_goal"; }
        @Override public String getDescription() { return "terminate the test goal"; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public org.gitee.jmeter.ai.agent.model.ToolResult execute(Map<String, Object> parameters) {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.load(session);
            goal.markTerminal(GoalState.Status.COMPLETED, "all verified");
            GoalState.store(session, goal);
            loop.getSessionManager().saveSession(session);
            return org.gitee.jmeter.ai.agent.model.ToolResult.success("terminated");
        }
    }

    @Test
    void budgetExhaustedWithEvidenceKeepsOrdinaryNudge() throws Exception {
        // 第 3 次冒烟+静态双 PASS 后预算虽尽但硬标准已达标：防收工注入必须是普通
        // nudge（指向 update_goal complete），不得再灌「still failing」的结构化交回
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("done try"));
        ai.script().script(LLMResponse.text("done final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("objective", null);
            goal.recordSmokeAttempt();
            goal.recordSmokeAttempt();
            goal.recordSmokeAttempt();
            goal.recordStaticPass();
            goal.recordSmokePass();
            GoalState.store(session, goal);

            AgentResponse response = loop.processMessage("go", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(response.getContent().contains("done final"));

            String nudged = session.getMessages().stream()
                    .filter(m -> m.getRole() == Message.Role.USER)
                    .map(Message::getContent)
                    .filter(c -> c != null && c.contains("active sustained goal"))
                    .findFirst().orElse("");
            assertFalse(nudged.isEmpty(), "the ordinary nudge must be injected");
            assertFalse(nudged.contains("structured handback"),
                    "an evidence-complete goal must not get the failing-flavored handback");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void goalCompletedInFinalIterationSurfacesRecap() throws Exception {
        // goal 在末迭代内被终结（update_goal complete）：回合终态必须是 goal 的 recap，
        // 不是通用「达到最大迭代」失败文案
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new org.gitee.jmeter.ai.agent.testsupport.NoopTool());
        AgentLoop loop = newLoop(ai, workspace, registry);
        try {
            registry.register(new CompleteGoalTool(loop));
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            // 49 次 noop 填满迭代 1..49；第 50 次（末迭代）终结 goal → max_iterations 收尾
            for (int i = 0; i < 49; i++) {
                ai.script().script(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", "noop_tool", Map.of())), null));
            }
            ai.script().script(LLMResponse.withToolCalls(
                    List.of(new ToolCall("complete_goal", "complete_goal", Map.of())), null));

            AgentResponse response = loop.processMessage("go", "chat:main").get(60, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("Recap: all verified"),
                    "final-iteration termination must surface the recap: " + response.getContent());
        } finally {
            loop.shutdown();
        }
    }

    /** 回合内建 goal 的工具：模拟 create_goal 在 /goal 请求回合内录制目标。 */
    private static final class CreateGoalInTurnTool implements org.gitee.jmeter.ai.agent.tools.Tool {
        private final AgentLoop loop;
        CreateGoalInTurnTool(AgentLoop loop) { this.loop = loop; }
        @Override public String getName() { return "create_goal_in_turn"; }
        @Override public String getDescription() { return "record the test goal in-turn"; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public org.gitee.jmeter.ai.agent.model.ToolResult execute(Map<String, Object> parameters) {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));
            return org.gitee.jmeter.ai.agent.model.ToolResult.success("created");
        }
    }

    @Test
    void goalCreatedAndCompletedWithinRequestTurnSurfacesRecap() throws Exception {
        // /goal 请求回合内 create+complete 一气呵成且末迭代耗尽：回合起点 goal 尚不
        // 存在（goalActiveAtStart=false），recap 终态仍须生效——不得回落到通用
        // 「达到最大迭代」失败文案
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new org.gitee.jmeter.ai.agent.testsupport.NoopTool());
        AgentLoop loop = newLoop(ai, workspace, registry);
        try {
            registry.register(new CreateGoalInTurnTool(loop));
            registry.register(new CompleteGoalTool(loop));

            // 迭代 1..48 noop，49 建 goal，50（末迭代）终结 goal → max_iterations 收尾
            for (int i = 0; i < 48; i++) {
                ai.script().script(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", "noop_tool", Map.of())), null));
            }
            ai.script().script(LLMResponse.withToolCalls(
                    List.of(new ToolCall("create_goal_in_turn", "create_goal_in_turn", Map.of())), null));
            ai.script().script(LLMResponse.withToolCalls(
                    List.of(new ToolCall("complete_goal", "complete_goal", Map.of())), null));

            AgentResponse response = loop.processMessage("/goal objective", "chat:main")
                    .get(60, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertTrue(response.getContent().contains("Recap: all verified"),
                    "same-turn create+complete must surface the recap: " + response.getContent());
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void continuationTriggerIsMarkedHostSyntheticInSession() throws Exception {
        // 宿主合成标记在消息创建点挂上（而非早落盘一处）：续跑触发消息进入会话后
        // 必带 _host_synthetic——记忆/归档视图据此整条跳过，不作为用户话语呈现
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        Supplier<LLMResponse> alwaysTool = () -> LLMResponse.withToolCalls(
                List.of(new ToolCall("noop_tool", "noop_tool", Map.of())), null);
        ai.script().override = alwaysTool;
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState.store(session, GoalState.create("objective", null));

            loop.processMessage("go", "chat:main").get(60, TimeUnit.SECONDS);

            ai.script().override = null;
            ai.script().script(LLMResponse.text("slice final"));
            ai.script().script(LLMResponse.text("slice final"));

            assertTrue(await(() -> loop.getSessionManager().getOrCreate("chat:main")
                            .getMessages().stream().anyMatch(m ->
                                    m.getRole() == Message.Role.USER
                                            && m.getContent() != null
                                            && m.getContent().contains("Continue the active sustained goal")
                                            && m.getMetadata() != null
                                            && m.getMetadata().containsKey(
                                                    ContextBuilder.HOST_SYNTHETIC_META_KEY))),
                    "the continuation trigger must carry the host-synthetic marker in-session");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void budgetExhaustedWithLatePassingSmokeKeepsOrdinaryNudge() throws Exception {
        // 门禁点仲裁联动：预算耗尽 + 证据缺失，但最近一次（超时的）冒烟已事后完成
        // 且通过——交回的「still failing」口径是误报，回落普通 nudge（complete
        // 门禁会经仲裁补章放行）
        Path workspace = Files.createTempDirectory("goal-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("done try"));
        ai.script().script(LLMResponse.text("done final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("objective", null);
            goal.recordSmokeAttempt();
            goal.recordSmokeAttempt();
            goal.recordSmokeAttempt();
            goal.recordStaticPass();
            GoalState.store(session, goal);

            org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.reset(
                    org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.RunProvenance.AGENT,
                    true);
            org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector collector =
                    new org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector();
            org.apache.jmeter.samplers.SampleResult sample =
                    new org.apache.jmeter.samplers.SampleResult();
            sample.setSampleLabel("smoke-late");
            sample.setSuccessful(true);
            sample.setResponseCode("200");
            collector.testStarted("local");
            collector.sampleOccurred(
                    new org.apache.jmeter.samplers.SampleEvent(sample, "tg"));
            collector.testEnded("local");

            AgentResponse response = loop.processMessage("go", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(response.getContent().contains("done final"));

            String nudged = session.getMessages().stream()
                    .filter(m -> m.getRole() == Message.Role.USER)
                    .map(Message::getContent)
                    .filter(c -> c != null && c.contains("active sustained goal"))
                    .findFirst().orElse("");
            assertFalse(nudged.isEmpty(), "the ordinary nudge must be injected");
            assertFalse(nudged.contains("structured handback"),
                    "a late-passing timed-out smoke must not get the failing-flavored handback");
        } finally {
            org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.reset(
                    org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.RunProvenance.USER);
            loop.shutdown();
        }
    }

    /** Spin until condition is true, max ~10s. */
    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
