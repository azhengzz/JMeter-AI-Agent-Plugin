package org.gitee.jmeter.ai.service.provider;

import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.ClaudeService;
import org.gitee.jmeter.ai.tracing.LangSmithClient;
import org.gitee.jmeter.ai.tracing.TracedAiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating AI service instances based on provider detection.
 * Uses ProviderRegistry to detect the appropriate provider from model ID.
 * Automatically wraps services with LangSmith tracing when enabled.
 */
public class AiServiceFactory {
    private static final Logger log = LoggerFactory.getLogger(AiServiceFactory.class);

    private static final Map<String, AiService> SERVICE_CACHE = new ConcurrentHashMap<>();

    private AiServiceFactory() {
        // Private constructor to prevent instantiation
    }

    /**
     * Create an AI service based on the model ID.
     * Detects the provider from the model ID and returns the appropriate service.
     *
     * @param modelId The model ID (e.g., "deepseek-chat", "gpt-4o", "claude-sonnet-4-6")
     * @return An AI service instance
     */
    public static AiService createService(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            log.warn("Model ID is null or empty, using default provider '{}'", AiConfig.getDefaultProvider());
            return getCachedService(AiConfig.getDefaultProvider(), AiConfig.getDefaultModel());
        }

        // Detect provider from model ID
        ProviderSpec spec = ProviderRegistry.detectProvider(modelId);
        if (spec == null) {
            log.warn("No provider detected for model: {}, using default provider '{}'",
                    modelId, AiConfig.getDefaultProvider());
            spec = ProviderRegistry.findByName(AiConfig.getDefaultProvider());
            if (spec == null) {
                return null;
            }
        }

        String cacheKey = spec.getName() + ":" + modelId;

        // Check cache
        if (SERVICE_CACHE.containsKey(cacheKey)) {
            log.debug("Returning cached service for: {}", cacheKey);
            AiService cachedService = SERVICE_CACHE.get(cacheKey);
            // Update the model if needed
            if (cachedService instanceof OpenAICompatibleProvider provider) {
                provider.setModel(modelId);
            }
            return cachedService;
        }

        // Create new service instance
        AiService service = createServiceForSpec(spec, modelId);
        if (service != null) {
            SERVICE_CACHE.put(cacheKey, service);
            log.info("Created and cached service for: {}", cacheKey);
        }

