package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.gitee.jmeter.ai.agent.model.ToolResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Output-shape tests for {@link GetTestResultsTool}'s assertion-failure rendering:
 * failed assertions appear in the details view; samples without assertion failures
 * keep the exact pre-existing output shape (no empty assertion block).
 */
class GetTestResultsToolTest {

    private final GetTestResultsTool tool = new GetTestResultsTool();

    @BeforeEach
    void resetCollector() {
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT);
    }

    private static SampleResult sampleWithFailedAssertion(String label) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(false); // assertion failure flips the sample outcome
        result.setResponseCode("200");
        result.sampleStart();
        result.sampleEnd();
        AssertionResult failed = new AssertionResult("JSON Assertion");
        failed.setFailure(true);
        failed.setFailureMessage("Expected code=0 but got 401");
        result.addAssertionResult(failed);
        return result;
    }

    private static SampleResult plainSample(String label) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(true);
        result.setResponseCode("200");
        result.sampleStart();
        result.sampleEnd();
        return result;
    }

    @Test
    void detailsIncludeAssertionNameAndFailureMessage() {
        new AgentResultCollector().sampleOccurred(
                new SampleEvent(sampleWithFailedAssertion("login"), "tg-1"));

        ToolResult result = tool.executeInternal(Map.of(
                "format", "samples", "include_details", true));

        assertTrue(result.isSuccess());
        String content = result.getContent();
        assertTrue(content.contains("Failed Assertions"), "assertion block header expected");
        assertTrue(content.contains("JSON Assertion"), "assertion name expected");
        assertTrue(content.contains("Expected code=0 but got 401"), "failure message expected");
    }

    private static SampleResult timedSample(String label, long durationMs) {
        SampleResult result = new SampleResult(1_000_000L, durationMs);
        result.setSampleLabel(label);
        result.setSuccessful(true);
        result.setResponseCode("200");
        return result;
    }

    @Test
    void summaryIncludesPercentileRows() {
        AgentResultCollector collector = new AgentResultCollector();
        for (int t = 1; t <= 100; t++) {
            collector.sampleOccurred(new SampleEvent(timedSample("api", t), "tg"));
        }

        ToolResult result = tool.executeInternal(Map.of("format", "summary"));

        assertTrue(result.isSuccess());
        String content = result.getContent();
        assertTrue(content.contains("| P50 Response Time | 50 ms |"), "P50 row expected");
        assertTrue(content.contains("| P90 Response Time | 90 ms |"), "P90 row expected");
        assertTrue(content.contains("| P95 Response Time | 95 ms |"), "P95 row expected");
        assertTrue(content.contains("| P99 Response Time | 99 ms |"), "P99 row expected");
    }

    @Test
    void perSamplerBreakdownIncludesPercentileColumns() {
        AgentResultCollector collector = new AgentResultCollector();
        for (int t = 1; t <= 100; t++) {
            collector.sampleOccurred(new SampleEvent(timedSample("api", t), "tg"));
        }

        ToolResult result = tool.executeInternal(Map.of("format", "summary"));

        assertTrue(result.isSuccess());
        String content = result.getContent();
        assertTrue(content.contains("P50(ms)"), "per-sampler P50 column expected");
        assertTrue(content.contains("P90(ms)"), "per-sampler P90 column expected");
        assertTrue(content.contains("P95(ms)"), "per-sampler P95 column expected");
        assertTrue(content.contains("P99(ms)"), "per-sampler P99 column expected");
        assertTrue(content.contains("| api | 100 | 0 | 0.0% | 51 | 50 | 90 | 95 | 99 | 1 | 100 |"),
                "per-sampler row must carry percentile values between Avg and Min/Max");
    }

    @Test
    void noAssertionFailureKeepsLegacyOutputShape() {
        new AgentResultCollector().sampleOccurred(new SampleEvent(plainSample("ping"), "tg-1"));

        ToolResult result = tool.executeInternal(Map.of(
                "format", "samples", "include_details", true));

        assertTrue(result.isSuccess());
        assertFalse(result.getContent().contains("Failed Assertions"),
                "no empty assertion block for samples without assertion failures");
        assertTrue(result.getContent().contains("ping"));
    }
}
