package org.qainsights.jmeter.ai.agent.model;

import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Default generation parameters shared across all providers.
 * Stored on each provider so every call site inherits the same defaults
 * without having to pass temperature / max_tokens / reasoning_effort
 * through every layer. Individual call sites can still override by
 * passing explicit values via LlmCallOptions.
 */
public class GenerationSettings {

    private double temperature;
    private int maxTokens;
    private String reasoningEffort;

    public GenerationSettings(double temperature, int maxTokens, String reasoningEffort) {
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * Create GenerationSettings from global configuration defaults.
     */
    public static GenerationSettings fromConfig() {
        return new GenerationSettings(
            Double.parseDouble(AiConfig.getProperty("jmeter.ai.temperature", "0.7")),
            Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tokens", "4096")),
            AiConfig.getProperty("jmeter.ai.reasoning.effort", "medium")
        );
    }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    @Override
    public String toString() {
        return "GenerationSettings{temperature=" + temperature
            + ", maxTokens=" + maxTokens
            + ", reasoningEffort=" + reasoningEffort + "}";
    }
}
