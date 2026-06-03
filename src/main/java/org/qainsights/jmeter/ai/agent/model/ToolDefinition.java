package org.qainsights.jmeter.ai.agent.model;

import java.util.Map;
import java.util.Objects;

/**
 * Tool definition in OpenAI function calling format.
 * Used to describe available tools to the LLM.
 */
public class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    private ToolDefinition(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameters = builder.parameters;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description;
        private Map<String, Object> parameters;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public ToolDefinition build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Tool name cannot be null or empty");
            }
            return new ToolDefinition(this);
        }
    }

    /**
     * Convert to OpenAI function calling format
     */
    public Map<String, Object> toOpenAIFunction() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description != null ? description : "",
                        "parameters", parameters != null ? parameters : Map.of("type", "object", "properties", Map.of())
                )
        );
    }

    /**
     * Convert to Anthropic tool format
     */
    public Map<String, Object> toAnthropicTool() {
        return Map.of(
                "name", name,
                "description", description != null ? description : "",
                "input_schema", parameters != null ? parameters : Map.of("type", "object", "properties", Map.of())
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolDefinition that = (ToolDefinition) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "ToolDefinition{" +
                "name='" + name + '\'' +
                ", description='" + (description != null ? truncate(description, 50) : "null") + '\'' +
                '}';
    }

    private String truncate(String str, int max) {
        if (str == null) return null;
        return str.length() <= max ? str : str.substring(0, max) + "...(truncated)";
    }
}
