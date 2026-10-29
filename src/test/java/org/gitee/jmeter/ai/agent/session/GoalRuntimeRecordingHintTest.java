package org.gitee.jmeter.ai.agent.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务 6.1：goal 状态块携带浏览器录制引用入口（目录指引 + 人工驱动边界），
 * 录制本身不进 goal 自主循环。
 */
class GoalRuntimeRecordingHintTest {

    @Test
    void activeGoalContextCarriesRecordingsPointer() {
        GoalState goal = GoalState.create("enhance the latest browser recording", null);
        String context = GoalRuntime.buildGoalContext(true, goal);
        assertTrue(context.contains("Browser recordings"), context);
        assertTrue(context.contains("recordings"), "must name the recordings directory");
        assertTrue(context.contains("events.jsonl"), "must point at the readable data file");
        assertTrue(context.contains("human-driven"), "must state the human-driven boundary");
        assertTrue(context.contains("16KB"), "must carry the body-truncation guidance");
    }

    @Test
    void nudgeAndContinuationAlsoCarryPointer() {
        GoalState goal = GoalState.create("obj", null);
        assertTrue(GoalRuntime.nudgeText(goal).contains("Browser recordings"));
        assertTrue(GoalRuntime.continuationText(goal).contains("Browser recordings"));
    }
}
