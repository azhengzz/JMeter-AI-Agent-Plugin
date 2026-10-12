package org.gitee.jmeter.ai.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session metadata persistence + GoalState state machine. Metadata lives in the jsonl
 * header's metadata object, survives Session.clear() (/new keeps the goal alive —
 * Nanobot parity) and round-trips through SessionManager.
 */
class SessionGoalMetadataTest {

    @Test
    void clearKeepsMetadata() {
        Session session = new Session("k");
        session.addMessage(org.gitee.jmeter.ai.agent.model.Message.builder()
                .role(org.gitee.jmeter.ai.agent.model.Message.Role.USER)
                .content("hi").build());
        session.putMetadataValue("goal_state", Map.of("status", "ACTIVE"));

        session.clear();

        assertTrue(session.getMessages().isEmpty());
        assertEquals(Map.of("status", "ACTIVE"), session.getMetadataValue("goal_state"));
    }

    @Test
    void metadataRoundTripsThroughSessionManager(@TempDir Path workspace) {
        SessionManager manager = new SessionManager(workspace, "instance-1");
        Session session = manager.getOrCreate("instance-1");
        GoalState goal = GoalState.create("为登录接口建脚本并验证通过", "login goal");
        goal.recordSmokeAttempt();
        GoalState.store(session, goal);
        manager.saveSession(session);

        // fresh manager reloads from disk only for the focused key
        SessionManager reloaded = new SessionManager(workspace, "instance-1");
        Session loaded = reloaded.getOrCreate("instance-1");
        GoalState back = GoalState.load(loaded);

        assertNotNull(back);
        assertTrue(back.isActive());
        assertEquals("为登录接口建脚本并验证通过", back.getObjective());
        assertEquals(1, back.getSmokeAttempts());
    }

    @Test
    void goalSurvivesInvalidateAndGetOrCreateReload(@TempDir Path workspace) {
        // /new 核心：clear → saveSession（盘上头行含 goal_state）→ invalidate。
        // invalidate 的契约是「下次 getOrCreate 从盘重载」——重载必须带回 goal_state，
        // 否则 /new 后的新会话永久丢失 goal（缓存未命中时新建空 Session 会立刻
        // 覆写盘文件，不可逆销毁头行 metadata）
        SessionManager manager = new SessionManager(workspace, "instance-1");
        Session session = manager.getOrCreate("instance-1");
        GoalState.store(session, GoalState.create("跨重置存活的目标", null));
        manager.saveSession(session);

        manager.invalidate("instance-1");

        Session reloaded = manager.getOrCreate("instance-1");
        GoalState goal = GoalState.load(reloaded);
        assertNotNull(goal, "getOrCreate after invalidate must reload the persisted header metadata");
        assertTrue(goal.isActive());
        assertEquals("跨重置存活的目标", goal.getObjective());
    }

    @Test
    void legacyHeaderWithoutMetadataObjectLoads(@TempDir Path workspace) throws Exception {
        Path sessionsDir = workspace.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve("legacy-1.jsonl"), """
                {"_type":"metadata","key":"legacy-1","created_at":"2026-01-01T00:00:00","updated_at":"2026-01-01T00:00:00","last_consolidated":0}
                {"role":"user","content":"hello","timestamp":"2026-01-01T00:00:00"}
                """);

        SessionManager manager = new SessionManager(workspace, "legacy-1");
        Session session = manager.getOrCreate("legacy-1");

