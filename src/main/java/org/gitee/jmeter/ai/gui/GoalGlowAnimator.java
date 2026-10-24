package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.gui.theme.ThemeColors;

import javax.swing.Timer;
import java.awt.Color;

/**
 * goal 呼吸光晕动画器（goal-status-ui）：ACTIVE 时输入卡外围呈「涟漪 + 扫掠」双动画
 * ——三圈呼吸环 alpha 在 [0.28, 0.60] 内正弦起伏（80ms 帧、周期 ~1.05s）且相位错开，
 * 读作一道向内传播的明暗涟漪；叠加一道绕边框匀速旋转的扫掠高亮（6°/帧 ≈ 4.8s 一圈，
 * 宿主用沿直径的 LinearGradientPaint 渲染成头亮尾隐的流光弧）。BLOCKED 为静态琥珀环
 * （<b>Timer 停</b>，零动画成本，无涟漪无扫掠）；PAUSED 与终态走 {@link #off()} 熄灭。
 *
 * <p>纯逻辑 + Swing Timer 包装，独立成类使 paint 数学可脱离组件单测。
 * {@code ringAlpha()/sweepAngle()/glowColor()} 供宿主（AiChatPanel 经
 * {@link GoalGlowPainter}）在 paint 时实时读取——颜色经 {@link ThemeColors} 每次
 * 调用现取，LAF 切换自适应。动画只 repaint 宿主光晕带（构造注入 {@code onTick}），
 * 绝不 revalidate。单计数器 {@code ticks} 同时驱动呼吸正弦（按周期取模）与扫掠角
 * （按步长累加），停表保留相位——OFF/恢复不产生可见相位漂移。
 */
class GoalGlowAnimator {

    private static final int FRAME_DELAY_MS = 80;
    /** 呼吸周期（帧数）：13 帧 × 80ms ≈ 1.05s。 */
    private static final int PERIOD_FRAMES = 13;
    private static final float ALPHA_BASE = 0.28f;
    private static final float ALPHA_SWING = 0.32f;
    private static final float BLOCKED_ALPHA = 0.30f;
    /** 相邻呼吸环的相位错开帧数（涟漪感：明暗波沿圈序传播而非齐脉动）。 */
    private static final int RING_PHASE_OFFSET = 2;
    /** 扫掠角速度：6°/帧 × 80ms ≈ 4.8s 一圈。 */
    private static final float SWEEP_STEP_RADIANS = (float) (Math.PI / 30);
    /** 一整圈的帧数（6° × 60 = 360°；扫掠角取模防长会话浮点精度劣化）。 */
    private static final int SWEEP_FRAMES_PER_LAP = 60;

    enum Mode {
        OFF, BREATHING, STATIC_BLOCKED
    }

    private final Runnable onTick;
    /** 模式边沿回调（宿主注册一次）：仅 setMode 实际迁移时同步调用，同态幂等不触发。 */
    private Runnable modeListener;
    private Timer timer;
    private Mode mode = Mode.OFF;
    /** 总帧计数：呼吸正弦按 PERIOD_FRAMES 取模，扫掠角按 SWEEP_FRAMES_PER_LAP 取模。 */
    private int ticks;
    /** 销毁粘位（面板 cleanup 后置位）：此后一切模式请求是空操作，迟到的快照/显隐桥不得复活 Timer。 */
    private boolean disposed;

    GoalGlowAnimator(Runnable onTick) {
        this.onTick = onTick;
    }

    /** ACTIVE：呼吸动画（Timer 运行）。 */
    void setGoalActive() {
        setMode(Mode.BREATHING);
    }

    /** BLOCKED：静态琥珀环（Timer 停，零动画成本）。 */
    void setGoalBlocked() {
        setMode(Mode.STATIC_BLOCKED);
    }

    /** PAUSED / 终态 / 清除：熄灭。 */
    void off() {
        setMode(Mode.OFF);
    }

