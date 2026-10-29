package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.agent.session.GoalState;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalStrip 状态机与交互契约：五态呈现与按钮互斥、终态链（HOLD→FADING→HIDDEN）、
 * 复活中断、去重 repaint-only、展开 clamp、降级文本、dispose 计时器清理。
 * 计时器经手动步进桩注入（无真实时序依赖）。
 */
class GoalStripTest {

    /** 手动步进计时器：记录启停与 delay、由测试显式 fire。 */
    private static final class ManualTimer implements GoalStrip.StripTimer {
        final int delayMs;
        boolean running;
        final Runnable action;

        ManualTimer(int delayMs, Runnable action) {
            this.delayMs = delayMs;
            this.action = action;
        }

        @Override public void start() {
            running = true;
        }

        @Override public void stop() {
            running = false;
        }

        @Override public boolean isRunning() {
            return running;
        }

        void fire() {
            if (running) {
                action.run();
            }
        }
    }

    private final List<ManualTimer> timers = new ArrayList<>();
    private final List<String> actionLog = new ArrayList<>();

    private static final int HOLD_MS = 1500;
    private static final int FADE_MS = 20;
    private static final int BREATH_MS = 80;

    private final GoalStrip.GoalActions actions = new GoalStrip.GoalActions() {
        @Override public void onPauseRequested() {
            actionLog.add("pause");
        }

        @Override public void onResumeRequested() {
            actionLog.add("resume");
        }
    };

    private GoalStrip strip;
    private final AtomicInteger hostHeight = new AtomicInteger(400);

    @BeforeEach
    void buildStrip() throws Exception {
        edt(() -> {
            timers.clear();
            actionLog.clear();
            strip = new GoalStrip(actions, (delayMs, action) -> {
                ManualTimer timer = new ManualTimer(delayMs, action);
                timers.add(timer);
                return timer;
            }, hostHeight::get);
        });
    }

    @AfterEach
    void disposeStrip() throws Exception {
        edt(strip::dispose);
    }

