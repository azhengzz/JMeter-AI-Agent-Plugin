package org.gitee.jmeter.ai.selection;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;

/**
 * L1 + L2 合并快照。
 *
 * <p>业务消费方通过单个 snapshot 一次性获得"当前选中的树节点"和"当前编辑面板焦点控件"
 * 两个层面的信息，避免业务方面板刷新两次。
 */
public final class SelectionSnapshot {

    /** L1：当前选中的 TestElement，可能为 {@code null}（树为空）。 */
    public final TestElement element;
    /** L1：当前选中的 JMeterTreeNode，可能为 {@code null}。 */
    public final JMeterTreeNode node;
    /** L1：元素类型（通过 {@code JMeterTreeUtils.getElementType} 反查），可能为 {@code null}。 */
    public final String elementType;
    /** L1：从根节点到当前节点的路径字符串（"Test Plan &gt; Thread Group &gt; ..."），可能为 {@code null}。 */
    public final String path;
    /** L1：子元素数量。 */
    public final int childCount;
    /** L1：节点 ID（{@code System.identityHashCode(node)}），与 GetSelectedElementTool 返回的 elementId 一致。 */
    public final int elementId;
    /** L2：当前焦点控件的描述，可能为 {@code null}（无焦点控件或焦点不在编辑面板内）。 */
    public final ElementInfo focusControl;

    public SelectionSnapshot(TestElement element, JMeterTreeNode node, String elementType,
                             String path, int childCount, int elementId, ElementInfo focusControl) {
        this.element = element;
        this.node = node;
        this.elementType = elementType;
        this.path = path;
        this.childCount = childCount;
        this.elementId = elementId;
        this.focusControl = focusControl;
    }

    /** 构造一个空 snapshot（树为空时使用）。 */
    public static SelectionSnapshot empty() {
        return new SelectionSnapshot(null, null, null, null, 0, 0, null);
    }

    public boolean isEmpty() {
        return element == null && node == null;
    }

    /**
     * 基于当前 snapshot 派生一个新的 snapshot，替换其中的 focusControl 字段。
     * 用于 L2 焦点变化时，复用 L1 上下文。
     */
    public SelectionSnapshot withFocus(ElementInfo newFocus) {
        return new SelectionSnapshot(element, node, elementType, path, childCount, elementId, newFocus);
    }
}
