package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转录流式核心：合并 flush（手动步进调度器）、懒建卡片、终态升级/整段替换、
 * 迟到 delta 丢弃、清屏取消 pending flush（无孤儿卡）、按回合键控互不串卡、
 * streamedFinal 按 streamId 查卡与无卡回退。
 */
class TranscriptStreamingTest {

    /** 手动步进调度器：schedule 记住任务（合并），fire() 同步执行。 */
    private static final class ManualScheduler implements TranscriptView.StreamFlushScheduler {
        AtomicReference<Runnable> pending = new AtomicReference<>();

        @Override public void schedule(Runnable flush) {
            pending.set(flush);   // 合并：已有 pending 则保持原任务
        }

        @Override public void cancel() {
            pending.set(null);
        }

        void fire() {
            Runnable r = pending.getAndSet(null);
            if (r != null) {
                r.run();
            }
        }

        boolean isPending() {
            return pending.get() != null;
        }
    }

    private static void onEdt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InvocationTargetException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deltasCoalesceUntilFlushThenRenderCumulative() {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);

        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.started(10L, 1));
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "我"));
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "我用"));
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "思考"));
        });
        assertEquals(0, view.getCardCount(), "flush 前不建卡（合并窗口）");
        assertTrue(scheduler.isPending());

        onEdt(scheduler::fire);
        assertEquals(1, view.getCardCount(), "正文卡懒建于 flush");
        assertEquals("我用", view.getCard(0).getText(), "累计文本幂等渲染");
        assertTrue(view.getStreamThinkingCard(1L) != null, "流式思考卡 flush 后建立");
        assertTrue(view.hasStreamedEvents(1L));

        onEdt(() -> view.streamDelta(1L,
                StreamDelta.ended(10L, 1, StreamDelta.Status.SUCCESS, "我用HTTP", "思考")));
        assertEquals("我用HTTP", view.getCard(0).getText(), "成功终态以 ended 载荷终渲");
    }

    @Test
    void lateDeltasAfterEndedAreDropped() {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "部分"));
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.SUCCESS, "部分", ""));
            // 迟到 delta（流段已结束）
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "迟到"));
            scheduler.fire();
        });
        assertEquals("部分", view.getCard(0).getText(), "迟到 delta 丢弃");
    }

    @Test
    void clearTranscriptCancelsPendingFlushNoOrphanCard() {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "旧会话内容"));
            view.clearTranscript();   // /new：取消 pending + 弃缓冲
        });
        assertFalse(scheduler.isPending(), "pending flush 已取消");
        onEdt(scheduler::fire);   // 即便误 fire：无缓冲无孤儿卡
        assertEquals(0, view.getCardCount(), "清屏后无旧会话孤儿卡");
        assertFalse(view.hasStreamedEvents(1L), "流式状态随清屏弃置");
    }

    @Test
    void overlappingTurnsKeepSeparateCards() {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "回合一的内容"));
            view.streamDelta(2L, StreamDelta.contentDelta(20L, 1, "回合二的内容"));
            scheduler.fire();
        });
        assertEquals(2, view.getCardCount());
        assertEquals("回合一的内容", view.getCard(0).getText());
        assertEquals("回合二的内容", view.getCard(1).getText());
    }

    @Test
    void failureEndedWithSecondAttemptPayloadReplacesWholeSegment() {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "第一次的部分"));
            scheduler.fire();
            // 尝试 2 屏外缓冲，全败：ended(FAILED, attempt=2) 携带最新非空载荷
            view.streamDelta(1L, StreamDelta.ended(10L, 2, StreamDelta.Status.FAILED,
                    "重试后的部分", "重试思考"));
        });
        assertEquals("重试后的部分", view.getCard(0).getText(), "ended 载荷整体替换");
        assertTrue(view.getCard(0).getText().contains("重试"));
    }

    @Test
    void twoLlmCallsInOneTurnKeepIndependentCards() {
        // S1 复现：同回合 LLM→工具→LLM——两次调用各建独立卡片对，第二次不得覆盖第一次
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "FIRST"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.SUCCESS, "FIRST", ""));
            // 工具轮后第二次调用：新 streamId
            view.streamDelta(1L, StreamDelta.started(20L, 1));
            view.streamDelta(1L, StreamDelta.contentDelta(20L, 1, "SECOND"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(20L, 1, StreamDelta.Status.SUCCESS, "SECOND", ""));
        });
        assertEquals(2, view.getCardCount(), "two streamed calls → two content cards");
        assertEquals("FIRST", view.getCard(0).getText());
        assertEquals("SECOND", view.getCard(1).getText());
    }

    @Test
    void olderCumulativePayloadCannotShrinkNewerRendering() {
        // S2 复现：迟到/乱序的旧累计快照不得把已渲染内容缩回去
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "abcdef"));
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "abc"));   // 旧快照
            scheduler.fire();
        });
        assertEquals("abcdef", view.getCard(0).getText(), "older cumulative dropped");
    }

    @Test
    void successfulRetryEndedReplacesBothChannelsInPlace() {
        // S3 复现：重试成功（attemptSeq=2，屏外无实时 delta）——ENDED 载荷整段替换两通道
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "OLD-CONTENT"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.FAILED,
                    "OLD-CONTENT", "OLD-REASON"));
            view.streamDelta(1L, StreamDelta.started(10L, 2));
            view.streamDelta(1L, StreamDelta.ended(10L, 2, StreamDelta.Status.SUCCESS,
                    "NEW-CONTENT", "NEW-REASON"));
        });
        assertEquals("NEW-CONTENT", view.getCard(0).getText(),
                "successful retry's ended payload replaces content");
        ThinkingCard thinking = view.getStreamThinkingCard(1L);
        assertEquals("NEW-REASON", thinking.getText(),
                "successful retry's ended payload replaces reasoning");
    }

    @Test
    void successfulEndedPayloadFlushesReasoningTail() {
        // S4 复现：终态载荷是权威累计——最后一个节流窗口内的思考尾部须收敛
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "reason-head"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.SUCCESS,
                    "", "reason-head-tail"));
        });
        ThinkingCard thinking = view.getStreamThinkingCard(1L);
        assertEquals("reason-head-tail", thinking.getText(),
                "ended reasoning payload converges the thinking card");
    }

    @Test
    void flushDoesNotDragViewportWhenUserScrolledUp() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        javax.swing.JScrollBar bar = new javax.swing.JScrollBar();
        bar.setUnitIncrement(16);
        javax.swing.JScrollPane pane = new javax.swing.JScrollPane(view) {
            @Override public javax.swing.JScrollBar createVerticalScrollBar() { return bar; }
        };
        pane.setSize(400, 300);
        onEdt(() -> {
            bar.setValue(bar.getMaximum());   // 贴底
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "第一段"));
            // 用户滚轮上滚（真实手势：代际递增，跟随脱离）再离开底部
            pane.dispatchEvent(new java.awt.event.MouseWheelEvent(pane,
                    java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                    1, 1, 0, 0, 1, false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1));
            bar.setValue(0);
            scheduler.fire();
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
        assertFalse(atBottom(bar),
                "用户手势上滚后流式增长不得拽走视口");
        assertEquals(1, view.getCardCount());
    }

    @Test
    void streamEndedStopsThinkingSpinner() {
        // B1 修复：纯思考流段结束（成功/中止一致）须收尾 spinner——否则
        // "Thinking…" 无限旋转（Stop 中止后无下一段来收尾时最明显）
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "思考中"));
            scheduler.fire();
        });
        ThinkingCard thinking = view.getStreamThinkingCard(1L);
        assertTrue(thinking.isSpinnerRunning(), "流式中 spinner 运行");

        onEdt(() -> view.streamDelta(1L, StreamDelta.ended(10L, 1,
                StreamDelta.Status.ABORTED, "", "思考中")));
        assertFalse(thinking.isSpinnerRunning(), "ENDED 收尾本段 spinner");
        assertTrue(thinking.isCollapsed());
        assertEquals("思考中", thinking.getText(), "已显示内容保留");
    }

    @Test
    void finishStreamAnimationsSettlesAllStreamSpinners() {
        // B1 兜底：Stop 即时复位路径（STREAM_ENDED(ABORTED) 尚未到达/被过滤的窗口）
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "回合一思考"));
            view.streamDelta(2L, StreamDelta.reasoningDelta(20L, 1, "回合二思考"));
            scheduler.fire();
        });
        assertTrue(view.getStreamThinkingCard(1L).isSpinnerRunning());
        assertTrue(view.getStreamThinkingCard(2L).isSpinnerRunning());

        onEdt(view::finishStreamAnimations);
        assertFalse(view.getStreamThinkingCard(1L).isSpinnerRunning(),
                "全部流式段 spinner 收尾（Stop 兜底）");
        assertFalse(view.getStreamThinkingCard(2L).isSpinnerRunning());
    }

    @Test
    void newStreamStartFinishesPreviousActivityGroup() {
        // 需求 2 复现与验收：无正文的工具轮不建正文卡 → 旧活动组不被收尾 →
        // 下一轮工具行路由进上方旧组（顺序错乱）。新流段开始即收尾上一活动组。
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            // 轮 1：思考 + 工具（无正文）——工具行带回合身份路由（面板真实口径）
            view.streamDelta(1L, StreamDelta.started(10L, 1));
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "第一轮思考"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.SUCCESS, "", "第一轮思考"));
            view.addToolActivity(1L, "tool-a-1");
            view.addToolActivity(1L, "tool-a-2");
        });
        ToolActivityGroup firstGroup = view.getActivityGroup();
        assertTrue(firstGroup.isRunning(), "轮 1 工具组运行中");

        // 轮 2：新流段开始（思考先行，仍无正文）→ 上一活动组必须收尾
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.started(20L, 1));
            view.streamDelta(1L, StreamDelta.reasoningDelta(20L, 1, "第二轮思考"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(20L, 1, StreamDelta.Status.SUCCESS, "", "第二轮思考"));
            view.addToolActivity(1L, "tool-b-1");
        });
        assertFalse(firstGroup.isRunning(), "新流段开始即收尾上一活动组");
        ToolActivityGroup secondGroup = view.getActivityGroup();
        assertTrue(secondGroup != firstGroup, "轮 2 工具行落入新组");
        // 顺序：新组在第二张思考卡之后（队尾）
        java.awt.Component[] comps = view.getComponents();
        int secondGroupIdx = -1;
        for (int i = 0; i < comps.length; i++) {
            if (comps[i] == secondGroup) {
                secondGroupIdx = i;
            }
        }
        assertTrue(secondGroupIdx >= 0 && secondGroupIdx >= comps.length - 2,
                "新活动组位于转录尾部（贴合调用顺序），idx=" + secondGroupIdx
                        + " total=" + comps.length);
    }

    @Test
    void overlappingTurnsKeepSeparateActivityGroups() throws Exception {
        // R1 复现与验收：活动组按回合键控——重叠活回合的工具行各归各组，
        // 兄弟回合的新流段/首条正文不得收尾另一回合的活动组
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.addToolActivity(1L, "回合一工具a");
            view.addToolActivity(2L, "回合二工具b");
        });
        ToolActivityGroup group1 = view.getActivityGroup();   // 最近写入 = 回合二组
        onEdt(() -> view.addToolActivity(1L, "回合一工具c"));
        ToolActivityGroup group1Again = view.getActivityGroup();
        assertTrue(group1Again != group1, "回合一后续行路由进回合一组（不串组）");

        // 回合二的新流段开始：只收尾回合二自己的组，回合一组不受影响
        onEdt(() -> view.streamDelta(2L, StreamDelta.started(20L, 1)));
        assertTrue(group1Again.getText().contains("回合一工具"), "sanity: turn-1 group has content");
        assertTrue(group1Again.isRunning(), "兄弟回合新流段不收尾回合一的活动组");
    }

    @Test
    void streamedFinalInvalidatesPendingFlush() throws Exception {
        // R3 复现：终答原位覆盖后，此前排队的合并 flush 不得用旧累计回写权威终答
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "OLD"));
            scheduler.fire();   // 旧内容已渲染
        });
        // 新 delta 排队 flush（未 fire）→ 终答覆盖先行
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "OLD-PENDING"));
            view.renderStreamedFinal(1L, 10L, "FINAL-AUTHORITATIVE");
            scheduler.fire();   // 迟到的旧 flush
        });
        assertEquals("FINAL-AUTHORITATIVE", view.getCard(0).getText(),
                "终答覆盖使 pending flush 失效——旧累计不得回写");
    }

    @Test
    void postContentReasoningOpensNewThinkingCard() throws Exception {
        // R2 复现与验收：同流段首条可见正文后的新到思考增量开新思考卡
        // （思考-正文-思考交错保持时间顺序，不并入已收尾的旧卡）
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "前置思考"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "正文"));
            scheduler.fire();   // 首条可见正文 → 折叠前置思考卡
        });
        ThinkingCard firstCard = view.getStreamThinkingCard(1L);
        assertFalse(firstCard.isRunning(), "正文到达后前置思考卡收尾");

        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "前置思考后置思考"));
            scheduler.fire();
        });
        ThinkingCard secondCard = view.getStreamThinkingCard(1L);
        assertNotSame(firstCard, secondCard, "正文后的思考增量开新卡（不并入已收尾卡）");
        assertEquals("前置思考", firstCard.getText(), "旧卡保留前段内容");
        assertEquals("后置思考", secondCard.getText(), "新卡只承接尾部增量");
        assertTrue(secondCard.isRunning(), "新卡运行中");
    }

    @Test
    void retryEndedWithEmptyChannelsClearsOldContent() throws Exception {
        // R11 复现与验收：成功重试（attemptSeq=2）的权威载荷空通道 = 新尝试无该
        // 通道内容——清理旧内容，不得静默保留上一尝试的残留
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "OLD-CONTENT"));
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "OLD-REASON"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.FAILED,
                    "OLD-CONTENT", "OLD-REASON"));
            view.streamDelta(1L, StreamDelta.started(10L, 2));
            // 新尝试两通道皆空（如只有隐藏思考）——权威清空
            view.streamDelta(1L, StreamDelta.ended(10L, 2, StreamDelta.Status.SUCCESS, "", ""));
        });
        assertEquals(0, view.getCardCount(), "空正文通道：旧正文卡移除");
        assertNull(view.getStreamThinkingCard(1L), "空思考通道：旧思考卡移除");
    }

    @Test
    void retryEndedWithEmptyReasoningClearsThinkingOnly() throws Exception {
        // R11 半空变体：新尝试有正文无思考——思考卡清空、正文整段替换
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "OLD-CONTENT"));
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "OLD-REASON"));
            scheduler.fire();
            view.streamDelta(1L, StreamDelta.ended(10L, 1, StreamDelta.Status.FAILED,
                    "OLD-CONTENT", "OLD-REASON"));
            view.streamDelta(1L, StreamDelta.started(10L, 2));
            view.streamDelta(1L, StreamDelta.ended(10L, 2, StreamDelta.Status.SUCCESS,
                    "NEW-CONTENT", ""));
        });
        assertEquals("NEW-CONTENT", view.getCard(0).getText(), "正文整段替换");
        assertNull(view.getStreamThinkingCard(1L), "空思考通道清空旧思考卡");
    }

    private static boolean atBottom(javax.swing.JScrollBar bar) {
        return bar.getValue() + bar.getVisibleAmount() == bar.getMaximum();
    }

    @Test
    void turnCancelSettlePreventsPendingFlushFromCreatingOrphanThinkingCard() throws Exception {
        // Stop 后孤儿思考卡复现：思考增量入缓冲（100ms 窗口内点 Stop，卡未建）→
        // TURN_CANCELLED 收敛流段 → 迟到 STREAM_ENDED 被面板活回合过滤丢弃
        // （不会到达 TranscriptView）→ 已排队 flush 才 fire——不得再建运行中的卡
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "被打断前的思考"));
            // 不 fire：缓冲中
            view.settleStreamTurn(1L);   // TURN_CANCELLED 路径（面板接线）
            scheduler.fire();            // Stop 之后才执行的旧 flush
        });
        assertNull(view.getStreamThinkingCard(1L),
                "终态收敛后 pending flush 不得为缓冲思考增量新建孤儿卡");
    }

    @Test
    void turnCancelSettleFinishesAlreadyRunningThinkingSpinner() throws Exception {
        // 已建卡变体：思考卡运行中 → 回合终态收敛 → spinner 收尾、内容保留
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.reasoningDelta(10L, 1, "思考中"));
            scheduler.fire();
        });
        ThinkingCard thinking = view.getStreamThinkingCard(1L);
        assertTrue(thinking.isSpinnerRunning(), "流式中 spinner 运行");

        onEdt(() -> view.settleStreamTurn(1L));
        assertFalse(thinking.isSpinnerRunning(), "终态收敛收尾 spinner");
        assertEquals("思考中", thinking.getText(), "已显示内容保留");
    }

    @Test
    void streamFollowSurvivesRenderSettleLagButNotUserGesture() throws Exception {
        // 问题2 复现：流式 tick 全量重渲的布局落定滞后会让追逐一拍追不到精确底部，
        // 逐 tick 贴底捕获把"落定滞后"误判为用户上滚 → 永久停止跟随。
        // 修正口径：跟随以手势代际为界（真实滚轮/拖动/键才脱离），渲染滞后不脱离。
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        javax.swing.JScrollBar bar = new javax.swing.JScrollBar();
        bar.setUnitIncrement(16);
        javax.swing.JScrollPane pane = new javax.swing.JScrollPane(view) {
            @Override public javax.swing.JScrollBar createVerticalScrollBar() { return bar; }
        };
        pane.setSize(400, 300);
        // 视口有真实布局：断言用"是否贴底"不变式（追逐后必贴底；脱离后保持离底）
        Runnable pump = () -> {
            try {
                for (int i = 0; i < 4; i++) {
                    SwingUtilities.invokeAndWait(() -> { });
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        // tick 1：贴底起播 → 跟随到底
        onEdt(() -> {
            bar.setValue(bar.getMaximum());
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "第一段"));
            scheduler.fire();
        });
        pump.run();
        assertTrue(atBottom(bar), "tick1 follows to bottom");

        // 模拟落定滞后（程序置离底，无任何手势）——旧逻辑下一 tick 会因
        // isPinnedToBottom=false 永久停止跟随
        // tick 2：继续增长 → 仍应跟随回底（非手势离底不脱离跟随）
        onEdt(() -> {
            bar.setValue(Math.max(0, bar.getMaximum() - bar.getVisibleAmount() - 40));
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "第一段第二段"));
            scheduler.fire();
        });
        pump.run();
        assertTrue(atBottom(bar),
                "render settle lag must NOT cancel following (only a real gesture may)");

        // 真实用户手势（滚轮）→ 脱离跟随：后续增长不得拽走视口
        onEdt(() -> pane.dispatchEvent(new java.awt.event.MouseWheelEvent(pane,
                java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                1, 1, 0, 0, 1, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1)));
        onEdt(() -> {
            bar.setValue(0);
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "第一段第二段第三段"));
            scheduler.fire();
        });
        pump.run();
        assertFalse(atBottom(bar), "user gesture cancels following; viewport stays");
    }

    @Test
    void streamedFinalRendersInPlaceAndFallsBackWhenNoCard() {
        ManualScheduler scheduler = new ManualScheduler();
        TranscriptView view = new TranscriptView(null, scheduler);
        onEdt(() -> {
            view.streamDelta(1L, StreamDelta.contentDelta(10L, 1, "流式正文"));
            scheduler.fire();
        });
        MessageCard streamed = view.getCard(0);

        AtomicReference<Boolean> rendered = new AtomicReference<>();
        onEdt(() -> rendered.set(view.renderStreamedFinal(1L, 10L, "权威终答[Goal still active]")));
        assertTrue(rendered.get());
        assertEquals("权威终答[Goal still active]", streamed.getText(), "goal 后缀并入同卡");
        assertSame(streamed, view.getCard(0), "原位重渲不新增卡");
        assertEquals(1, view.getCardCount(), "无重复卡");

        // 无卡回退：纯思考流（无正文卡）或空终答
        ManualScheduler scheduler2 = new ManualScheduler();
        TranscriptView view2 = new TranscriptView(null, scheduler2);
        AtomicReference<Boolean> rendered2 = new AtomicReference<>();
        onEdt(() -> {
            view2.streamDelta(5L, StreamDelta.reasoningDelta(50L, 1, "只有思考"));
            scheduler2.fire();
            rendered2.set(view2.renderStreamedFinal(5L, 50L, ""));
        });
        assertFalse(rendered2.get(), "空终答回退既有占位路径");
    }
}
