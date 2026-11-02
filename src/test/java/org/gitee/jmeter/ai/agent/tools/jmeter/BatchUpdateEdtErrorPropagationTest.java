package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Regression anchor: batch_update MUST NOT swallow an EDT execution error into a
 * success-shaped "Updated 0 of N" result — the EDT wait timeout carries an
 * outcome-UNKNOWN contract the model needs to see (a late-executing orphan plus a
 * blind retry would double-apply the change).
 *
 * <p>Drives the package-private {@code applyBatchUpdateOnEdt} seam directly with a
 * pinned EDT and a tiny {@code agent.tools.edt.wait.timeout.ms}.
 */
class BatchUpdateEdtErrorPropagationTest {

    @BeforeAll
    static void ensureJMeterProps() throws Exception {
        try {
            java.awt.EventQueue.invokeAndWait(() -> { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Field f = JMeterUtils.class.getDeclaredField("appProperties");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new Properties());
        }
    }

    @Test
    @Timeout(20)
    void edtTimeout_errorIsReturned_notSwallowedAsSuccess() throws Exception {
        String key = "agent.tools.edt.wait.timeout.ms";
        JMeterUtils.setProperty(key, "300");
        CountDownLatch edtGate = new CountDownLatch(1);
        java.awt.EventQueue.invokeLater(() -> {
            try {
                edtGate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            JMeterTreeNode node = mock(JMeterTreeNode.class);
            org.mockito.Mockito.when(node.getTestElement()).thenReturn(mock(TestElement.class));
            Map<Integer, JMeterTreeNode> nodeMap = new HashMap<>();
            nodeMap.put(1, node);

            BatchUpdateJMeterElementTool tool = new BatchUpdateJMeterElementTool();
            List<AbstractBatchJMeterElementTool.ElementResult> results = new ArrayList<>();

            String refusal = tool.applyBatchUpdateOnEdt(mock(GuiPackage.class), nodeMap,
                    Map.of(), Map.of(), null, results);

            // 吞没回归:修复前这里返回 null(无 guard 违规时),调用方以成功形态收尾
            assertTrue(refusal != null && refusal.startsWith("Failed to batch update elements: "),
                    "EDT error must surface, got: " + refusal);
            assertTrue(refusal.contains("UNKNOWN"), "outcome-UNKNOWN contract must reach the model");
            assertTrue(refusal.contains("get_test_plan_tree"), "recovery guidance must reach the model");
            assertTrue(results.isEmpty(), "no element may be reported updated on timeout");
        } finally {
            edtGate.countDown();
            JMeterUtils.setProperty(key, ""); // 回落默认,防跨测试类污染
        }
    }

    @Test
    @Timeout(20)
    void guardViolation_stillTakesPrecedencePath() {
        // 快乐守卫冒烟:EDT 健康时 helper 正常返回 null(无 guard 违规、无 EDT 错误),
        // 不因重构改变 guard 分支行为
        JMeterTreeNode node = mock(JMeterTreeNode.class);
        org.mockito.Mockito.when(node.getTestElement()).thenReturn(mock(TestElement.class));
        Map<Integer, JMeterTreeNode> nodeMap = new HashMap<>();
        nodeMap.put(1, node);

        BatchUpdateJMeterElementTool tool = new BatchUpdateJMeterElementTool();
        List<AbstractBatchJMeterElementTool.ElementResult> results = new ArrayList<>();
        GuiPackage gui = mock(GuiPackage.class);
        org.mockito.Mockito.when(gui.getTreeModel()).thenReturn(null); // refresh 块 try/catch 兜底

        String refusal = tool.applyBatchUpdateOnEdt(gui, nodeMap, Map.of(), Map.of(), null, results);
        if (refusal != null) {
            fail("healthy EDT must not produce a refusal, got: " + refusal);
        }
    }
}
