package org.qainsights.jmeter.ai.agent.memory;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;


/**
 * Two-layer memory storage for Agent.
 * - MEMORY.md: Long-term facts and knowledge
 * - HISTORY.md: Searchable conversation log
 */
public class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

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
        Path defaultWorkspace = Paths.get(System.getProperty("user.home")).resolve(".jmeter-ai").resolve("agent");
        String configuredPath = AiConfig.getProperty("agent.workspace.path", null);
        if (configuredPath != null && !configuredPath.isEmpty()) {
            // Fix path separators: replace backslashes with forward slashes
            String fixedPath = configuredPath.replace('\\', '/');
            return Paths.get(fixedPath);
        }
        return defaultWorkspace;
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
     * Append an entry to HISTORY.md.
     * Callers are responsible for including a [YYYY-MM-DD HH:MM] timestamp prefix.
     */
    public void appendHistory(String entry) {
        try {
            String formattedEntry = entry + "\n\n";
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
