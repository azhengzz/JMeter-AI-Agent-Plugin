package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.command.BuiltinCommands;
import org.gitee.jmeter.ai.agent.command.CommandContext;
import org.gitee.jmeter.ai.agent.command.CommandRouter;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.hooks.ProgressCallbackHookAdapter;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.*;
import org.gitee.jmeter.ai.agent.run.AgentRunResult;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final GenerationSettings generationSettings;
    private final CommandRouter commandRouter;
    private final ConcurrentHashMap<String, CompletableFuture<AgentResponse>> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> abortFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> completionLatches = new ConcurrentHashMap<>();

    // Runtime state for /status command (matching Nanobot's loop._last_usage / _start_time)
    private final Instant startTime = Instant.now();
    private volatile Map<String, Integer> lastUsage = Map.of();
    private volatile ProgressCallback progressCallback;

    public AgentLoop(
            ToolRegistry toolRegistry,
            MemoryStore memoryStore,
            MemoryConsolidator memoryConsolidator,
            ContextBuilder contextBuilder,
            SessionManager sessionManager,
            AiService aiService) {

        int maxIterations = Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tool.iterations", "50"));
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
        this.generationSettings = aiService.getGenerationSettings();
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
        this.progressCallback = callback;
    }

    /**
     * Process a message through the agent loop (legacy API).
     */
    public CompletableFuture<AgentResponse> processMessage(
            String message,
            String sessionKey) {
        return processMessage(message, sessionKey, progressCallback);
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
        final AtomicBoolean abortFlag = new AtomicBoolean(false);
        final CountDownLatch completionLatch = new CountDownLatch(1);
        abortFlags.put(sessionKey, abortFlag);
        completionLatches.put(sessionKey, completionLatch);

        CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
            // Check regular commands first (inside executor)
            Session session = sessionManager.getOrCreate(sessionKey);
            CommandContext ctx = new CommandContext(raw, "", session, sessionKey, this);
            String cmdResult = commandRouter.dispatch(ctx);
            if (cmdResult != null) {
                return AgentResponse.success(cmdResult);
            }

            // Build run spec with generation defaults
            AgentRunSpec spec = AgentRunSpec.builder()
                .userMessage(message)
                .sessionKey(sessionKey)
                .hook(callback != null ? new ProgressCallbackHookAdapter(callback) : null)
                .maxIterations(defaultMaxIterations)
                .concurrentTools(false)
                .model(AiConfig.getDefaultModel())
                .temperature(generationSettings.getTemperature())
                .maxTokens(generationSettings.getMaxTokens())
                .reasoningEffort(generationSettings.getReasoningEffort())
                .abortFlag(abortFlag)
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

        // Track active task for cancellation support
        activeTasks.put(sessionKey, future);
        future.whenComplete((r, e) -> {
            activeTasks.remove(sessionKey);
            abortFlags.remove(sessionKey);
            completionLatches.remove(sessionKey);
            completionLatch.countDown();
        });

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
        // 1. Set abort flag first (signals agent loop to stop)
        AtomicBoolean abort = abortFlags.get(sessionKey);
        if (abort != null) {
            abort.set(true);
        }

        // 2. Interrupt the actual agent loop thread (stops in-progress LLM calls)
        agentRunner.interrupt();

        // 3. Cancel the future
        CompletableFuture<AgentResponse> future = activeTasks.remove(sessionKey);
        boolean cancelled = false;
        if (future != null && !future.isDone()) {
            cancelled = future.cancel(true);
            log.info("Cancelled active task for session {}: {}", sessionKey, cancelled);
        }

        // 4. Wait for actual completion
        CountDownLatch latch = completionLatches.get(sessionKey);
        if (latch != null) {
            try {
                boolean completed = latch.await(5, TimeUnit.SECONDS);
                if (!completed) {
                    log.warn("Timed out waiting for task cleanup in session {}", sessionKey);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return cancelled || (abort != null);
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
     * Progress callback interface for receiving typed updates during agent execution.
     */
    public interface ProgressCallback {
        void onProgress(ProgressUpdate update);
    }
}
