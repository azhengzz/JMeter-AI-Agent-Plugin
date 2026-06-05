package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.util.List;
import java.util.Map;

/**
 * Tool to get test execution results including summary statistics
 * and optionally individual sample details.
 */
public class GetTestResultsTool extends AbstractTool {

    @Override
    public String getName() {
        return "get_test_results";
    }

    @Override
    public String getDescription() {
        return "Get test execution results. Returns summary statistics (response times, throughput, error rate) " +
                "and optionally individual sample details including request/response data. " +
                "Can be called during or after test execution.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "format": {
                            "type": "string",
                            "enum": ["summary", "samples", "both"],
                            "description": "Result format: 'summary' for aggregate stats, 'samples' for individual sample details, 'both' for both",
                            "default": "summary"
                        },
                        "limit": {
                            "type": "integer",
                            "description": "Number of recent samples to return (for 'samples' or 'both' format)",
                            "default": 20,
                            "maximum": 100
                        },
                        "offset": {
                            "type": "integer",
                            "description": "Offset into sample list for pagination",
                            "default": 0
                        },
                        "include_details": {
                            "type": "boolean",
                            "description": "Include request body, request headers, response body, response headers for each sample",
                            "default": false
                        }
                    }
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String format = getStringParameter(parameters, "format", "summary");
        int limit = Math.min(getIntParameter(parameters, "limit", 20), 100);
        int offset = getIntParameter(parameters, "offset", 0);
        boolean includeDetails = getBooleanParameter(parameters, "include_details", false);

        AgentResultCollector.SummaryStats summary = AgentResultCollector.getSummary();

        if (summary.totalSamples == 0) {
            return ToolResult.error("No test results available. Run a test first using run_test.");
        }

        StringBuilder sb = new StringBuilder();

        if (format.equals("summary") || format.equals("both")) {
            appendSummary(sb, summary);
        }

        if (format.equals("samples") || format.equals("both")) {
            appendSamples(sb, limit, offset, includeDetails);
        }

        return ToolResult.success(sb.toString());
    }

    private void appendSummary(StringBuilder sb, AgentResultCollector.SummaryStats summary) {
        sb.append("## Test Results Summary\n\n");

        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append(String.format("| Total Samples | %d |\n", summary.totalSamples));
        sb.append(String.format("| Errors | %d |\n", summary.totalErrors));
        sb.append(String.format("| Error Rate | %.1f%% |\n", summary.errorRate));
        sb.append(String.format("| Min Response Time | %d ms |\n", summary.minResponseTime));
        sb.append(String.format("| Max Response Time | %d ms |\n", summary.maxResponseTime));
        sb.append(String.format("| Avg Response Time | %.0f ms |\n", summary.avgResponseTime));
        sb.append(String.format("| Throughput | %.1f samples/sec |\n", summary.throughput));
        if (summary.elapsedMs > 0) {
            sb.append(String.format("| Duration | %s |\n", formatDuration(summary.elapsedMs)));
        }

        if (summary.perLabel != null && !summary.perLabel.isEmpty()) {
            sb.append("\n### Per-Sampler Breakdown\n\n");
            sb.append("| Sampler | Samples | Errors | Error% | Avg(ms) | Min(ms) | Max(ms) |\n");
            sb.append("|---------|---------|--------|--------|---------|---------|--------|\n");
            for (Map.Entry<String, AgentResultCollector.SummaryStats> entry : summary.perLabel.entrySet()) {
                AgentResultCollector.SummaryStats s = entry.getValue();
                sb.append(String.format("| %s | %d | %d | %.1f%% | %.0f | %d | %d |\n",
                        entry.getKey(), s.totalSamples, s.totalErrors, s.errorRate,
                        s.avgResponseTime, s.minResponseTime, s.maxResponseTime));
            }
        }
    }

    private void appendSamples(StringBuilder sb, int limit, int offset, boolean includeDetails) {
        List<AgentResultCollector.SampleSnapshot> samples = AgentResultCollector.getRecentSamples(limit, offset);

        sb.append("\n### Recent Samples (offset=").append(offset).append(", limit=").append(limit).append(")\n\n");
        sb.append("| # | Label | Code | Success | Time(ms) | Latency(ms) | URL |\n");
        sb.append("|---|-------|------|---------|----------|-------------|-----|\n");

        int idx = offset + 1;
        for (AgentResultCollector.SampleSnapshot s : samples) {
            String url = s.url != null && s.url.length() > 60
                    ? s.url.substring(0, 57) + "...(truncated)"
                    : (s.url != null ? s.url : "");
            sb.append(String.format("| %d | %s | %s | %s | %d | %d | %s |\n",
                    idx++, s.label, s.responseCode, s.success ? "OK" : "FAIL",
                    s.responseTime, s.latency, url));
        }

        if (includeDetails) {
            int detailIdx = offset + 1;
            for (AgentResultCollector.SampleSnapshot s : samples) {
                sb.append("\n#### Sample ").append(detailIdx++).append(": ").append(s.label).append("\n\n");

                if (s.url != null && !s.url.isEmpty()) {
                    sb.append("- **URL**: ").append(s.url).append("\n");
                }
                sb.append("- **Status**: ").append(s.responseCode)
                        .append(" (").append(s.success ? "OK" : "FAIL").append(")\n");
                sb.append("- **Response Time**: ").append(s.responseTime).append(" ms\n");
                sb.append("- **Latency**: ").append(s.latency).append(" ms\n");

                appendSection(sb, "Request Headers", s.requestHeaders);
                appendSection(sb, "Request Body", s.requestData);
                appendSection(sb, "Response Headers", s.responseHeaders);
                appendSection(sb, "Response Body", s.responseData);
            }
        }
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        if (content == null || content.isEmpty()) return;
        sb.append("\n**").append(title).append(":**\n```\n").append(content).append("\n```\n");
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return String.format("%dm %ds", minutes, seconds);
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}
