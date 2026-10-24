package org.gitee.jmeter.ai.agent.tools.goal;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Terminate or replace the sustained goal (Nanobot update_goal). Terminal actions
 * (complete / cancel / block) are available on any local/CLI turn while a goal is
 * active and require a recap. complete additionally demands verification evidence
 * (static PASS + latest smoke error-free) unless the smoke budget is exhausted.
 * replace carries the same permission gate as create_goal (/goal turns only).
 */
public class UpdateGoalTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(UpdateGoalTool.class);

    private final SessionManager sessionManager;

    public UpdateGoalTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String getName() {
        return "update_goal";
    }

    @Override
    public String getDescription() {
        return "Update or terminate the sustained goal. action='complete' only after the objective "
                + "is actually achieved and verified (static validation PASS + smoke run with zero errors), "
                + "or after the smoke budget is exhausted — except goals created with "
                + "verification='non_test_plan' (no test-plan involvement), which complete once their own "
                + "stated completion evidence is met; 'cancel' when the user cancels; 'block' when "
                + "progress is genuinely blocked; 'replace' only when the objective changes (/goal turns "
                + "only). Terminal actions require a recap: objective, verification results, smoke rounds "
                + "used, residual risks.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": ["complete", "cancel", "block", "replace"],
                            "description": "Goal transition to perform"
                        },
                        "recap": {
                            "type": "string",
                            "description": "Required for complete/cancel/block (max %d chars): objective, verification results, smoke rounds used, residual risks",
                            "maxLength": %d
                        },
                        "objective": {
                            "type": "string",
                            "description": "New objective (replace only, max %d chars)",
                            "maxLength": %d
                        },
                        "ui_summary": {
                            "type": "string",
                            "description": "New one-line UI summary (replace only, max 120 chars)",
                            "maxLength": 120
                        }
                    },
                    "required": ["action"]
                }
                """.formatted(GoalState.MAX_RECAP_CHARS, GoalState.MAX_RECAP_CHARS,
                GoalState.MAX_OBJECTIVE_CHARS, GoalState.MAX_OBJECTIVE_CHARS);
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        AgentRunContext runContext = AgentRunContext.current();
        if (runContext == null) {
            return ToolResult.error("update_goal is unavailable outside an agent run.");
        }
        if (org.gitee.jmeter.ai.instance.DelegationGuard.isActive()) {
            // 委派回合是宿主会话之外的独立任务，无权终结宿主的 goal
            return ToolResult.error("update_goal is unavailable on delegated turns: the goal "
                    + "belongs to the host session.");
        }
        String action = getStringParameter(parameters, "action", "").toLowerCase(Locale.ROOT);
        String recap = getStringParameter(parameters, "recap", "");

        var session = sessionManager.getOrCreate(runContext.getSessionKey());
        GoalState goal = GoalState.load(session);
        if (goal == null) {
            return ToolResult.error("No goal is active for this session.");
        }

        switch (action) {
            case "complete" -> {
                int smokeLimit = AiConfig.getGoalSmokeLimit();
                // 非测试计划目标（create_goal verification='non_test_plan'）免测试计划验证证据：
                // validate/smoke 与其目标边界冲突，以 objective 自身声明的完成证据为准
                if (!goal.isNonTestPlan() && !goal.canComplete(smokeLimit)) {
                    arbitrateTimedOutSmokePass(session, goal);
                    if (!goal.canComplete(smokeLimit)) {
                        return ToolResult.error(String.format(
                                "Goal not complete: missing verification evidence (static %s, smoke %s; "
                                        + "smoke attempts %d/%d). Run validate_test_plan and a smoke run "
                                        + "(run_test mode=smoke) and fix the reported errors first%s%s.",
                                goal.hasStaticPass() ? "PASS" : "missing",
                                goal.hasSmokePass() ? "PASS" : "missing",
                                goal.getSmokeAttempts(), smokeLimit,
                                goal.getSmokeAttempts() >= smokeLimit
                                        ? "" : ", or use action='block' if genuinely stuck",
                                goal.getSmokeAttempts() <= 0
                                        ? ". If this objective involves no JMeter test-plan change "
                                        + "at all, it should have been created with "
                                        + "verification='non_test_plan' — ask the user to resubmit it as "
                                        + "`/goal <objective>` so it can complete on its own evidence"
                                        : ""));
                    }
                }
                return terminate(session, goal, GoalState.Status.COMPLETED, recap);
            }
            case "cancel" -> {
                return terminate(session, goal, GoalState.Status.CANCELLED, recap);
            }
            case "block" -> {
                return terminate(session, goal, GoalState.Status.BLOCKED, recap);
            }
            case "replace" -> {
                if (!runContext.isGoalRequested()) {
                    return ToolResult.error("update_goal action='replace' is unavailable for this turn. "
                            + "Ask the user to resubmit the changed objective as `/goal <objective>`.");
                }
                if (goal.getStatus() == GoalState.Status.PAUSED) {
                    // 侧门封堵：create_goal 的 PAUSED 门只挡正门——replace 同样能达成
                    // 「objective 被静默替换 + 暂停态被复活 + 计数清空」的复合效果。
                    // 暂停目标的去留必须由用户显式选择（cancel 后重提 / ▶ 恢复）
                    return ToolResult.error("A sustained goal is currently paused for this "
                            + "session. Do NOT use action='replace' to swap a paused goal — "
                            + "its objective must not be discarded silently. Ask the user "
                            + "either to cancel it first (update_goal action='cancel', then "
                            + "resubmit the new request as `/goal <objective>`), or to resume "
                            + "it from the chat panel (the ▶ button) to continue it.");
                }
                String objective = getStringParameter(parameters, "objective", "");
                String uiSummary = GoalState.truncateUtf16Safe(
                        getStringParameter(parameters, "ui_summary", ""),
                        GoalState.MAX_UI_SUMMARY_CHARS);
                try {
                    goal.replace(objective, uiSummary);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
                GoalState.store(session, goal);
                sessionManager.saveSession(session);
                return ToolResult.success("Goal replaced. Counters and verification evidence reset. "
                        + "Continue toward the new objective.");
            }
            default -> {
                return ToolResult.error("Unknown action '" + action
                        + "'. Use 'complete', 'cancel', 'block', or 'replace'.");
            }
        }
    }

    /**
     * 门禁点仲裁：冒烟阻塞等待超时（工具返回 "still running"）而引擎事后完成且
     * 通过的运行，其判定没写进 goal blob——当场向采集器对账补章。五条件缺一不
     * 可：{@link AgentResultCollector#completedAgentSmokePassed()} 的四条（AGENT
     * 来源、冒烟运行、已完成、发过流量零 error）+ 本 goal 确实消耗过冒烟预算
     * （防跨 goal 陈旧数据被冒名）。本方法只补冒烟章，不绕过 canComplete 复评
     * ——静态证据仍缺照拒。
     */
    private void arbitrateTimedOutSmokePass(Session session, GoalState goal) {
        if (goal.getSmokeAttempts() <= 0 || !AgentResultCollector.completedAgentSmokePassed()) {
            return;
        }
        goal.recordSmokePass();
        GoalState.store(session, goal);
        sessionManager.saveSession(session);
        log.info("Smoke evidence arbitrated at the complete gate for session {} "
                + "(timed-out smoke run finished clean)", session.getKey());
    }

    private ToolResult terminate(org.gitee.jmeter.ai.agent.session.Session session,
                                 GoalState goal, GoalState.Status status, String recap) {
        try {
            goal.markTerminal(status, recap);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        GoalState.store(session, goal);
        sessionManager.saveSession(session);
        return ToolResult.success(String.format("Goal marked %s. Recap:%n%s",
                status.name().toLowerCase(Locale.ROOT), recap));
    }

    @Override
    public Set<String> getScopes() {
        return Set.of(SCOPE_CORE);
    }
}
