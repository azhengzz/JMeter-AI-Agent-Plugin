package org.gitee.jmeter.ai.agent.tools.goal;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.util.Map;
import java.util.Set;

/**
 * Registers the sustained goal for the current session (Nanobot create_goal). Only
 * turns triggered by {@code /goal <objective>} hold the mutation permission —
 * anything else is rejected with a hint to resubmit via /goal. Goal state persists
 * in the session jsonl metadata header and survives /new.
 */
public class CreateGoalTool extends AbstractTool {

    private final SessionManager sessionManager;

    public CreateGoalTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String getName() {
        return "create_goal";
    }

    @Override
    public String getDescription() {
        return "Record the sustained goal for this session, derived from the user's /goal request. "
                + "Call it when the requested outcome is clear, before extended planning or execution. "
                + "The objective must be self-contained, bounded, safe under repetition, and explicit "
                + "about the evidence that proves completion. Only available on /goal-triggered turns.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "objective": {
                            "type": "string",
                            "description": "Durable objective text (max %d chars): desired end state, acceptance criteria, completion evidence",
                            "maxLength": %d
                        },
                        "ui_summary": {
                            "type": "string",
                            "description": "One-line summary for UI display (max 120 chars)",
                            "maxLength": 120
                        },
                        "verification": {
                            "type": "string",
                            "enum": ["test_plan", "non_test_plan"],
                            "description": "Verification mode. 'test_plan' (default): the objective is about building or fixing the JMeter test plan — completing later requires validate_test_plan PASS plus a smoke run with zero errors. 'non_test_plan': the objective is NOT about the JMeter test plan (system queries, analysis, report writing, etc. — the task may still create or modify OTHER artifacts such as scripts and report files); test-plan validation/smoke are meaningless for it — complete once the objective's own stated completion evidence is met instead."
                        }
                    },
                    "required": ["objective"]
                }
                """.formatted(GoalState.MAX_OBJECTIVE_CHARS, GoalState.MAX_OBJECTIVE_CHARS);
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        AgentRunContext runContext = AgentRunContext.current();
        if (runContext == null || !runContext.isGoalRequested()) {
            return ToolResult.error("create_goal is unavailable for this turn. "
                    + "Ask the user to submit the complete objective as `/goal <objective>`.");
        }
        if (org.gitee.jmeter.ai.instance.DelegationGuard.isActive()) {
            // 委派回合是宿主会话之外的独立任务，无权代宿主录制 goal（对齐 update_goal 的门）
            return ToolResult.error("create_goal is unavailable on delegated turns: the goal "
                    + "belongs to the host session.");
        }
        String objective = getStringParameter(parameters, "objective", "");
        String uiSummary = GoalState.truncateUtf16Safe(
                getStringParameter(parameters, "ui_summary", ""), GoalState.MAX_UI_SUMMARY_CHARS);

        GoalState goal;
        try {
            goal = GoalState.create(objective, uiSummary);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        String verification = getStringParameter(parameters, "verification", "test_plan")
                .toLowerCase(java.util.Locale.ROOT);
        if ("non_test_plan".equals(verification)) {
            // 非测试计划目标：validate/smoke 对其无意义，complete 免测试计划证据
            goal.markNonTestPlan();
        } else if (!"test_plan".equals(verification)) {
            return ToolResult.error("Unknown verification mode '" + verification
                    + "'. Use 'test_plan' (default) or 'non_test_plan'.");
        }

        var session = sessionManager.getOrCreate(runContext.getSessionKey());
        GoalState existing = GoalState.load(session);
        if (existing != null && existing.isActive()) {
            return ToolResult.error("A sustained goal is already active for this session. "
                    + "Use update_goal with action='replace' if the requested outcome actually changed, "
                    + "or finish/cancel the current goal first.");
        }
        if (existing != null && existing.getStatus() == GoalState.Status.PAUSED) {
            // 暂停中的 goal 不被静默覆盖：新目标会丢弃被暂停的 objective，先恢复或终结。
            // 显式封侧门：replace 同样能达成静默替换，错误口径与工具门互指 cancel
            return ToolResult.error("A sustained goal is currently paused for this session. "
                    + "Ask the user to resume it from the chat panel (the ▶ button) to continue it, "
                    + "or to terminate it via update_goal(action='cancel') before submitting "
                    + "a new /goal request. Do NOT use update_goal(action='replace') to swap "
                    + "a paused goal — cancel it first.");
        }

        GoalState.store(session, goal);
        sessionManager.saveSession(session);
        return ToolResult.success("Goal recorded. Keep working toward the objective using ordinary "
                + "tools (layered verification: validate_test_plan, then run_test mode=smoke). "
                + "When fully done and verified, call update_goal with action='complete'.");
    }

    @Override
    public Set<String> getScopes() {
        return Set.of(SCOPE_CORE);
    }
}
