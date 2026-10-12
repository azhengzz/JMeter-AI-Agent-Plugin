package org.gitee.jmeter.ai.agent.tools.goal;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * create_goal / update_goal 的权限门、状态机与 complete 证据校验。工具经
 * AgentRunContext 感知回合身份（goalRequested = /goal 回合的变更权限）。
 */
class GoalToolsTest {

    @TempDir
    Path workspace;

    private SessionManager sessionManager;
    private CreateGoalTool createTool;
    private UpdateGoalTool updateTool;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(workspace, "instance-1");
        createTool = new CreateGoalTool(sessionManager);
        updateTool = new UpdateGoalTool(sessionManager);
        // 采集器全局静态：本类测试会伪造门禁仲裁的运行状态，前后都归位防跨类污染
        org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.reset(
                org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.RunProvenance.USER);
    }

    @AfterEach
    void clearContext() {
        AgentRunContext.clear();
        org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.reset(
                org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.RunProvenance.USER);
    }

    private Session session() {
        return sessionManager.getOrCreate("instance-1");
    }

    /** 伪造一次「AGENT 冒烟运行、已完成、零错」的采集器状态（先 reset 绑当代再喂事件）。 */
    private void fakeCompletedCleanAgentSmoke() {
        org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.reset(
                org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector.RunProvenance.AGENT,
                true);
        org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector collector =
                new org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector();
        org.apache.jmeter.samplers.SampleResult sample =
                new org.apache.jmeter.samplers.SampleResult();
        sample.setSampleLabel("smoke-1");
        sample.setSuccessful(true);
        sample.setResponseCode("200");
        collector.testStarted("local");
        collector.sampleOccurred(new org.apache.jmeter.samplers.SampleEvent(sample, "tg"));
        collector.testEnded("local");
    }

    private void bindTurn(boolean goalRequested) {
        AgentRunContext.set(new AgentRunContext("instance-1", "run-test", goalRequested));
    }

    @Test
    void createGoalRejectedWithoutPermission() {
        bindTurn(false);
        ToolResult result = createTool.executeInternal(Map.of("objective", "build the login script"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("/goal"));
    }

    @Test
    void createGoalRejectedWithoutRunContext() {
        ToolResult result = createTool.executeInternal(Map.of("objective", "o"));
        assertFalse(result.isSuccess());
    }

    @Test
    void completeArbitratesTimedOutButPassedSmokeRun() {
        // 冒烟阻塞等待超时（工具返回 still running）而引擎事后完成且通过：门禁点
        // 当场对账补章——预算只烧 1 轮即可干净 complete，不再重跑两轮
        bindTurn(false);
        GoalState goal = GoalState.create("o", null);
        goal.recordSmokeAttempt();      // 超时分支已计数（1/3）
        goal.recordStaticPass();        // validate PASS 在账
        GoalState.store(session(), goal); // 冒烟证据缺失（超时未盖章）

        fakeCompletedCleanAgentSmoke();

        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "complete", "recap", "smoke passed after the wait timed out"));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals(GoalState.Status.COMPLETED, GoalState.load(session()).getStatus());
    }

    @Test
    void arbitrationRefusesStaleSmokeFromAnotherGoal() {
        // 跨 goal 陈旧数据：新 goal 一发冒烟都没跑（attempts=0），采集器里躺着上一
        // 个 goal 通过的冒烟——不得冒名补章
        bindTurn(false);
        GoalState goal = GoalState.create("fresh goal", null);
        goal.recordStaticPass();
        GoalState.store(session(), goal);

        fakeCompletedCleanAgentSmoke();

        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "complete", "recap", "stale data attempt"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("missing verification evidence"));
        assertTrue(GoalState.load(session()).isActive(),
                "a fresh goal must not complete on a stale smoke run");
    }

    @Test
    void arbitrationDoesNotBypassTheStaticGate() {
        // 预算消耗过 + 采集器是通过的冒烟，但静态校验从未 PASS：补章后 canComplete
        // 复评仍不过——照拒
        bindTurn(false);
        GoalState goal = GoalState.create("o", null);
        goal.recordSmokeAttempt();
        GoalState.store(session(), goal);

        fakeCompletedCleanAgentSmoke();

        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "complete", "recap", "no static pass"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("static missing"));
        assertTrue(GoalState.load(session()).isActive());
    }

    @Test
    void goalMutationDeniedOnDelegatedTurns() {
        // 委派回合是宿主会话之外的独立任务：create/update 双向拒绝，宿主 goal 不被
        // 代录/代终结（对齐 update_goal 既有门，create_goal 补齐同款）
        bindTurn(true);
        org.gitee.jmeter.ai.instance.DelegationGuard.begin();
        try {
            ToolResult create = createTool.executeInternal(Map.of("objective", "delegated goal"));
            assertFalse(create.isSuccess());
            assertTrue(create.getError().contains("delegated"));

            GoalState.store(session(), GoalState.create("host goal", null));
            ToolResult update = updateTool.executeInternal(Map.of(
                    "action", "cancel", "recap", "delegated cancel attempt"));
            assertFalse(update.isSuccess());
            assertTrue(update.getError().contains("delegated"));
        } finally {
            org.gitee.jmeter.ai.instance.DelegationGuard.end();
        }
        assertTrue(GoalState.load(session()).isActive(),
                "the host goal must survive the delegated turn untouched");
    }

    @Test
    void createGoalOnGoalTurnRecordsActiveGoal() {
        bindTurn(true);
        ToolResult result = createTool.executeInternal(Map.of(
                "objective", "为登录接口建脚本并验证通过", "ui_summary", "login goal"));

        assertTrue(result.isSuccess(), result.getError());
        GoalState goal = GoalState.load(session());
        assertNotNull(goal);
        assertTrue(goal.isActive());
        assertEquals("为登录接口建脚本并验证通过", goal.getObjective());
        assertTrue(result.getContent().contains("update_goal"));
    }

    @Test
    void duplicateCreateRejectedWhileActive() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "first"));
        ToolResult result = createTool.executeInternal(Map.of("objective", "second"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("replace"));
    }

    @Test
    void completeWithoutEvidenceRejected() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "o"));

        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "complete", "recap", "done"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("verification evidence"));
    }

    @Test
    void completeAcceptedWithHardStandardEvidence() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "o"));
        GoalState goal = GoalState.load(session());
        goal.recordStaticPass();
        goal.recordSmokePass();
        GoalState.store(session(), goal);

        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "complete", "recap", "objective met, 1 smoke round, no residual risk"));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals(GoalState.Status.COMPLETED, GoalState.load(session()).getStatus());
        assertTrue(result.getContent().contains("Recap"), "termination reply must echo the recap");
    }

    @Test
    void completeAcceptedAfterSmokeBudgetExhausted() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "o"));
        GoalState goal = GoalState.load(session());
        goal.recordSmokeAttempt();
        goal.recordSmokeAttempt();
        goal.recordSmokeAttempt();
        GoalState.store(session(), goal);

        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "complete", "recap", "stuck at 401, 3 smoke rounds used"));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals(GoalState.Status.COMPLETED, GoalState.load(session()).getStatus());
    }

    @Test
    void terminalRequiresRecap() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "o"));

        ToolResult missing = updateTool.executeInternal(Map.of("action", "cancel"));
        assertFalse(missing.isSuccess());
        assertTrue(missing.getError().contains("recap"));

        GoalState goal = GoalState.load(session());
        goal.recordSmokeAttempt();
        goal.recordSmokeAttempt();
        goal.recordSmokeAttempt();
        GoalState.store(session(), goal);
        ToolResult ok = updateTool.executeInternal(Map.of("action", "block", "recap", "blocked by auth"));
        assertTrue(ok.isSuccess(), ok.getError());
        assertEquals(GoalState.Status.BLOCKED, GoalState.load(session()).getStatus());
    }

    @Test
    void replaceGatedAndResetsCounters() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "old"));
        GoalState goal = GoalState.load(session());
        goal.recordSmokeAttempt();
        GoalState.store(session(), goal);

        bindTurn(false); // 非授权回合
        ToolResult denied = updateTool.executeInternal(Map.of(
                "action", "replace", "objective", "new"));
        assertFalse(denied.isSuccess());
        assertTrue(denied.getError().contains("/goal"));

        bindTurn(true);
        ToolResult ok = updateTool.executeInternal(Map.of(
                "action", "replace", "objective", "new objective"));
        assertTrue(ok.isSuccess(), ok.getError());
        GoalState replaced = GoalState.load(session());
        assertTrue(replaced.isActive());
        assertEquals("new objective", replaced.getObjective());
        assertEquals(0, replaced.getSmokeAttempts());
    }

    @Test
    void updateWithoutActiveGoalRejected() {
        bindTurn(true);
        ToolResult result = updateTool.executeInternal(Map.of(
                "action", "cancel", "recap", "nothing to cancel"));
        assertFalse(result.isSuccess());
    }

    @Test
    void goalPersistsAcrossSessionReload() {
        bindTurn(true);
        createTool.executeInternal(Map.of("objective", "persistent goal"));

        SessionManager reloaded = new SessionManager(workspace, "instance-1");
        GoalState goal = GoalState.load(reloaded.getOrCreate("instance-1"));
        assertNotNull(goal, "goal must survive manager reload (jsonl header persistence)");
        assertEquals("persistent goal", goal.getObjective());
    }
}
