package org.qainsights.jmeter.ai.agent.hooks;

import org.qainsights.jmeter.ai.agent.AgentLoop;
import org.qainsights.jmeter.ai.agent.model.ProgressUpdate;
import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.qainsights.jmeter.ai.agent.model.ToolEvent;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Adapter to convert legacy ProgressCallback to AgentHook.
 * Mirrors Nanobot's _LoopHook: pushes model thought and tool info
 * to the UI during the agent loop.
 */
public class ProgressCallbackHookAdapter implements AgentHook {
    private static final Logger log = LoggerFactory.getLogger(ProgressCallbackHookAdapter.class);

    private final AgentLoop.ProgressCallback callback;
    private final boolean showThinking;
    private int lastEventCount = 0;

    public ProgressCallbackHookAdapter(AgentLoop.ProgressCallback callback) {
        this.callback = callback;
        this.showThinking = Boolean.parseBoolean(
            AiConfig.getProperty("ai.chat.show.thinking", "false"));
    }

    @Override
    public void beforeExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (callback == null) return;

        if (context.getLastLlmResponse() != null) {
            String content = context.getLastLlmResponse().getContent();
            String display = showThinking ? content : TextUtils.stripThink(content);
            if (display != null && !display.isEmpty()) {
                publish(ProgressUpdate.thinking(display));
            }
        }
    }

    @Override
    public void afterExecuteTools(List<ToolCall> toolCalls, AgentHookContext context) {
        if (callback == null) return;

        // Show tool execution results for this iteration
        List<ToolEvent> allEvents = context.getToolEvents();
        if (lastEventCount < allEvents.size()) {
            List<ToolEvent> newEvents = allEvents.subList(lastEventCount, allEvents.size());
            for (ToolEvent event : newEvents) {
                publish(ProgressUpdate.toolCall(event));
            }
            lastEventCount = allEvents.size();
        }
    }

    @Override
    public String finalizeContent(String content, AgentHookContext context) {
        return this.showThinking ? content : TextUtils.stripThink(content);
    }

    @Override
    public void onError(Throwable error, AgentHookContext context) {
        if (callback != null) {
            publish(ProgressUpdate.error("Error: " + error.getMessage()));
        }
    }

    private void publish(ProgressUpdate update) {
        try {
            callback.onProgress(update);
        } catch (Exception e) {
            log.warn("Error in progress callback", e);
        }
    }
}
