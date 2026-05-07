package org.qainsights.jmeter.ai.agent.swing;

import org.qainsights.jmeter.ai.agent.AgentLoop;
import org.qainsights.jmeter.ai.agent.model.AgentResponse;
import org.qainsights.jmeter.ai.agent.model.ProgressUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * SwingWorker for executing Agent Loop operations in the background.
 * Provides typed progress updates to the UI during execution.
 */
public class AgentSwingWorker extends SwingWorker<AgentResponse, ProgressUpdate> {
    private static final Logger log = LoggerFactory.getLogger(AgentSwingWorker.class);

    private final AgentLoop agentLoop;
    private final String message;
    private final String sessionKey;
    private final Consumer<AgentResponse> callback;
    private final Consumer<ProgressUpdate> progressCallback;

    /**
     * Create an AgentSwingWorker
     */
    public AgentSwingWorker(
            AgentLoop agentLoop,
            String message,
            String sessionKey,
            Consumer<AgentResponse> callback) {
        this(agentLoop, message, sessionKey, callback, null);
    }

    /**
     * Create an AgentSwingWorker with progress callback
     */
    public AgentSwingWorker(
            AgentLoop agentLoop,
            String message,
            String sessionKey,
            Consumer<AgentResponse> callback,
            Consumer<ProgressUpdate> progressCallback) {
        this.agentLoop = agentLoop;
        this.message = message;
        this.sessionKey = sessionKey;
        this.callback = callback;
        this.progressCallback = progressCallback;
    }

    @Override
    protected AgentResponse doInBackground() throws Exception {
        log.info("Starting AgentSwingWorker for session: {}", sessionKey);

        // Set progress callback - pass ProgressUpdate directly through SwingWorker's publish
        agentLoop.setProgressCallback(update -> publish(update));

        // Execute the agent loop
        AgentResponse response = agentLoop.processMessage(message, sessionKey).get();

        log.info("AgentSwingWorker completed for session: {}", sessionKey);
        return response;
    }

    @Override
    protected void process(List<ProgressUpdate> chunks) {
        for (ProgressUpdate update : chunks) {
            try {
                if (progressCallback != null) {
                    progressCallback.accept(update);
                }
            } catch (Exception e) {
                log.warn("Error in progress callback", e);
            }
        }
    }

    @Override
    protected void done() {
        // If cancelled, the UI fast-path already handled display — skip callback
        if (isCancelled()) {
            log.info("AgentSwingWorker was cancelled");
            return;
        }
        try {
            AgentResponse response = get();
            if (callback != null) {
                callback.accept(response);
            }
        } catch (Exception e) {
            log.error("Error in AgentSwingWorker", e);
            if (callback != null) {
                callback.accept(AgentResponse.error("Processing failed: " + e.getMessage()));
            }
        }
    }
}
