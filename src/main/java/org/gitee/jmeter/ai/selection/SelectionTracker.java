package org.gitee.jmeter.ai.selection;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JTree;
import javax.swing.Timer;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 选中追踪器（核心安装器）。
 *
 * <p>静态单例，提供两个事件源的统一订阅入口：
 * <ul>
 *   <li><b>L1 树节点选中</b>：注册在 {@code MainFrame.getTree()} 上的 TreeSelectionListener</li>
 *   <li><b>L2 焦点控件</b>：通过 200ms {@link Timer} 主动轮询
 *       {@link KeyboardFocusManager#getFocusOwner()}，不监听任何 AWT 事件 —
 *       实测 {@code Toolkit.addAWTEventListener(FOCUS_EVENT_MASK)} 会干扰
 *       JMeter 表格 / RSyntaxTextArea 的 cell editor，主动轮询方式无此副作用</li>
 * </ul>
 *
 * <p>初始化时机：由 {@link SelectionInitCommand} 在 JMeter {@code ADD_ALL} 事件时调用，
 * 也可由 {@code AiChatPanel} 构造函数兜底调用 — {@link #install()} 幂等。
 *
 * <p>线程约束：所有回调在 EDT 派发；AWT 事件已在 EDT 上，无需再 invokeLater，
 * 但业务消费方回调实现里禁止做重活。
 */
public final class SelectionTracker {

    private static final Logger log = LoggerFactory.getLogger(SelectionTracker.class);

    /** L2 焦点轮询间隔（毫秒）。200ms 既能保证视觉响应又不会拖慢 EDT。 */
    private static final int L2_POLL_INTERVAL_MS = 200;

    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static final List<SelectionListener> LISTENERS = new CopyOnWriteArrayList<>();

    /** 最近一次派发的 snapshot，用于 L2 焦点变化时复用 L1 上下文（避免重新查 node）。 */
    private static volatile SelectionSnapshot lastSnapshot;

    /** 是否将当前选中元素注入到发给 LLM 的 UserMessage 上下文中。会话内有效，不持久化。 */
    private static volatile boolean injectToContextEnabled = true;

    // 保留引用以便未来扩展卸载逻辑
    private static TreeSelectionListener treeListener;
    private static Timer l2PollTimer;
    /** 上一次轮询到的焦点控件，用于去重（同一控件不重复触发）。 */
    private static Component lastPolledFocusComponent;

    private SelectionTracker() {
    }

    /** 幂等安装。重复调用安全。 */
    public static synchronized void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        GuiPackage gp = GuiPackage.getInstance();
        if (gp == null || gp.getMainFrame() == null) {
            installed.set(false);
            log.debug("SelectionTracker.install: GuiPackage or MainFrame not ready, deferring");
            return;
        }
        try {
            installL1(gp);
            installL2();
            log.info("SelectionTracker installed (L2 polling at {}ms)", L2_POLL_INTERVAL_MS);
        } catch (Exception e) {
            installed.set(false);
            log.error("SelectionTracker.install failed", e);
        }
    }

    public static boolean isInstalled() {
        return installed.get();
    }

    /** 返回最近一次 L1/L2 事件产生的 snapshot，可能返回 null（初始化未完成或树为空）。 */
    public static SelectionSnapshot getCurrentSnapshot() {
        return lastSnapshot;
    }

    /** 开关：是否将选中元素注入 UserMessage。 */
    public static boolean isInjectToContextEnabled() {
        return injectToContextEnabled;
    }

    public static void setInjectToContextEnabled(boolean enabled) {
        injectToContextEnabled = enabled;
    }

    public static void addListener(SelectionListener l) {
        if (l != null) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(SelectionListener l) {
        LISTENERS.remove(l);
    }

    /** 调试用：当前订阅的 listener 数量，用于检查泄漏。 */
    public static int getListenerCount() {
        return LISTENERS.size();
    }

    /**
     * 向指定 listener 同步派发一次当前选中状态。
     * 用于新订阅者立即获取当前选中元素（而非等下一次事件）。
     */
    public static void fireInitialSnapshot(SelectionListener l) {
        if (l == null) return;
        try {
            GuiPackage gp = GuiPackage.getInstance();
            JMeterTreeNode node = (gp != null && gp.getTreeListener() != null)
                    ? gp.getTreeListener().getCurrentNode() : null;
            SelectionSnapshot snapshot = buildSnapshot(node, null);
            l.onComponentSelected(snapshot);
        } catch (Exception e) {
            log.warn("SelectionTracker.fireInitialSnapshot failed", e);
        }
    }

    // ---------- L1 ----------

    private static void installL1(GuiPackage gp) {
        JTree tree = gp.getMainFrame().getTree();
        treeListener = (TreeSelectionEvent e) -> {
            TreePath path = e.getPath();
            if (path == null) return;
            if (!e.isAddedPath()) return; // 排除取消选中
            Object last = path.getLastPathComponent();
            if (!(last instanceof JMeterTreeNode)) return;
            log.debug("L1 TreeSelection: {}", ((JMeterTreeNode) last).getName());
            fireComponentSelected((JMeterTreeNode) last);
        };
        tree.addTreeSelectionListener(treeListener);
        log.info("L1 TreeSelectionListener registered on JMeter MainFrame tree");
    }

    private static void fireComponentSelected(JMeterTreeNode node) {
        // L1 切换时清空 L2 焦点缓存（新元素编辑面板的焦点控件由轮询发现）
        lastPolledFocusComponent = null;
        SelectionSnapshot snapshot = buildSnapshot(node, null);
        lastSnapshot = snapshot;
        log.info("fireComponentSelected: node={}, listeners={}",
                node.getName(), LISTENERS.size());
        for (SelectionListener l : LISTENERS) {
            try {
                l.onComponentSelected(snapshot);
            } catch (Exception ex) {
                log.warn("SelectionListener.onComponentSelected threw", ex);
            }
        }
    }

    // ---------- L2 ----------

    /**
     * L2 焦点追踪：主动轮询 {@link KeyboardFocusManager#getFocusOwner()}。
     *
     * <p><b>为什么不用 {@code Toolkit.addAWTEventListener(FOCUS_EVENT_MASK)}</b>：
     * 实测在 JMeter 表格 / RSyntaxTextArea（Body Data）编辑场景下，AWTEventListener
     * 会干扰 cell editor，导致用户输入丢失。改用主动轮询方式完全不监听 AWT 事件，
     * 不会拦截或修改事件分发链。
     *
     * <p>轮询在 EDT 上执行（{@link Timer} 默认在 EDT 派发），
     * {@code getFocusOwner()} 是 O(1) 操作，性能开销可忽略。
     */
    private static void installL2() {
        l2PollTimer = new Timer(L2_POLL_INTERVAL_MS, e -> pollFocus());
        l2PollTimer.setRepeats(true);
        l2PollTimer.setInitialDelay(L2_POLL_INTERVAL_MS);
        l2PollTimer.start();
        log.info("L2 focus polling started (interval={}ms)", L2_POLL_INTERVAL_MS);
    }

    /**
     * Timer 在 EDT 上派发：查询当前焦点控件，与上次相同则跳过（去重）。
     *
     * <p><b>关键</b>：不调用 {@link GuiPackage#getCurrentGui()} — 该方法内部会执行
     * {@code clearGui() + configure(curNode)}，相当于每次都重置整个编辑面板的内容，
     * 会导致用户正在编辑的输入框内容被覆盖、表格新增的行被清除。
     *
     * <p><b>焦点离开编辑面板时不清空</b>：当用户从 JMeter 编辑面板切到 AiChatPanel
     * 输入框/按钮或其他位置时，保留上一次的 L2 焦点信息。因为展示栏目的是让 AI 看到用户
     * "刚才在编辑哪个字段"，用户切到 AiChatPanel 写消息时不应该把上下文抹掉。
     * 只有 L1 切换（选了新元素）时才会真正清空 L2（见 {@link #fireComponentSelected}）。
     */
    private static void pollFocus() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp == null || gp.getMainFrame() == null) {
                return;
            }

            Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (c == null) {
                // 焦点完全丢失，保持上次 L2 内容不变
                return;
            }
            if (!isInsideMainPanel(c)) {
                // 焦点离开 JMeter 编辑面板（如移到 AiChatPanel 输入框/按钮），
                // 保留上一次的 L2 内容，不清空也不更新
                log.debug("L2 pollFocus: focus moved outside main panel to {}, keeping last L2 info",
                        c.getClass().getSimpleName());
                return;
            }

            // 不再用 c == lastPolledFocusComponent 提前 return。
            // 对 JTable/JList 这类 selection 容器，focus owner 实例不变但 selectedRow/Col 会变；
            // describe 是 O(1) 操作，下游 SelectionContextBar.computeKey 会基于 value 去重，
            // 内容真正没变时不会触发 paint。
            if (c != lastPolledFocusComponent) {
                log.info("L2 pollFocus: new focus owner = {}", c.getClass().getSimpleName());
                lastPolledFocusComponent = c;
            }
            ElementInfo info = ElementDescriptor.describe(c);
            fireElementFocused(info);
        } catch (Exception ex) {
            log.warn("SelectionTracker.pollFocus failed", ex);
        }
    }

    private static void fireElementFocused(ElementInfo info) {
        SelectionSnapshot base = lastSnapshot;
        if (base == null || base.isEmpty()) {
            GuiPackage gp = GuiPackage.getInstance();
            JMeterTreeNode node = (gp != null && gp.getTreeListener() != null)
                    ? gp.getTreeListener().getCurrentNode() : null;
            base = buildSnapshot(node, info);
        } else {
            base = base.withFocus(info);
        }
        lastSnapshot = base;
        if (!LISTENERS.isEmpty()) {
            log.debug("fireElementFocused: info={}, listeners={}",
                    info == null ? "null" : info.controlType + "/" + info.fieldName, LISTENERS.size());
        }
        for (SelectionListener l : LISTENERS) {
            try {
                l.onElementFocused(base);
            } catch (Exception ex) {
                log.warn("SelectionListener.onElementFocused threw", ex);
            }
        }
    }

    // ---------- helpers ----------

    private static SelectionSnapshot buildSnapshot(JMeterTreeNode node, ElementInfo focusControl) {
        if (node == null) return SelectionSnapshot.empty();
        if (node.getTestElement() == null) return SelectionSnapshot.empty();
        String elementType = JMeterTreeUtils.getElementType(node);
        String path = JMeterTreeUtils.getNodePath(node);
        int childCount = node.getChildCount();
        // 与 JMeterTreeUtils.buildTreeData 第 67 行一致：System.identityHashCode(node)
        int elementId = System.identityHashCode(node);
        return new SelectionSnapshot(node.getTestElement(), node, elementType, path, childCount, elementId, focusControl);
    }

    /**
     * 判断焦点控件是否位于 JMeter 主窗口的右侧编辑面板内。
     *
     * <p>JMeter 5.6.3 中 MainFrame.mainPanel 是一个无名 JScrollPane，
     * 不能用 name 反查。改用 MainFrame 实例引用比较：检查 ancestor 链中
     * 是否含 MainFrame，且不在 MainFrame 的 JTree（左侧导航）或 menuBar 上。
     *
     * <p><b>排除本插件的 GUI</b>：AiChatPanel 通过 JSplitPane 挂在 MainFrame 的
     * contentPane 上，所以它的子组件也会满足"ancestor 含 MainFrame"。必须显式排除
     * {@code org.gitee.jmeter.ai.} 包下的组件，否则用户切到 AiChatPanel 输入框时
     * L2 会被错误地更新成 AiChatPanel 的控件。
     */
    private static boolean isInsideMainPanel(Component c) {
        if (c == null) return false;
        GuiPackage gp = GuiPackage.getInstance();
        if (gp == null || gp.getMainFrame() == null) return false;
        org.apache.jmeter.gui.MainFrame mainFrame = gp.getMainFrame();

        Container p = c.getParent();
        boolean insideMainFrame = false;
        while (p != null) {
            if (p == mainFrame) {
                insideMainFrame = true;
                break;
            }
            // 排除本插件的 GUI 组件（AiChatPanel 及其子孙）
            if (p.getClass().getName().startsWith("org.gitee.jmeter.ai.")) {
                return false;
            }
            // 左侧 JTree 直接挂在 MainFrame 上，其内控件不算编辑面板
            if (p instanceof javax.swing.JTree) return false;
            // 顶部 toolbar / menu 不算
            if (p instanceof javax.swing.JMenuBar) return false;
            if (p instanceof javax.swing.JToolBar) return false;
            p = p.getParent();
        }
        return insideMainFrame;
    }
}
