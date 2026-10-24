package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalGlowAnimator 契约：ACTIVE「涟漪 + 扫掠」双动画（Timer 跑、各环 alpha 正弦
 * ∈ [0.28, 0.60]、相邻环相位错开非齐脉动、扫掠角 6°/帧匀速前进）、BLOCKED 静态
 * 琥珀（alpha 恒定、无扫掠、Timer 停）、off/dispose 熄灭、模式边沿回调仅真实
 * 状态迁移触发（同态幂等与 dispose 均不触发）。
 */
class GoalGlowAnimatorTest {

    private GoalGlowAnimator animator;

    @AfterEach
    void dispose() {
        if (animator != null) {
            animator.dispose();
        }
    }

    @Test
    void activeBreathesWithinSinusoidalRange() {
        animator = new GoalGlowAnimator(() -> { });
        animator.setGoalActive();

        assertTrue(animator.isAnimating(), "ACTIVE runs the animation timer");
        assertEquals(GoalGlowAnimator.Mode.BREATHING, animator.mode());
        assertTrue(animator.sweeping(), "ACTIVE draws the sweep highlight");

        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        for (int i = 0; i < 13; i++) { // 一个完整呼吸周期
            float alpha = animator.ringAlpha(0);
            assertTrue(alpha >= 0.28f - 1e-6 && alpha <= 0.60f + 1e-6,
                    "alpha must stay within [0.28, 0.60]: " + alpha);
            min = Math.min(min, alpha);
            max = Math.max(max, alpha);
            animator.advanceFrameForTest();
        }
        assertTrue(max - min > 0.15f, "the breathing amplitude must be visible: " + min + ".." + max);
    }

    @Test
    void ringsRippleWithPhaseOffset() {
        animator = new GoalGlowAnimator(() -> { });
        animator.setGoalActive();

        float maxSpread = 0f;
        for (int i = 0; i < 13; i++) {
            float spread = Math.abs(animator.ringAlpha(0) - animator.ringAlpha(2));
            maxSpread = Math.max(maxSpread, spread);
            animator.advanceFrameForTest();
        }
        assertTrue(maxSpread > 0.05f,
                "rings must be phase-offset (ripple), not pulse in lockstep: " + maxSpread);
    }

    @Test
    void sweepAdvancesUniformlyAtSixDegreesPerTick() {
        animator = new GoalGlowAnimator(() -> { });
        animator.setGoalActive();

        assertEquals(0f, animator.sweepAngle(), 1e-6f);
        animator.advanceFrameForTest();
        assertEquals((float) (Math.PI / 30), animator.sweepAngle(), 1e-3f,
                "one tick advances exactly 6° (frozen or 12°/tick regressions must fail)");
        for (int i = 1; i < 30; i++) {
            animator.advanceFrameForTest();
        }
        assertEquals((float) Math.PI, animator.sweepAngle(), 1e-3f,
                "30 ticks = half lap (early-wrapping lap constants must fail)");
        for (int i = 30; i < 60; i++) {
            animator.advanceFrameForTest();
        }
        assertEquals(0f, animator.sweepAngle(), 1e-3f,
                "60 ticks complete exactly one lap (modulo must wrap)");
    }

    @Test
    void blockedIsStaticAmberWithoutAnimation() {
        animator = new GoalGlowAnimator(() -> { });
        animator.setGoalActive();
        assertTrue(animator.isAnimating());

        animator.setGoalBlocked();
        assertEquals(GoalGlowAnimator.Mode.STATIC_BLOCKED, animator.mode());
        assertFalse(animator.isAnimating(), "BLOCKED uses a static ring — the timer stops");
        assertFalse(animator.sweeping(), "BLOCKED carries no sweep highlight");
        assertEquals(animator.ringAlpha(0), animator.ringAlpha(1), 1e-6,
                "BLOCKED rings are uniform (no ripple)");
        float first = animator.ringAlpha(0);
        animator.advanceFrameForTest();
        assertEquals(first, animator.ringAlpha(0), "static ring alpha never moves");
        assertEquals(0.30f, first, 1e-6);
        assertEquals(org.gitee.jmeter.ai.gui.theme.ThemeColors.warning(), animator.glowColor());
    }

    @Test
    void offAndDisposeStopEverything() {
        animator = new GoalGlowAnimator(() -> { });
        animator.setGoalActive();
        assertTrue(animator.isAnimating());

        animator.off();
        assertEquals(GoalGlowAnimator.Mode.OFF, animator.mode());
        assertFalse(animator.isAnimating());
        assertEquals(0f, animator.ringAlpha(0));
        assertFalse(animator.sweeping());

        animator.setGoalActive();
        animator.dispose();
        assertFalse(animator.isAnimating(), "dispose frees the timer");
        assertEquals(0f, animator.ringAlpha(0));
    }

    @Test
    void modeListenerFiresOnlyOnRealTransitions() {
        animator = new GoalGlowAnimator(() -> { });
        int[] fires = {0};
        animator.onModeChange(() -> fires[0]++);
        animator.setGoalActive();
        assertEquals(1, fires[0], "OFF→BREATHING edge fires once");
        animator.setGoalActive();
        assertEquals(1, fires[0], "same-mode repeats are idempotent");
        animator.setGoalBlocked();
        assertEquals(2, fires[0], "BREATHING→STATIC_BLOCKED edge fires");
        animator.off();
        assertEquals(3, fires[0], "→OFF edge fires (host repaints the glow band to clear residue)");
        animator.dispose();
        assertEquals(3, fires[0], "dispose bypasses setMode: no fire on teardown");
    }

    @Test
    void disposeIsStickyAgainstLaterModeRequests() {
        animator = new GoalGlowAnimator(() -> { });
        animator.setGoalActive();
        assertTrue(animator.isAnimating());

        animator.dispose();
        assertFalse(animator.isAnimating(), "dispose frees the timer");

        // 面板 cleanup 后迟到的快照/显隐桥不得复活 Timer（dispose 必须不可逆）
        animator.setGoalActive();
        animator.setGoalBlocked();
        animator.off();
        assertEquals(GoalGlowAnimator.Mode.OFF, animator.mode(), "post-dispose mode stays OFF");
        assertFalse(animator.isAnimating(), "post-dispose mode requests must not revive the timer");
        assertEquals(0f, animator.ringAlpha(0), "post-dispose paint math stays dark");
        assertFalse(animator.sweeping(), "post-dispose sweep stays dark");
    }
}
