package org.qainsights.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Remoteable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Result collector injected into the GUI tree before test execution.
 * Collects sample results in memory for the AI agent to query.
 * <p>
 * All data is stored in static fields so that the cloned instance (used by the
 * engine) and the original instance (read by tools) share the same data.
 * Implements NoThreadClone so a single engine-level instance receives all samples.
 * Auto-removes itself from the GUI tree after test ends.
 */
public class AgentResultCollector extends AbstractTestElement
        implements SampleListener, TestStateListener, NoThreadClone, Remoteable {

    private static final Logger log = LoggerFactory.getLogger(AgentResultCollector.class);
    public static final String ELEMENT_NAME = "__agent_result_collector__";
    private static final int MAX_SAMPLES = 1000;
    private static final int MAX_BODY_SIZE = 4096;

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

    public AgentResultCollector() {
        setName(ELEMENT_NAME);
        setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.reporters.gui.SummariserGui");
    }

    /**
     * Reset all collected data. Called before each test start.
     */
    public static void reset() {
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
    }

    @Override
    public void testStarted() {
        testStarted("local");
    }

    @Override
    public void testStarted(String host) {
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
        testRunning = false;
        testEndTime = System.currentTimeMillis();
        log.info("AgentResultCollector: test ended. Total samples: {}, errors: {}",
                totalSamples.get(), totalErrors.get());

        SwingUtilities.invokeLater(() -> removeFromGuiTree());
    }

    @Override
    public void sampleOccurred(SampleEvent e) {
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
                truncate(result.getRequestHeaders()));
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
     * Can be called from any thread — dispatches to EDT internally.
     */
    public static void removeFromGuiTree() {
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

    private static void updateMin(AtomicLong minRef, long value) {
        long current;
        while (value < (current = minRef.get())) {
            if (minRef.compareAndSet(current, value)) break;
        }
    }

    private static String truncate(String data) {
        if (data == null) return null;
        if (data.length() <= MAX_BODY_SIZE) return data;
        return data.substring(0, MAX_BODY_SIZE) + "\n... [truncated, total " + data.length() + " chars]";
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
        List<SampleSnapshot> all = new ArrayList<>(recentSamples);
        int start = Math.min(offset, all.size());
        int end = Math.min(start + limit, all.size());
        if (start >= end) return Collections.emptyList();
        return all.subList(start, end);
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

        public SampleSnapshot(String label, String responseCode, boolean success,
                              long responseTime, long latency, long timestamp,
                              String url, int errorCount,
                              String requestData, String requestHeaders,
                              String responseData, String responseHeaders) {
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