    private static void edt(Runnable body) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
        } else {
            SwingUtilities.invokeAndWait(body);
        }
    }

    /** 按 delay 查找计时器（hold/fade/breath 各一；呼吸表加入后索引会漂移）。 */
    private ManualTimer timerOf(int delayMs) {
        return timers.stream()
                .filter(t -> t.delayMs == delayMs)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no timer with delay " + delayMs));
    }

    private static GoalState.GoalSnapshot snapshot(GoalState.Status status, String summary,
            String objective, int smoke, int rounds) {
        boolean terminal = status == GoalState.Status.COMPLETED
                || status == GoalState.Status.CANCELLED;
        return new GoalState.GoalSnapshot(status, summary,
                objective == null ? "objective body" : objective,
                terminal ? "recap of " + status : null,
                smoke, rounds, "2026-10-19T10:00:00", "s1");
    }

    // --- 呈现与按钮互斥 ---

    @Test
    void activeSnapshotShowsStripWithPauseButtonOnly() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "login goal", null, 1, 2)));
        edt(() -> {
            assertTrue(strip.isVisible(), "ACTIVE makes the strip visible");
            assertEquals(GoalStrip.Phase.ACTIVE, strip.phase());
            assertTrue(strip.pauseButtonVisible(), "⏸ visible only while ACTIVE");
            assertFalse(strip.resumeButtonVisible());
            assertTrue(strip.chevronButtonVisible());
            assertTrue(strip.getToolTipText().contains("Smoke attempts: 1/3"),
                    "冒烟计数带上限口径（对齐模型侧 statusLines 的 X/上限）");
            assertTrue(strip.getToolTipText().contains("Continuation rounds: 2/"));
        });
    }

    @Test
    void pausedShowsGreyWithResumeButtonOnly() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.PAUSED, "s", null, 0, 0)));
        edt(() -> {
            assertEquals(GoalStrip.Phase.PAUSED, strip.phase());
            assertFalse(strip.pauseButtonVisible(), "⏸ hidden while PAUSED");
            assertTrue(strip.resumeButtonVisible(), "▶ visible only while PAUSED");
        });
        edt(() -> assertEquals(ThemeColors.secondaryText(),
                GoalStrip.styleFor(GoalState.Status.PAUSED).color(),
                "PAUSED reads as de-emphasized grey"));
    }

    @Test
    void blockedShowsAmberWithNoActionButtons() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.BLOCKED, "s", null, 0, 0)));
        edt(() -> {
            assertEquals(GoalStrip.Phase.BLOCKED, strip.phase());
            assertFalse(strip.pauseButtonVisible());
            assertFalse(strip.resumeButtonVisible(), "BLOCKED carries no action button");
            assertTrue(strip.chevronButtonVisible(), "BLOCKED stays expandable");
        });
        edt(() -> assertEquals(ThemeColors.warning(),
                GoalStrip.styleFor(GoalState.Status.BLOCKED).color()));
    }

    @Test
    void statusColorMapping() {
        assertEquals(ThemeColors.info(), GoalStrip.styleFor(GoalState.Status.ACTIVE).color());
        assertEquals(ThemeColors.success(), GoalStrip.styleFor(GoalState.Status.COMPLETED).color());
        assertEquals(ThemeColors.error(), GoalStrip.styleFor(GoalState.Status.CANCELLED).color());
    }

    // --- 终态链与复活中断 ---

    @Test
    void terminalChainHoldsFadesThenHides() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.COMPLETED, "s", null, 0, 0)));
        ManualTimer hold = timerOf(HOLD_MS);
        edt(() -> {
            assertEquals(GoalStrip.Phase.TERMINAL_HOLD, strip.phase());
            assertTrue(strip.isHoldTimerRunning());
            assertTrue(strip.isVisible());
        });

        edt(hold::fire);
        edt(() -> assertFalse(hold.isRunning(),
                "hold 表 fire 后必须自停（Swing Timer 默认重复，漏 stop 会僵尸复燃）"));
        ManualTimer fade = timerOf(FADE_MS);
        edt(() -> {
            assertEquals(GoalStrip.Phase.FADING, strip.phase());
            assertTrue(strip.isFadeTimerRunning(), "fade keeps the layout height (repaint-only)");
        });

        for (int i = 0; i < 10; i++) {
            edt(fade::fire);
        }
        edt(() -> {
            assertFalse(strip.isVisible(), "after the fade the strip leaves the layout");
            assertEquals(GoalStrip.Phase.HIDDEN, strip.phase());
            assertNull(strip.status());
        });
    }

    @Test
    void activeRevivalDuringHoldCancelsTerminalChain() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.COMPLETED, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isHoldTimerRunning()));

        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "new goal", null, 0, 0)));
        edt(() -> {
            assertEquals(GoalStrip.Phase.ACTIVE, strip.phase(),
                    "revival during HOLD cancels the timers immediately");
            assertFalse(strip.isHoldTimerRunning());
            assertFalse(strip.isFadeTimerRunning());
            assertTrue(strip.isVisible());
        });
    }

    @Test
    void activeRevivalDuringFadeCancelsTerminalChain() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.CANCELLED, "s", null, 0, 0)));
        edt(() -> timerOf(HOLD_MS).fire()); // hold → fading
        edt(() -> assertTrue(strip.isFadeTimerRunning()));

        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "revived", null, 0, 0)));
        edt(() -> {
            assertEquals(GoalStrip.Phase.ACTIVE, strip.phase());
            assertFalse(strip.isFadeTimerRunning());
        });
    }

    @Test
    void blockedAndActiveSwapDirectlyWithoutTerminalBar() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.BLOCKED, "s", null, 0, 0)));
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> {
            assertEquals(GoalStrip.Phase.ACTIVE, strip.phase());
            assertFalse(strip.isHoldTimerRunning(),
                    "BLOCKED→ACTIVE swaps color directly, no terminal chain");
        });
    }

    @Test
    void nullSnapshotHidesImmediatelyWithoutTerminalBar() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> strip.applySnapshot(null));
        edt(() -> {
            assertFalse(strip.isVisible());
            assertEquals(GoalStrip.Phase.HIDDEN, strip.phase());
            assertFalse(strip.isHoldTimerRunning(), "clear hides without the terminal chain");
        });
    }

    // --- 图标呼吸（仅 ACTIVE；PAUSED/终态/清场停表） ---

    @Test
    void activeBreathesAndPauseStopsBreathing() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> {
            assertTrue(strip.isBreathTimerRunning(), "ACTIVE runs the icon breath timer");
            float alpha = strip.iconBreathAlphaForTest();
            assertTrue(alpha >= 0.45f && alpha <= 0.90f, "breath alpha in range: " + alpha);
        });

        ManualTimer breath = timerOf(BREATH_MS);
        final float[] min = {1f};
        final float[] max = {0f};
        for (int i = 0; i < 13; i++) {
            edt(breath::fire); // 完整一个呼吸周期（逐帧采样，13 fire 后帧回卷到 0）
            edt(() -> {
                float a = strip.iconBreathAlphaForTest();
                min[0] = Math.min(min[0], a);
                max[0] = Math.max(max[0], a);
            });
        }
        edt(() -> {
            assertTrue(max[0] - min[0] > 0.3f,
                    "呼吸 alpha 须跨周期起伏（帧计数冻结会恒为起点 alpha）: "
                            + min[0] + " .. " + max[0]);
            assertTrue(min[0] >= 0.45f && max[0] <= 0.90f,
                    "整周期落在标称区间 [0.45, 0.90]: " + min[0] + " .. " + max[0]);
        });

        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.PAUSED, "s", null, 0, 0)));
        edt(() -> {
            assertFalse(strip.isBreathTimerRunning(), "PAUSED stops the breath timer");
            assertEquals(1f, strip.iconBreathAlphaForTest(), "static icon at full alpha");
        });

        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isBreathTimerRunning(), "re-activation restarts breathing"));
    }

    @Test
    void breathStopsOnTerminalAndOnClear() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isBreathTimerRunning()));
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.COMPLETED, "s", null, 0, 0)));
        edt(() -> assertFalse(strip.isBreathTimerRunning(), "terminal stops breathing"));

        // 清场（null 快照）同样停表
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isBreathTimerRunning(), "复活重新点火呼吸表"));
        edt(() -> strip.applySnapshot(null));
        edt(() -> assertFalse(strip.isBreathTimerRunning(), "clear stops breathing"));
    }

    @Test
    void hostHidingPausesBreathingAndShowingResumes() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isBreathTimerRunning()));

        edt(() -> strip.setHostShowing(false));
        edt(() -> {
            assertFalse(strip.isBreathTimerRunning(), "宿主隐藏停呼吸表（防后台空转）");
            assertEquals(1f, strip.iconBreathAlphaForTest(), "隐藏期图标静态全色");
        });

        // 隐藏期到达的新 ACTIVE 快照同样不点火
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s2", null, 1, 1)));
        edt(() -> assertFalse(strip.isBreathTimerRunning(), "隐藏期快照不点火呼吸"));

        edt(() -> strip.setHostShowing(true));
        edt(() -> assertTrue(strip.isBreathTimerRunning(), "恢复可见续跑呼吸"));

        // PAUSED 期间恢复可见不点火（呼吸仅 ACTIVE）
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.PAUSED, "s", null, 0, 0)));
        edt(() -> strip.setHostShowing(false));
        edt(() -> strip.setHostShowing(true));
        edt(() -> assertFalse(strip.isBreathTimerRunning(), "PAUSED 不因恢复可见而呼吸"));
    }

    @Test
    void identicalSnapshotIsDeduped() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 1, 1)));
        // 内容不变快照再投：状态机早退（可观测面：不新建计时器、phase 与可见性不变）
        int timersBefore = timers.size();
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 1, 1)));
        edt(() -> {
            assertEquals(GoalStrip.Phase.ACTIVE, strip.phase());
            assertEquals(timersBefore, timers.size(), "dedupe must not create timers");
            assertTrue(strip.isVisible());
        });
    }

    // --- 展开/收起与高度 clamp ---

    @Test
    void expandRendersBodyAndClampsToHostShare() throws Exception {
        String longObjective = "line\n".repeat(200);
        GoalState.GoalSnapshot active = snapshot(GoalState.Status.ACTIVE, "sum", longObjective, 0, 0);
        edt(() -> strip.applySnapshot(active));

        edt(() -> strip.setExpanded(true));
        edt(() -> {
            assertTrue(strip.isExpanded());
            assertTrue(strip.bodyVisible());
            int expected = UiTokens.GOAL_STRIP_HEIGHT + 400 * 2 / 5; // host 400 → body ≤160
            assertEquals(expected, strip.getPreferredSize().height,
                    "expanded height clamps to ~40% of the host");
        });

        hostHeight.set(0); // 未布局/退化宿主：固定上限回落
        edt(() -> {
            assertTrue(strip.getPreferredSize().height <= UiTokens.GOAL_STRIP_HEIGHT + 200);
            assertTrue(strip.getPreferredSize().height >= UiTokens.GOAL_STRIP_HEIGHT + 100);
        });

        edt(() -> strip.setExpanded(false));
        edt(() -> assertEquals(UiTokens.GOAL_STRIP_HEIGHT, strip.getPreferredSize().height));
    }

    @Test
    void terminalHidesAllButtonsYetKeepsHeaderRowHeight() throws Exception {
        // 终态三钮全隐：FlowLayout 的首选高塌缩到边距，无 UI 委托的状态标签首选
        // 只有 1×1——行高若跟随内容塌陷，状态文字会被裁成条顶残迹。行高地板钉住
        // 色带高度，钮显隐与行高正交：终态条文字必须保持完整可读
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.headerRowPreferredHeightForTest() >= UiTokens.GOAL_STRIP_HEIGHT));

        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.COMPLETED, "s", null, 0, 0)));
        edt(() -> {
            assertFalse(strip.pauseButtonVisible());
            assertFalse(strip.resumeButtonVisible());
            assertFalse(strip.chevronButtonVisible(), "terminal hides all three buttons");
            assertEquals(UiTokens.GOAL_STRIP_HEIGHT, strip.headerRowPreferredHeightForTest(),
                    "钮全隐后行高仍钉在色带高度，不随内容塌缩");
        });

        // 取消终态同形态（✕ 条同样全钮隐藏）
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.CANCELLED, "s", null, 0, 0)));
        edt(() -> assertEquals(UiTokens.GOAL_STRIP_HEIGHT, strip.headerRowPreferredHeightForTest()));
    }

    @Test
    void collapsedHeightCoversContentNeed() {
        // 收起态行高常量与内容实需（24px 钮 + buttonRow 上下边距）零余量耦合：
        // getPreferredSize 硬钉返回同一常量，钉常量的断言发现不了常量跌破实需
        // （钮径或边距单边加大 → NORTH 按实需高越出条底界、按钮底部被裁且不可点）。
        // 本守卫从内容侧独立度量，实需高越过常量时先红
        assertTrue(strip.headerRowPreferredHeightForTest() <= UiTokens.GOAL_STRIP_HEIGHT,
                "行内容实需高 " + strip.headerRowPreferredHeightForTest()
                        + " 不得越过收起态行高常量 " + UiTokens.GOAL_STRIP_HEIGHT);
    }

    @Test
    void expandedBodyCollapsesOnTerminal() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", "objective", 0, 0)));
        edt(() -> strip.setExpanded(true));
        edt(() -> assertTrue(strip.isExpanded()));

        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.COMPLETED, "s", "objective", 0, 0)));
        edt(() -> assertFalse(strip.isExpanded(), "terminal entry auto-collapses the body"));
    }

    @Test
    void objectiveReplaceRerendersExpandedBody() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "sum", "old objective", 0, 0)));
        edt(() -> strip.setExpanded(true));
        edt(() -> assertTrue(strip.bodyVisible()));

        // replace（objective 变更，key 去重放行）：header 已切新摘要，展开体必须
        // 跟随重渲染——否则同屏两份目标信息，仅在收起再展开时自愈
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "sum", "new objective", 0, 0)));
        edt(() -> {
            assertTrue(strip.bodyVisible(), "replace 不改变展开态");
            assertTrue(strip.bodyTextForTest().contains("new objective"),
                    "展开体跟随新 objective 重渲染");
            assertFalse(strip.bodyTextForTest().contains("old objective"));
        });

        // 计数 bump（objective 不变的高频路径）：不重渲染，正文保持当前内容
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "sum", "new objective", 1, 1)));
        edt(() -> assertTrue(strip.bodyTextForTest().contains("new objective"),
                "计数刷新不动正文"));
    }

    // --- 降级文本与操作回调 ---

    @Test
    void missingUiSummaryFallsBackToObjectiveFirstLine() {
        GoalState.GoalSnapshot s = snapshot(GoalState.Status.ACTIVE, null, "first line\nsecond line", 0, 0);
        assertEquals("first line", strip.summaryText(s));
        assertEquals("summary", strip.summaryText(
                snapshot(GoalState.Status.ACTIVE, "summary", "objective", 0, 0)));
    }

    @Test
    void buttonsFireActionCallbacks() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> strip.clickPauseForTest());
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.PAUSED, "s", null, 0, 0)));
        edt(() -> strip.clickResumeForTest());
        edt(() -> assertEquals(List.of("pause", "resume"), actionLog));
    }

    @Test
    void disposeStopsAllTimers() throws Exception {
        // 呼吸表与终态表同在清理范围（dispose 粘位后快照不再重启计时器，故先点火后清理）
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isBreathTimerRunning()));
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.COMPLETED, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isHoldTimerRunning()));
        edt(() -> assertFalse(strip.isBreathTimerRunning(), "terminal stops breathing"));
        edt(strip::dispose);
        edt(() -> assertFalse(strip.isHoldTimerRunning()));
        edt(() -> assertFalse(strip.isBreathTimerRunning(), "dispose stops the breath timer"));
    }

    @Test
    void disposeIsStickyAgainstLaterSnapshots() throws Exception {
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "s", null, 0, 0)));
        edt(() -> assertTrue(strip.isBreathTimerRunning()));
        edt(strip::dispose);

        // 销毁粘位：cleanup 后迟到的快照/显隐桥不得重启呼吸表（防死面板 Timer 复活）
        edt(() -> strip.applySnapshot(snapshot(GoalState.Status.ACTIVE, "late", null, 1, 1)));
        edt(() -> strip.setHostShowing(true));
        edt(() -> strip.setHostShowing(false));
        edt(() -> assertFalse(strip.isBreathTimerRunning(),
                "post-dispose snapshots must not revive the breath timer"));
    }
}
