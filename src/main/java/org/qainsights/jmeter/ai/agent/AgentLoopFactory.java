package org.qainsights.jmeter.ai.agent;

import org.qainsights.jmeter.ai.agent.config.AgentConfig;
import org.qainsights.jmeter.ai.agent.context.ContextBuilder;
import org.qainsights.jmeter.ai.agent.memory.MemoryConsolidator;
import org.qainsights.jmeter.ai.agent.memory.MemoryStore;
import org.qainsights.jmeter.ai.agent.session.SessionManager;
import org.qainsights.jmeter.ai.agent.tools.JMeterToolRegistry;
import org.qainsights.jmeter.ai.agent.tools.ToolRegistry;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating Agent Loop instances.
 * Provides a single point of configuration and initialization.
 */
public class AgentLoopFactory {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopFactory.class);

    private static AgentLoop instance;

    /**
     * Get or create the Agent Loop singleton instance
     */
    public static synchronized AgentLoop getAgentLoop(AiService aiService) {
        // Always recreate if a different service is requested
        if (instance == null || !instance.getAiService().equals(aiService)) {
            if (instance != null) {
                log.info("Switching AgentLoop from {} to {}", instance.getAiService().getName(), aiService.getName());
                instance.shutdown();
            }
            instance = createAgentLoop(aiService);
        }
        return instance;
    }

    /**
     * Get the Agent Loop singleton instance (must be initialized first)
     */
    public static synchronized AgentLoop getAgentLoop() {
        if (instance == null) {
            throw new IllegalStateException("Agent Loop not initialized. Call getAgentLoop(AiService) first.");
        }
        return instance;
    }

    /**
     * Create a new Agent Loop instance
     */
    private static AgentLoop createAgentLoop(AiService aiService) {
        AgentConfig config = AgentConfig.getInstance();

        if (!config.isEnabled()) {
            log.warn("Agent Loop is disabled in configuration");
            return null;
        }

        log.info("Creating Agent Loop with AI service: {}", aiService.getName());

        // Create components
        ToolRegistry toolRegistry = new ToolRegistry();
        MemoryStore memoryStore = new MemoryStore(config.getWorkspacePath());
        MemoryConsolidator consolidator = new MemoryConsolidator(memoryStore, aiService);
        SessionManager sessionManager = new SessionManager(config.getWorkspacePath());

        // Get system prompt from AI service if available
        String systemPrompt = "";
        if (aiService instanceof ClaudeService) {
            // Could extract system prompt from ClaudeService if needed
            systemPrompt = ""; // Will use default from ContextBuilder
        }

        ContextBuilder contextBuilder = new ContextBuilder(
                memoryStore,
                systemPrompt,
                config.getWorkspacePath()
        );

        // Register tools
        if (config.isJmeterToolsEnabled()) {
            JMeterToolRegistry.registerDefaultTools(toolRegistry, aiService);
        }

        // Create Agent Loop
        AgentLoop agentLoop = new AgentLoop(
                toolRegistry,
                memoryStore,
                consolidator,
                contextBuilder,
                sessionManager,
                aiService
        );

        log.info("Agent Loop created successfully with {} tools", toolRegistry.size());
        return agentLoop;
    }

    /**
     * Reset the Agent Loop instance (for testing or reconfiguration)
     */
    public static synchronized void reset() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    /**
     * Shutdown the Agent Loop
     */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
}
