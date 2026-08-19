package org.gitee.jmeter.ai.agent.config;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.WorkspaceInitializer;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Configuration for Agent Loop.
 * Centralizes all agent-related configuration properties.
 */
public class AgentConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    // Agent Loop Configuration
    private final boolean enabled;
    private final int maxIterations;
    private final int contextWindowTokens;
    private final int toolResultMaxChars;
    private final int maxHistorySize;
    private final int maxStringLength;

    // Memory Configuration
    private final boolean memoryEnabled;
    private final double memoryConsolidationThreshold;
    private final Path workspacePath;

    // Session Configuration
    private final long sessionTimeout;
    private final int maxSessions;

    // Tool Configuration
    private final boolean jmeterToolsEnabled;
    private final boolean filesystemToolsEnabled;
    private final boolean websearchToolsEnabled;
    private final boolean failOnToolError;
    private final long toolTimeoutMs;

    private AgentConfig() {
        // Agent Loop Configuration
        this.enabled = AiConfig.getBoolean("agent.enabled", true);
        this.maxIterations = AiConfig.getInt("jmeter.ai.max.tool.iterations", 50);
        this.contextWindowTokens = AiConfig.getInt("jmeter.ai.context.window.tokens", 65536);
        this.toolResultMaxChars = AiConfig.getInt("agent.tool.result.max.chars", 16000);
        this.maxHistorySize = AiConfig.getInt("jmeter.ai.max.history.size", 120);
        this.maxStringLength = AiConfig.getInt("jmeter.ai.tool.max.string.length", 2048);

        // Memory Configuration
        this.memoryEnabled = AiConfig.getBoolean("agent.memory.enabled", true);
        this.memoryConsolidationThreshold = AiConfig.getDouble("agent.memory.consolidation.threshold", 0.5);

        // Workspace path configuration (single source of truth: WorkspacePaths)
        this.workspacePath = WorkspacePaths.resolveWorkspace();
        log.debug("Resolved workspacePath: {}", this.workspacePath);

        // Initialize workspace with template files
        initializeWorkspace();

        // Session Configuration
        this.sessionTimeout = AiConfig.getLong("agent.session.timeout", 3600000);
        this.maxSessions = AiConfig.getInt("agent.session.max.sessions", 100);

        // Tool Configuration
        this.jmeterToolsEnabled = AiConfig.getBoolean("agent.tools.jmeter.enabled", true);
        this.filesystemToolsEnabled = AiConfig.getBoolean("agent.tools.filesystem.enabled", false);
        this.websearchToolsEnabled = AiConfig.getBoolean("agent.tools.websearch.enabled", false);
        this.failOnToolError = AiConfig.getBoolean("agent.tools.fail.on.error", false);
        this.toolTimeoutMs = AiConfig.getLong("agent.tools.timeout.ms", 30000);

        logConfiguration();
    }

    /**
     * Initialize workspace with template files if not already initialized.
     */
    private void initializeWorkspace() {
        if (!WorkspaceInitializer.isInitialized(workspacePath)) {
            log.info("Initializing workspace: {}", workspacePath);
            WorkspaceInitializer.initialize(workspacePath);
        }
    }

    private void logConfiguration() {
        log.info("Agent Configuration:");
        log.info("  enabled: {}", enabled);
        log.info("  maxIterations: {}", maxIterations);
        log.info("  contextWindowTokens: {}", contextWindowTokens);
        log.info("  maxHistorySize: {}", maxHistorySize);
        log.info("  maxStringLength: {}", maxStringLength);
        log.info("  memoryEnabled: {}", memoryEnabled);
        log.info("  workspacePath: {}", workspacePath);
        log.info("  jmeterToolsEnabled: {}", jmeterToolsEnabled);
        log.info("  failOnToolError: {}", failOnToolError);
        log.info("  toolTimeoutMs: {}", toolTimeoutMs);
    }

    // Singleton instance
    private static final AgentConfig INSTANCE = new AgentConfig();

    public static AgentConfig getInstance() {
        return INSTANCE;
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public int getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public int getMaxHistorySize() {
        return maxHistorySize;
    }

    public boolean isMemoryEnabled() {
        return memoryEnabled;
    }

    public double getMemoryConsolidationThreshold() {
        return memoryConsolidationThreshold;
    }

    public Path getWorkspacePath() {
        return workspacePath;
    }

    public long getSessionTimeout() {
        return sessionTimeout;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public boolean isJmeterToolsEnabled() {
        return jmeterToolsEnabled;
    }

    public boolean isFilesystemToolsEnabled() {
        return filesystemToolsEnabled;
    }

    public boolean isWebsearchToolsEnabled() {
        return websearchToolsEnabled;
    }

    public boolean isFailOnToolError() {
        return failOnToolError;
    }

    public long getToolTimeoutMs() {
        return toolTimeoutMs;
    }
}
