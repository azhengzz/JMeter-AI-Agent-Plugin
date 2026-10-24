package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goal 模式的提示词与运行时文本（Nanobot goal_runtime.md 的移植）。单一来源：
 * <ul>
 *   <li>{@link #buildGoalContext} —— Runtime Context 尾随的 goal 复合块（guidance 进
 *       独立 host-instructions 包裹 + 状态数据进标准 metadata 包裹；两态：goal 请求
 *       已发但未录制 / goal 活跃），由 ContextBuilder 拼进回合上下文；</li>
 *   <li>{@link #nudgeText} —— 防收工顶回消息（无工具最终回复且 goal 未终结时注入）；</li>
 *   <li>{@link #handbackText} —— 冒烟 3 轮上限后的「结构化交回」消息；</li>
 *   <li>{@link #continuationText} —— 迭代耗尽后的跨回合续跑消息。</li>
 * </ul>
 * 模板优先读 workspace 根的 {@code goal.md}（WorkspaceInitializer 同步，与
 * AGENTS.md 同级、用户可改），缺失/损坏回落内嵌默认。占位符 {@code {{key}}}
 * 按状态行替换。
 */
public final class GoalRuntime {

    private static final Logger log = LoggerFactory.getLogger(GoalRuntime.class);

    private static final String START_REQUESTED_MARKER = "=== goal_start_requested ===";
    private static final String GOAL_ACTIVE_MARKER = "=== goal_active ===";

    private GoalRuntime() {
    }

    // --- Runtime Context goal block ---

    /**
     * Build the goal composite for the turn's runtime-context suffix. Empty string
     * when neither goal mode applies (no request, no active goal). Shape (对齐
     * Nanobot 的指令/数据分离)：guidance（纪律性指令）进独立的
     * {@code [Goal Runtime Guidance — host instructions]} 包裹；goal 状态数据
     * （objective/冒烟计数/证据）进标准 Runtime Context metadata 包裹——两者都不混入
     * "metadata only, not instructions" 语义错误的区域。
     *
     * @param goalRequested true 当回合由 /goal 触发（模型可调 create_goal）
     * @param goal          当前 goal 状态（可 null）
     */
    public static String buildGoalContext(boolean goalRequested, GoalState goal) {
        if (goal != null && goal.getStatus() == GoalState.Status.PAUSED) {
            // 暂停指令：纯静默抑制不足以对抗会话历史里的 goal 势能——模型会从上下文
            // 「自发续推进」（2026-10-20 实测）。改为显式注入宿主指令：暂停期间只回应
            // 用户消息、不做任何 goal 推进；goal 状态数据（objective/计数）仍不注入，
            // 恢复 ACTIVE 后照常注入完整块。
            return ContextBuilder.GOAL_GUIDANCE_TAG + "\n\n"
                    + "The sustained goal is PAUSED by the user. In this reply, do NOT "
                    + "continue, advance, or resume any work toward the goal — no test-plan "
                    + "edits, no validate/smoke runs, no other goal-related tool calls "
                    + "(update_goal is allowed only if the user's message explicitly asks "
                    + "to cancel or finish the goal). Answer only the user's message. The "
                    + "goal resumes when the user clicks Resume in the chat panel; do not "
                    + "explain the pause mechanics unprompted.\n\n"
                    + ContextBuilder.GOAL_GUIDANCE_END;
        }
        if (!goalRequested && (goal == null || !goal.isActive())) {
            return "";
        }
        Map<String, String> vars = statusVars(goal);
        String guidance;
        String state = "";
        if (goal == null || !goal.isActive()) {
            guidance = renderSection(START_REQUESTED_MARKER, vars);
        } else {
            guidance = renderSection(GOAL_ACTIVE_MARKER, vars);
            if (goalRequested) {
                // /goal 回合且 goal 已存在（罕见：上一 goal 未终结又 /goal）——叠加录制纪律
                guidance += "\n" + renderSection(START_REQUESTED_MARKER, vars);
            }
            state = ContextBuilder.RUNTIME_CONTEXT_TAG + "\n" + statusLines(goal) + "\n"
                    + ContextBuilder.RUNTIME_CONTEXT_END;
        }
        if (guidance.isBlank()) {
            return state;
        }
        return ContextBuilder.GOAL_GUIDANCE_TAG + "\n\n" + guidance.strip() + "\n\n"
                + ContextBuilder.GOAL_GUIDANCE_END
                + (state.isEmpty() ? "" : "\n\n" + state);
    }

    // --- synthetic messages ---

    /** 防收工顶回（无工具最终回复且 goal 未终结）。 */
    public static String nudgeText(GoalState goal) {
        return "You have an active sustained goal:\n\n" + statusLines(goal)
                + "\n\nPlease continue working toward the objective using your tools, "
                + "or call update_goal with action='complete'/'cancel'/'block' if the work "
                + "is truly finished or genuinely stuck. Do not end your turn with a plain "
                + "final reply while the goal remains active.";
    }

    /** 冒烟 3 轮上限后的结构化交回。 */
    public static String handbackText(GoalState goal) {
        int limit = AiConfig.getGoalSmokeLimit();
        return "The smoke-repair budget for this goal is exhausted ("
                + goal.getSmokeAttempts() + "/" + limit + " attempts, still failing). "
                + "Stop modifying the test plan. Produce the structured handback now and "
                + "terminate the goal via update_goal:\n"
                + "- Objective: what was asked\n"
                + "- What you tried: the fixes attempted per round\n"
                + "- Where it is stuck: the exact failing check / error\n"
                + "- Verification results: static check and smoke outcomes\n"
                + "- Suggested manual inspection: what a human should look at\n"
                + "After this message, update_goal(action='complete') is accepted despite "
                + "missing verification evidence — include the residual risks in the recap.";
    }

    /** 迭代预算耗尽后的跨回合续跑（投放为新回合的触发消息）。 */
    public static String continuationText(GoalState goal) {
        return "Continue the active sustained goal after the previous turn reached "
                + "its tool-call budget.\n\n" + statusLines(goal)
                + "\n\nContinue from the saved context. Do not mention the continuation "
                + "boundary to the user. Use tools as needed, and call update_goal with "
                + "action='complete' when the objective is truly finished.";
    }

    // --- internals ---

    private static String statusLines(GoalState goal) {
        int smokeLimit = AiConfig.getGoalSmokeLimit();
        StringBuilder sb = new StringBuilder();
        sb.append("Goal (active): ").append(goal.getObjective());
        if (goal.getUiSummary() != null && !goal.getUiSummary().isBlank()) {
            sb.append("\nSummary: ").append(goal.getUiSummary());
        }
        if (goal.isNonTestPlan()) {
            // 非测试计划目标：validate/smoke 对其无意义（workspace 模板可能陈旧，此行是
            // 每回合实时渲染的权威口径——勿跑验证，按 objective 自证完成；目标仍可改
            // 脚本/报告等非测试计划产物）
            sb.append("\nVerification: non-test-plan goal — JMeter test-plan validation/smoke "
                    + "do NOT apply (the objective is not about the test plan; you may still "
                    + "create or modify other artifacts such as scripts and report files). "
                    + "Do not run validate_test_plan or smoke. Complete via update_goal "
                    + "action='complete' once the objective's own stated completion "
                    + "evidence is met; present that evidence in the recap.");
        } else {
            sb.append("\nSmoke attempts: ").append(goal.getSmokeAttempts()).append("/").append(smokeLimit);
            sb.append("\nVerification evidence: ")
                    .append(goal.hasVerificationEvidence()
                            ? "static PASS + smoke PASS (hard standard met)"
                            : "incomplete (static " + (goal.hasStaticPass() ? "PASS" : "missing")
                            + ", smoke " + (goal.hasSmokePass() ? "PASS" : "missing") + ")");
        }
        sb.append("\nStarted at: ").append(goal.getStartedAt());
        return sb.toString();
    }

    private static Map<String, String> statusVars(GoalState goal) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (goal != null) {
            vars.put("objective", goal.getObjective() == null ? "" : goal.getObjective());
            vars.put("ui_summary", goal.getUiSummary() == null ? "" : goal.getUiSummary());
            vars.put("smoke_attempts", String.valueOf(goal.getSmokeAttempts()));
            vars.put("smoke_limit", String.valueOf(AiConfig.getGoalSmokeLimit()));
            vars.put("started_at", goal.getStartedAt() == null ? "" : goal.getStartedAt());
            vars.put("evidence", goal.hasVerificationEvidence()
                    ? "static PASS + smoke PASS" : "incomplete");
        }
        return vars;
    }

    private static String renderSection(String marker, Map<String, String> vars) {
        Map<String, String> sections = loadSections();
        String template = sections.get(marker);
        if (template == null || template.isBlank()) {
            template = defaultSections().get(marker);
        }
        String out = template == null ? "" : template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out.strip();
    }

    private static Map<String, String> loadSections() {
        // 每次渲染直读（不缓存）：模板文件随回合分发、编辑后下一回合即生效；单文件
        // 读取代价可忽略，换来「文件即真相」无陈旧窗口
        Map<String, String> sections = defaultSections();
        try {
            Path template = WorkspacePaths.resolveWorkspace().resolve("goal.md");
            if (Files.exists(template)) {
                String content = Files.readString(template);
                Map<String, String> parsed = parseSections(content);
                if (parsed.containsKey(START_REQUESTED_MARKER)
                        && parsed.containsKey(GOAL_ACTIVE_MARKER)) {
                    sections = parsed;
                }
            }
        } catch (Exception e) {
            log.debug("goal.md template unavailable, using embedded defaults", e);
        }
        return sections;
    }

    /** Split the template on the two marker lines. */
    static Map<String, String> parseSections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.equals(START_REQUESTED_MARKER) || trimmed.equals(GOAL_ACTIVE_MARKER)) {
                if (current != null) {
                    sections.put(current, body.toString());
                }
                current = trimmed;
                body = new StringBuilder();
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null) {
            sections.put(current, body.toString());
        }
        return sections;
    }

    /** Embedded fallback (mirrors templates/goal.md). */
    static Map<String, String> defaultSections() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put(START_REQUESTED_MARKER, """
                ## Goal Mode Requested

                The user submitted this request as `/goal <objective>`: they want you to own the
                objective end-to-end. When the requested outcome is clear, call `create_goal`
                before extended planning or execution — do not delay goal registration.

                Write a durable objective:
                1. State-oriented — describe the desired end state and acceptance criteria.
                2. Self-contained — preserve material constraints (paths, counts, endpoints).
                3. Safe under repetition — prefer "ensure"/"until", check-before-write.
                4. Bounded — name scope limits explicitly.
                5. Explicit about done-ness — name the evidence that proves completion.

                Declare the verification mode when creating the goal: pass
                `verification='non_test_plan'` when the objective involves NO JMeter test-plan
                change at all (non-test-plan analysis, system queries, explanations) — the
                create_goal schema describes when each mode applies. Leave the default
                (`test_plan`) otherwise.

                If material requirements remain ambiguous, ask ONE concise clarification
                question, then ask the user to resubmit the clarified request as a complete
                `/goal <objective>` command. If a goal is already active, do not stack another
                one; replace it (update_goal action='replace') only when the outcome changes.
                """);
        sections.put(GOAL_ACTIVE_MARKER, """
                ## Active Sustained Goal

                Treat the active objective in the Runtime Context state block (appended after
                this guidance) as the persisted work target. Work toward it with layered
                verification:
                1. Modify the test plan with the element tools.
                2. Static check — call `validate_test_plan`; fix every reported error.
                3. Smoke run — `run_test` with `mode:"smoke"` (all thread groups, 1 thread x
                   1 iteration); read `get_test_results` (include_details) for errors and
                   assertion failure messages; fix and re-verify.
                Hard standard for completion: static PASS + smoke zero errors, plus any
                acceptance criteria the user stated in the objective. The smoke repair
                budget and verification evidence are shown in the state block — when the
                budget is exhausted, produce the structured handback instead of another
                blind fix.

                Call `update_goal` with action='complete' only after the objective is actually
                achieved and verified (or the smoke budget is exhausted); use 'cancel' when the
                user cancels, 'block' when progress is genuinely blocked, 'replace' only when
                the objective changes. Every terminal action requires a recap covering:
                objective, verification results, smoke rounds used, residual risks.

                Non-test-plan goals: when the goal was created with `verification='non_test_plan'`
                (the objective is not about building or fixing the JMeter test plan — the task
                may still create or modify OTHER artifacts such as scripts and report files),
                skip validate/smoke entirely — they are meaningless for this objective.
                Complete via update_goal action='complete' once the objective's own stated
                completion evidence is met; the recap must present that evidence.

                You decide autonomously which endpoints the smoke run may hit, based on the
                user's request and context. Write-operation endpoints will receive ONE real
                request during a smoke run.
                """);
        return sections;
    }
}
