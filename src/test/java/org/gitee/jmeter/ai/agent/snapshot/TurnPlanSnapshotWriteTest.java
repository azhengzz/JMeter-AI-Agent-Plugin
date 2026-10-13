package org.gitee.jmeter.ai.agent.snapshot;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.AgentResultCollector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 终点比对与条件落盘（writeIfChanged）的<b>真实路径</b>测试——补上审计确认的核心
 * 缺口：此前 completeTurn 的比对分支与写盘分支在全部用例中执行次数为零。经
 * package-private 的 dir 注入重载直接驱动生产代码（不经 GuiPackage/EDT），
 * 序列化走真实 SaveService（JMETER_HOME 门控同 SnapshotTreeCloneTest）。
 */
class TurnPlanSnapshotWriteTest {

    private static final long TURN = 31L;

    @BeforeAll
    static void initJMeterHome() {
        String home = System.getenv("JMETER_HOME");
        if (home == null) {
            home = System.getProperty("jmeter.home");
        }
        assumeTrue(home != null
                && new File(home, "bin" + File.separator + "saveservice.properties").isFile(),
                "JMETER_HOME unavailable; snapshot write test skipped");
        JMeterUtils.setJMeterHome(home);
    }

    @TempDir
    Path dir;

    private static TurnPlanSnapshotService newService() {
        // writeIfChanged 不触碰执行器（同步直调），池仅满足构造器
        return new TurnPlanSnapshotService(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snap-write-test");
            t.setDaemon(true);
            return t;
        }));
    }

    private static HashTree sampleTree(String planName) {
        TestPlan testPlan = new TestPlan();
        testPlan.setName(planName);
        ThreadGroup tg = new ThreadGroup();
        tg.setName("TG");
        LoopController loop = new LoopController();
        loop.setLoops(1);
        HashTree root = new ListedHashTree();
        root.add(testPlan).add(tg).add(loop);
        root.getTree(testPlan).add(new AgentResultCollector());
        return root;
    }

    private static HashTree detached(HashTree tree) {
        TreeCloner cloner = new TreeCloner(false);
        tree.traverse(cloner);
        return cloner.getClonedTree();
    }

    /** 作用域：基线 future 直接用起点树的序列化字节完成（与生产 beginTurn 任务等效）。 */
    private static TurnPlanSnapshotService.TurnSnapshotScope scopeWithBaseline(HashTree beginTree)
            throws Exception {
        TurnPlanSnapshotService.TurnSnapshotScope scope =
                new TurnPlanSnapshotService.TurnSnapshotScope(TURN, TurnOrigin.LOCAL_PANEL,
                        "write-test-plan.jmx");
        scope.baseline.complete(SnapshotTreeCapture.serializeDetached(detached(beginTree)));
        return scope;
    }

    @Test
    void changedTree_writesExactlyOneFile_withBeginBytesNotEndBytes() throws Exception {
        TurnPlanSnapshotService service = newService();
        HashTree beginTree = sampleTree("Plan-Begin");
        // 终态树 = 起点树的独立克隆上做回合内修改（与生产终点克隆同构的元素键树）
        HashTree endTree = sampleTree("Plan-Begin");
        HashTree endDetached = detached(endTree);
        TestPlan tp = (TestPlan) endDetached.getArray()[0];
        tp.setProperty("turn.marked", "end-state-marker");

        service.writeIfChanged(scopeWithBaseline(beginTree), endDetached, dir);

        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".jmx")).toList();
        }
        assertEquals(1, files.size(), "改树回合恰落一份快照");
        Path file = files.get(0);
        assertTrue(file.getFileName().toString().endsWith("-" + TURN + "-local-write-test-plan.jmx"),
                "文件名含 turnId、origin 与计划文件名段: " + file.getFileName());
        byte[] written = Files.readAllBytes(file);
        byte[] expectedBegin = SnapshotTreeCapture.serializeDetached(detached(beginTree));
        byte[] endBytes = SnapshotTreeCapture.serializeDetached(endDetached);
        assertFalse(java.util.Arrays.equals(written, endBytes),
                "落盘的绝不是终态字节（全变更最核心的正确性保证）");
        assertArrayEquals(expectedBegin, written, "落盘的是起点基线字节");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
                "原子写盘无临时文件残留");
    }

    @Test
    void unchangedTree_writesNothing() throws Exception {
        TurnPlanSnapshotService service = newService();
        HashTree tree = sampleTree("Plan-Same");
        // 终态 = 起点的独立克隆（净零回合的等价物：两次独立构建、字节一致）
        service.writeIfChanged(scopeWithBaseline(tree), detached(tree), dir);
        try (var s = Files.list(dir)) {
            assertEquals(0, s.count(), "树未变化（含净零）不落任何文件");
        }
    }

    @Test
    void userManualEditDuringChatTurn_alsoCountsAsChange() throws Exception {
        // Q2.3 语义：变化不问作者。用户手改 → 终态字节 != 起点 → 落起点基线
        TurnPlanSnapshotService service = newService();
        HashTree beginTree = sampleTree("Plan-Chat");
        HashTree endTree = sampleTree("Plan-Chat");
        HashTree endDetached = detached(endTree);
        ((TestPlan) endDetached.getArray()[0]).setProperty("user.edited", "true");
        service.writeIfChanged(scopeWithBaseline(beginTree), endDetached, dir);
        try (var s = Files.list(dir)) {
            assertEquals(1, s.filter(p -> p.toString().endsWith(".jmx")).count(),
                    "用户手改同样触发落盘");
        }
    }
}
