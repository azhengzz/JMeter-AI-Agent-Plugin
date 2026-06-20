package org.gitee.jmeter.ai.selection;

/**
 * 选中事件业务回调接口。
 *
 * <p>由业务消费方实现，通过 {@link SelectionTracker#addListener(SelectionListener)} 订阅。
 * 所有回调在 EDT 派发，业务方不应在回调中做重活，必要时自行转发到工作线程。
 */
public interface SelectionListener {

    /**
     * L1：用户在测试树中切换了选中的组件（点击节点 / ActionRouter 触发的 EDIT、PASTE、ADD）。
     *
     * @param snapshot 包含新的 element / node / path；focusControl 字段携带上一个焦点控件信息（可能为 null）
     */
    void onComponentSelected(SelectionSnapshot snapshot);

    /**
     * L2：右侧编辑面板中焦点控件变化（去抖 80ms 后触发）。
     *
     * @param snapshot 包含当前 L1 上下文 + 新的 focusControl
     */
    void onElementFocused(SelectionSnapshot snapshot);
}
