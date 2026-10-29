package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.recorder.RecorderEngine;
import org.gitee.jmeter.ai.recorder.RecorderJson;
import org.gitee.jmeter.ai.recorder.RecorderRuntime;

import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code get_recording_status}：查询当前录制会话状态与计数。
 */
public class GetRecordingStatusTool extends AbstractRecorderTool {

    private final Supplier<RecorderEngine.Status> statusSupplier;

    public GetRecordingStatusTool() {
        super();
        this.statusSupplier = () -> RecorderRuntime.get().statusSnapshot();
    }

    public GetRecordingStatusTool(Supplier<RecorderEngine> engineSupplier) {
        super(engineSupplier);
        this.statusSupplier = () -> engineSupplier.get().status();
    }

    @Override
    public String getName() {
        return "get_recording_status";
    }

    @Override
    public boolean isConcurrencySafe() {
        return true; // 只读
    }

    @Override
    public String getDescription() {
        return "Get the current browser-recording session status: state (IDLE/ARMED/RECORDING/FINALIZING/"
                + "DONE/CANCELLED), captured request/response/UI-event counts, startedAt and the "
                + "recording data directory.";
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
        RecorderEngine.Status s = statusSupplier.get();
        var n = RecorderJson.MAPPER.createObjectNode();
        n.put("state", s.state().name());
        n.put("requests", s.requests());
        n.put("responses", s.responses());
        n.put("uiEvents", s.uiEvents());
        n.put("startedAt", s.startedAt());
        if (s.dir() == null) {
            n.putNull("dir");
        } else {
            n.put("dir", s.dir().toString());
        }
        return ToolResult.success(n.toString());
    }
}
