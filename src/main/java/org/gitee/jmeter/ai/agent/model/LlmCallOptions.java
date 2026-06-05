package org.gitee.jmeter.ai.agent.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Per-call LLM parameter overrides.
 * All fields are nullable; null means "use the service's configured default."
 * Mirrors Nanobot's kwargs override pattern in runner.py.
 */
public class LlmCallOptions {

    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final String reasoningEffort;

    private LlmCallOptions(Builder builder) {
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.reasoningEffort = builder.reasoningEffort;
    }

    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getReasoningEffort() { return reasoningEffort; }

    public boolean hasOverrides() {
        return model != null || temperature != null || maxTokens != null || reasoningEffort != null;
    }

    public static LlmCallOptions defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String reasoningEffort;

        public Builder model(String model) { this.model = model; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder reasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; return this; }

        public LlmCallOptions build() {
            return new LlmCallOptions(this);
        }
    }
}
