package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.model.Message;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final String reasoningEffort;
    private final List<Message> initialMessages;
    private final AtomicBoolean abortFlag;

    private AgentRunSpec(Builder builder) {
        this.userMessage = builder.userMessage;
        this.sessionKey = builder.sessionKey;
        this.hook = builder.hook;
        this.concurrentTools = builder.concurrentTools;
        this.maxIterations = builder.maxIterations;
        this.failOnToolError = builder.failOnToolError;
        this.options = builder.options != null ? builder.options : Collections.emptyMap();
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.reasoningEffort = builder.reasoningEffort;
        this.initialMessages = builder.initialMessages;
        this.abortFlag = builder.abortFlag;
    }

    public String getUserMessage() { return userMessage; }
    public String getSessionKey() { return sessionKey; }
    public AgentHook getHook() { return hook; }
    public boolean isConcurrentTools() { return concurrentTools; }
    public int getMaxIterations() { return maxIterations; }
    public boolean isFailOnToolError() { return failOnToolError; }
    public Map<String, Object> getOptions() { return options; }
    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getReasoningEffort() { return reasoningEffort; }
    public List<Message> getInitialMessages() { return initialMessages; }
    public AtomicBoolean getAbortFlag() { return abortFlag; }

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
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String reasoningEffort;
        private List<Message> initialMessages;
        private AtomicBoolean abortFlag;

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

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public Builder initialMessages(List<Message> messages) {
            this.initialMessages = messages;
            return this;
        }

        public Builder abortFlag(AtomicBoolean flag) {
            this.abortFlag = flag;
            return this;
        }

        public AgentRunSpec build() {
            Objects.requireNonNull(userMessage, "userMessage is required");
            Objects.requireNonNull(sessionKey, "sessionKey is required");
            return new AgentRunSpec(this);
        }
    }
}
