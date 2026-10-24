package org.gitee.jmeter.ai.agent.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * store() 咽喉点发布契约：每次持久化变更同动作内 publish 快照、字段一致、
 * null 清除发布 null、load-only 不发布；PAUSED 迁移的合法性与持久化 roundtrip。
 */
class GoalStatePublishTest {

    private final List<GoalState.GoalSnapshot> published = new ArrayList<>();
    private final List<String> publishedKeys = new ArrayList<>();

    @BeforeEach
    void registerListener() {
        GoalStateEvents.resetForTest();
        GoalStateEvents.addListener((key, snapshot) -> {
            publishedKeys.add(key);
            published.add(snapshot);
        });
    }

    @AfterEach
    void resetRegistry() {
        GoalStateEvents.resetForTest();
    }

    @Test
    void storePublishesSnapshotMatchingGoal() {
        Session session = new Session("s1");
        GoalState goal = GoalState.create("objective text", "summary");
        goal.recordSmokeAttempt();
        goal.recordSmokeAttempt();
        goal.incrementContinuationRounds();

        GoalState.store(session, goal);

        assertEquals(1, published.size());
        assertEquals("s1", publishedKeys.get(0));
        GoalState.GoalSnapshot snapshot = published.get(0);
        assertEquals(GoalState.Status.ACTIVE, snapshot.status());
        assertEquals("summary", snapshot.uiSummary());
        assertEquals("objective text", snapshot.objective());
        assertNull(snapshot.recap());
        assertEquals(2, snapshot.smokeAttempts());
        assertEquals(1, snapshot.continuationRounds());
        assertEquals("s1", snapshot.sessionKey());
        assertEquals(snapshot.status(), GoalStateEvents.lastKnown("s1").status(),
                "publish also refreshes the process-local cache");
    }

    @Test
    void storeNullPublishesNull() {
        Session session = new Session("s1");
        GoalState.store(session, GoalState.create("o", null));
        assertEquals(1, published.size());

        GoalState.store(session, null);
        assertEquals(2, published.size());
        assertNull(published.get(1), "clearing publishes a null snapshot");
        assertNull(GoalStateEvents.lastKnown("s1"));
    }

    @Test
    void loadOnlyDoesNotPublish() {
        Session session = new Session("s1");
        GoalState.store(session, GoalState.create("o", null));
        published.clear();

        GoalState loaded = GoalState.load(session);

        assertNotNull(loaded);
        assertTrue(published.isEmpty(), "load must not re-publish (non-test-plan path)");
    }

    // --- PAUSED 状态机 ---

    @Test
    void pauseResumeCyclePublishesEachTransition() {
        Session session = new Session("s1");
        GoalState goal = GoalState.create("o", "s");
        GoalState.store(session, goal); // publish #1 ACTIVE

        goal.pauseFromActive();
        GoalState.store(session, goal); // publish #2 PAUSED
        assertFalse(goal.isActive());
        assertEquals(GoalState.Status.PAUSED, goal.getStatus());
        assertNull(goal.getRecap(), "pause is non-terminal: no recap");
        assertNull(goal.getEndedAt(), "pause is non-terminal: no endedAt");

        goal.resumeFromPaused();
        GoalState.store(session, goal); // publish #3 ACTIVE
        assertTrue(goal.isActive());

        assertEquals(3, published.size());
        assertEquals(GoalState.Status.ACTIVE, published.get(0).status());
        assertEquals(GoalState.Status.PAUSED, published.get(1).status());
        assertEquals(GoalState.Status.ACTIVE, published.get(2).status());
    }

    @Test
    void pauseKeepsCountersAndStartedAt() {
        GoalState goal = GoalState.create("o", null);
        goal.recordSmokeAttempt();
        goal.incrementContinuationRounds();
        String startedAt = goal.getStartedAt();

        goal.pauseFromActive();
        goal.resumeFromPaused();

        assertEquals(1, goal.getSmokeAttempts(), "pause/resume must not reset counters");
        assertEquals(1, goal.getContinuationRounds(), "continuation budget continues after resume");
        assertEquals(startedAt, goal.getStartedAt());
    }

    @Test
    void illegalTransitionsRejected() {
        GoalState active = GoalState.create("o", null);
        active.pauseFromActive();
        assertThrows(IllegalArgumentException.class, active::pauseFromActive,
                "PAUSED -> PAUSED is illegal");

        GoalState freshActive = GoalState.create("o", null);
        assertThrows(IllegalArgumentException.class, freshActive::resumeFromPaused,
                "ACTIVE -> ACTIVE via resume is illegal");

        GoalState terminal = GoalState.create("o", null);
        terminal.markTerminal(GoalState.Status.COMPLETED, "recap");
        assertThrows(IllegalArgumentException.class, terminal::pauseFromActive,
                "terminal goal cannot be paused");
    }

    @Test
    void pausedRoundTripsThroughPersistence() {
        Session session = new Session("s1");
        GoalState goal = GoalState.create("persisted objective", "sum");
        goal.recordSmokeAttempt();
        goal.pauseFromActive();
        GoalState.store(session, goal);

        GoalState back = GoalState.load(session);

        assertNotNull(back);
        assertEquals(GoalState.Status.PAUSED, back.getStatus());
        assertFalse(back.isActive());
        assertEquals("persisted objective", back.getObjective());
        assertEquals("sum", back.getUiSummary());
        assertEquals(1, back.getSmokeAttempts());
        assertNull(back.getEndedAt(), "paused blob carries no terminal fields");

        back.resumeFromPaused();
        assertTrue(back.isActive());
    }

    @Test
    void verificationModeRoundTripsAndDrivesContextWord() {
        Session session = new Session("s1");
        GoalState goal = GoalState.create("non-test-plan analysis objective", null);
        goal.markNonTestPlan();
        GoalState.store(session, goal);

        GoalState back = GoalState.load(session);
        assertEquals(GoalState.Verification.NON_TEST_PLAN, back.getVerification());
        assertTrue(back.isNonTestPlan());

        // 注入口径随模式切换：non_test_plan 不呈现冒烟计数（勿跑 validate/smoke），
        // test_plan 维持既有验证纪律行
        String readOnlyContext = GoalRuntime.buildGoalContext(false, back);
        assertTrue(readOnlyContext.contains("non-test-plan goal"),
                "non_test_plan goals must be told validate/smoke are not applicable");
        assertFalse(readOnlyContext.contains("Smoke attempts:"),
                "smoke counters are meaningless for a non-test-plan goal");

        GoalState testPlan = GoalState.create("build the login script", null);
        GoalState.store(session, testPlan);
        String testPlanContext = GoalRuntime.buildGoalContext(false, testPlan);
        assertTrue(testPlanContext.contains("Smoke attempts:"),
                "test_plan goals keep the smoke verification discipline");
        assertFalse(testPlanContext.contains("non-test-plan goal"));
    }
}
