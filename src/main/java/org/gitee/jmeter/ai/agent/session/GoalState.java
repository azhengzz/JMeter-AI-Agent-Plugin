package org.gitee.jmeter.ai.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session-level sustained-goal state (Nanobot parity). Persisted under
 * {@link #SESSION_METADATA_KEY} in the session jsonl's metadata header, so it
 * survives {@code /new} (Session.clear keeps metadata) and outlives individual
 * turns; only update_goal transitions terminate it.
 *
 * <p>Not thread-safe by itself — all access goes through {@link #load}/{@link #store},
 * which read/write under the session monitor. Counters (smokeAttempts,
 * continuationRounds) accumulate per goal and reset on {@code replace}.
 */
public class GoalState {

    private static final Logger log = LoggerFactory.getLogger(GoalState.class);

    public enum Status { ACTIVE, COMPLETED, CANCELLED, BLOCKED }

    public static final String SESSION_METADATA_KEY = "goal_state";
    public static final int MAX_OBJECTIVE_CHARS = 4000;
    public static final int MAX_RECAP_CHARS = 8000;
    public static final int MAX_UI_SUMMARY_CHARS = 120;

    private Status status = Status.ACTIVE;
    private String objective;
    private String uiSummary;
    private String startedAt;
    private String endedAt;
    private String recap;
    private int smokeAttempts;
    private int continuationRounds;
    private String lastStaticPassAt;
    private String lastSmokePassAt;
    /** 强制交回指令已投放过（续跑链的终结闸：交回回合再耗尽也不再调度）。 */
    private boolean handbackScheduled;

    private GoalState() {
    }

    /** Create a new ACTIVE goal. Validates objective length. */
    public static GoalState create(String objective, String uiSummary) {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        if (objective.length() > MAX_OBJECTIVE_CHARS) {
            throw new IllegalArgumentException(
                    "objective exceeds " + MAX_OBJECTIVE_CHARS + " chars (got " + objective.length() + ")");
        }
        GoalState g = new GoalState();
        g.objective = objective;
        g.uiSummary = uiSummary;
        g.startedAt = LocalDateTime.now().toString();
        return g;
    }

    // --- state accessors ---

    public Status getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public String getObjective() {
        return objective;
    }

    public String getUiSummary() {
        return uiSummary;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getRecap() {
        return recap;
    }

    public int getSmokeAttempts() {
        return smokeAttempts;
    }

    public int getContinuationRounds() {
        return continuationRounds;
    }

    // --- transitions ---

    /** Terminate the goal. Recap is required in practice for every terminal action. */
    public void markTerminal(Status terminal, String recap) {
        if (terminal == Status.ACTIVE) {
            throw new IllegalArgumentException("terminal status required");
        }
        if (recap == null || recap.isBlank()) {
            throw new IllegalArgumentException("recap is required for goal termination");
        }
        this.recap = truncateUtf16Safe(recap, MAX_RECAP_CHARS);
        this.status = terminal;
        this.endedAt = LocalDateTime.now().toString();
    }

    /**
     * Replace the objective (update_goal action='replace'). Counters and verification
     * evidence reset — the new objective gets a fresh smoke budget and continuation budget.
     */
    public void replace(String newObjective, String newUiSummary) {
        if (newObjective == null || newObjective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        if (newObjective.length() > MAX_OBJECTIVE_CHARS) {
            throw new IllegalArgumentException(
                    "objective exceeds " + MAX_OBJECTIVE_CHARS + " chars");
        }
        this.objective = newObjective;
        this.uiSummary = newUiSummary;
        this.startedAt = LocalDateTime.now().toString();
        this.endedAt = null;
        this.recap = null;
        this.smokeAttempts = 0;
        this.continuationRounds = 0;
        this.lastStaticPassAt = null;
        this.lastSmokePassAt = null;
        this.handbackScheduled = false;
        this.status = Status.ACTIVE;
    }

    /** 续跑链终结闸：置位后交回指令已投放，续跑调度不再发生。 */
    public void markHandbackScheduled() {
        this.handbackScheduled = true;
    }

    public boolean isHandbackScheduled() {
        return handbackScheduled;
    }

    /** Count one smoke-run attempt; returns the new count. */
    public int recordSmokeAttempt() {
        return ++smokeAttempts;
    }

    public void recordStaticPass() {
        this.lastStaticPassAt = LocalDateTime.now().toString();
    }

    public void recordSmokePass() {
        this.lastSmokePassAt = LocalDateTime.now().toString();
    }

    /** 最新一次静态校验未过：旧证据作废（证据口径 = 最近一次检查）。 */
    public void clearStaticPass() {
        this.lastStaticPassAt = null;
    }

    /** 最新一次冒烟存在 error：旧冒烟证据作废。 */
    public void clearSmokePass() {
        this.lastSmokePassAt = null;
    }

    /** Hard-standard evidence: static PASS plus the latest smoke run error-free. */
    public boolean hasVerificationEvidence() {
        return lastStaticPassAt != null && lastSmokePassAt != null;
    }

    public boolean hasStaticPass() {
        return lastStaticPassAt != null;
    }

    public boolean hasSmokePass() {
        return lastSmokePassAt != null;
    }

    /**
     * Whether update_goal(complete) may be accepted: verification evidence present,
     * or the smoke-repair budget is exhausted (structured handback allowed).
     */
    public boolean canComplete(int smokeLimit) {
        return hasVerificationEvidence() || smokeAttempts >= smokeLimit;
    }

    /** Count one continuation slice; returns the new count. */
    public int incrementContinuationRounds() {
        return ++continuationRounds;
    }

    /**
     * 代理对安全截断：切点劈开增补字符（emoji/CJK 扩展 B）的 UTF-16 代理对时回退
     * 一位，避免孤代理对落进 jsonl 与 Swing 渲染。null 安全、不超限原样返回。
     */
    public static String truncateUtf16Safe(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s;
        }
        String cut = s.substring(0, maxChars);
        if (!cut.isEmpty() && Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    // --- persistence (session metadata blob) ---

    /** Load the goal state from a session; null when no goal is recorded. */
    public static GoalState load(Session session) {
        if (session == null) {
            return null;
        }
        Object raw;
        synchronized (session) {
            raw = session.getMetadataValue(SESSION_METADATA_KEY);
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            GoalState g = new GoalState();
            g.status = Status.valueOf(string(map.get("status"), Status.ACTIVE.name()));
            g.objective = string(map.get("objective"), null);
            g.uiSummary = string(map.get("ui_summary"), null);
            g.startedAt = string(map.get("started_at"), null);
            g.endedAt = string(map.get("ended_at"), null);
            g.recap = string(map.get("recap"), null);
            g.smokeAttempts = intOf(map.get("smoke_attempts"));
            g.continuationRounds = intOf(map.get("continuation_rounds"));
            g.lastStaticPassAt = string(map.get("last_static_pass_at"), null);
            g.lastSmokePassAt = string(map.get("last_smoke_pass_at"), null);
            g.handbackScheduled = Boolean.TRUE.equals(map.get("handback_scheduled"));
            return g;
        } catch (IllegalArgumentException e) {
            // 容错契约：坏 blob 按无 goal 处理（钉定于 SessionGoalMetadataTest），但必须
            // 留痕——静默 null 会让活跃 goal 的全部效应无声蒸发且盘上 blob 还在
            log.warn("Corrupt goal_state blob in session {} ({}); treating as no goal — "
                    + "the recorded goal's effects are dropped", session.getKey(), e.toString());
            return null;
        }
    }

    /** Store the goal state into the session (pass null to clear). */
    public static void store(Session session, GoalState goal) {
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.putMetadataValue(SESSION_METADATA_KEY, goal == null ? null : goal.toMap());
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status.name());
        map.put("objective", objective);
        if (uiSummary != null) {
            map.put("ui_summary", uiSummary);
        }
        if (startedAt != null) {
            map.put("started_at", startedAt);
        }
        if (endedAt != null) {
            map.put("ended_at", endedAt);
        }
        if (recap != null) {
            map.put("recap", recap);
        }
        map.put("smoke_attempts", smokeAttempts);
        map.put("continuation_rounds", continuationRounds);
        if (lastStaticPassAt != null) {
            map.put("last_static_pass_at", lastStaticPassAt);
        }
        if (lastSmokePassAt != null) {
            map.put("last_smoke_pass_at", lastSmokePassAt);
        }
        map.put("handback_scheduled", handbackScheduled);
        return map;
    }

    private static String string(Object value, String dflt) {
        return value instanceof String s ? s : dflt;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
