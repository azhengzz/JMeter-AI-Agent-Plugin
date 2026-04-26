package org.qainsights.jmeter.ai.agent.run;

import org.qainsights.jmeter.ai.agent.hooks.AgentHook;

import java.util.*;

/**
 * Specification for running an agent.
 * Encapsulates all configuration needed for an agent run.
 */
public class AgentRunSpec {

    private final String userMessage;
    private final String sessionKey;
    private final AgentHook hook;
    private final boolean concurrentTools;
    private final int maxIterations;
    private final boolean failOnToolError;
    private final Map<String, Object> options;

    private AgentRunSpec(Builder builder) {
        this.userMessage = builder.userMessage;
        this.sessionKey = builder.sessionKey;
        this.hook = builder.hook;
        this.concurrentTools = builder.concurrentTools;
        this.maxIterations = builder.maxIterations;
        this.failOnToolError = builder.failOnToolError;
        this.options = builder.options != null ? builder.options : Collections.emptyMap();
    }

    public String getUserMessage() { return userMessage; }
    public String getSessionKey() { return sessionKey; }
    public AgentHook getHook() { return hook; }
    public boolean isConcurrentTools() { return concurrentTools; }
    public int getMaxIterations() { return maxIterations; }
    public boolean isFailOnToolError() { return failOnToolError; }
    public Map<String, Object> getOptions() { return options; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String userMessage;
        private String sessionKey;
        private AgentHook hook;
        private boolean concurrentTools = false;
        private int maxIterations = 40;
        private boolean failOnToolError = false;
        private Map<String, Object> options;

        public Builder userMessage(String message) {
            this.userMessage = message;
            return this;
        }

        public Builder sessionKey(String key) {
            this.sessionKey = key;
            return this;
        }

        public Builder hook(AgentHook hook) {
            this.hook = hook;
            return this;
        }

        public Builder concurrentTools(boolean concurrent) {
            this.concurrentTools = concurrent;
            return this;
        }

        public Builder maxIterations(int iterations) {
            this.maxIterations = iterations;
            return this;
        }

        public Builder failOnToolError(boolean fail) {
            this.failOnToolError = fail;
            return this;
        }

        public Builder option(String key, Object value) {
            if (this.options == null) {
                this.options = new HashMap<>();
            }
            this.options.put(key, value);
            return this;
        }

        public AgentRunSpec build() {
            Objects.requireNonNull(userMessage, "userMessage is required");
            Objects.requireNonNull(sessionKey, "sessionKey is required");
            return new AgentRunSpec(this);
        }
    }
}
