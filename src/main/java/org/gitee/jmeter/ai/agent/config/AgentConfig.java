package org.gitee.jmeter.ai.agent.config;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.WorkspaceInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    private final boolean concurrentToolsEnabled;
    private final boolean failOnToolError;
    private final long toolTimeoutMs;

    private AgentConfig() {
        // Agent Loop Configuration
        this.enabled = Boolean.parseBoolean(AiConfig.getProperty("agent.enabled", "true"));
        this.maxIterations = Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tool.iterations", "50"));
        this.contextWindowTokens = Integer.parseInt(AiConfig.getProperty("jmeter.ai.context.window.tokens", "65536"));
        this.toolResultMaxChars = Integer.parseInt(AiConfig.getProperty("agent.tool.result.max.chars", "16000"));
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.history.size", "120"));
        this.maxStringLength = Integer.parseInt(AiConfig.getProperty("jmeter.ai.tool.max.string.length", "2048"));

        // Memory Configuration
        this.memoryEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.memory.enabled", "true"));
        this.memoryConsolidationThreshold = Double.parseDouble(AiConfig.getProperty("agent.memory.consolidation.threshold", "0.5"));

        // Workspace path configuration
        String configuredPath = AiConfig.getProperty("agent.workspace.path", null);
        if (configuredPath != null && !configuredPath.isEmpty()) {
            // Fix path separators: replace backslashes with forward slashes for cross-platform compatibility
            // This handles cases where config files use single backslashes on Windows
            String fixedPath = configuredPath.replace('\\', '/');
            log.debug("Original configuredPath: [{}]", configuredPath);
            log.debug("Fixed configuredPath: [{}]", fixedPath);
            this.workspacePath = Paths.get(fixedPath).toAbsolutePath().normalize();
            log.debug("Resolved workspacePath from config: {}", this.workspacePath);
        } else {
            // Use default workspace path
            String userHome = System.getProperty("user.home");
            Path userHomePath = Paths.get(userHome).toAbsolutePath().normalize();
            this.workspacePath = userHomePath.resolve(".jmeter-ai").resolve("agent").normalize();
            log.debug("Using default workspacePath: {}", this.workspacePath);
        }

        // Initialize workspace with template files
        initializeWorkspace();

        // Session Configuration
        this.sessionTimeout = Long.parseLong(AiConfig.getProperty("agent.session.timeout", "3600000"));
        this.maxSessions = Integer.parseInt(AiConfig.getProperty("agent.session.max.sessions", "100"));

        // Tool Configuration
        this.jmeterToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.jmeter.enabled", "true"));
        this.filesystemToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.filesystem.enabled", "false"));
        this.websearchToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.websearch.enabled", "false"));
        this.concurrentToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.concurrent.enabled", "false"));
        this.failOnToolError = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.fail.on.error", "false"));
        this.toolTimeoutMs = Long.parseLong(AiConfig.getProperty("agent.tools.timeout.ms", "30000"));

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
        log.info("  concurrentToolsEnabled: {}", concurrentToolsEnabled);
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

    public boolean isConcurrentToolsEnabled() {
        return concurrentToolsEnabled;
    }

    public boolean isFailOnToolError() {
        return failOnToolError;
    }

    public long getToolTimeoutMs() {
        return toolTimeoutMs;
    }
}