        return service;
    }

    /**
     * Create an AI service by provider name and model name.
     * This is used when the provider and model are already separated.
     *
     * @param providerName The provider name (e.g., "minimax", "deepseek")
     * @param modelName The model name (e.g., "MiniMax-M2.7", "deepseek-chat")
     * @return An AI service instance
     */
    public static AiService createServiceByName(String providerName, String modelName) {
        if (providerName == null || providerName.isEmpty()) {
            log.warn("Provider name is null or empty, using default provider '{}'",
                    AiConfig.getDefaultProvider());
            providerName = AiConfig.getDefaultProvider();
        }

        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        if (spec == null) {
            log.warn("Provider not found: {}, using default provider '{}'",
                    providerName, AiConfig.getDefaultProvider());
            spec = ProviderRegistry.findByName(AiConfig.getDefaultProvider());
            if (spec == null) {
                return null;
            }
        }

        String cacheKey = spec.getName() + ":" + modelName;

        // Check cache
        if (SERVICE_CACHE.containsKey(cacheKey)) {
            log.debug("Returning cached service for: {}", cacheKey);
            AiService cachedService = SERVICE_CACHE.get(cacheKey);
            // Update the model if needed
            if (cachedService instanceof OpenAICompatibleProvider provider) {
                provider.setModel(modelName);
            }
            return cachedService;
        }

        // Create new service instance
        AiService service = createServiceForSpec(spec, modelName);
        if (service != null) {
            SERVICE_CACHE.put(cacheKey, service);
            log.info("Created and cached service for: {}", cacheKey);
        }

        return service;
    }

    /**
     * Create an AI service for a specific provider.
     *
     * @param providerName The provider name
     * @return An AI service instance
     */
    public static AiService createServiceByProvider(String providerName) {
        if (providerName == null || providerName.isEmpty()) {
            providerName = AiConfig.getDefaultProvider();
        }
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        if (spec == null) {
            log.warn("Provider not found: {}, using default provider '{}'",
                    providerName, AiConfig.getDefaultProvider());
            spec = ProviderRegistry.findByName(AiConfig.getDefaultProvider());
        }

        return createServiceForSpec(spec, null);
    }

    /**
     * Create a service instance based on provider spec.
     * Wraps with LangSmith tracing if enabled.
     */
    private static AiService createServiceForSpec(ProviderSpec spec, String modelId) {
        String backend = spec.getBackend();

        AiService service = switch (backend) {
            case "openai_compat" -> {
                // Use the unified OpenAI-compatible provider
                OpenAICompatibleProvider provider = new OpenAICompatibleProvider(spec);
                if (modelId != null) {
                    provider.setModel(modelId);
                }
                yield provider;
            }
            case "anthropic" -> {
                // Use the existing Claude service
                ClaudeService claudeService = new ClaudeService();
                if (modelId != null) {
                    // The "anthropic:" prefix is a UI-routing tag; the Anthropic API rejects a
                    // prefixed id, so strip it to the bare model name. supportsToolCalling()
                    // returns true unconditionally and no longer validates the id.
                    claudeService.setModel(bareModelName(modelId));
                }
                yield claudeService;
            }
            default -> {
                log.warn("Unknown backend: {}, using OpenAI-compatible provider", backend);
                OpenAICompatibleProvider provider = new OpenAICompatibleProvider(spec);
                if (modelId != null) {
                    provider.setModel(modelId);
                }
                yield provider;
            }
        };

        // Wrap with LangSmith tracing if enabled
        if (LangSmithClient.getInstance().isEnabled()) {
            log.debug("Wrapping service with LangSmith tracing: {}", spec.getName());
            return TracedAiService.wrap(service);
        }

        return service;
    }

    /**
     * Strip a leading {@code "provider:"} prefix to get the bare model id that the
     * Anthropic/Ollama SDKs require (e.g. {@code "anthropic:claude-opus-4-8" ->
     * "claude-opus-4-8"}). OpenAI-compatible providers keep the prefix and are not routed
     * through here. A bare id (no colon) is returned unchanged.
     */
    private static String bareModelName(String modelId) {
        if (modelId == null) {
            return null;
        }
        int colon = modelId.indexOf(':');
        return colon >= 0 ? modelId.substring(colon + 1) : modelId;
    }

    /**
     * Get or create a cached service.
     */
    private static AiService getCachedService(String providerName, String modelId) {
        String cacheKey = providerName + ":" + (modelId != null ? modelId : "default");
        return SERVICE_CACHE.computeIfAbsent(cacheKey, k -> {
            ProviderSpec spec = ProviderRegistry.findByName(providerName);
            if (spec == null) {
                // providerName 解析不到(理论上默认 provider 恒已注册),退回全局默认 provider
                spec = ProviderRegistry.findByName(AiConfig.getDefaultProvider());
            }
            return spec != null ? createServiceForSpec(spec, modelId) : null;
        });
    }

    /**
     * Clear the service cache.
     * This should be called when switching accounts or updating API keys.
     */
    public static void clearCache() {
        log.info("Clearing service cache (size: {})", SERVICE_CACHE.size());
        SERVICE_CACHE.clear();
    }

    /**
     * Remove a specific service from the cache.
     *
     * @param modelId The model ID
     */
    public static void evictFromCache(String modelId) {
        ProviderSpec spec = ProviderRegistry.detectProvider(modelId);
        if (spec != null) {
            String cacheKey = spec.getName() + ":" + modelId;
            SERVICE_CACHE.remove(cacheKey);
            log.info("Evicted service from cache: {}", cacheKey);
        }
    }

    /**
     * Get the current cache size.
     */
    public static int getCacheSize() {
        return SERVICE_CACHE.size();
    }

    /**
     * Check if a service is cached for the given model ID.
     */
    public static boolean isCached(String modelId) {
        ProviderSpec spec = ProviderRegistry.detectProvider(modelId);
        if (spec != null) {
            String cacheKey = spec.getName() + ":" + modelId;
            return SERVICE_CACHE.containsKey(cacheKey);
        }
        return false;
    }
}
