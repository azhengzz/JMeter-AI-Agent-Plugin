package org.qainsights.jmeter.ai.agent.memory;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Internal tool for saving memory consolidation results.
 * Used by MemoryConsolidator to save structured memory data.
 */
public class SaveMemoryTool extends AbstractTool {
    private static final Logger log = LoggerFactory.getLogger(SaveMemoryTool.class);

    private final MemoryStore memoryStore;

    public SaveMemoryTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String getName() {
        return "save_memory";
    }

    @Override
    public String getDescription() {
        return "Save the memory consolidation result to persistent storage. " +
                "Use this tool when you have completed analyzing the conversation " +
                "and want to save the consolidated memory.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "history_entry": {
                            "type": "string",
                            "description": "A paragraph summarizing key events/decisions/topics. " +
                                         "Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search."
                        },
                        "memory_update": {
                            "type": "string",
                            "description": "Full updated long-term memory as markdown. " +
                                         "Include all existing facts plus new ones. Return unchanged if nothing new."
                        }
                    },
                    "required": ["history_entry", "memory_update"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        try {
            Object historyEntryObj = parameters.get("history_entry");
            Object memoryUpdateObj = parameters.get("memory_update");

            if (historyEntryObj == null || memoryUpdateObj == null) {
                return ToolResult.error("Missing required parameters: history_entry and/or memory_update");
            }

            String historyEntry = normalizeToString(historyEntryObj);
            String memoryUpdate = normalizeToString(memoryUpdateObj);

            if (historyEntry.isEmpty()) {
                return ToolResult.error("history_entry cannot be empty");
            }

            // Save history entry
            memoryStore.appendHistory(historyEntry);

            // Save memory update if changed
            String currentMemory = memoryStore.readLongTermMemory();
            if (!memoryUpdate.equals(currentMemory)) {
                memoryStore.writeLongTermMemory(memoryUpdate);
            }

            log.info("Memory saved successfully: history_entry={}, memory_changed={}",
                    historyEntry.length() > 0, !memoryUpdate.equals(currentMemory));

            return ToolResult.success("Memory saved successfully");

        } catch (Exception e) {
            log.error("Error saving memory", e);
            return ToolResult.error("Failed to save memory: " + e.getMessage());
        }
    }

    /**
     * Normalize various parameter types to string.
     */
    private String normalizeToString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        // Handle JSON-encoded strings
        String str = value.toString();
        if (str.startsWith("\"") && str.endsWith("\"")) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }
}
