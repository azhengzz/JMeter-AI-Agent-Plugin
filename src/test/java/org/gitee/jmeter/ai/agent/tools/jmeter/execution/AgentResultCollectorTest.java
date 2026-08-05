package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.gui.action.ActionNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.event.ActionEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-logic surface of {@link AgentResultCollector}: provenance recording,
 * the start-command whitelist, the pre-listener early-return paths (stop-filter, agent-skip),
 * and the save-before-run re-injection arming state machine. None of these require a live
 * JMeter GUI. Full injection / save-strip / end-to-end behaviour is covered by manual GUI
 * verification (tasks 7.x).
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

        // STOP/SHUTDOWN must be excluded (R2: a stop must never reset captured results).
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
        // R4 + save-before-run fix: an Agent start still arms so the Save POST-listener can
        // restore the collector that popupShouldSave's SAVE stripped.
        assertTrue(AgentResultCollector.isStartArmed());
    }

    @Test
    void onTestStartActionIsSafeWithoutGui() {
        // No GuiPackage in a unit test -> the USER path returns at the gui==null guard,
        // and any internal failure is swallowed (R1: never throw out of a pre-listener).
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
}