        assertEquals(1, session.getMessageCount());
        assertNull(session.getMetadataValue(GoalState.SESSION_METADATA_KEY));
    }

    @Test
    void headerWithForeignTimestampLoadsInsteadOfThrowing(@TempDir Path workspace) throws Exception {
        // 手工编辑/同步工具写回的带 Z 偏移头行：getOrCreate 不得被
        // DateTimeParseException 沿重载路径楔死（回落加载期默认值，会话照常可用）
        Path sessionsDir = workspace.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve("foreign-ts.jsonl"), """
                {"_type":"metadata","key":"foreign-ts","created_at":"2026-10-10T10:00:00Z","updated_at":"2026-10-10T10:00:00Z","last_consolidated":0,"metadata":{}}
                {"role":"user","content":"hello","timestamp":"2026-01-01T00:00:00"}
                """);

        SessionManager manager = new SessionManager(workspace, "foreign-ts");
        Session session = manager.getOrCreate("foreign-ts");
        assertEquals(1, session.getMessageCount(), "foreign-format header must not wedge the session");
    }

    @Test
    void unreadableExistingFileIsNeverOverwritten(@TempDir Path workspace) throws Exception {
        // 既有文件读失败（杀软/索引器短暂锁文件）≠ 无头行：getOrCreate 必须响亮失败，
        // 绝不新建空会话覆写盘面（消息 + goal_state 会被不可逆销毁）。
        // 目录占位使 newBufferedReader 必抛 IOException（跨平台确定性）
        Path sessionsDir = workspace.resolve("sessions");
        Files.createDirectories(sessionsDir);
        Path blocker = Files.createDirectory(sessionsDir.resolve("blocked.jsonl"));

        SessionManager manager = new SessionManager(workspace, "blocked");
        assertThrows(java.io.UncheckedIOException.class, () -> manager.getOrCreate("blocked"));
        assertTrue(Files.isDirectory(blocker), "the existing entry must not be overwritten");
    }

    // --- GoalState state machine ---

    @Test
    void createRejectsBlankAndOversizedObjective() {
        assertThrows(IllegalArgumentException.class, () -> GoalState.create("  ", null));
        assertThrows(IllegalArgumentException.class, () -> GoalState.create("x".repeat(4001), null));
        assertNotNull(GoalState.create("x".repeat(4000), null));
    }

    @Test
    void terminalTransitionsRequireRecapAndRecordIt() {
        GoalState goal = GoalState.create("objective", null);
        assertThrows(IllegalArgumentException.class, () -> goal.markTerminal(GoalState.Status.COMPLETED, " "));

        goal.markTerminal(GoalState.Status.COMPLETED, "done recap");
        assertFalse(goal.isActive());
        assertEquals(GoalState.Status.COMPLETED, goal.getStatus());
        assertEquals("done recap", goal.getRecap());
        assertNotNull(goal.getStartedAt());
    }

    @Test
    void replaceResetsCountersAndEvidence() {
        GoalState goal = GoalState.create("old objective", null);
        goal.recordSmokeAttempt();
        goal.recordSmokeAttempt();
        goal.incrementContinuationRounds();
        goal.recordStaticPass();

        goal.replace("new objective", "new summary");

        assertTrue(goal.isActive());
        assertEquals("new objective", goal.getObjective());
        assertEquals(0, goal.getSmokeAttempts());
        assertEquals(0, goal.getContinuationRounds());
        assertFalse(goal.hasVerificationEvidence());
    }

    @Test
    void canCompleteRequiresEvidenceOrExhaustedSmokeBudget() {
        GoalState goal = GoalState.create("o", null);
        assertFalse(goal.canComplete(3));
        goal.recordStaticPass();
        assertFalse(goal.canComplete(3), "static pass alone is not enough");
        goal.recordSmokePass();
        assertTrue(goal.canComplete(3), "static + smoke pass is the hard standard");

        GoalState exhausted = GoalState.create("o", null);
        exhausted.recordSmokeAttempt();
        exhausted.recordSmokeAttempt();
        exhausted.recordSmokeAttempt();
        assertFalse(exhausted.hasVerificationEvidence());
        assertTrue(exhausted.canComplete(3), "smoke budget exhausted unlocks structured handback");
    }

    @Test
    void storeNullClearsAndLoadReturnsNullWithoutGoal() {
        Session session = new Session("k");
        assertNull(GoalState.load(session));

        GoalState.store(session, GoalState.create("o", null));
        assertNotNull(GoalState.load(session));

        GoalState.store(session, null);
        assertNull(GoalState.load(session));
    }

    @Test
    void corruptBlobTreatedAsNoGoal() {
        Session session = new Session("k");
        session.putMetadataValue(GoalState.SESSION_METADATA_KEY,
                Map.of("status", "NOT_A_STATUS"));
        assertNull(GoalState.load(session));
    }

    @Test
    void utf16SafeTruncationBacksOffSplitSurrogatePair() {
        // 切点落在增补字符（emoji）的代理对中间时回退一位，孤代理对不得进 jsonl
        String s = "a".repeat(119) + "😀" + "b"; // 😀 跨在 120 切点上
        String cut = GoalState.truncateUtf16Safe(s, 120);
        assertEquals(119, cut.length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        assertEquals("a".repeat(119), cut);

        // null 安全 + 不超限原样返回
        assertNull(GoalState.truncateUtf16Safe(null, 10));
        assertEquals("abc", GoalState.truncateUtf16Safe("abc", 10));
        // 切点不劈代理对时按位截断
        assertEquals("ab", GoalState.truncateUtf16Safe("ab😀", 2));
    }
}
