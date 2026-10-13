package org.gitee.jmeter.ai.agent.snapshot;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 树克隆与「克隆上剥收集器」的纯 HashTree 层测试。序列化经真实
 * {@code SaveService}——其静态初始化必须读到 {JMETER_HOME}/bin/saveservice.properties
 * （别名/XStream 转换器的唯一来源），故以 JMETER_HOME 环境变量定位安装目录，
 * 缺席时跳过（CI 无 JMeter 安装的环境不跑本类）。
 */
class SnapshotTreeCloneTest {

    private static boolean jmeterReady;

    @BeforeAll
    static void initJMeterHome() {
        String home = System.getenv("JMETER_HOME");
        if (home == null) {
            home = System.getProperty("jmeter.home");
        }
        jmeterReady = home != null
                && new File(home, "bin" + File.separator + "saveservice.properties").isFile();
        assumeTrue(jmeterReady, "JMETER_HOME unavailable; SaveService serialization test skipped");
        JMeterUtils.setJMeterHome(home);
    }

    /** 构造一棵元素键树（模拟 replaceNodeKeysWithElements 之后、GUI 无关）：TestPlan 下挂 TG/Loop 与收集器。 */
    private static HashTree buildSampleTree() {
        TestPlan testPlan = new TestPlan();
        testPlan.setName("Snapshot Test Plan");
        ThreadGroup tg = new ThreadGroup();
        tg.setName("TG-Snapshot");
        tg.setNumThreads(1);
        tg.setRampUp(1);
        LoopController loop = new LoopController();
        loop.setName("Loop");
        loop.setLoops(1);

        HashTree root = new ListedHashTree();
        HashTree planSub = root.add(testPlan);
        HashTree tgSub = planSub.add(tg);
        tgSub.add(loop);
        planSub.add(new AgentResultCollector());
        return root;
    }

    @Test
    void serializedSnapshotExcludesCollectorAndKeepsPlanElements() throws Exception {
        HashTree liveLike = buildSampleTree();
        // 模拟 captureDetachedPlanClone：深克隆脱离副本（活树不动）
        TreeCloner cloner = new TreeCloner(false);
        liveLike.traverse(cloner);
        HashTree detached = cloner.getClonedTree();
        assertNotNull(detached);

        byte[] bytes = SnapshotTreeCapture.serializeDetached(detached);
        String xml = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(xml.contains("<?xml"), "是 JMX XML");
        assertTrue(xml.contains("Snapshot Test Plan"), "计划内容在");
        assertTrue(xml.contains("LoopController") || xml.contains("LoopControlPanel"),
                "元素经别名/类名序列化: " + firstLine(xml));
        assertFalse(xml.contains(AgentResultCollector.ELEMENT_NAME),
                "收集器节点不泄漏进快照");
        assertFalse(xml.contains("AgentResultCollector"),
                "收集器类名不泄漏进快照");

        // 活树原样：收集器仍在其上（剥离只发生在克隆副本）
        assertTrue(containsCollector(liveLike), "活树上的收集器节点未被摘除");
    }

    @Test
    void identicalIndependentlyBuiltTreesSerializeToIdenticalBytes() throws Exception {
        byte[] a = SnapshotTreeCapture.serializeDetached(cloneOf(buildSampleTree()));
        byte[] b = SnapshotTreeCapture.serializeDetached(cloneOf(buildSampleTree()));
        assertArrayEquals(a, b, "同构树独立构建 → 字节一致（终态比对的核心假设）");
    }

    @Test
    void stripCollectorLeavesOtherNodesIntact() {
        HashTree detached = cloneOf(buildSampleTree());
        int before = countNodes(detached);
        SnapshotTreeCapture.stripCollectorNodes(detached);
        int after = countNodes(detached);
        assertFalse(containsCollector(detached), "剥离后无收集器");
        assertEquals(before - after, 1, "恰移除一个收集器节点");
    }

    @Test
    void replaceNodeKeysWithElementsSwapsNodeKeysForElements() {
        // getTestPlan() 的原生形态：JMeterTreeNode 为键。JMeter 特有变换此前零覆盖。
        TestPlan testPlan = new TestPlan();
        testPlan.setName("NodeKey Plan");
        ThreadGroup tg = new ThreadGroup();
        tg.setName("TG-NK");
        org.apache.jmeter.gui.tree.JMeterTreeNode planNode =
                new org.apache.jmeter.gui.tree.JMeterTreeNode(testPlan, null);
        org.apache.jmeter.gui.tree.JMeterTreeNode tgNode =
                new org.apache.jmeter.gui.tree.JMeterTreeNode(tg, null);
        HashTree root = new ListedHashTree();
        root.add(planNode).add(tgNode);

        SnapshotTreeCapture.replaceNodeKeysWithElements(root);

        assertEquals(testPlan, root.getArray()[0], "顶层键已从节点换为 TestElement");
        assertEquals(tg, root.getTree(testPlan).getArray()[0], "子树键同步替换、结构保留");
    }

    private static HashTree cloneOf(HashTree tree) {
        TreeCloner cloner = new TreeCloner(false);
        tree.traverse(cloner);
        return cloner.getClonedTree();
    }

    private static boolean containsCollector(HashTree tree) {
        for (Object key : tree.list()) {
            if (key instanceof AgentResultCollector) {
                return true;
            }
            if (containsCollector(tree.getTree(key))) {
                return true;
            }
        }
        return false;
    }

    private static int countNodes(HashTree tree) {
        int count = 0;
        for (Object key : tree.list()) {
            count += 1 + countNodes(tree.getTree(key));
        }
        return count;
    }

    private static String firstLine(String s) {
        return s.split("\n", 2)[0];
    }
}
