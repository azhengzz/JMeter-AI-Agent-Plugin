package org.gitee.jmeter.ai.agent.memory;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Internal tool for saving memory consolidation results.
 * Used by MemoryConsolidator to save structured memory data.
 *
 * @deprecated 死代码——全仓库无 {@code new SaveMemoryTool} 注册,从未接线。活路径见
 *             {@link MemoryConsolidator} 的 {@code SAVE_MEMORY_TOOL_DEF} 强制工具调用 + 直接落盘。
 *             本类锁语义(仅 {@code read-compare-write})与 MemoryConsolidator 的
 *             {@code read→LLM→write} 全程持锁分叉:若误接线,会以陈旧 {@code memoryUpdate}
 *             覆盖并发深度提炼结果(lost-update)。请勿使用。
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

            // Save memory update if changed (跨进程写锁:与并发深度提炼的 MEMORY.md 读改写
            // 串行化,防基于陈旧读覆盖丢提炼;等锁失败按 best-effort 降级为无锁执行)
            boolean memoryChanged = persistMemoryUpdate(memoryUpdate);

            log.info("Memory saved successfully: history_entry={}, memory_changed={}",
                    historyEntry.length() > 0, memoryChanged);

            return ToolResult.success("Memory saved successfully");

        } catch (Exception e) {
            log.error("Error saving memory", e);
            return ToolResult.error("Failed to save memory: " + e.getMessage());
        }
    }

    /**
     * 写 MEMORY.md 的 read-modify-write,全程持跨进程写锁与并发深度提炼串行化;
     * 等锁被中止/中断视为未执行、不降级写盘(降级会重新打开 lost-update 敞口);
     * 仅真实 IO 故障(盘满/权限)按既有 best-effort 语义降级为无锁执行。
     *
     * @return 是否实际改写(与当前盘上内容不同)
     */
    private boolean persistMemoryUpdate(String memoryUpdate) {
        MemoryStore.MemoryWriteLock lock;
        try {
            lock = memoryStore.lockLongTermMemory(() -> false);
        } catch (IOException e) {
            log.error("Failed to acquire MEMORY.md write lock, proceeding unlocked (best-effort)", e);
            return writeIfChanged(memoryUpdate);
        }
        if (lock == null) {
            // 等锁期间被中断 → 视为未执行、不降级写盘(与 MemoryConsolidator 一致)
            log.info("Memory save aborted while waiting for MEMORY.md write lock");
            return false;
        }
        try (MemoryStore.MemoryWriteLock ignored = lock) {
            return writeIfChanged(memoryUpdate);
        }
    }

    private boolean writeIfChanged(String memoryUpdate) {
        String currentMemory = memoryStore.readLongTermMemory();
        if (!memoryUpdate.equals(currentMemory)) {
            return memoryStore.writeLongTermMemory(memoryUpdate);
        }
        return false;
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
