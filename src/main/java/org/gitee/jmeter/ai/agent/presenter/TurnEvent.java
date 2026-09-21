package org.gitee.jmeter.ai.agent.presenter;

import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;

/**
 * 回合事件：不可变值对象，一个事件 = 一条事实。七类 Kind 各有语义：TURN_STARTED
 * （回合开始）、PROGRESS（回合进度）、TURN_COMPLETED（回合完成，失败经 error 语义
 * 承载）、TURN_CANCELLED（回合取消，cause 表示取消类型）、INJECTED（会话忙时注入
 * 确认）、REJECTED_BUSY（委派撞会话忙被快拒）、COMMAND_RESULT（Phase1/Phase2 同步
 * 命令结果）。
 *
 * <p>字段按 Kind 条件存在：{@link #turn} 仅回合系事件（INJECTED/REJECTED_BUSY/
 * COMMAND_RESULT 为 null——后两者是会话级事实，无回合身份）；{@link #origin} 字段
 * 仅 INJECTED（注入方来源）与 COMMAND_RESULT（命令方来源）非 null（REJECTED_BUSY
 * 恒 null），回合系事件字段为 null 但 {@code origin()} 访问器解析自 {@link #turn}；
 * {@link #progress} 仅 PROGRESS；{@link #response} 仅 TURN_COMPLETED（含失败 error
 * 语义）与 COMMAND_RESULT；{@link #message} 仅 INJECTED（注入文本）与 COMMAND_RESULT
 * （命令原文）；{@link #cause} 仅 TURN_CANCELLED。
 */
public final class TurnEvent {

    public enum Kind {
        TURN_STARTED, PROGRESS, TURN_COMPLETED, TURN_CANCELLED,
        INJECTED, REJECTED_BUSY, COMMAND_RESULT
    }

    private final Kind kind;
    private final String sessionKey;
    private final TurnHandle turn;
    private final TurnOrigin origin;
    private final ProgressUpdate progress;
    private final AgentResponse response;
    private final String message;
    private final CancelCause cause;

    private TurnEvent(Kind kind, String sessionKey, TurnHandle turn, TurnOrigin origin,
                      ProgressUpdate progress, AgentResponse response, String message, CancelCause cause) {
        this.kind = kind;
        this.sessionKey = sessionKey;
        this.turn = turn;
        this.origin = origin;
        this.progress = progress;
        this.response = response;
        this.message = message;
        this.cause = cause;
    }

    public static TurnEvent started(TurnHandle turn) {
        return new TurnEvent(Kind.TURN_STARTED, turn.sessionKey(), turn, null, null, null, null, null);
    }

    public static TurnEvent progress(TurnHandle turn, ProgressUpdate update) {
        return new TurnEvent(Kind.PROGRESS, turn.sessionKey(), turn, null, update, null, null, null);
    }

    public static TurnEvent completed(TurnHandle turn, AgentResponse response) {
        return new TurnEvent(Kind.TURN_COMPLETED, turn.sessionKey(), turn, null, null, response, null, null);
    }

    public static TurnEvent cancelled(TurnHandle turn, CancelCause cause) {
        return new TurnEvent(Kind.TURN_CANCELLED, turn.sessionKey(), turn, null, null, null, null, cause);
    }

    /** 注入确认（会话忙时 offer 成功）：携带注入方来源（本地/IPC 同一渲染）。 */
    public static TurnEvent injected(String sessionKey, TurnOrigin injectorOrigin, String message) {
        return new TurnEvent(Kind.INJECTED, sessionKey, null, injectorOrigin, null, null, message, null);
    }

    /** 委派撞会话忙被快拒（面板一行系统提示；委派方收到既有 busy 错误）。 */
    public static TurnEvent rejectedBusy(String sessionKey) {
        return new TurnEvent(Kind.REJECTED_BUSY, sessionKey, null, null, null, null, null, null);
    }

    /** Phase1/Phase2 同步命令结果：携带命令方来源与命令原文（发起方界面显示域规则）。 */
    public static TurnEvent commandResult(String sessionKey, TurnOrigin origin, String raw, AgentResponse response) {
        return new TurnEvent(Kind.COMMAND_RESULT, sessionKey, null, origin, null, response, raw, null);
    }

    public Kind kind() {
        return kind;
    }

    public String sessionKey() {
        return sessionKey;
    }

    /** 回合系事件的回合句柄；INJECTED/REJECTED_BUSY/COMMAND_RESULT 为 null（见类注释）。 */
    public TurnHandle turn() {
        return turn;
    }

    /**
     * 事件的来源：回合系事件解析自 {@link #turn}（与发射来源恒一致，免得两处取值
     * 出现「字段为 null 但语义需要来源」的 NPE 陷阱）；INJECTED（注入方）与
     * COMMAND_RESULT（命令方）取独立字段；REJECTED_BUSY 恒 null。
     */
    public TurnOrigin origin() {
        return turn != null ? turn.origin() : origin;
    }

    public ProgressUpdate progress() {
        return progress;
    }

    public AgentResponse response() {
        return response;
    }

    /** 仅 INJECTED（注入文本）与 COMMAND_RESULT（命令原文）。 */
    public String message() {
        return message;
    }

    public CancelCause cause() {
        return cause;
    }

    @Override
    public String toString() {
        return "TurnEvent{" + kind + ", session=" + sessionKey
                + (turn != null ? ", turn=" + turn.id() + "/" + turn.origin() : "")
                + (origin != null ? ", origin=" + origin : "")
                + (cause != null ? ", cause=" + cause : "")
                + (message != null ? ", message='" + abbreviate(message) + "'" : "")
                + (response != null ? ", response=" + (response.isSuccess() ? "ok" : "error") : "")
                + "}";
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
