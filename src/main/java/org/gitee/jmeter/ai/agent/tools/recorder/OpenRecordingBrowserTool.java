package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.recorder.RecorderEngine;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code open_recording_browser}：打开录制浏览器入预备态（零采集）。与 GUI ●REC 按钮同一引擎。
 */
public class OpenRecordingBrowserTool extends AbstractRecorderTool {

    private static final int MAX_URL = 2048;

    public OpenRecordingBrowserTool() {
    }

    public OpenRecordingBrowserTool(Supplier<RecorderEngine> engineSupplier) {
        super(engineSupplier);
    }

    @Override
    public String getName() {
        return "open_recording_browser";
    }

    @Override
    public String getDescription() {
        return "Open a dedicated Chrome browser for HTTP request recording and enter the "
                + "ARMED (preparation) state — nothing is captured until begin_recording is called. "
                + "Tell the user to do login and navigation prep in the opened browser, then call "
                + "begin_recording. Optional params: url (start page), include_domains "
                + "(array of domains or a comma-separated string).";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "url": {
                      "type": "string",
                      "description": "Start page URL opened in the recording browser (optional)"
                    },
                    "include_domains": {
                      "oneOf": [
                        { "type": "array", "items": { "type": "string" } },
                        { "type": "string" }
                      ],
                      "description": "Only capture requests whose host matches these domains (optional; empty = capture all). Accepts an array or a comma-separated string."
                    }
                  }
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        // 录制是人工驱动动作：goal 自主回合不得拉起浏览器（spec「录制不进 goal 自主循环」）
        AgentRunContext runContext = AgentRunContext.current();
        if (runContext != null && runContext.isGoalRequested()) {
            return ToolResult.error("Browser recording is human-driven and disabled in goal turns. "
                    + "Ask the user to run the recording from a normal chat turn or the GUI REC button.");
        }
        String url = getStringParameter(parameters, "url", null);
        if (url != null) {
            url = url.trim();
            if (url.isEmpty()) {
                url = null;
            } else if (url.length() > MAX_URL) {
                return ToolResult.error("url is too long (max " + MAX_URL + " chars).");
            }
        }
        Set<String> domains;
        try {
            domains = parseDomains(parameters.get("include_domains"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        try {
            engine().open(new RecorderEngine.OpenParams(url, domains));
        } catch (IllegalStateException e) {
            return ToolResult.error("A recording session is already in progress (" + engine().state()
                    + "). Tools and the GUI button share one session: call begin_recording / "
                    + "stop_recording on the current session, or stop it first.");
        } catch (Exception e) {
            return ToolResult.error("Failed to open the recording browser: " + e.getMessage());
        }
        return ToolResult.success("Recording browser opened; session is ARMED (nothing captured yet). "
                + "Ask the user to finish login/prep in that browser (this phase is NOT recorded), "
                + "then call begin_recording to start capture (the user may also click the "
                + "\"start recording\" pill in the page). Recording dir: " + engine().status().dir());
    }

    /** 接受数组或逗号字符串；其它类型显式报错，绝不静默放行成全量采集。 */
    static Set<String> parseDomains(Object raw) {
        Set<String> domains = new LinkedHashSet<>();
        if (raw == null) {
            return domains;
        }
        if (raw instanceof java.util.List<?> list) {
            for (Object d : list) {
                if (d != null && !String.valueOf(d).isBlank()) {
                    domains.add(String.valueOf(d).trim());
                }
            }
            return domains;
        }
        if (raw instanceof String s) {
            for (String d : s.split(",")) {
                if (!d.isBlank()) {
                    domains.add(d.trim());
                }
            }
            return domains;
        }
        throw new IllegalArgumentException("include_domains must be an array of strings or a "
                + "comma-separated string; got " + raw.getClass().getSimpleName() + ".");
    }
}
