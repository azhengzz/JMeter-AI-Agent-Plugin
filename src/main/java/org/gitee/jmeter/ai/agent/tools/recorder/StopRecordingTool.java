package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.recorder.RecorderEngine;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@code stop_recording}：停止收官（RECORDING→产物就绪；ARMED→取消）。
 * 并发停止的败者（single-flight）会短暂停在 FINALIZING——等待终态后再报告实际结果。
 */
public class StopRecordingTool extends AbstractRecorderTool {

    private static final long TERMINAL_WAIT_MS = 5000;

    public StopRecordingTool() {
    }

    public StopRecordingTool(Supplier<RecorderEngine> engineSupplier) {
        super(engineSupplier);
    }

    @Override
    public String getName() {
        return "stop_recording";
    }

    @Override
    public String getDescription() {
        return "Stop the recording session and finalize: events.jsonl + cookies.json are persisted "
                + "under the recording directory (closing the browser window finalizes equivalently). "
                + "Calling this in ARMED state cancels the session (nothing recorded). "
                + "If another stop is already finalizing, this call waits briefly and reports the "
                + "actual terminal state. The user picks insert/save/discard in the GUI dialog.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {}
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        AgentRunContext runContext = AgentRunContext.current();
        if (runContext != null && runContext.isGoalRequested()) {
            return ToolResult.error("Browser recording is human-driven and cannot be stopped by a goal "
                    + "turn. Ask the user to stop it (GUI button / pill / close the browser).");
        }
        RecorderEngine engine = engine();
        switch (engine.state()) {
            case RECORDING -> {
                engine.stop();
                awaitTerminal(engine);
                RecorderEngine.Status after = engine.status();
                if (after.state() == RecorderEngine.State.FINALIZING) {
                    return ToolResult.success("Recording finalization is still in progress. "
                            + "Data dir: " + after.dir() + ". Call get_recording_status to confirm DONE.");
                }
                if (after.state() != RecorderEngine.State.DONE) {
                    return ToolResult.error("Recording stop ended in unexpected state "
                            + after.state() + ".");
                }
                return ToolResult.success("Recording finalized (DONE): " + after.requests()
                        + " requests, " + after.uiEvents() + " UI events. Data dir: " + after.dir()
                        + " (events.jsonl + cookies.json persist; credentials are plaintext by design). "
                        + "The GUI shows the result dialog for insert/save/discard; to enhance the plan "
                        + "later, read events.jsonl from that dir (truncate bodies beyond 16KB when "
                        + "reading; do not redact credentials).");
            }
            case ARMED -> {
                engine.stop(); // 预备态停止 = 取消（零产物）
                awaitTerminal(engine);
                return ToolResult.success("Armed session cancelled (nothing was recorded; dir removed).");
            }
            default -> {
                return ToolResult.error("No recording session in progress (state=" + engine.state() + ").");
            }
        }
    }

    /** 并发停止的败者等待胜者收官（single-flight），避免假报 DONE。 */
    private static void awaitTerminal(RecorderEngine engine) {
        long deadline = System.currentTimeMillis() + TERMINAL_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            RecorderEngine.State s = engine.state();
            if (s == RecorderEngine.State.DONE || s == RecorderEngine.State.CANCELLED) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
