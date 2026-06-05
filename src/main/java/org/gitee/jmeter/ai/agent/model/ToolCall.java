package org.gitee.jmeter.ai.agent.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a tool call from the LLM.
 * Contains the tool name, arguments, and a unique ID for result matching.
 */
public class ToolCall {
    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public ToolCall(String name, Map<String, Object> arguments) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.arguments = arguments != null ? arguments : Map.of();
    }

    /**
     * Constructor with explicit ID (used when parsing from LLM response)
     */
    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.arguments = arguments != null ? arguments : Map.of();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public String getArgumentsAsString() {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": ");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ToolCall toolCall = (ToolCall) o;
        return Objects.equals(id, toolCall.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ToolCall{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", arguments=" + getArgumentsAsString() +
                '}';
    }
}
