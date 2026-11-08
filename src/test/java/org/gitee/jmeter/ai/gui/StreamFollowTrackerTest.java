package org.gitee.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式跟随判定单元：手势代际粘滞——渲染落定滞后（非手势离底）不脱离跟随，
 * 真实手势即刻脱离，滚回底部可重挂，清屏复位。
 */
class StreamFollowTrackerTest {

    @Test
    void armsOnlyWhenPinned() {
        StreamFollowTracker tracker = new StreamFollowTracker();
        assertFalse(tracker.shouldFollow(false, 5), "离底不挂基线");
        assertTrue(tracker.shouldFollow(true, 5), "贴底挂基线并跟随");
    }

    @Test
    void settleLagDoesNotCancelFollowing() {
        StreamFollowTracker tracker = new StreamFollowTracker();
        tracker.shouldFollow(true, 5);   // arm @ gen 5
        // 后续帧 pinnedNow=false（追逐落定滞后）但代际未变 → 仍跟随
        assertTrue(tracker.shouldFollow(false, 5), "非手势离底（渲染滞后）不脱离");
        assertTrue(tracker.isFollowing());
    }

    @Test
    void userGestureCancelsAndReArmsAtBottom() {
        StreamFollowTracker tracker = new StreamFollowTracker();
        tracker.shouldFollow(true, 5);
        assertFalse(tracker.shouldFollow(false, 6), "手势代际递增 → 脱离");
        assertFalse(tracker.isFollowing());
        // 用户滚回底部（贴底且代际稳定）→ 立即重新挂基线跟随
        assertTrue(tracker.shouldFollow(true, 6), "pinned re-arms following immediately");
        assertTrue(tracker.isFollowing());
    }

    @Test
    void resetDisarms() {
        StreamFollowTracker tracker = new StreamFollowTracker();
        tracker.shouldFollow(true, 5);
        tracker.reset();
        assertFalse(tracker.isFollowing());
        assertFalse(tracker.shouldFollow(false, 5), "复位后需重新贴底挂基线");
    }
}
