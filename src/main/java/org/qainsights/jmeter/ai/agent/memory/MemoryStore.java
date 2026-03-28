package org.qainsights.jmeter.ai.agent.memory;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Two-layer memory storage for Agent.
 * - MEMORY.md: Long-term facts and knowledge
 * - HISTORY.md: Searchable conversation log
 */
public class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;

    public MemoryStore() {
        this(getDefaultWorkspace());
    }

    public MemoryStore(Path workspace) {
        this.memoryDir = workspace.resolve("memory");
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("HISTORY.md");
        ensureDirectories();
    }

    private static Path getDefaultWorkspace() {
        String path = AiConfig.getProperty("agent.memory.workspace.path",
                System.getProperty("user.home") + "/.jmeter-ai/agent");
        return Paths.get(path);
    }

    private void ensureDirectories() {
        try {
            if (!Files.exists(memoryDir)) {
                Files.createDirectories(memoryDir);
                log.info("Created memory directory: {}", memoryDir);
            }
        } catch (IOException e) {
            log.error("Failed to create memory directory", e);
        }
    }

    /**
     * Read long-term memory from MEMORY.md
     */
    public String readLongTermMemory() {
        try {
            if (Files.exists(memoryFile)) {
                String content = Files.readString(memoryFile);
                log.debug("Read long-term memory: {} characters", content.length());
                return content;
            }
        } catch (IOException e) {
            log.error("Error reading memory file", e);
        }
        return "";
    }

    /**
     * Write long-term memory to MEMORY.md
     */
    public void writeLongTermMemory(String content) {
        try {
            Files.writeString(memoryFile, content != null ? content : "");
            log.info("Updated long-term memory: {} characters", content != null ? content.length() : 0);
        } catch (IOException e) {
            log.error("Error writing memory file", e);
        }
    }

    /**
     * Append an entry to HISTORY.md
     */
    public void appendHistory(String entry) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String formattedEntry = "[" + timestamp + "] " + entry + "\n\n";
            Files.writeString(historyFile, formattedEntry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Appended to history: {} characters", formattedEntry.length());
        } catch (IOException e) {
            log.error("Error appending to history file", e);
        }
    }

    /**
     * Get memory context for system prompt
     */
    public String getMemoryContext() {
        String longTerm = readLongTermMemory();
        if (!longTerm.isEmpty()) {
            return "## Long-term Memory\n" + longTerm;
        }
        return "";
    }

    /**
     * Check if memory system is enabled
     */
    public boolean isEnabled() {
        return Boolean.parseBoolean(AiConfig.getProperty("agent.memory.enabled", "true"));
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    public Path getMemoryFile() {
        return memoryFile;
    }

    public Path getHistoryFile() {
        return historyFile;
    }
}
