package org.qainsights.jmeter.ai.agent.swing;

import org.qainsights.jmeter.ai.agent.AgentLoop;
import org.qainsights.jmeter.ai.agent.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * SwingWorker for executing Agent Loop operations in the background.
 * Provides progress updates to the UI during execution.
 */
public class AgentSwingWorker extends SwingWorker<AgentResponse, ProgressUpdate> {
    private static final Logger log = LoggerFactory.getLogger(AgentSwingWorker.class);

    private final AgentLoop agentLoop;
    private final String message;
    private final String sessionKey;
    private final Consumer<AgentResponse> callback;
    private final Consumer<String> progressCallback;

    /**
     * Create an AgentSwingWorker
     *
     * @param agentLoop The agent loop to execute
     * @param message The user message to process
     * @param sessionKey The session key
     * @param callback Callback for final response
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
     *
     * @param agentLoop The agent loop to execute
     * @param message The user message to process
     * @param sessionKey The session key
     * @param callback Callback for final response
     * @param progressCallback Callback for progress updates
     */
    public AgentSwingWorker(
            AgentLoop agentLoop,
            String message,
            String sessionKey,
            Consumer<AgentResponse> callback,
            Consumer<String> progressCallback) {
        this.agentLoop = agentLoop;
        this.message = message;
        this.sessionKey = sessionKey;
        this.callback = callback;
        this.progressCallback = progressCallback;
    }

    @Override
    protected AgentResponse doInBackground() throws Exception {
        log.info("Starting AgentSwingWorker for session: {}", sessionKey);

        // Set progress callback for agent loop
        agentLoop.setProgressCallback(progress -> {
            publish(new ProgressUpdate(progress, ProgressUpdate.Type.PROGRESS));
        });

        // Execute the agent loop
        AgentResponse response = agentLoop.processMessage(message, sessionKey).get();

        log.info("AgentSwingWorker completed for session: {}", sessionKey);
        return response;
    }

    @Override
    protected void process(List<ProgressUpdate> chunks) {
        // Process progress updates in EDT
        for (ProgressUpdate update : chunks) {
            try {
                if (progressCallback != null) {
                    progressCallback.accept(update.getMessage());
                }
            } catch (Exception e) {
                log.warn("Error in progress callback", e);
            }
        }
    }

    @Override
    protected void done() {
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
