package org.qainsights.jmeter.ai.agent.hooks;

import org.qainsights.jmeter.ai.agent.AgentLoop;
import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Adapter to convert legacy ProgressCallback to AgentHook.
 * Maintains backward compatibility with existing code.
 */
public class ProgressCallbackHookAdapter implements AgentHook {
    private static final Logger log = LoggerFactory.getLogger(ProgressCallbackHookAdapter.class);

    private final AgentLoop.ProgressCallback callback;

    public ProgressCallbackHookAdapter(AgentLoop.ProgressCallback callback) {
        this.callback = callback;
    }

    @Override
    public void beforeIteration(AgentHookContext context) {
        if (callback != null && context.getCurrentIteration() > 1) {
            publish("Iteration " + context.getCurrentIteration() + "...");
        }
    }

    @Override
    public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (callback != null && !toolCalls.isEmpty()) {
            for (ToolCall call : toolCalls) {
                publish("Executing tool: " + call.getName());
            }
        }
    }

    @Override
    public void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (callback != null && !toolCalls.isEmpty()) {
            List<String> toolNames = toolCalls.stream()
                    .map(ToolCall::getName)
                    .toList();
            publish("Used tools: " + String.join(", ", toolNames));
        }
    }

    @Override
    public void onError(Throwable error, AgentHookContext context) {
        if (callback != null) {
            publish("Error: " + error.getMessage());
        }
    }

    private void publish(String message) {
        try {
            callback.onProgress(message);
        } catch (Exception e) {
            log.warn("Error in progress callback", e);
        }
    }
}
