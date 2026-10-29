package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.gitee.jmeter.ai.recorder.RecorderRuntime;
import org.gitee.jmeter.ai.recorder.convert.JmeterElementBridge;
import org.gitee.jmeter.ai.recorder.convert.RecordingModel;
import org.gitee.jmeter.ai.recorder.convert.SkeletonConverter;
import org.gitee.jmeter.ai.utils.WorkspacePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code import_recording}：「稍后从录制目录导入」的入口——
 * 把已有录制场次确定性转换为骨架 .jmx 文件（不弹 GUI 窗、不碰当前测试树），
 * 配合 open_jmx_file 使用；Agent 增强流程也用它。
 */
public class ImportRecordingTool extends org.gitee.jmeter.ai.agent.tools.AbstractTool {

    @Override
    public String getName() {
        return "import_recording";
    }

    @Override
    public String getDescription() {
        return "Convert an existing browser-recording session directory into a JMeter skeleton "
                + ".jmx file (deterministic, no GUI dialog, current test plan untouched). "
                + "Use this to import later after choosing discard/not-inserting, or to build on a "
                + "recording inside a goal turn. Params: dir (optional; defaults to the latest "
                + "rec_* session under the recordings output dir), output_path (optional).";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "dir": {
                      "type": "string",
                      "description": "Recording session directory (contains events.jsonl). Defaults to the latest session."
                    },
                    "output_path": {
                      "type": "string",
                      "description": "Target .jmx path (optional; defaults to workspace/recording-import-<ts>-<instanceId>.jmx)"
                    }
                  }
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        try {
            Path dir = resolveRecordingDir(getStringParameter(parameters, "dir", null));
            if (dir == null) {
                return ToolResult.error("No recording session found under the recordings output dir. "
                        + "Record a session first or pass the 'dir' parameter.");
            }
            RecordingModel model = RecordingModel.load(dir);
            if (model.requests.isEmpty()) {
                return ToolResult.error("Recording '" + dir + "' contains no captured requests.");
            }
            SkeletonConverter.ConversionResult result = new SkeletonConverter().convert(model);
            Path target = resolveTarget(getStringParameter(parameters, "output_path", null));
            JmeterElementBridge.create().saveAsJmx(result.tree(), target);
            return ToolResult.success("Recording imported: " + result.samplerCount() + " samplers, "
                    + result.domains() + " domains → " + target
                    + ". Open it with open_jmx_file or ask for enhancement (correlation/assertions).");
        } catch (Exception e) {
            return ToolResult.error("Failed to import recording: " + e.getMessage());
        }
    }

    private static Path resolveRecordingDir(String dirParam) throws Exception {
        if (dirParam != null && !dirParam.isBlank()) {
            Path p = Path.of(dirParam.trim());
            return Files.isRegularFile(p.resolve("events.jsonl")) ? p : null;
        }
        Path outputDir = RecorderConfig.load().resolveOutputDir(WorkspacePaths.resolveWorkspace());
        if (!Files.isDirectory(outputDir)) {
            return null;
        }
        try (Stream<Path> list = Files.list(outputDir)) {
            return list.filter(p -> p.getFileName().toString().startsWith("rec_"))
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("events.jsonl")))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static Path resolveTarget(String outputParam) {
        if (outputParam != null && !outputParam.isBlank()) {
            return Path.of(outputParam.trim()).toAbsolutePath().normalize();
        }
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path base = WorkspacePaths.resolveWorkspace()
                .resolve("recording-import-" + ts + "-" + RecorderRuntime.get().instanceId() + ".jmx");
        return RecorderRuntime.uniquePath(base);
    }
}
