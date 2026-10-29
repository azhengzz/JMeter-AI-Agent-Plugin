package org.gitee.jmeter.ai.agent.snapshot;

import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * 快照用树捕获：EDT 上取计划树并深克隆为脱离 GUI 的副本，池线程上序列化为字节。
 *
 * <p>克隆管线与 JMeter 自家 Save/Start 同源：{@code getTreeModel().getTestPlan()} 返回
 * 以 {@link JMeterTreeNode} 为键的即时树（TestElement 与活树共享），先按 Save 的
 * convertSubTree 习惯把键换成 TestElement，再经 {@link TreeCloner}(false) 深克隆——
 * 克隆后的元素是独立副本，其序列化可以安全移出 EDT 与活树的编辑并发。
 *
 * <p>收集器剥离只作用于<b>克隆副本</b>（按 {@code instanceof AgentResultCollector}
 * 识别）——绝不摘活动 GUI 树上的节点，那是 Save 前置监听器与 {@code testEnded}
 * 自清理的职责；快照若在运行中的回合终态采样，活树上存在收集器属正常状态。
 */
final class SnapshotTreeCapture {

    private static final Logger log = LoggerFactory.getLogger(SnapshotTreeCapture.class);

    private SnapshotTreeCapture() {
    }

    /**
     * EDT 上捕获当前测试计划的脱离副本。必须在非 EDT 线程调用（内部
     * {@code invokeAndWait}）。空计划/无 TestPlan 节点/EDT 派发失败返回 null。
     */
    static HashTree captureDetachedPlanClone(GuiPackage gui) {
        HashTree[] result = new HashTree[1];
        Exception err = EdtRunner.run(gui, () -> {
            Object root = gui.getTreeModel().getRoot();
            if (!(root instanceof JMeterTreeNode) || ((JMeterTreeNode) root).getChildCount() == 0) {
                return; // 空树（无 TestPlan 节点）：无可快照内容
            }
            HashTree plan = gui.getTreeModel().getTestPlan();
            replaceNodeKeysWithElements(plan);
            TreeCloner cloner = new TreeCloner(false);
            plan.traverse(cloner);
            result[0] = cloner.getClonedTree();
        });
        if (err != null) {
            log.warn("Snapshot: EDT tree clone failed (best-effort): {}", err.toString());
            return null;
        }
        return result[0];
    }

    /**
     * {@link #captureDetachedPlanClone} 的中断位保持变体：清位执行克隆、结束回置。
     *
     * <p>上一回合 Stop 的迟到中断（signalCancel 读到 runnerThread 引用到
     * {@code interrupt()} 实际送达之间，线程可能已复用进入下一回合）若落在 EDT
     * 等待中，{@code invokeAndWait} 直接抛 InterruptedException 使克隆失败、
     * 本回合静默失去基线。清位后克隆可正常完成；回置保证 {@code isAborted}
     * （abortFlag ‖ isInterrupted）的中止语义对运行循环完全不变——该位若真是
     * 陈旧残留，与无快照代码时一样照常被消费。
     */
    static HashTree captureDetachedPlanClonePreservingInterrupt(GuiPackage gui) {
        boolean interrupted = Thread.interrupted();
        try {
            return captureDetachedPlanClone(gui);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 序列化脱离副本为 .jmx 字节（与 {@code SaveService.saveTree} 同一格式）。
     * 序列化前剥离收集器节点；期间切换 TCCL 到 JMeter 核心加载器（XStream 转换器
     * 经类名解析，快照执行器线程的默认 TCCL 不保证可见）。
     */
    static byte[] serializeDetached(HashTree detached) throws IOException {
        stripCollectorNodes(detached);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SaveService.class.getClassLoader());
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            SaveService.saveTree(detached, bos);
            return bos.toByteArray();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /** 递归剔除克隆副本上的收集器节点（纯数据结构操作，副本已脱离 GUI）。 */
    static void stripCollectorNodes(HashTree tree) {
        for (Object key : new ArrayList<>(tree.list())) {
            if (key instanceof AgentResultCollector) {
                tree.remove(key);
            } else {
                stripCollectorNodes(tree.getTree(key));
            }
        }
    }

    /**
     * 节点键 → TestElement 键替换（对 getTestPlan() 返回的即时树就地变换，
     * 不影响 GUI 模型本体）。子树先于本键替换，与 JMeter Save.convertSubTree 同序。
     * Package-private 以便无 GUI 单测（JMeter 特有变换，见 SnapshotTreeCloneTest）。
     */
    static void replaceNodeKeysWithElements(HashTree tree) {
        for (Object key : new ArrayList<>(tree.list())) {
            replaceNodeKeysWithElements(tree.getTree(key));
            if (key instanceof JMeterTreeNode node) {
                TestElement element = node.getTestElement();
                if (element != null) {
                    tree.replaceKey(key, element);
                }
            }
        }
    }
}
