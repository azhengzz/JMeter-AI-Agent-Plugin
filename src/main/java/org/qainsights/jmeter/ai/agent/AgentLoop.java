package org.qainsights.jmeter.ai.agent;

import org.qainsights.jmeter.ai.agent.command.BuiltinCommands;
import org.qainsights.jmeter.ai.agent.command.CommandContext;
import org.qainsights.jmeter.ai.agent.command.CommandRouter;
import org.qainsights.jmeter.ai.agent.context.ContextBuilder;
import org.qainsights.jmeter.ai.agent.hooks.AgentHook;
import org.qainsights.jmeter.ai.agent.hooks.ProgressCallbackHookAdapter;
import org.qainsights.jmeter.ai.agent.memory.MemoryConsolidator;
import org.qainsights.jmeter.ai.agent.memory.MemoryStore;
import org.qainsights.jmeter.ai.agent.model.*;
import org.qainsights.jmeter.ai.agent.run.AgentRunResult;
import org.qainsights.jmeter.ai.agent.run.AgentRunSpec;
import org.qainsights.jmeter.ai.agent.run.AgentRunner;
import org.qainsights.jmeter.ai.agent.session.Session;
import org.qainsights.jmeter.ai.agent.session.SessionManager;
import org.qainsights.jmeter.ai.agent.tools.ToolRegistry;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Facade for Agent Loop operations.
 * Delegates to AgentRunner for actual execution.
 * Maintains backward compatibility with existing code.
 */
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentRunner agentRunner;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryConsolidator memoryConsolidator;
    private final ExecutorService executorService;
    private final int defaultMaxIterations;
    private final CommandRouter commandRouter;
    private final ConcurrentHashMap<String, CompletableFuture<AgentResponse>> activeTasks = new ConcurrentHashMap<>();

    // Runtime state for /status command (matching Nanobot's loop._last_usage / _start_time)
    private final Instant startTime = Instant.now();
    private volatile Map<String, Integer> lastUsage = Map.of();

    public AgentLoop(
            ToolRegistry toolRegistry,
            MemoryStore memoryStore,
            MemoryConsolidator memoryConsolidator,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            AiService aiService) {

        int maxIterations = Integer.parseInt(AiConfig.getProperty("agent.max.iterations", "40"));
        int toolResultMaxChars = Integer.parseInt(AiConfig.getProperty("agent.tool.result.max.chars", "16000"));
        long toolTimeoutMs = Long.parseLong(AiConfig.getProperty("agent.tools.timeout.ms", "30000"));

        this.agentRunner = new AgentRunner(
            toolRegistry,
            memoryConsolidator,
            contextBuilder,
            sessionManager,
            aiService,
            maxIterations,
            toolResultMaxChars,
            toolTimeoutMs
        );

        this.toolRegistry = toolRegistry;
        this.memoryConsolidator = memoryConsolidator;
        this.sessionManager = sessionManager;
        this.defaultMaxIterations = maxIterations;
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agent-loop");
            thread.setDaemon(true);
            return thread;
        });

        log.info("AgentLoop initialized with maxIterations={}, tools={}", maxIterations, toolRegistry.size());

        // Initialize command router
        this.commandRouter = new CommandRouter();
        BuiltinCommands.registerBuiltinCommands(commandRouter);
    }

    /**
     * Set progress callback for this processing run (legacy API).
     * Note: This is now handled per-request via hooks.
     */
    public void setProgressCallback(ProgressCallback callback) {
        // Note: Progress callback is now handled per-request via hooks
        log.debug("setProgressCallback called (legacy API, will be used in next processMessage call)");
    }

    /**
     * Process a message through the agent loop (legacy API).
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey) {
        return processMessage(message, sessionKey, null);
    }

    /**
     * Process a message through the agent loop with progress callback (legacy API).
     * Supports slash command routing before agent execution.
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey,
            ProgressCallback callback) {

        String raw = message.trim();

        // Phase 1: Priority command dispatch (immediate, no executor needed)
        if (commandRouter.isPriority(raw)) {
            CommandContext ctx = new CommandContext(raw, "", null, sessionKey, this);
            String result = commandRouter.dispatchPriority(ctx);
            if (result != null) {
                return CompletableFuture.completedFuture(AgentResponse.success(result));
            }
        }

        // Phase 2: Normal processing (via executor)
        CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
            // Check regular commands first (inside executor)
            Session session = sessionManager.getOrCreate(sessionKey);
            CommandContext ctx = new CommandContext(raw, "", session, sessionKey, this);
            String cmdResult = commandRouter.dispatch(ctx);
            if (cmdResult != null) {
                return AgentResponse.success(cmdResult);
            }

            // Build hook list including legacy callback adapter
            List<AgentHook> hooks = new ArrayList<>();
            if (callback != null) {
                hooks.add(new ProgressCallbackHookAdapter(callback));
            }

            // Build run spec
            AgentRunSpec spec = AgentRunSpec.builder()
                .userMessage(message)
                .sessionKey(sessionKey)
                .hooks(hooks)
                .maxIterations(defaultMaxIterations)
                .concurrentTools(false) // Default to serial for backward compatibility
                .build();

            // Run agent
            AgentRunResult result = agentRunner.run(spec).join();

            // Capture usage stats for /status command
            try {
                Map<String, Object> meta = result.getMetadata();
                if (meta != null && meta.containsKey("usage")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Integer> usage = (Map<String, Integer>) meta.get("usage");
                    setLastUsage(usage);
                }
            } catch (Exception e) {
                log.debug("Could not capture usage stats", e);
            }

            // Convert to legacy response format
            return result.toAgentResponse();

        }, executorService);

        // Track active task for /stop support
        activeTasks.put(sessionKey, future);
        future.whenComplete((r, e) -> activeTasks.remove(sessionKey));

        return future;
    }

    /**
     * New API: Process a message with full run specification.
     */
    public CompletableFuture<AgentRunResult> run(AgentRunSpec spec) {
        return agentRunner.run(spec);
    }

    /**
     * Get the tool registry.
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Get the session manager.
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Get the AI service used by this agent loop.
     */
    public AiService getAiService() {
        return agentRunner.getAiService();
    }

    /**
     * Get the command router.
     */
    public CommandRouter getCommandRouter() {
        return commandRouter;
    }

    /**
     * Get the memory consolidator.
     */
    public MemoryConsolidator getMemoryConsolidator() {
        return memoryConsolidator;
    }

    /**
     * Get the agent loop start time.
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Get last LLM call usage (prompt_tokens, completion_tokens).
     */
    public Map<String, Integer> getLastUsage() {
        return lastUsage;
    }

    /**
     * Update last usage stats after an LLM call.
     */
    public void setLastUsage(Map<String, Integer> usage) {
        this.lastUsage = usage != null ? Map.copyOf(usage) : Map.of();
    }

    /**
     * Cancel the active task for a session.
     * @return true if a task was cancelled
     */
    public boolean cancelActiveTask(String sessionKey) {
        CompletableFuture<AgentResponse> future = activeTasks.remove(sessionKey);
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            log.info("Cancelled active task for session {}: {}", sessionKey, cancelled);
            return cancelled;
        }
        return false;
    }

    /**
     * Shutdown the agent loop.
     */
    public void shutdown() {
        executorService.shutdown();
        sessionManager.shutdown();
        log.info("AgentLoop shutdown complete");
    }

    /**
     * Progress callback interface (legacy, kept for backward compatibility).
     */
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
