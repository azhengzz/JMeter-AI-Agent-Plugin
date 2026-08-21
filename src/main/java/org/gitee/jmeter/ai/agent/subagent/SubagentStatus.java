package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.model.ToolEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Live status of one subagent run, readable while it executes.
 *
 * <p>Thread safety: exactly one writer (the subagent's own thread, via
 * {@link SubagentHook}) and any number of readers (the main agent calling
 * {@code subagent_status} on a tool-executor thread). Scalars are volatile;
 * the collections are replaced with immutable copies rather than mutated, so a
 * reader always sees a consistent list — never one being written.
 */
public class SubagentStatus {

    /** Lifecycle phase, mirroring Nanobot's subagent phases. */
    public enum Phase {
        INITIALIZING,
        AWAITING_TOOLS,
        TOOLS_COMPLETED,
        FINAL_RESPONSE,
        DONE,
        ERROR
    }

    private final String taskId;
    private final String label;
    private final String taskDescription;
    private final Instant startedAt;
    private final String mainSessionKey;

    private volatile Phase phase = Phase.INITIALIZING;
    private volatile int iteration;
    private volatile List<ToolEvent> toolEvents = List.of();
    private volatile Map<String, Integer> usage = Map.of();
    private volatile String stopReason;
    private volatile String error;
    private volatile String result;
    private volatile Instant finishedAt;

    public SubagentStatus(String taskId, String label, String taskDescription,
                          String mainSessionKey, Instant startedAt) {
        this.taskId = taskId;
        this.label = label;
        this.taskDescription = taskDescription;
        this.mainSessionKey = mainSessionKey;
        this.startedAt = startedAt;
    }

    public String getTaskId() { return taskId; }
    public String getLabel() { return label; }
    public String getTaskDescription() { return taskDescription; }
    public String getMainSessionKey() { return mainSessionKey; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Phase getPhase() { return phase; }
    public int getIteration() { return iteration; }
    public List<ToolEvent> getToolEvents() { return toolEvents; }
    public Map<String, Integer> getUsage() { return usage; }
    public String getStopReason() { return stopReason; }
    public String getError() { return error; }
    public String getResult() { return result; }

    public void setPhase(Phase phase) { this.phase = phase; }

    public void setIteration(int iteration) { this.iteration = iteration; }

    /** Store an immutable snapshot so readers never observe a partially-written list. */
    public void setToolEvents(List<ToolEvent> events) {
        this.toolEvents = events == null ? List.of() : List.copyOf(events);
    }

    /** Store an immutable snapshot so readers never observe a partially-written map. */
    public void setUsage(Map<String, Integer> usage) {
        this.usage = usage == null ? Map.of() : Map.copyOf(usage);
    }

    public void setStopReason(String stopReason) { this.stopReason = stopReason; }

    public void setError(String error) { this.error = error; }

    /** Mark a successful finish and record the result text. */
    public void markFinished(String result, String stopReason) {
        this.result = result;
        this.stopReason = stopReason;
        this.finishedAt = Instant.now();
        this.phase = Phase.DONE;
    }

    /** Mark a failed finish and record the error text. */
    public void markError(String error) {
        this.error = error;
        this.finishedAt = Instant.now();
        this.phase = Phase.ERROR;
    }

    /** Whether this subagent has finished (successfully or not). */
    public boolean isTerminal() {
        return phase == Phase.DONE || phase == Phase.ERROR;
    }

    /** Elapsed run time in milliseconds (to completion, or to now if still running). */
    public long getElapsedMs() {
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }
}
