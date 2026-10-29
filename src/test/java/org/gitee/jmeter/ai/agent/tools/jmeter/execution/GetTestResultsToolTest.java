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
