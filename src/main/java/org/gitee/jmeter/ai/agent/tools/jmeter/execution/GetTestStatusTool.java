package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.threads.JMeterContextService;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.util.Map;

/**
 * Tool to get current test execution status including running state,
 * thread progress, and quick sample counts.
 */
public class GetTestStatusTool extends AbstractTool {

    @Override
    public String getName() {
        return "get_test_status";
    }

    @Override
    public String getDescription() {
        return "Get the current test execution status: running state, thread progress, " +
                "elapsed time, and sample counts. Call this to check if a test is running or has completed.";
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
        AgentResultCollector.SummaryStats summary = AgentResultCollector.getSummary();

        // No test has been run yet
        if (!summary.running && summary.totalSamples == 0 && AgentResultCollector.getTestStartTimeMs() == 0) {
            return ToolResult.success("No test is currently running and no results are available.");
        }

        // Thread info from JMeterContextService (may be 0 if test already ended)
        JMeterContextService.ThreadCounts threads = JMeterContextService.getThreadCounts();
        int totalThreads = JMeterContextService.getTotalThreads();

        StringBuilder sb = new StringBuilder();
        sb.append("## Test Status\n\n");
        sb.append("- **State**: ").append(summary.running ? "Running" : "Completed").append("\n");

        if (summary.elapsedMs > 0) {
            sb.append("- **Elapsed**: ").append(formatDuration(summary.elapsedMs)).append("\n");
        }

        sb.append("\n### Thread Progress\n");
        if (totalThreads > 0) {
            sb.append("- Total threads: ").append(totalThreads).append("\n");
            sb.append("- Active: ").append(threads.activeThreads).append("\n");
            sb.append("- Started: ").append(threads.startedThreads).append("\n");
            sb.append("- Finished: ").append(threads.finishedThreads).append("\n");
        }

        sb.append("\n### Samples\n");
        sb.append("- Total samples: ").append(summary.totalSamples).append("\n");
        sb.append("- Errors: ").append(summary.totalErrors).append("\n");

        if (summary.totalSamples > 0) {
            sb.append("- Error rate: ").append(String.format("%.1f%%", summary.errorRate)).append("\n");
            sb.append("- Avg response time: ").append(String.format("%.0f ms", summary.avgResponseTime)).append("\n");
            sb.append("- Throughput: ").append(String.format("%.1f/sec", summary.throughput)).append("\n");
        }

        if (!summary.running && summary.totalSamples > 0) {
            sb.append("\nUse **get_test_results** for detailed results and per-sampler breakdown.");
        }

        return ToolResult.success(sb.toString());
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
