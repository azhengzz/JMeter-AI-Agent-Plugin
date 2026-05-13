package org.qainsights.jmeter.ai.service.provider;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable metadata class for LLM provider specifications.
 * Uses Builder pattern for construction.
 */
public final class ProviderSpec {
    private final String name;
    private final String displayName;
    private final String defaultApiBase;
    private final String envKey;
    private final String[] keywords;
    private final String backend;
    private final Map<String, Map<String, Object>> modelOverrides;
    private final boolean rawHttpClientOnly;  // Use raw HTTP instead of SDK (for incompatible APIs)

    // How to inject the thinking on/off toggle into extra_body.
    // ""              — no extra_body needed (default)
    // "thinking_type" — {"thinking": {"type": "enabled"/"disabled"}}  (DeepSeek, VolcEngine, BytePlus)
    // "enable_thinking" — {"enable_thinking": true/false}  (DashScope)
    // "reasoning_split" — {"reasoning_split": true/false}  (MiniMax)
    private final String thinkingStyle;

    private ProviderSpec(Builder builder) {
        this.name = builder.name;
        this.displayName = builder.displayName;
        this.defaultApiBase = builder.defaultApiBase;
        this.envKey = builder.envKey;
        this.keywords = builder.keywords;
        this.backend = builder.backend;
        this.modelOverrides = Collections.unmodifiableMap(new HashMap<>(builder.modelOverrides));
        this.rawHttpClientOnly = builder.rawHttpClientOnly;
        this.thinkingStyle = builder.thinkingStyle;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultApiBase() {
        return defaultApiBase;
    }

    public String getEnvKey() {
        return envKey;
    }

    public String[] getKeywords() {
        return keywords;
    }

    public String getBackend() {
        return backend;
    }

    public Map<String, Map<String, Object>> getModelOverrides() {
        return modelOverrides;
    }

    public Map<String, Object> getOverridesForModel(String model) {
        return modelOverrides.getOrDefault(model, Collections.emptyMap());
    }

    public boolean isRawHttpClientOnly() {
        return rawHttpClientOnly;
    }

    public String getThinkingStyle() {
        return thinkingStyle;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProviderSpec that = (ProviderSpec) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "ProviderSpec{" +
                "name='" + name + '\'' +
                ", displayName='" + displayName + '\'' +
                ", defaultApiBase='" + defaultApiBase + '\'' +
                ", backend='" + backend + '\'' +
                '}';
    }

    /**
     * Builder for ProviderSpec.
     */
    public static class Builder {
        private String name;
        private String displayName;
        private String defaultApiBase;
        private String envKey;
        private String[] keywords = new String[0];
        private String backend = "openai_compat";
        private final Map<String, Map<String, Object>> modelOverrides = new HashMap<>();
        private boolean rawHttpClientOnly = false;
        private String thinkingStyle = "";

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder defaultApiBase(String defaultApiBase) {
            this.defaultApiBase = defaultApiBase;
            return this;
        }

        public Builder envKey(String envKey) {
            this.envKey = envKey;
            return this;
        }

        public Builder keywords(String... keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder backend(String backend) {
            this.backend = backend;
            return this;
        }

        /**
         * Mark this provider as requiring raw HTTP client only.
         * This is for providers whose API responses are not fully compatible with OpenAI SDK.
         *
         * @param rawHttpClientOnly true to use raw HTTP client instead of SDK
         * @return this builder
         */
        public Builder rawHttpClientOnly(boolean rawHttpClientOnly) {
            this.rawHttpClientOnly = rawHttpClientOnly;
            return this;
        }

        /**
         * Set the thinking style for this provider.
         * @param thinkingStyle one of "", "thinking_type", "enable_thinking", "reasoning_split"
         * @return this builder
         */
        public Builder thinkingStyle(String thinkingStyle) {
            this.thinkingStyle = thinkingStyle != null ? thinkingStyle : "";
            return this;
        }

        /**
         * Add a parameter override for a specific model.
         *
         * @param model The model name
         * @param param The parameter name (e.g., "temperature")
         * @param value The parameter value
         * @return this builder
         */
        public Builder addModelOverride(String model, String param, Object value) {
            modelOverrides.computeIfAbsent(model, k -> new HashMap<>()).put(param, value);
            return this;
        }

        public ProviderSpec build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Provider name is required");
            }
            if (displayName == null || displayName.isEmpty()) {
                displayName = name;
            }
            if (defaultApiBase == null || defaultApiBase.isEmpty()) {
                throw new IllegalArgumentException("Default API base URL is required");
            }
            if (envKey == null || envKey.isEmpty()) {
                envKey = name.toUpperCase() + "_API_KEY";
            }
            return new ProviderSpec(this);
        }
    }
}
