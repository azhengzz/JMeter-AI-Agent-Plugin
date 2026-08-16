package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.model.Message;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Specification for running an agent.
 * Encapsulates all configuration needed for an agent run.
 */
public class AgentRunSpec {

    /**
     * Session key prefix marking an ephemeral subagent run.
     * Runs using this prefix must be fully isolated: no session persistence,
     * no memory consolidation, and no mid-turn injection of their own.
     */
    public static final String SUBAGENT_SESSION_PREFIX = "subagent:";

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
    private final Function<Integer, List<String>> injectionCallback;
    private final boolean persistSession;
    private final boolean delegated;

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
        this.injectionCallback = builder.injectionCallback;
        this.persistSession = builder.persistSession;
        this.delegated = builder.delegated;
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
    public Function<Integer, List<String>> getInjectionCallback() { return injectionCallback; }

    /**
     * Whether this run persists its messages to the session store and runs memory
     * consolidation. Subagent runs set this to false for complete isolation.
     */
    public boolean isPersistSession() { return persistSession; }

    /**
     * Whether this run executes a cross-instance delegated task (IPC {@code /agent}
     * with {@code delegated=true}). The runner arms {@code DelegationGuard} inside
     * the run task so tools executed in this turn refuse to delegate again.
     */
    public boolean isDelegated() { return delegated; }

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
        private Function<Integer, List<String>> injectionCallback;
        private boolean persistSession = true;
        private boolean delegated = false;

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

        public Builder injectionCallback(Function<Integer, List<String>> callback) {
            this.injectionCallback = callback;
            return this;
        }

        /**
         * Whether to persist messages to the session store and run memory
         * consolidation. Defaults to true (main-agent behaviour).
         */
        public Builder persistSession(boolean persist) {
            this.persistSession = persist;
            return this;
        }

        /** Mark this run as a cross-instance delegated turn (arms DelegationGuard). */
        public Builder delegated(boolean delegated) {
            this.delegated = delegated;
            return this;
        }

        public AgentRunSpec build() {
            Objects.requireNonNull(sessionKey, "sessionKey is required");

            // Enforce the subagent isolation invariants at construction time so a
            // mis-wired subagent run fails fast instead of silently polluting the
            // main session (see design.md blocker 2). Checked before the
            // userMessage requirement so a subagent gets the actionable error.
            if (sessionKey.startsWith(SUBAGENT_SESSION_PREFIX)) {
                if (persistSession) {
                    throw new IllegalArgumentException(
                        "Subagent run must set persistSession(false): " + sessionKey);
                }
                if (injectionCallback != null) {
                    throw new IllegalArgumentException(
                        "Subagent run must not have an injectionCallback: " + sessionKey);
                }
                if (initialMessages == null || initialMessages.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Subagent run requires non-empty initialMessages: " + sessionKey);
                }
            }

            // A run driven by initialMessages needs no userMessage; otherwise the
            // user turn is what the run is built from.
            if (initialMessages == null || initialMessages.isEmpty()) {
                Objects.requireNonNull(userMessage, "userMessage is required");
            }

            return new AgentRunSpec(this);
        }
    }
}
