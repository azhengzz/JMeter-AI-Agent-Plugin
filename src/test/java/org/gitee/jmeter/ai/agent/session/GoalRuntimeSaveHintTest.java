package org.gitee.jmeter.ai.agent.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * goal 状态块携带「验证通过后、update_goal(complete) 之前由模型自己调 save_test_plan」
 * 的说明。该说明由代码侧实时渲染（statusLines），workspace 的 goal.md 模板陈旧时同样
 * 生效；只对 test_plan 目标渲染——non_test_plan 目标不以测试计划为对象，不存回。
 */
class GoalRuntimeSaveHintTest {

    /** 状态块里该说明的行首标记（区别于引导文本里的近似措辞）。 */
    private static final String HINT = "Plan persistence:";

    @Test
    void testPlanGoalContextCarriesSaveBackHint() {
        GoalState goal = GoalState.create("make the login flow pass the smoke run", null);
        String context = GoalRuntime.buildGoalContext(true, goal);
        assertTrue(context.contains(HINT), context);
        assertTrue(context.contains("call save_test_plan yourself BEFORE update_goal(complete)"), context);
        assertTrue(GoalRuntime.nudgeText(goal).contains(HINT));
        assertTrue(GoalRuntime.continuationText(goal).contains(HINT));
    }

    @Test
    void nonTestPlanGoalContextOmitsSaveBackHint() {
        GoalState goal = GoalState.create("explain the thread group settings", null);
        goal.markNonTestPlan();
        String context = GoalRuntime.buildGoalContext(true, goal);
        assertFalse(context.contains(HINT), context);
    }
}
