package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.event.ActionEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-logic surface of {@link AgentResultCollector}: provenance recording,
 * the start-command whitelist, the pre-listener early-return paths (stop-filter, agent-skip),
 * and the save-before-run re-injection arming state machine. None of these require a live
 * JMeter GUI. Full injection / save-strip / end-to-end behaviour is covered by manual GUI
 * verification.
 */
class AgentResultCollectorTest {

    @BeforeEach
    void resetStaticState() {
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.USER);
        AgentResultCollector.clearStartArmed(new ActionEvent(new Object(), 0, "reset"));
    }

    @Test
    void resetRecordsProvenance() {
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT);
        assertEquals(AgentResultCollector.RunProvenance.AGENT, AgentResultCollector.getLastProvenance());

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.USER);
        assertEquals(AgentResultCollector.RunProvenance.USER, AgentResultCollector.getLastProvenance());

        AgentResultCollector.reset(null);
        assertEquals(AgentResultCollector.RunProvenance.USER, AgentResultCollector.getLastProvenance());

        AgentResultCollector.reset();
        assertEquals(AgentResultCollector.RunProvenance.USER, AgentResultCollector.getLastProvenance());
    }

    @Test
    void isStartCommandWhitelistAcceptsStartVariantsOnly() {
        assertTrue(AgentResultCollector.isStartCommand(ActionNames.ACTION_START));
        assertTrue(AgentResultCollector.isStartCommand(ActionNames.ACTION_START_NO_TIMERS));
        assertTrue(AgentResultCollector.isStartCommand(ActionNames.RUN_TG));
        assertTrue(AgentResultCollector.isStartCommand(ActionNames.RUN_TG_NO_TIMERS));

        // STOP/SHUTDOWN must be excluded (a stop must never reset captured results).
        assertFalse(AgentResultCollector.isStartCommand(ActionNames.ACTION_STOP));
        assertFalse(AgentResultCollector.isStartCommand(ActionNames.ACTION_SHUTDOWN));
        // VALIDATE_TG excluded (validation runs would mislead get_test_status).
        assertFalse(AgentResultCollector.isStartCommand(ActionNames.VALIDATE_TG));
        assertFalse(AgentResultCollector.isStartCommand(null));
        assertFalse(AgentResultCollector.isStartCommand("anything-else"));
    }

    @Test
    void onTestStartActionSkipsStopCommandWithoutMutatingState() {
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT);
        ActionEvent stop = new ActionEvent(new Object(), ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_STOP);

        AgentResultCollector.onTestStartAction(stop); // must no-op: stop never resets/injects/arms

        assertEquals(AgentResultCollector.RunProvenance.AGENT, AgentResultCollector.getLastProvenance());
        assertFalse(AgentResultCollector.isStartArmed());
    }

    @Test
    void onTestStartActionSkipsAgentSourceRunButArmsReinject() {
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT);
        ActionEvent agentStart = new ActionEvent(
                new RunTestTool(), ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_START);

        AgentResultCollector.onTestStartAction(agentStart); // skips inject (RunTestTool did it) but arms

        assertEquals(AgentResultCollector.RunProvenance.AGENT, AgentResultCollector.getLastProvenance());
        // save-before-run fix: an Agent start still arms so the Save POST-listener can
        // restore the collector that popupShouldSave's SAVE stripped.
        assertTrue(AgentResultCollector.isStartArmed());
    }

    @Test
    void onTestStartActionIsSafeWithoutGui() {
        // No GuiPackage in a unit test -> the USER path returns at the gui==null guard,
        // and any internal failure is swallowed (never throw out of a pre-listener).
        ActionEvent userStart = new ActionEvent(new Object(), ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_START);

        AgentResultCollector.onTestStartAction(userStart); // must not throw
    }

    @Test
    void startReinjectArmingLifecycle() {
        assertFalse(AgentResultCollector.isStartArmed());

        AgentResultCollector.armForStartReinject(); // as RunTestTool does before firing ACTION_START
        assertTrue(AgentResultCollector.isStartArmed());

        AgentResultCollector.clearStartArmed(new ActionEvent(new Object(), 0, "start")); // Start POST
        assertFalse(AgentResultCollector.isStartArmed());
    }

    @Test
    void reinjectIfArmedIsNoopWhenNotArmed() {
        assertFalse(AgentResultCollector.isStartArmed());
        ActionEvent save = new ActionEvent(new Object(), ActionEvent.ACTION_PERFORMED, ActionNames.SAVE);

        AgentResultCollector.reinjectIfArmed(save); // not armed -> early return, no EDT/injection

        assertFalse(AgentResultCollector.isStartArmed());
    }

    // --- assertion result capture (sampleOccurred path, headless) ---

    private static SampleResult sample(String label, boolean success) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(success);
        result.setResponseCode(success ? "200" : "500");
        result.sampleStart();
        result.sampleEnd();
        return result;
    }

    @Test
    void assertionFailureCapturedIntoSnapshot() {
        SampleResult result = sample("login", false);
        AssertionResult failed = new AssertionResult("JSON Assertion");
        failed.setFailure(true);
        failed.setFailureMessage("Expected code=0 but got 401");
        result.addAssertionResult(failed);

        new AgentResultCollector().sampleOccurred(new SampleEvent(result, "tg-1"));

        List<AgentResultCollector.SampleSnapshot> samples = AgentResultCollector.getRecentSamples(10, 0);
        assertEquals(1, samples.size());
        List<AgentResultCollector.AssertionSnapshot> assertions = samples.get(0).assertions;
        assertNotNull(assertions, "assertion list must be present when the sampler has assertions");
        assertEquals(1, assertions.size());
        assertTrue(assertions.get(0).failed);
        assertEquals("JSON Assertion", assertions.get(0).name);
        assertEquals("Expected code=0 but got 401", assertions.get(0).failureMessage);
    }

    @Test
    void longAssertionFailureMessageTruncated() {
        SampleResult result = sample("login", false);
        AssertionResult failed = new AssertionResult("big");
        failed.setFailure(true);
        failed.setFailureMessage("x".repeat(10_000));
        result.addAssertionResult(failed);

        new AgentResultCollector().sampleOccurred(new SampleEvent(result, "tg-1"));

        AgentResultCollector.SampleSnapshot snapshot = AgentResultCollector.getRecentSamples(10, 0).get(0);
        String message = snapshot.assertions.get(0).failureMessage;
        assertTrue(message.length() < 10_000, "message must be truncated");
        assertTrue(message.contains("...(truncated"), "truncation marker expected");
    }

    @Test
    void sampleWithoutAssertionsHasNullAssertionField() {
        new AgentResultCollector().sampleOccurred(new SampleEvent(sample("plain", true), "tg-1"));

        AgentResultCollector.SampleSnapshot snapshot = AgentResultCollector.getRecentSamples(10, 0).get(0);
        assertNull(snapshot.assertions, "no-assertion sample must carry a null assertion field");
    }

    @Test
    void transactionParentSubResultAssertionsReachable() {
        // TransactionController "generate parent sample": sub-samples nest inside the
        // parent result and never fire their own sampleOccurred — recursion is required
        SampleResult sub = sample("child", false);
        AssertionResult failed = new AssertionResult("child-assert");
        failed.setFailure(true);
        failed.setFailureMessage("child expected 200 got 500");
        sub.addAssertionResult(failed);
        SampleResult parent = sample("txn", false);
        parent.addSubResult(sub);

        new AgentResultCollector().sampleOccurred(new SampleEvent(parent, "tg-1"));

        List<AgentResultCollector.AssertionSnapshot> assertions =
                AgentResultCollector.getRecentSamples(10, 0).get(0).assertions;
        assertNotNull(assertions);
        assertTrue(assertions.stream().anyMatch(a ->
                        "child-assert".equals(a.name) && "child expected 200 got 500".equals(a.failureMessage)),
                "sub-result assertion failure must be captured");
    }

    @Test
    void passingAssertionCapturedWithFailedFalse() {
        SampleResult result = sample("login", true);
        AssertionResult passed = new AssertionResult("status-200");
        passed.setFailure(false);
        result.addAssertionResult(passed);

        new AgentResultCollector().sampleOccurred(new SampleEvent(result, "tg-1"));

        List<AgentResultCollector.AssertionSnapshot> assertions =
                AgentResultCollector.getRecentSamples(10, 0).get(0).assertions;
        assertEquals(1, assertions.size());
        assertFalse(assertions.get(0).failed);
    }

    @Test
    void completedAgentSmokePassedMatrix() {
        // 门禁点仲裁复合判定的全矩阵：只有「AGENT 来源 + 冒烟运行 + 已完成 +
        // 发过流量零 error」四条全真才为真
        AgentResultCollector normal = new AgentResultCollector();
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT, false);
        normal.testStarted("local");
        normal.sampleOccurred(new SampleEvent(sample("n1", true), "tg"));
        normal.testEnded("local");
        assertFalse(AgentResultCollector.completedAgentSmokePassed(),
                "a normal full-scale AGENT run must never arbitrate as smoke evidence");

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT, true);
        AgentResultCollector smoke = new AgentResultCollector();
        smoke.testStarted("local");
        smoke.sampleOccurred(new SampleEvent(sample("s1", true), "tg"));
        smoke.sampleOccurred(new SampleEvent(sample("s2", true), "tg"));
        smoke.testEnded("local");
        assertTrue(AgentResultCollector.completedAgentSmokePassed(),
                "a completed clean AGENT smoke run must arbitrate");

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.USER, true);
        AgentResultCollector user = new AgentResultCollector();
        user.testStarted("local");
        user.sampleOccurred(new SampleEvent(sample("u1", true), "tg"));
        user.testEnded("local");
        assertFalse(AgentResultCollector.completedAgentSmokePassed(),
                "USER provenance must not arbitrate");

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT, true);
        AgentResultCollector running = new AgentResultCollector();
        running.testStarted("local");
        assertFalse(AgentResultCollector.completedAgentSmokePassed(),
                "a still-running smoke must not arbitrate");

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT, true);
        AgentResultCollector failed = new AgentResultCollector();
        failed.testStarted("local");
        failed.sampleOccurred(new SampleEvent(sample("bad", false), "tg"));
        failed.testEnded("local");
        assertFalse(AgentResultCollector.completedAgentSmokePassed(),
                "a smoke with errors must not arbitrate");

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT, true);
        AgentResultCollector empty = new AgentResultCollector();
        empty.testStarted("local");
        empty.testEnded("local");
        assertFalse(AgentResultCollector.completedAgentSmokePassed(),
                "a zero-sample smoke run must not arbitrate");
    }

    @Test
    void lateEventsFromPreviousRunGenerationAreIgnored() {
        // stop 后立即冒烟的幻影判定钉定：垂死引擎的迟到 testEnded/testStarted/sample
        // 落在新一代 reset 之后——必须整体丢弃，不得伪造「已结束未在跑」启发式、
        // 不得污染当前统计，也不得摘掉当前运行的收集器节点
        AgentResultCollector oldCollector = new AgentResultCollector();
        SampleResult oldSample = sample("stale", true);

        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT); // 新一代（本次冒烟）
        AgentResultCollector current = new AgentResultCollector();

        oldCollector.testStarted("local");
        oldCollector.sampleOccurred(new SampleEvent(oldSample, "tg-old"));
        oldCollector.testEnded("local");

        assertEquals(0, AgentResultCollector.getTestStartTimeMs(), "stale start must not register");
        assertEquals(0, AgentResultCollector.getTestEndTimeMs(), "stale end must not register");
        assertEquals(0, AgentResultCollector.getSummary().totalSamples, "stale samples must not count");
        assertFalse(AgentResultCollector.isTestRunning());

        // 当前代实例照常记录
        current.testStarted("local");
        assertTrue(AgentResultCollector.getTestStartTimeMs() > 0);
        current.testEnded("local");
        assertTrue(AgentResultCollector.getTestEndTimeMs() > 0);
    }
}
