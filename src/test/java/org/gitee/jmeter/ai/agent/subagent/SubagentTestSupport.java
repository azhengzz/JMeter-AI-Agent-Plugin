package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared fixtures for subagent tests.
 *
 * <p>Tests build a REAL {@link ContextBuilder} over a throwaway workspace rather
 * than passing null: {@code AgentRunner} dereferences it on every iteration, so a
 * null would fail the run for a reason that never occurs in production (the
 * factory always injects a real one).
 */
final class SubagentTestSupport {

    private SubagentTestSupport() {
    }

    /** A ContextBuilder backed by an empty temporary workspace. */
    static ContextBuilder contextBuilder() {
        try {
            Path workspace = Files.createTempDirectory("subagent-test-ws");
            workspace.toFile().deleteOnExit();
            return new ContextBuilder(new MemoryStore(workspace), workspace);
        } catch (Exception e) {
            throw new IllegalStateException("could not create test workspace", e);
        }
    }
}