    /** 释放 Timer（面板 cleanup；幂等且不可逆——置销毁粘位，此后 setMode 全部空操作）。 */
    void dispose() {
        disposed = true;
        stopTimer();
        mode = Mode.OFF;
    }

    /**
     * 注册模式边沿回调：每次 {@link #setMode} 实际发生状态迁移（OFF ⟷ 非 OFF 等）
     * 时同步调用一次，同态幂等重复不触发。零布局方案下宿主的唯一职责是对光晕带
     * 做区域 repaint——点亮沿画上第一帧光环、熄灭沿复用同一 repaint 清残留像素；
     * 回调里 MUST NOT 调整布局或 revalidate（点亮/熄灭不改任何布局尺寸是零布局
     * 光晕的不变式）。须在 EDT 注册与触发。{@link #dispose()} 直接置位不经
     * setMode，不触发——teardown 场景宿主在 dispose 后对光晕带自清一次。
     */
    void onModeChange(Runnable listener) {
        this.modeListener = listener;
    }

    /**
     * 第 {@code ring} 圈呼吸环的透明度：BREATHING 在 [0.28, 0.60] 内正弦起伏且各圈
     * 相位错开（涟漪）；STATIC_BLOCKED 恒为静态琥珀；OFF 为 0。
     */
    float ringAlpha(int ring) {
        return switch (mode) {
            case OFF -> 0f;
            case STATIC_BLOCKED -> BLOCKED_ALPHA;
            case BREATHING -> {
                // 相位先按周期取模再进浮点：int→float 仅 24 位有效数字，裸 ticks 累积超过
                // 2^24 后相邻帧坍缩为同一浮点数，呼吸会劣化成卡顿（扫掠侧同款取模已先行）。
                // floorMod 防负——减去环相位错开后可为负，Java 的 % 会保留负号；取模只增减
                // 整周期，正弦结果不变，纯长会话防劣化。
                int phaseFrame = Math.floorMod(ticks - ring * RING_PHASE_OFFSET, PERIOD_FRAMES);
                float phase = phaseFrame * 2f * (float) Math.PI / PERIOD_FRAMES;
                float wave = 0.5f + 0.5f * (float) Math.sin(phase);
                yield ALPHA_BASE + ALPHA_SWING * wave;
            }
        };
    }

    /** 扫掠高亮仅在 BREATHING 绘制（BLOCKED 静态零动画、OFF 熄灭）。 */
    boolean sweeping() {
        return mode == Mode.BREATHING;
    }

    /** 扫掠头当前方位角（弧度，[0, 2π)；6°/帧匀速旋转）。 */
    float sweepAngle() {
        return (ticks % SWEEP_FRAMES_PER_LAP) * SWEEP_STEP_RADIANS;
    }

    /** paint 时实时读：ACTIVE→info 蓝、BLOCKED→warning 琥珀。 */
    Color glowColor() {
        return mode == Mode.STATIC_BLOCKED ? ThemeColors.warning() : ThemeColors.info();
    }

    boolean isAnimating() {
        return timer != null && timer.isRunning();
    }

    Mode mode() {
        return mode;
    }

    /** 测试钩子：步进一帧（与 Timer action 同款推进）。 */
    void advanceFrameForTest() {
        ticks++;
    }

    private void setMode(Mode next) {
        if (disposed || mode == next) {
            return; // 销毁粘位：cleanup 后迟到的模式迁移不复活 Timer；同态幂等（含 BREATHING：Timer 已在跑）
        }
        mode = next;
        switch (next) {
            case BREATHING -> {
                if (timer == null) {
                    timer = new Timer(FRAME_DELAY_MS, e -> {
                        ticks++;
                        onTick.run();
                    });
                }
                if (!timer.isRunning()) {
                    timer.start();
                }
            }
            case STATIC_BLOCKED, OFF -> stopTimer();
        }
        if (modeListener != null) {
            modeListener.run(); // 零布局：仅触发宿主对光晕带区域 repaint（熄灭沿同此清残影）
        }
        onTick.run(); // 状态切换立即重绘一次（静态环也要上屏）
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
        }
    }
}
