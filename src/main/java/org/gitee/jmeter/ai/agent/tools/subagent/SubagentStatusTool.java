package org.gitee.jmeter.ai.agent.tools.subagent;

import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.subagent.SubagentManager;
import org.gitee.jmeter.ai.agent.subagent.SubagentStatus;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.util.List;
import java.util.Map;

/**
 * Lets the main agent inspect its subagents while they run.
 *
 * <p>Keeps the default {@code {core}} scope: a subagent must not introspect
 * subagents (and has none of its own).
 */
public class SubagentStatusTool extends AbstractTool {

    private static final int MAX_TOOL_EVENTS = 5;

    private final SubagentManager manager;

    public SubagentStatusTool(SubagentManager manager) {
        this.manager = manager;
    }

    @Override
    public String getName() {
        return "subagent_status";
    }

    @Override
    public String getDescription() {
        return "Check the progress of subagents spawned in this conversation: phase, iteration, "
            + "recent tool calls, token usage, and the result of any that already finished. "
            + "Use it when you want to know whether a subagent is still working.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "task_id": {
                            "type": "string",
                            "description": "Optional subagent id to inspect. If omitted, all subagents of this conversation are listed."
                        },
                        "include_completed": {
                            "type": "boolean",
                            "description": "Include recently completed subagents (default: true)"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String taskId = getStringParameter(parameters, "task_id", "");
        boolean includeCompleted = getBooleanParameter(parameters, "include_completed", true);

        if (!taskId.isBlank()) {
            SubagentStatus status = manager.getStatus(taskId);
            if (status == null) {
                return ToolResult.error("No subagent found with id: " + taskId);
            }
            return ToolResult.success(format(status));
        }

        AgentRunContext context = AgentRunContext.current();
        String sessionKey = context != null ? context.getSessionKey() : null;
        List<SubagentStatus> statuses = manager.getStatuses(sessionKey, includeCompleted);

        if (statuses.isEmpty()) {
            return ToolResult.success("No subagents are running or recently completed.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Subagents (").append(statuses.size()).append(")\n\n");
        for (SubagentStatus status : statuses) {
            sb.append(format(status)).append('\n');
        }
        return ToolResult.success(sb.toString());
    }

    private String format(SubagentStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append("### [").append(status.getTaskId()).append("] ").append(status.getLabel()).append('\n');
        sb.append("- Phase: ").append(status.getPhase()).append('\n');
        sb.append("- Iteration: ").append(status.getIteration()).append('\n');
        sb.append("- Elapsed: ").append(status.getElapsedMs() / 1000).append("s\n");

        Map<String, Integer> usage = status.getUsage();
        if (!usage.isEmpty()) {
            sb.append("- Usage: ").append(usage).append('\n');
        }

        List<ToolEvent> events = status.getToolEvents();
        if (!events.isEmpty()) {
            sb.append("- Recent tools:\n");
            int from = Math.max(0, events.size() - MAX_TOOL_EVENTS);
            for (ToolEvent event : events.subList(from, events.size())) {
                sb.append("  - ").append(event.getToolName())
                  .append(": ").append(event.isError() ? "error" : "ok");
                if (event.getDetail() != null && !event.getDetail().isEmpty()) {
                    sb.append(" — ").append(truncate(event.getDetail()));
                }
                sb.append('\n');
            }
        }

        if (status.getError() != null) {
            sb.append("- Error: ").append(status.getError()).append('\n');
        }
        if (status.getResult() != null) {
            sb.append("- Result:\n").append(status.getResult()).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > 120 ? flat.substring(0, 117) + "..." : flat;
    }
}
