package org.qainsights.jmeter.ai.agent.config;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private AgentConfig() {
        // Agent Loop Configuration
        this.enabled = Boolean.parseBoolean(AiConfig.getProperty("agent.enabled", "true"));
        this.maxIterations = Integer.parseInt(AiConfig.getProperty("agent.max.iterations", "40"));
        this.contextWindowTokens = Integer.parseInt(AiConfig.getProperty("agent.context.window.tokens", "60000"));
        this.toolResultMaxChars = Integer.parseInt(AiConfig.getProperty("agent.tool.result.max.chars", "16000"));

        // Memory Configuration
        this.memoryEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.memory.enabled", "true"));
        this.memoryConsolidationThreshold = Double.parseDouble(AiConfig.getProperty("agent.memory.consolidation.threshold", "0.5"));
        this.workspacePath = Paths.get(AiConfig.getProperty("agent.memory.workspace.path",
                System.getProperty("user.home") + "/.jmeter-ai/agent"));

        // Session Configuration
        this.sessionTimeout = Long.parseLong(AiConfig.getProperty("agent.session.timeout", "3600000"));
        this.maxSessions = Integer.parseInt(AiConfig.getProperty("agent.session.max.sessions", "100"));

        // Tool Configuration
        this.jmeterToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.jmeter.enabled", "true"));
        this.filesystemToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.filesystem.enabled", "false"));
        this.websearchToolsEnabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.websearch.enabled", "false"));

        logConfiguration();
    }

    private void logConfiguration() {
        log.info("Agent Configuration:");
        log.info("  enabled: {}", enabled);
        log.info("  maxIterations: {}", maxIterations);
        log.info("  contextWindowTokens: {}", contextWindowTokens);
        log.info("  memoryEnabled: {}", memoryEnabled);
        log.info("  workspacePath: {}", workspacePath);
        log.info("  jmeterToolsEnabled: {}", jmeterToolsEnabled);
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
}
