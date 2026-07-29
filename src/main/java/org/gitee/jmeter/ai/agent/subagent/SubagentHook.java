package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Writes a subagent run's progress into its {@link SubagentStatus}.
 *
 * <p>Port of Nanobot's {@code _SubagentHook}. Deliberately does NOT emit
 * progress to the UI: subagent internals stay invisible to the end user, who
 * only sees the completion summary the main agent relays. Progress reaches the
 * main agent on demand through the {@code subagent_status} tool.
 */
public class SubagentHook implements AgentHook {
    private static final Logger log = LoggerFactory.getLogger(SubagentHook.class);

    private final String taskId;
    private final SubagentStatus status;

    public SubagentHook(String taskId, SubagentStatus status) {
        this.taskId = taskId;
        this.status = status;
    }

    @Override
    public void beforeIteration(AgentHookContext context) {
        status.setPhase(SubagentStatus.Phase.AWAITING_TOOLS);
        status.setIteration(context.getCurrentIteration());
    }

    @Override
    public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (ToolCall call : toolCalls) {
            log.debug("Subagent [{}] executing: {} with arguments: {}",
                taskId, call.getName(), call.getArguments());
        }
    }

    @Override
    public void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        status.setPhase(SubagentStatus.Phase.TOOLS_COMPLETED);
    }

    @Override
    public void afterIteration(AgentHookContext context) {
        status.setIteration(context.getCurrentIteration());
        status.setToolEvents(context.getToolEvents());
        status.setUsage(context.getUsage());
        if (context.getError() != null) {
            status.setError(context.getError());
        }
    }

    @Override
    public String finalizeContent(String content, AgentHookContext context) {
        status.setPhase(SubagentStatus.Phase.FINAL_RESPONSE);
        status.setToolEvents(context.getToolEvents());
        status.setUsage(context.getUsage());
        return content;
    }

    @Override
    public void onError(Throwable error, AgentHookContext context) {
        status.setError(error != null ? error.getMessage() : "unknown error");
    }
}
