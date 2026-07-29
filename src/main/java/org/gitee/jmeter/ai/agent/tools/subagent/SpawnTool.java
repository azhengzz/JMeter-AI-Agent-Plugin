package org.gitee.jmeter.ai.agent.tools.subagent;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.subagent.SubagentManager;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Delegates a self-contained task to a background subagent.
 *
 * <p>Returns as soon as the subagent is started; the result arrives later in the
 * same conversation turn through the injection queue.
 *
 * <p>Keeps the default {@code {core}} scope on purpose — that is what keeps it
 * out of the subagent toolset and prevents a subagent from spawning another.
 */
public class SpawnTool extends AbstractTool {

    private final SubagentManager manager;
    private final Supplier<SubagentManager.TurnToken> turnTokenSupplier;

    public SpawnTool(SubagentManager manager, Supplier<SubagentManager.TurnToken> turnTokenSupplier) {
        this.manager = manager;
        this.turnTokenSupplier = turnTokenSupplier;
    }

    @Override
    public String getName() {
        return "spawn";
    }

    @Override
    public String getDescription() {
        return "Delegate a self-contained analysis task to a background subagent. "
            + "Use it for work that would otherwise flood this conversation with intermediate "
            + "tool output — surveying a large test plan, cross-checking many elements, or "
            + "researching a topic. The subagent has read-only tools (it cannot modify the test "
            + "plan, run tests, or write files) and reports back a summary when done. "
            + "This call returns immediately; continue working and the result will arrive.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "task": {
                            "type": "string",
                            "description": "The task for the subagent. Be self-contained: the subagent does not see this conversation, only this text."
                        },
                        "label": {
                            "type": "string",
                            "description": "Optional short label for display (e.g. 'audit assertions')"
                        }
                    },
                    "required": ["task"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String task = getStringParameter(parameters, "task", "");
        if (task.isBlank()) {
            return ToolResult.error("Parameter 'task' is required");
        }
        String label = getStringParameter(parameters, "label", null);

        // The session this tool call belongs to; bound by AgentRunner and replayed
        // onto tool-executor threads for concurrent execution.
        AgentRunContext context = AgentRunContext.current();
        if (context == null || context.getSessionKey() == null) {
            return ToolResult.error(
                "Cannot spawn subagent: no active agent run context (session unknown).");
        }

        SubagentManager.TurnToken turnToken =
            turnTokenSupplier != null ? turnTokenSupplier.get() : null;

        String receipt = manager.spawn(task, label, context.getSessionKey(), turnToken);
        return ToolResult.success(receipt);
    }
}
