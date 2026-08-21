package org.gitee.jmeter.ai.agent.subagent;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial coverage for {@link SubagentManager#pruneTerminalStatuses()}: the
 * "bounded retention" of finished subagent statuses that keeps a long-lived instance
 * from accumulating them without bound, while a late/undeliverable result stays
 * queryable via {@code subagent_status} inside its window.
 *
 * <p>These tests override the config knobs via reflection (see
 * {@code JMeterUtils.appProperties} is null in pure unit tests) to a tiny value so
 * the eviction branch actually runs — the defaults (60s / 10) never fire under the
 * fast-finishing, low-count subagents of the other suites.
 *
 * <p>Spawns are serial by default (maxConcurrent=1), so each test spawns one subagent,
 * awaits its delivery latch, then spawns the next. This mirrors real usage and avoids
 * the concurrency limit rejecting the burst.
 */
class SubagentStatusPruneTest {

    private static final String RETENTION_KEY = "agent.subagent.status.retention.seconds";
    private static final String MAX_COMPLETED_KEY = "agent.subagent.status.max.completed";

    private static class InstantAiService implements AiService {
        private final String answer;
        InstantAiService(String answer) { this.answer = answer; }
        @Override public String getName() { return "fake"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return LLMResponse.text(answer);
        }
    }

    @BeforeEach
    void initProperties() throws Exception {
        Field f = JMeterUtils.class.getDeclaredField("appProperties");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new Properties());
        }
    }

    @AfterEach
    void clearProperties() throws Exception {
        JMeterUtils.getJMeterProperties().remove(RETENTION_KEY);
        JMeterUtils.getJMeterProperties().remove(MAX_COMPLETED_KEY);
    }

    /** A manager whose sink counts delivered results. */
    private static final class TestRig {
        final SubagentManager manager;
        CountDownLatch delivered = new CountDownLatch(1);

        TestRig() {
            this.manager = new SubagentManager(
                new InstantAiService("answer"),
                SubagentTestSupport.contextBuilder(), null, new ToolRegistry(),
                (key, tok, msg) -> { delivered.countDown(); return true; });
        }

        /** Spawn one subagent and block until its result is delivered (run finished). */
        void spawnAndAwait(String sessionKey, String label) throws Exception {
            delivered = new CountDownLatch(1);
            manager.spawn("audit " + label, label, sessionKey, () -> true);
            assertTrue(delivered.await(15, TimeUnit.SECONDS), "subagent " + label + " did not finish in time");
            // The sink fires before the run's finally (releaseSlot → prune) settles, so
            // pump a beat until the terminal-status set reaches a stable size.
            awaitStable(sessionKey);
        }

        /** Wait until the terminal count stops changing (prune has fully settled). */
        void awaitStable(String sessionKey) throws Exception {
            long deadline = System.currentTimeMillis() + 5_000;
            int prev = -1;
            while (System.currentTimeMillis() < deadline) {
                int cur = countTerminal(manager, sessionKey);
                if (cur == prev && prev >= 0) {
                    Thread.sleep(100); // one more beat to confirm a true stable state
                    if (cur == countTerminal(manager, sessionKey)) {
                        return;
                    }
                }
                prev = cur;
                Thread.sleep(20);
            }
        }
    }

    private static int countTerminal(SubagentManager manager, String sessionKey) {
        int n = 0;
        for (SubagentStatus s : manager.getStatuses(sessionKey, true)) {
            if (s.isTerminal()) {
                n++;
            }
        }
        return n;
    }

    /** Per-session cap: the oldest terminal status is evicted beyond max.completed. */
    @Test
    void perSessionCapEvictsOldestBeyondMax() throws Exception {
        JMeterUtils.getJMeterProperties().setProperty(MAX_COMPLETED_KEY, "2");
        JMeterUtils.getJMeterProperties().setProperty(RETENTION_KEY, "3600"); // TTL effectively off

        TestRig rig = new TestRig();
        try {
            rig.spawnAndAwait("chat:main", "a");
            rig.spawnAndAwait("chat:main", "b");
            rig.spawnAndAwait("chat:main", "c");

            List<SubagentStatus> terminal = rig.manager.getStatuses("chat:main", true).stream()
                .filter(SubagentStatus::isTerminal).toList();
            assertEquals(2, terminal.size(),
                "per-session cap of 2 should bound terminal statuses exactly, got "
                    + terminal.stream().map(SubagentStatus::getLabel).toList());

            List<String> labels = terminal.stream().map(SubagentStatus::getLabel).sorted().toList();
            assertEquals(List.of("b", "c"), labels,
                "the oldest terminal status must be the one evicted");
        } finally {
            rig.manager.shutdown();
        }
    }

    /** retention.seconds=0 disables TTL reclaiming (statuses survive regardless of age). */
    @Test
    void zeroRetentionDisablesTtl() throws Exception {
        JMeterUtils.getJMeterProperties().setProperty(MAX_COMPLETED_KEY, "100"); // cap off
        JMeterUtils.getJMeterProperties().setProperty(RETENTION_KEY, "0"); // 0 = TTL off

        TestRig rig = new TestRig();
        try {
            rig.spawnAndAwait("chat:main", "first");
            rig.spawnAndAwait("chat:main", "second");

            assertEquals(2, countTerminal(rig.manager, "chat:main"),
                "TTL=0 must not reclaim by age (both survive)");
        } finally {
            rig.manager.shutdown();
        }
    }

    /** max.completed=0 disables the per-session cap: nothing is evicted by count. */
    @Test
    void zeroCapDisablesCountBound() throws Exception {
        JMeterUtils.getJMeterProperties().setProperty(MAX_COMPLETED_KEY, "0");
        JMeterUtils.getJMeterProperties().setProperty(RETENTION_KEY, "3600");

        TestRig rig = new TestRig();
        try {
            for (int i = 0; i < 4; i++) {
                rig.spawnAndAwait("chat:main", "s" + i);
            }
            assertEquals(4, countTerminal(rig.manager, "chat:main"),
                "cap=0 means no count-based eviction at all");
        } finally {
            rig.manager.shutdown();
        }
    }

    /** Running (non-terminal) statuses are never pruned, regardless of the knobs. */
    @Test
    void runningStatusesAreNeverPruned() throws Exception {
        AiService hanging = new InstantAiService("never") {
            @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return LLMResponse.text("late");
            }
        };
        JMeterUtils.getJMeterProperties().setProperty(MAX_COMPLETED_KEY, "1");
        JMeterUtils.getJMeterProperties().setProperty(RETENTION_KEY, "0");

        SubagentManager manager = new SubagentManager(
            hanging, SubagentTestSupport.contextBuilder(), null, new ToolRegistry(),
            (key, tok, msg) -> true);
        try {
            manager.spawn("slow", "slow", "chat:main", () -> true);
            Thread.sleep(300); // let the run register and stay non-terminal

            List<SubagentStatus> statuses = manager.getStatuses("chat:main", true);
            assertFalse(statuses.isEmpty(), "a running subagent must remain observable");
            assertFalse(statuses.get(0).isTerminal(), "the running subagent must not be marked terminal");
        } finally {
            manager.shutdown();
        }
    }
}