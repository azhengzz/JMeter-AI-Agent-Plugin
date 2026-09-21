package org.gitee.jmeter.ai.agent.presenter;

/**
 * 回合来源：事件的显示域规则（如命令结果归发起方界面）与面板渲染分支据此区分。
 *
 * <p>REPUBLISH 回合的 {@code echoText} 为 null——其 "{@code [Injected] You:}" 回显
 * 已由 INJECTED 事件给过，面板不重画 You 行。
 */
public enum TurnOrigin {
    /** 面板 Enter/Send 提交（processMessage 由 AiChatPanel 在 EDT 同步调，fire-and-forget）。 */
    LOCAL_PANEL,
    /** jmeter-cli 直连（echoText 已带 "{@code [from cli] }" 前缀）。 */
    IPC_CLI,
    /** 对端 DelegateToInstanceTool 委派（"{@code [delegated-from …]}" 前缀；取代 delegated 布林）。 */
    IPC_DELEGATED,
    /** 回合收尾 re-publish 的孤儿回合（echoText=null，见类注释）。 */
    REPUBLISH;

    /**
     * 本地面板源（Enter/Send 提交、本地命令结果）：命令结果显示域与领养豁免的判据。
     * 穷尽 switch 无 default——新增来源常量时此处编译失败，强制显式归类。
     */
    public boolean isLocalPanel() {
        return switch (this) {
            case LOCAL_PANEL -> true;
            case IPC_CLI, IPC_DELEGATED, REPUBLISH -> false;
        };
    }

    /**
     * IPC 对端源（CLI 直连或跨实例委派）：取消终止回执与命令结果显示域的判据。
     * REPUBLISH 归 false——孤儿无对端调用方，回执文案无的放矢。
     */
    public boolean isIpcPeer() {
        return switch (this) {
            case IPC_CLI, IPC_DELEGATED -> true;
            case LOCAL_PANEL, REPUBLISH -> false;
        };
    }
}
