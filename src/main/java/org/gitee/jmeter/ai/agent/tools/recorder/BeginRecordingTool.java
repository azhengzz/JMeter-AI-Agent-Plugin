package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.recorder.RecorderEngine;

import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code begin_recording}：ARMED → RECORDING 显式开始采集（可接管 GUI 打开的预备态会话）。
 */
public class BeginRecordingTool extends AbstractRecorderTool {

    public BeginRecordingTool() {
    }

    public BeginRecordingTool(Supplier<RecorderEngine> engineSupplier) {
        super(engineSupplier);
    }

    @Override
    public String getName() {
        return "begin_recording";
    }

    @Override
    public String getDescription() {
        return "Explicitly start capturing HTTP requests in the ARMED recording session (preparation "
                + "actions before this call are NOT recorded). Works on the session opened by "
                + "open_recording_browser OR by the GUI REC button (same single session). "
                + "A cookie snapshot is taken at this moment so login cookies survive.";
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
            return ToolResult.error("Browser recording is human-driven and disabled in goal turns.");
        }
        switch (engine().state()) {
            case ARMED -> {
                engine().begin();
                return ToolResult.success("Recording started (RECORDING). Let the user operate the "
                        + "business flow in the browser; when done call stop_recording, or let the user "
                        + "click the pill's stop button / close the browser window.");
            }
            case RECORDING -> {
                return ToolResult.error("The session is already recording. Wait for the user to "
                        + "finish, then call stop_recording.");
            }
            default -> {
                return ToolResult.error("No armed recording session (state=" + engine().state()
                        + "). Call open_recording_browser first.");
            }
        }
    }
}
