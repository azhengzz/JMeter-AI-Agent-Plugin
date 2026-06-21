package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.LoggerPanel;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;

import javax.swing.JTextArea;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read JMeter LoggerPanel log content by line range.
 *
 * <p>Line numbers are 1-based and match the visual line number shown when the user
 * selects a row in the bottom LoggerPanel (same algorithm as
 * {@code ElementDescriptor.describeTextSelection}: Document.getDefaultRootElement().getElementIndex + 1).
 *
 * <p>Default behavior is tail mode: returns the most recent {@code maxLines} lines.
 * Supports context queries around a user-selected line for log-based troubleshooting.
 */
public class GetLogPanelContentTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int DEFAULT_MAX_LINES = 50;
    private static final int HARD_MAX_LINES = 500;

    private static final String TEXT_AREA_FIELD = "textArea";
    private static final String CAPACITY_PROP = "jmeter.loggerpanel.maxlength";
    private static final int DEFAULT_CAPACITY = 1000;

    @Override
    public String getName() {
        return "get_log_panel_content";
    }

    @Override
    public String getDescription() {
        return "Read JMeter LoggerPanel (bottom log panel) content by line range. " +
                "Line numbers match the line shown when the user selects a row in the log panel, " +
                "so you can use the line number from \"Selected: Log Panel / line=N\" context " +
                "to fetch surrounding log content for troubleshooting. " +
                "Defaults to tail mode (returns the most recent maxLines lines).";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "startLine": {
                            "type": "integer",
                            "description": "1-based start line number (inclusive). Matches the line number shown when user selects a row in LoggerPanel. If omitted with endLine also omitted: tail mode (returns most recent maxLines lines)."
                        },
                        "endLine": {
                            "type": "integer",
                            "description": "1-based end line number (inclusive). If omitted: defaults to startLine + maxLines - 1 (clamped to total)."
                        },
                        "maxLines": {
                            "type": "integer",
                            "description": "Maximum lines to return (default 50, hard cap 500). Bounds output size regardless of startLine/endLine range."
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        int maxLines = clamp(getIntParameter(parameters, "maxLines", DEFAULT_MAX_LINES), 1, HARD_MAX_LINES);
        boolean hasStart = parameters.containsKey("startLine");
        boolean hasEnd = parameters.containsKey("endLine");

        final List<String> snapshot = new ArrayList<>();
        final LoggerPanel[] panelHolder = new LoggerPanel[1];
        Exception err = EdtRunner.run(guiPackage, () -> {
            panelHolder[0] = guiPackage.getLoggerPanel();
            if (panelHolder[0] == null) {
                return;
            }
            Field f = LoggerPanel.class.getDeclaredField(TEXT_AREA_FIELD);
            f.setAccessible(true);
            JTextArea ta = (JTextArea) f.get(panelHolder[0]);
            String text = ta.getText();
            if (text != null && !text.isEmpty()) {
                String[] raw = text.split("\r?\n", -1);
                for (String s : raw) {
                    snapshot.add(s);
                }
                // getText() usually ends with '\n', which produces a trailing empty element — drop it
                if (!snapshot.isEmpty() && snapshot.get(snapshot.size() - 1).isEmpty()) {
                    snapshot.remove(snapshot.size() - 1);
                }
            }
        });
        if (err != null) {
            log.error("Failed to read LoggerPanel textArea on EDT", err);
            return ToolResult.error("Failed to read log panel: " + err.getMessage());
        }
        if (panelHolder[0] == null) {
            return ToolResult.error("Logger panel is not initialized");
        }

        int total = snapshot.size();
        int capacity = JMeterUtils.getPropDefault(CAPACITY_PROP, DEFAULT_CAPACITY);

        // Resolve startLine / endLine with tail-mode default and boundary clamp
        int start;
        int end;
        if (!hasStart && !hasEnd) {
            start = Math.max(1, total - maxLines + 1);
            end = total;
        } else if (!hasStart) {
            end = clamp(getIntParameter(parameters, "endLine", total), 1, total);
            start = Math.max(1, end - maxLines + 1);
        } else if (!hasEnd) {
            start = clamp(getIntParameter(parameters, "startLine", 1), 1, total);
            end = Math.min(total, start + maxLines - 1);
        } else {
            start = getIntParameter(parameters, "startLine", 1);
            end = getIntParameter(parameters, "endLine", total);
        }

        // Friendly boundary clamp (do not error on out-of-range)
        if (start < 1) {
            start = 1;
        }
        if (end > total) {
            end = total;
        }

        // maxLines secondary guard
        boolean truncated = false;
        if (end - start + 1 > maxLines) {
            end = start + maxLines - 1;
            truncated = true;
        }

        // Build output lines (only if range is valid)
        List<Map<String, Object>> lines = new ArrayList<>();
        if (total > 0 && start <= end) {
            for (int i = start - 1; i < end && i < total; i++) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("line", i + 1);
                entry.put("content", snapshot.get(i));
                lines.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalLines", total);
        result.put("capacity", capacity);
        result.put("truncatedByCapacity", total >= capacity);
        result.put("startLine", lines.isEmpty() ? 0 : start);
        result.put("endLine", lines.isEmpty() ? 0 : end);
        result.put("returnedLines", lines.size());
        result.put("truncated", truncated);
        result.put("lines", lines);

        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            log.error("Error serializing log panel content", e);
            return ToolResult.error("Failed to serialize log content: " + e.getMessage());
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
