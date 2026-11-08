package org.gitee.jmeter.ai.agent.model;

import java.util.function.BooleanSupplier;

/**
 * Per-call LLM parameter overrides.
 * All config fields are nullable; null means "use the service's configured default."
 * Mirrors Nanobot's kwargs override pattern in runner.py.
 *
 * Streaming seam (the only one): a non-null {@link StreamDeltaListener} means
 * "this call should stream" — providers check the listener and MUST NOT
 * stream without it. The abort predicate is polled between chunks and the
 * close registry receives the provider stream's close handle so cancellation
 * can break a stalled read.
 */
public class LlmCallOptions {

    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final String reasoningEffort;
    private final StreamDeltaListener streamDeltaListener;
    private final BooleanSupplier abortCheck;
    private final StreamCloseRegistry streamCloseRegistry;

    private LlmCallOptions(Builder builder) {
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.reasoningEffort = builder.reasoningEffort;
        this.streamDeltaListener = builder.streamDeltaListener;
        this.abortCheck = builder.abortCheck;
        this.streamCloseRegistry = builder.streamCloseRegistry;
    }

    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getReasoningEffort() { return reasoningEffort; }
    public StreamDeltaListener getStreamDeltaListener() { return streamDeltaListener; }
    public BooleanSupplier getAbortCheck() { return abortCheck; }
    public StreamCloseRegistry getStreamCloseRegistry() { return streamCloseRegistry; }

    /** True when this call carries a streaming consumer — the single streaming gate providers consult. */
    public boolean wantsStreaming() {
        return streamDeltaListener != null;
    }

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
        private StreamDeltaListener streamDeltaListener;
        private BooleanSupplier abortCheck;
        private StreamCloseRegistry streamCloseRegistry;

        public Builder model(String model) { this.model = model; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder reasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; return this; }
        public Builder streamDeltaListener(StreamDeltaListener listener) { this.streamDeltaListener = listener; return this; }
        public Builder abortCheck(BooleanSupplier abortCheck) { this.abortCheck = abortCheck; return this; }
        public Builder streamCloseRegistry(StreamCloseRegistry registry) { this.streamCloseRegistry = registry; return this; }

        public LlmCallOptions build() {
            return new LlmCallOptions(this);
        }
    }
}
