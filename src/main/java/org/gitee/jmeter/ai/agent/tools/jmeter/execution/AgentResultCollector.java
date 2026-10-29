package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Remoteable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Result collector injected into the GUI tree before test execution.
 * Collects sample results in memory for the AI agent to query.
 * <p>
 * All data is stored in static fields so that the cloned instance (used by the
 * engine) and the original instance (read by tools) share the same data.
 * Implements NoThreadClone so a single engine-level instance receives all samples.
 * Auto-removes itself from the GUI tree after test ends.
 * <p>
 * Capture is not limited to Agent-initiated ({@code run_test}) runs: a global
 * {@code Start.class} pre-action listener (see {@link #onTestStartAction}) injects
 * this collector before any GUI-initiated run too, so users can ask the Agent about
 * results of tests they started by clicking JMeter's Run button.
 */
public class AgentResultCollector extends AbstractTestElement
        implements SampleListener, TestStateListener, NoThreadClone, Remoteable {

    /** Who initiated the run currently being captured. */
    public enum RunProvenance { USER, AGENT }

    private static final Logger log = LoggerFactory.getLogger(AgentResultCollector.class);
    public static final String ELEMENT_NAME = "__agent_result_collector__";
    private static final int MAX_SAMPLES = 1000; // 最大保留采样数
    private static final int MAX_BODY_SIZE = 4096; // 单个响应体最大字符数，超出截断

    // --- All state is STATIC so clones share the same data ---

    private static final AtomicInteger totalSamples = new AtomicInteger(0);
    private static final AtomicInteger totalErrors = new AtomicInteger(0);
    private static final AtomicLong responseTimeSum = new AtomicLong(0);
    private static final AtomicLong responseTimeMin = new AtomicLong(Long.MAX_VALUE);
    private static final AtomicLong responseTimeMax = new AtomicLong(0);

    private static final ConcurrentHashMap<String, PerLabelStats> perLabelStats = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<SampleSnapshot> recentSamples = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger sampleCount = new AtomicInteger(0);

    private static volatile boolean testRunning = false;
    private static volatile long testStartTime = 0;
    private static volatile long testEndTime = 0;
    private static volatile RunProvenance lastProvenance = RunProvenance.USER;
    /** 最近一次运行是否 run_test 冒烟模式（reset 时与来源一并原子置位）。 */
    private static volatile boolean lastRunWasSmoke = false;

    /**
     * 运行代数：reset 翻新；采集器实例构造（含引擎侧树克隆对实例字段的浅拷贝）时
     * 捕获绑定代。事件写点（testStarted/testEnded/sampleOccurred）校验绑定代一致
     * 才写——前代引擎的迟到事件（如 stop 后立即冒烟时垂死引擎的异步 testEnded）
     * 不得污染当前运行的静态状态，也不得经 invokeLater 摘掉刚为当前运行注入的
     * 收集器节点。
     */
    private static final AtomicLong runGeneration = new AtomicLong();
    private final long boundGeneration = runGeneration.get();

    /**
     * True between a start's pre-action arming and the matching Start post-action listener.
     * JMeter's {@code Start.doAction} may synchronously fire SAVE via {@code popupShouldSave}
     * (default {@code save_automatically_before_run=true}) BEFORE {@code startEngine} clones
     * the tree; the Save PRE-listener strips the collector for a clean .jmx, so this flag tells
     * the Save POST-listener to restore the collector before startEngine reads the live tree.
     */
    private static volatile boolean startArmed = false;

    public AgentResultCollector() {
        setName(ELEMENT_NAME);
        setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.reporters.gui.SummariserGui");
    }

    /**
     * Reset all collected data, tagging the upcoming run with the given provenance.
     * Called before each test start (USER via the global Start pre-listener,
     * AGENT via {@link RunTestTool}).
     */
    public static void reset(RunProvenance provenance) {
        reset(provenance, false);
    }

    /**
     * @param smoke 本次运行是否 {@code run_test mode=smoke}（与来源一并原子置位，
     *              不拆 reset+mark 两步——两步窗口会把并发用户运行误标成冒烟）。
     *              证据仲裁只认冒烟运行的采集：普通 normal 全量运行即使零错也
     *              不得被洗成冒烟证据
     */
    public static void reset(RunProvenance provenance, boolean smoke) {
        runGeneration.incrementAndGet();
        totalSamples.set(0);
        totalErrors.set(0);
        responseTimeSum.set(0);
        responseTimeMin.set(Long.MAX_VALUE);
        responseTimeMax.set(0);
        perLabelStats.clear();
        recentSamples.clear();
        sampleCount.set(0);
        testRunning = false;
        testStartTime = 0;
        testEndTime = 0;
        lastProvenance = (provenance != null) ? provenance : RunProvenance.USER;
        lastRunWasSmoke = smoke;
    }

    /** Backwards-compatible reset; equivalent to {@link #reset(RunProvenance)} with USER. */
    public static void reset() {
        reset(RunProvenance.USER, false);
    }

    public static RunProvenance getLastProvenance() {
        return lastProvenance;
    }

    /** Package-private for tests. */
    static boolean isStartArmed() {
        return startArmed;
    }

    /**
     * Arm the save-post re-injection. Called by {@link RunTestTool} before it fires
     * ACTION_START (so Agent runs re-inject correctly regardless of the capture toggle) and by
     * {@link #onTestStartAction} for USER runs.
     */
    public static void armForStartReinject() {
        startArmed = true;
    }

    @Override
    public void testStarted() {
        testStarted("local");
    }

    @Override
    public void testStarted(String host) {
        if (boundGeneration != runGeneration.get()) {
            return; // 前代引擎的迟到事件：不写当前运行状态
        }
        testRunning = true;
        testStartTime = System.currentTimeMillis();
        testEndTime = 0;
        log.info("AgentResultCollector: test started");
    }

    @Override
    public void testEnded() {
        testEnded("local");
    }

    @Override
    public void testEnded(String host) {
        if (boundGeneration != runGeneration.get()) {
            return; // 前代引擎的迟到收尾：不写结束态、不摘当前运行的收集器节点
        }
        testRunning = false;
        testEndTime = System.currentTimeMillis();
        log.info("AgentResultCollector: test ended. Total samples: {}, errors: {}",
                totalSamples.get(), totalErrors.get());

        SwingUtilities.invokeLater(() -> removeFromGuiTree());
    }

    @Override
    public void sampleOccurred(SampleEvent e) {
        if (boundGeneration != runGeneration.get()) {
            return; // 前代引擎的迟到采样：不计入当前统计
        }
        SampleResult result = e.getResult();
        if (result == null) return;

        long time = result.getTime();
        boolean success = result.isSuccessful();
        String label = result.getSampleLabel();
        int errorCount = result.getErrorCount();

        totalSamples.incrementAndGet();
        responseTimeSum.addAndGet(time);
        updateMin(responseTimeMin, time);
        updateMax(responseTimeMax, time);
        if (!success || errorCount > 0) {
            totalErrors.incrementAndGet();
        }

        if (label != null && !label.isEmpty()) {
            perLabelStats.computeIfAbsent(label, k -> new PerLabelStats()).record(time, success);
        }

        SampleSnapshot snapshot = new SampleSnapshot(
                label, result.getResponseCode(), success, time,
                result.getLatency(), result.getStartTime(),
                result.getUrlAsString(), errorCount,
                truncate(result.getSamplerData()),
                truncate(result.getResponseHeaders()),
                truncate(result.getResponseDataAsString()),
                truncate(result.getRequestHeaders()),
                captureAssertions(result));
        recentSamples.add(snapshot);

        while (sampleCount.incrementAndGet() > MAX_SAMPLES) {
            if (recentSamples.poll() != null) {
                sampleCount.decrementAndGet();
            } else {
                sampleCount.decrementAndGet();
                break;
            }
        }
    }

    @Override
    public void sampleStarted(SampleEvent e) { }

    @Override
    public void sampleStopped(SampleEvent e) { }

    /**
     * Remove any existing collector nodes from the GUI tree.
     * Searches under TestPlan (not root) since that's where addComponent places them.
     * <p>MUST be invoked on the EDT — mutates the tree model directly (DefaultTreeModel
     * contract). All current callers (RunTestTool's EDT block, the testEnded invokeLater,
     * and the Start/Save pre-action listeners dispatched via ActionRouter) are on the EDT.
     */
    public static void removeFromGuiTree() {
        assert EventQueue.isDispatchThread() : "removeFromGuiTree must run on the EDT";
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) return;

        JMeterTreeNode root = (JMeterTreeNode) gui.getTreeModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode parent = (JMeterTreeNode) root.getChildAt(i);
            for (int j = parent.getChildCount() - 1; j >= 0; j--) {
                JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(j);
                if (ELEMENT_NAME.equals(child.getName())) {
                    gui.getTreeModel().removeNodeFromParent(child);
                    log.info("AgentResultCollector: removed from GUI tree under {}", parent.getName());
                }
            }
        }
    }

    // --- Global run-capture hook (Start.class / Save.class pre-action listeners) ---

    /**
     * Entry point for the {@code Start.class} ActionRouter pre-action listener. Injects +
     * resets this collector for user-initiated GUI runs BEFORE {@code Start.doAction} clones
     * the test tree, so TestCompiler wires it as a SampleListener for the run.
     * <p>Skips: non-start commands (stop/shutdown/validate), runs already in progress, and
     * Agent-initiated runs ({@link RunTestTool} already injected + reset in its own earlier
     * EDT dispatch). Swallows every exception so a collector failure can never abort a start
     * (ActionRouter runs pre-listeners and doAction in one try block).
     */
    public static void onTestStartAction(ActionEvent e) {
        try {
            if (!isStartCommand(e.getActionCommand())) {
                return;
            }
            if (isRunInProgress()) {
                return;
            }
            // Arm re-injection for BOTH provenances: Start.doAction may synchronously fire
            // SAVE (popupShouldSave, default save_automatically_before_run=true) BEFORE
            // startEngine clones the tree; the Save PRE-listener strips the collector for a
            // clean .jmx, so the Save POST-listener must restore it before startEngine reads
            // the live tree. (RunTestTool also arms via armForStartReinject for the
            // toggle-off case where this listener is not registered.)
            startArmed = true;
            RunProvenance p = (e.getSource() instanceof RunTestTool)
                    ? RunProvenance.AGENT : RunProvenance.USER;
            if (p == RunProvenance.AGENT) {
                // RunTestTool already injected + reset(AGENT) in its own EDT block, which
                // completes before this pre-listener's EDT dispatch — FIFO EDT order.
                return;
            }
            removeFromGuiTree();
            reset(RunProvenance.USER);
            addComponentSafely();
        } catch (Throwable t) {
            log.error("AgentResultCollector: pre-start injection failed", t);
        }
    }

    /**
     * Entry point for the {@code Save.class} ActionRouter pre-action listener. Strips any
     * collector node before Save reads the live GUI tree, preventing leakage into .jmx files
     * (covers save-during-run, which the testEnded auto-remove cannot). Independent of the
     * capture toggle — anti-leak is always on.
     */
    public static void stripCollectorNode(ActionEvent e) {
        try {
            removeFromGuiTree();
        } catch (Throwable t) {
            log.error("AgentResultCollector: save-time strip failed", t);
        }
    }

    /** Whitelist of start-variant commands that should trigger injection. Package-private for tests. */
    static boolean isStartCommand(String cmd) {
        return ActionNames.ACTION_START.equals(cmd)
                || ActionNames.ACTION_START_NO_TIMERS.equals(cmd)
                || ActionNames.RUN_TG.equals(cmd)
                || ActionNames.RUN_TG_NO_TIMERS.equals(cmd);
    }

    private static boolean isRunInProgress() {
        return JMeterContextService.getTestStartTime() > 0 || testRunning;
    }

    /**
     * {@code Save.class} POST-action listener. After Save.doAction wrote a collector-free .jmx
     * (the PRE-listener stripped it), restore the collector so startEngine — which
     * Start.doAction runs AFTER popupShouldSave — clones a tree that contains it. Armed by
     * {@link #onTestStartAction} (USER) / {@link #armForStartReinject} (RunTestTool); disarmed
     * by {@link #clearStartArmed} (Start POST). No-op when not armed.
     */
    public static void reinjectIfArmed(ActionEvent e) {
        if (!startArmed) {
            return;
        }
        try {
            removeFromGuiTree();
            addComponentSafely();
        } catch (Throwable t) {
            log.error("AgentResultCollector: post-save re-injection failed", t);
        }
    }

    /** {@code Start.class} POST-action listener: startEngine has now cloned the tree, so disarm. */
    public static void clearStartArmed(ActionEvent e) {
        startArmed = false;
    }

    /**
     * Inject a fresh collector under the TestPlan node with the JMeter core classloader as TCCL
     * (addComponent → GuiPackage.getGui → Class.forName needs it). Swallows nothing — callers
     * wrap in try/catch(Throwable). EDT-only.
     */
    private static void addComponentSafely() throws IllegalUserActionException {
        assert EventQueue.isDispatchThread() : "addComponentSafely must run on the EDT";
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) return;
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(gui.getClass().getClassLoader());
            JMeterTreeNode root = (JMeterTreeNode) gui.getTreeModel().getRoot();
            JMeterTreeNode testPlanNode = findTestPlanNode(root);
            if (testPlanNode != null) {
                gui.getTreeModel().addComponent(new AgentResultCollector(), testPlanNode);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    private static JMeterTreeNode findTestPlanNode(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof TestPlan) {
                return child;
            }
        }
        return null;
    }

    private static void updateMin(AtomicLong minRef, long value) {
        long current;
        while (value < (current = minRef.get())) {
            if (minRef.compareAndSet(current, value)) break;
        }
    }

    private static String truncate(String data) {
        if (data == null) return null;
        if (data.length() <= MAX_BODY_SIZE) return data;
        return data.substring(0, MAX_BODY_SIZE) + "\n...(truncated, total " + data.length() + " chars)";
    }

    /**
     * Capture per-sample assertion results (name / failed-or-error flag / failure message).
     * Transaction controllers with "generate parent sample" nest their sub-samples inside
     * the parent result (no separate sampleOccurred fires for them), so sub-results are
     * walked recursively — without this, assertion failures inside a transaction are
     * structurally invisible. Returns null when neither the sample nor any sub-result
     * carries assertions.
     */
    private static List<AssertionSnapshot> captureAssertions(SampleResult result) {
        List<AssertionSnapshot> captured = new ArrayList<>();
        collectAssertions(result, captured);
        return captured.isEmpty() ? null : captured;
    }

    private static void collectAssertions(SampleResult result, List<AssertionSnapshot> out) {
        AssertionResult[] results = result.getAssertionResults();
        if (results != null) {
            for (AssertionResult ar : results) {
                if (ar == null) continue;
                out.add(new AssertionSnapshot(ar.getName(), ar.isFailure() || ar.isError(),
                        truncate(ar.getFailureMessage())));
            }
        }
        SampleResult[] subs = result.getSubResults();
        if (subs != null) {
            for (SampleResult sub : subs) {
                if (sub != null) {
                    collectAssertions(sub, out);
                }
            }
        }
    }

    private static void updateMax(AtomicLong maxRef, long value) {
        long current;
        while (value > (current = maxRef.get())) {
            if (maxRef.compareAndSet(current, value)) break;
        }
    }

    // --- Static read methods for tools ---

    public static boolean isTestRunning() {
        return testRunning;
    }

    /**
     * 门禁点仲裁（update_goal complete 证据缺失时的当场对账）：最近一次运行是
     * 本插件发起的冒烟、确已完成、且发过流量零 error 才为真。撕裂读推演：任何
     * reset 只会否定条件（USER 来源败 / 完成态清零 / 非冒烟置否），不存在混合
     * 读出假 PASS 的交错——条件全真必对应某次真实完成的 AGENT 冒烟快照。调用方
     * 须另校验 goal 的冒烟预算确实消耗过（smokeAttempts &gt; 0，防跨 goal 陈旧
     * 数据被冒名）。样本&gt;0 &amp;&amp; 错误==0 与 {@code RunTestTool.isSmokePass}
     * 是同一硬标准。
     */
    public static boolean completedAgentSmokePassed() {
        if (getLastProvenance() != RunProvenance.AGENT || !lastRunWasSmoke) {
            return false;
        }
        if (testRunning || testEndTime <= 0) {
            return false;
        }
        return totalSamples.get() > 0 && totalErrors.get() == 0;
    }

    public static long getTestStartTimeMs() {
        return testStartTime;
    }

    public static long getTestEndTimeMs() {
        return testEndTime;
    }

    public static SummaryStats getSummary() {
        int samples = totalSamples.get();
        int errors = totalErrors.get();
        long sum = responseTimeSum.get();
        long min = responseTimeMin.get();
        long max = responseTimeMax.get();

        double avgTime = samples > 0 ? (double) sum / samples : 0;
        double errorRate = samples > 0 ? (double) errors / samples * 100 : 0;

        long elapsed;
        if (testStartTime > 0) {
            long end = testRunning ? System.currentTimeMillis() : (testEndTime > 0 ? testEndTime : System.currentTimeMillis());
            elapsed = end - testStartTime;
        } else {
            elapsed = 0;
        }
        double throughput = elapsed > 0 ? (double) samples / (elapsed / 1000.0) : 0;

        Map<String, SummaryStats> perLabel = new LinkedHashMap<>();
        for (Map.Entry<String, PerLabelStats> entry : perLabelStats.entrySet()) {
            perLabel.put(entry.getKey(), entry.getValue().toSummary());
        }

        return new SummaryStats(samples, errors, errorRate,
                min == Long.MAX_VALUE ? 0 : min, max, avgTime,
                throughput, elapsed, testRunning, perLabel);
    }

    public static List<SampleSnapshot> getRecentSamples(int limit, int offset) {
        return getRecentSamples(limit, offset, s -> true);
    }

    public static List<SampleSnapshot> getRecentSamples(int limit, int offset, Predicate<SampleSnapshot> filter) {
        List<SampleSnapshot> all = new ArrayList<>(recentSamples);
        return all.stream()
                .filter(filter)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    // --- Inner classes ---

    public static class SummaryStats {
        public final int totalSamples;
        public final int totalErrors;
        public final double errorRate;
        public final long minResponseTime;
        public final long maxResponseTime;
        public final double avgResponseTime;
        public final double throughput;
        public final long elapsedMs;
        public final boolean running;
        public final Map<String, SummaryStats> perLabel;

        public SummaryStats(int totalSamples, int totalErrors, double errorRate,
                            long minResponseTime, long maxResponseTime, double avgResponseTime,
                            double throughput, long elapsedMs, boolean running,
                            Map<String, SummaryStats> perLabel) {
            this.totalSamples = totalSamples;
            this.totalErrors = totalErrors;
            this.errorRate = errorRate;
            this.minResponseTime = minResponseTime;
            this.maxResponseTime = maxResponseTime;
            this.avgResponseTime = avgResponseTime;
            this.throughput = throughput;
            this.elapsedMs = elapsedMs;
            this.running = running;
            this.perLabel = perLabel;
        }
    }

    public static class SampleSnapshot {
        public final String label;
        public final String responseCode;
        public final boolean success;
        public final long responseTime;
        public final long latency;
        public final long timestamp;
        public final String url;
        public final int errorCount;
        public final String requestData;
        public final String requestHeaders;
        public final String responseData;
        public final String responseHeaders;
        /** Per-assertion results; null when the sampler has no assertions. */
        public final List<AssertionSnapshot> assertions;

        public SampleSnapshot(String label, String responseCode, boolean success,
                              long responseTime, long latency, long timestamp,
                              String url, int errorCount,
                              String requestData, String requestHeaders,
                              String responseData, String responseHeaders,
                              List<AssertionSnapshot> assertions) {
            this.label = label;
            this.responseCode = responseCode;
            this.success = success;
            this.responseTime = responseTime;
            this.latency = latency;
            this.timestamp = timestamp;
            this.url = url;
            this.errorCount = errorCount;
            this.requestData = requestData;
            this.requestHeaders = requestHeaders;
            this.responseData = responseData;
            this.responseHeaders = responseHeaders;
            this.assertions = assertions;
        }
    }

    /** Single assertion outcome attached to a {@link SampleSnapshot}. */
    public static class AssertionSnapshot {
        public final String name;
        public final boolean failed;
        public final String failureMessage;

        public AssertionSnapshot(String name, boolean failed, String failureMessage) {
            this.name = name;
            this.failed = failed;
            this.failureMessage = failureMessage;
        }
    }

    private static class PerLabelStats {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final AtomicLong timeSum = new AtomicLong(0);
        final AtomicLong timeMin = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong timeMax = new AtomicLong(0);

        void record(long time, boolean success) {
            count.incrementAndGet();
            timeSum.addAndGet(time);
            if (!success) errors.incrementAndGet();

            long current;
            while (time < (current = timeMin.get())) {
                if (timeMin.compareAndSet(current, time)) break;
            }
            while (time > (current = timeMax.get())) {
                if (timeMax.compareAndSet(current, time)) break;
            }
        }

        SummaryStats toSummary() {
            int c = count.get();
            int e = errors.get();
            long sum = timeSum.get();
            long min = timeMin.get();
            long max = timeMax.get();
            return new SummaryStats(c, e, c > 0 ? (double) e / c * 100 : 0,
                    min == Long.MAX_VALUE ? 0 : min, max, c > 0 ? (double) sum / c : 0,
                    0, 0, false, null);
        }
    }
}
