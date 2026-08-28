package org.gitee.jmeter.ai.agent.presenter;

import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;

/**
 * 回合呈现接口：让 GUI 面板「领养」非本地发起的回合（IPC 委派、CLI 直连），
 * 以与本地回合对等的方式渲染消息流并接驳 Stop 控制。
 *
 * <p>方向约束（D1）：IPC 层不拿 GUI 引用，{@code IpcServer} 经
 * {@code AgentLoop.setTurnPresenter} 注册的呈现者间接通知。注册者唯一：
 * {@code AiChatPanel}（构造时与 {@code switchAiService} 重建 loop 后，与
 * {@code republishListener} 同点位重注册）。未注册（面板未创建）= headless，
 * 回合照常执行不受影响。
 *
 * <p>过滤契约：{@code AgentLoop} 派发前按 {@code sessionKey} 与
 * {@code InstanceContext.currentSessionKey()} 比对——仅当前实例会话的回合派发
 * （显式指定其他会话键的 {@code /agent} 请求保持 headless）。实现方收到的
 * 回调已在非 EDT 线程（agent-loop / ipc-worker / commonPool 载体），<b>必须</b>
 * 自行 {@code invokeLater} 到 EDT，并以会话代数过滤迟到事件。
 *
 * <p>{@link #onTurnCancelled} 的 reason 取值与 {@code IpcResponse} 的
 * {@code CANCEL_REASON_*} 常量同源（wire 契约为单一事实源）。
 */
public interface TurnPresenter {

    /** 回合已在当前会话上开跑（IPC 入口在提交回合后通知；来源消息文本含来源前缀）。 */
    void onTurnStarted(String sessionKey, String message);

    /** 回合内进度事件（思考/工具调用/中间回复），与本地回合的 ProgressCallback 同源。 */
    void onProgress(String sessionKey, ProgressUpdate update);

    /** 回合终结：正常完成或失败（AgentResponse 携带成功/错误语义，失败按错误样式渲染）。 */
    void onTurnCompleted(String sessionKey, AgentResponse response);

    /** 回合终结：被取消。reason 为 {@code IpcResponse.CANCEL_REASON_TIMEOUT}（对端等待超时自取消）
     * 或 {@code IpcResponse.CANCEL_REASON_USER_STOP}（本实例用户 STOP）。 */
    void onTurnCancelled(String sessionKey, String reason);

    /** 委派请求因会话忙被快速拒绝（一行系统提示；委派方收到既有 busy 错误）。 */
    void onTurnRejectedBusy(String sessionKey);

    /** IPC 到达的非命令消息在会话忙时被注入正在跑的回合（面板显示该消息）。 */
    void onInjected(String sessionKey, String message);
}
