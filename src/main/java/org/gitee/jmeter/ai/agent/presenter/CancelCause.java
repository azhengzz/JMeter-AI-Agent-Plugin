package org.gitee.jmeter.ai.agent.presenter;

/**
 * 取消原因全集（类型化，取代裸 reason 串）。
 *
 * <p>订阅端显示域（以 {@code AiChatPanel.appendCancelLine} 为准，判据按
 * {@link TurnOrigin#isIpcPeer()}）：USER_STOP/TIMEOUT 仅 IPC 源回合渲染终止回执；
 * RESET 一律不渲染（重置 UI 自管、面板即将清理）。
 *
 * <p>wire 映射（{@code IpcResponse} 的 {@code CANCEL_REASON_*} 常量）收口在
 * IpcServer 构建响应信封处：USER_STOP→{@code CANCEL_REASON_USER_STOP}、
 * TIMEOUT→{@code CANCEL_REASON_TIMEOUT}（单一事实源保持，终止途径可区分性不弱化）；
 * RESET/SILENT 是纯本地原因，无 wire 对应。
 */
public enum CancelCause {
    /** 本实例用户 Stop（本地 "Stopped." / IPC 回合终止回执）。 */
    USER_STOP,
    /** /agent 对端等待超时自取消。 */
    TIMEOUT,
    /** /new、"+"、cmdNew、关闭整合清空引发的取消——订阅端不渲染（重置 UI 自管）。 */
    RESET,
    /** 关闭整合对话框静默取消在跑回合。取消照常执行（abort 置位 / interrupt / 终态
     * 恰好一次），仅本地面板对<b>本地侧源</b>回合（LOCAL_PANEL；REPUBLISH 孤儿本无对端
     * 调用方）不渲染取消行——那是退出前的内部清理，非用户主动 Stop，留行是噪音。
     * <b>IPC 源</b>回合照常按 USER_STOP 文案渲染终止回执，对端阻塞等待方不失去反馈。 */
    SILENT
}
