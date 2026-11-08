package org.gitee.jmeter.ai.gui;

/**
 * 流式增长的滚动跟随判定：以<b>用户手势代际</b>为界，而非逐帧精确贴底捕获。
 *
 * <p>背景（2026-11-07 手测问题 2）：流式 tick 每 ~100ms 对正文卡全量重渲，布局
 * 落定常慢于追逐的一拍——逐帧 {@code isPinnedToBottom} 捕获会把"追逐自身的落定
 * 滞后"误判为用户上滚，一帧没追到精确底部，下一帧即永久停止跟随（滚动条停在
 * 某轮输出）。改以手势代际粘滞：贴底起播挂基线；基线存续期间持续跟随（渲染
 * 滞后不脱离）；真实用户手势（拖动/滚轮/键，代际递增）即刻脱离；脱离后用户
 * 滚回底部可重新挂上。非流式路径（组件插入等）仍走既有贴底捕获协议。
 */
final class StreamFollowTracker {

    /** 粘滞基线；-1 = 未跟随。 */
    private long generation = -1L;

    /**
     * 本帧是否跟随。
     *
     * @param pinnedNow         当前是否精确贴底（仅未跟随状态用于挂基线）
     * @param gestureGeneration 当前用户手势代际（真实输入才递增）
     */
    boolean shouldFollow(boolean pinnedNow, long gestureGeneration) {
        if (generation >= 0) {
            if (gestureGeneration != generation) {
                generation = -1L;   // 用户手势：脱离跟随（滚回底部可重挂）
                return false;
            }
            return true;            // 渲染落定滞后不脱离
        }
        if (!pinnedNow) {
            return false;
        }
        generation = gestureGeneration;
        return true;
    }

    /** 是否处于跟随中（测试观测面）。 */
    boolean isFollowing() {
        return generation >= 0;
    }

    /** 复位（清屏/换会话）。 */
    void reset() {
        generation = -1L;
    }
}
