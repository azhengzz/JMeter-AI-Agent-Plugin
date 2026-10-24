package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 暂停/恢复引擎契约：Stop 打断的 auto-converge（USER_STOP 收敛 PAUSED、
 * SILENT 不收敛）、⏸ 入口（空闲直写/忙期取消+收敛幂等）、▶ 入口（PAUSED→ACTIVE +
 * 续跑片投放、预算续算、非 PAUSED no-op）、PAUSED 的注入隔离与跨 /new 存活。
 */
class AgentLoopGoalPauseTest {

    /** Capturing wrapper: records the user-content of every LLM call. */
    private static final class CapturingAiService implements AiService {
        private final GatedScriptAiService delegate = new GatedScriptAiService();
        final AtomicReference<List<Message>> lastMessages = new AtomicReference<>(List.of());
        final List<String> allUserContents = new CopyOnWriteArrayList<>();

        GatedScriptAiService script() {
            return delegate;
        }

        @Override
        public LLMResponse generateResponseWithTools(List<Message> messages,
                List<org.gitee.jmeter.ai.agent.model.ToolDefinition> tools,
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

    private static GoalState storeActiveGoal(AgentLoop loop) {
        var session = loop.getSessionManager().getOrCreate("chat:main");
        GoalState goal = GoalState.create("pause test objective", null);
        GoalState.store(session, goal);
        return goal;
    }

    private static GoalState loadGoal(AgentLoop loop) {
        return GoalState.load(loop.getSessionManager().getOrCreate("chat:main"));
    }

    // --- auto-converge：用户 Stop 打断 goal 回合 ---

    @Test
    void stopAutoConvergesActiveGoalToPaused() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        var gated = ai.script().scriptGated(LLMResponse.text("interrupted"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            CompletableFuture<AgentResponse> running = loop.processMessage("go", "chat:main");
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS), "turn should be in flight");
            loop.signalCancel("chat:main"); // Stop 按钮同款（USER_STOP）
            gated.release.countDown();
            try {
                running.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException expected) {
                // Stop contract
            }

            assertTrue(await(() -> loadGoal(loop) != null
                            && loadGoal(loop).getStatus() == GoalState.Status.PAUSED
                            && !loop.hasActiveRun("chat:main")),
                    "user-stop must auto-converge the active goal to PAUSED");
            GoalState goal = loadGoal(loop);
            assertFalse(goal.isActive(), "PAUSED is not isActive (injection/continuation gates)");
            assertNullRecap(goal);
            assertEquals(0, goal.getContinuationRounds(), "a stopped turn schedules no continuation");
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    @Test
    void silentCancelKeepsGoalActive() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        var gated = ai.script().scriptGated(LLMResponse.text("interrupted"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            CompletableFuture<AgentResponse> running = loop.processMessage("go", "chat:main");
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS));
            loop.signalCancel("chat:main", CancelCause.SILENT); // 关闭整合取消：不收敛
            gated.release.countDown();
            try {
                running.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException expected) {
                // cancelled future
            }

            assertTrue(await(() -> !loop.hasActiveRun("chat:main")));
            Thread.sleep(200); // any wrongly-scheduled convergence would land here
            assertTrue(loadGoal(loop).isActive(),
                    "silent cancel must NOT converge the goal (close-window ≠ pause)");
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    // --- ⏸ 入口 ---

    @Test
    void pauseIdleGoalWritesPausedWithoutStartingATurn() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            loop.pauseActiveGoal("chat:main");

            assertTrue(await(() -> loadGoal(loop) != null
                            && loadGoal(loop).getStatus() == GoalState.Status.PAUSED),
                    "idle pause must write PAUSED");
            Thread.sleep(200);
            assertTrue(ai.allUserContents.isEmpty(),
                    "idle pause must not start any turn (no LLM call, no synthetic message)");
            assertFalse(loop.hasActiveRun("chat:main"));
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void pauseConvergesImmediatelyWhileTurnHangsInLLMCall() throws Exception {
        // ⏸ 的 PAUSED 收敛不得排队到垂死回合之后（单线程执行器上它排在回合任务体
        // 后面）——中止是协作式的：在跑 LLM 调用/工具批（冒烟运行）须跑到自然结束
        // 才轮到收敛落盘，用户「点了没反应」可达分钟级。收敛须在回合仍挂起期间即
        // 写盘；被停回合终态的自动收敛仍为第二幂等来源
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        var gated = ai.script().scriptGated(LLMResponse.text("hanging"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            CompletableFuture<AgentResponse> running = loop.processMessage("go", "chat:main");
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS), "回合已进入 LLM 调用并挂起");

            loop.pauseActiveGoal("chat:main");

            assertTrue(await(() -> loadGoal(loop) != null
                            && loadGoal(loop).getStatus() == GoalState.Status.PAUSED),
                    "⏸ 须在回合仍挂起于 LLM 调用时即收敛 PAUSED（不等回合收尾）");
            assertEquals(1, gated.release.getCount(),
                    "印证：PAUSED 落盘时回合仍挂起在 LLM 调用（门闩未放行；"
                            + "hasActiveRun 不可作探针——signalCancel 摘槽即 false）");

            gated.release.countDown();
            try {
                running.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException expected) {
                // USER_STOP 取消契约
            }
            assertTrue(await(() -> !loop.hasActiveRun("chat:main")),
                    "垂死回合照常经取消链收尾");
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    @Test
    void pauseBusyGoalCancelsTurnAndConverges() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        var gated = ai.script().scriptGated(LLMResponse.text("working"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            CompletableFuture<AgentResponse> running = loop.processMessage("go", "chat:main");
            assertTrue(gated.entered.await(10, TimeUnit.SECONDS));
            loop.pauseActiveGoal("chat:main"); // ⏸：USER_STOP 取消 + 幂等收敛
            gated.release.countDown();
            try {
                running.get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.CancellationException expected) {
                // USER_STOP cancellation contract
            }

            assertTrue(await(() -> loadGoal(loop) != null
                            && loadGoal(loop).getStatus() == GoalState.Status.PAUSED
                            && !loop.hasActiveRun("chat:main")),
                    "busy pause must cancel the turn and converge the goal to PAUSED");
        } finally {
            gated.release.countDown();
            loop.shutdown();
        }
    }

    // --- ▶ 入口 ---

    @Test
    void resumePausedGoalFlipsActiveAndSchedulesContinuation() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        // 续跑片：首个普通终答被 nudge 顶回，第二条放行（对齐 GoalLoopTest 续跑模式）
        ai.script().script(LLMResponse.text("slice try"));
        ai.script().script(LLMResponse.text("slice final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("resume objective", null);
            goal.incrementContinuationRounds();
            goal.incrementContinuationRounds(); // 预算已烧 2 片
            goal.pauseFromActive();
            GoalState.store(session, goal);

            loop.resumePausedGoal("chat:main");

            // 钉在真正的可观测上（续跑触发文本到达 LLM）——「翻 ACTIVE + rounds 递增 +
            // 无在跑回合」在续跑片尚未取出的窗口内也会短暂成立，不可作为完成判据
            assertTrue(await(() -> {
                GoalState g = loadGoal(loop);
                return g != null && g.isActive() && g.getContinuationRounds() >= 3
                        && ai.allUserContents.stream()
                                .anyMatch(c -> c.contains("Continue the active sustained goal"));
            }), "resume must flip ACTIVE, schedule the standard continuation slice, "
                    + "and keep counting the budget");
            assertTrue(await(() -> !loop.hasActiveRun("chat:main")),
                    "the resume slice should finish");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void resumeIgnoredWhenGoalNotPaused() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop); // ACTIVE, not PAUSED

            loop.resumePausedGoal("chat:main");
            Thread.sleep(300);

            assertTrue(loadGoal(loop).isActive(), "resume on a non-paused goal is a no-op");
            assertEquals(0, loadGoal(loop).getContinuationRounds(),
                    "no continuation slice may be scheduled by a no-op resume");
            assertTrue(ai.allUserContents.isEmpty(), "no turn may start");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void resumeAfterExhaustedHandbackRedeliversHandbackSlice() throws Exception {
        // 预算耗尽且终结闸已置的 PAUSED goal：▶ 恢复须重投交回片——静默丢弃会让
        // goal 变回 ACTIVE 却无任何回合/反馈（无反馈僵尸 ACTIVE）
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("hb try"));   // 交回片首答被 nudge 顶回
        ai.script().script(LLMResponse.text("hb final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("capped objective", null);
            while (goal.getContinuationRounds() < org.gitee.jmeter.ai.utils.AiConfig
                    .getGoalMaxContinuations()) {
                goal.incrementContinuationRounds();
            }
            goal.markHandbackScheduled(); // 终结闸：交回片已投放过
            goal.pauseFromActive();
            GoalState.store(session, goal);

            loop.resumePausedGoal("chat:main");

            assertTrue(await(() -> {
                GoalState g = loadGoal(loop);
                return g != null && g.isActive()
                        && ai.allUserContents.stream()
                                .anyMatch(c -> c.contains("structured handback"))
                        && !loop.hasActiveRun("chat:main");
            }), "resume must redeliver the handback slice (not a silent zombie ACTIVE)");
        } finally {
            loop.shutdown();
        }
    }

    // --- /new 与 goal：缓存摘除后的 ▶ 恢复 + 活跃 goal 重置后重火 ---

    @Test
    void resumeWorksAfterNewResetInvalidatesSessionCache() throws Exception {
        // /new 尾部 invalidate 摘除会话缓存：裸 get 恒返 null 曾让 ▶ 静默失灵（无
        // 日志、无状态迁移、无续跑）。恢复入口必须经磁盘重载取会话——goal_state
        // 随会话头行存续
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        // 续跑片：首个普通终答被 nudge 顶回，第二条放行
        ai.script().script(LLMResponse.text("slice try"));
        ai.script().script(LLMResponse.text("slice final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("resume after new", null);
            goal.pauseFromActive();
            GoalState.store(session, goal);

            loop.resetConversation("chat:main");
            // 重火腿的重载已顺带回填缓存；显式再摘一次，重建「裸 get 恒 null」窗口
            // （archive=false 清空腿后缓存即停留此态）——▶ 入口必须自带重载，不得
            // 依赖他路径恰好暖缓存
            loop.getSessionManager().invalidate("chat:main");
            assertTrue(loop.getSessionManager().get("chat:main") == null,
                    "前置：会话缓存已摘除（裸 get 恒 null）");

            loop.resumePausedGoal("chat:main");

            assertTrue(await(() -> {
                GoalState g = loadGoal(loop);
                return g != null && g.isActive()
                        && ai.allUserContents.stream()
                                .anyMatch(c -> c.contains("Continue the active sustained goal"));
            }), "▶ 在缓存摘除后必须经磁盘重载恢复 ACTIVE 并投放续跑片");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void newResetRearmsActiveGoalContinuationInFreshSession() throws Exception {
        // 活跃 goal 中 /new：垂死回合的旧代数续跑片被 epoch 栅栏拦下后，无人点火即
        // 永久 ACTIVE 无 runner（状态条/光晕常亮、无人推进）。重置须以翻转后的新
        // 代数重新投放续跑片
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("rearm try"));
        ai.script().script(LLMResponse.text("rearm final"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            loop.resetConversation("chat:main");

            assertTrue(await(() -> {
                GoalState g = loadGoal(loop);
                return g != null && g.isActive() && g.getContinuationRounds() >= 1
                        && ai.allUserContents.stream()
                                .anyMatch(c -> c.contains("Continue the active sustained goal"));
            }), "/new 后活跃 goal 必须在新会话自动续跑（新代数新租约）");
            assertTrue(await(() -> !loop.hasActiveRun("chat:main")),
                    "重火片应正常收尾");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void closeConsolidationResetDoesNotRearmGoal() throws Exception {
        // 关闭整合清空腿（archiveSnapshot=false，JVM 退出期）不重火——退出期不再起
        // 回合。重火会同步递增 continuationRounds 并落盘，0 即确定性锚
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            storeActiveGoal(loop);

            loop.resetConversation("chat:main", false);

            GoalState g = loadGoal(loop);
            assertTrue(g != null && g.isActive(), "goal 本体跨清空存续");
            assertEquals(0, g.getContinuationRounds(),
                    "关闭整合清空不得投放续跑片");
            assertFalse(loop.hasActiveRun("chat:main"));
            assertTrue(ai.allUserContents.isEmpty(), "不得有任何回合开跑");
        } finally {
            loop.shutdown();
        }
    }

    // --- PAUSED 注入隔离（引擎级 + 单元级） ---

    @Test
    void pausedGoalInjectsPauseDirectiveInsteadOfStateBlock() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("plain answer"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("paused objective", null);
            goal.pauseFromActive();
            GoalState.store(session, goal);

            AgentResponse response = loop.processMessage("unrelated question", "chat:main")
                    .get(20, TimeUnit.SECONDS);
            assertTrue(response.isSuccess());
            assertEquals("plain answer", response.getContent(),
                    "PAUSED must not nudge a plain final reply either");
            String trigger = ai.lastMessages.get().stream()
                    .filter(m -> m.getRole() == Message.Role.USER
                            && m.getContent() != null
                            && m.getContent().startsWith("unrelated question"))
                    .findFirst().orElseThrow().getContent();
            assertTrue(trigger.contains("PAUSED by the user"),
                    "PAUSED turns carry the explicit pause directive "
                            + "(silent suppression loses to the goal momentum in history)");
            assertTrue(trigger.contains("do NOT"),
                    "the directive must forbid continuing the goal work");
            assertFalse(trigger.contains("Active Sustained Goal"),
                    "PAUSED goal must not inject its state block");
            assertFalse(trigger.contains("paused objective"),
                    "the objective text stays out of the context while paused");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void buildGoalContextReturnsPauseDirectiveForPausedGoal() {
        GoalState paused = GoalState.create("secretive objective", null);
        paused.pauseFromActive();
        String directive = GoalRuntime.buildGoalContext(false, paused);
        assertTrue(directive.contains("PAUSED by the user") && directive.contains("do NOT"),
                "ordinary turn during PAUSED carries the explicit pause directive");
        assertFalse(directive.contains("secretive objective"),
                "the directive carries no goal state data (objective/counters)");
        assertTrue(GoalRuntime.buildGoalContext(true, paused).contains("PAUSED by the user"),
                "/goal-requested turn during PAUSED gets the same directive "
                        + "(the create_goal gate rejects with the resume/cancel hint)");

        paused.resumeFromPaused();
        assertTrue(GoalRuntime.buildGoalContext(true, paused).contains("Active Sustained Goal"),
                "after resume the full goal block injects as usual");
    }

    // --- PAUSED 跨 /new 存活 ---

    @Test
    void pausedGoalSurvivesNewReset() throws Exception {
        Path workspace = Files.createTempDirectory("goal-pause-test");
        CapturingAiService ai = new CapturingAiService();
        ai.script().script(LLMResponse.text("after new"));
        AgentLoop loop = newLoop(ai, workspace, noopRegistry());
        try {
            var session = loop.getSessionManager().getOrCreate("chat:main");
            GoalState goal = GoalState.create("survives new", null);
            goal.pauseFromActive();
            GoalState.store(session, goal);
            loop.getSessionManager().saveSession(session);

            AgentResponse reset = loop.processMessage("/new", "chat:main").get(20, TimeUnit.SECONDS);
            assertTrue(reset.isSuccess());

            GoalState after = loadGoal(loop);
            assertTrue(after != null && after.getStatus() == GoalState.Status.PAUSED,
                    "paused goal must survive /new (metadata survives Session.clear + reload)");
        } finally {
            loop.shutdown();
        }
    }

    private static void assertNullRecap(GoalState goal) {
        assertTrue(goal.getRecap() == null && goal.getEndedAt() == null,
                "pause is non-terminal: no recap/endedAt");
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
