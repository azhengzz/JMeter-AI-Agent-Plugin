package org.qainsights.jmeter.ai.service.provider;

import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.tracing.LangSmithClient;
import org.qainsights.jmeter.ai.tracing.TracedAiService;
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
            log.warn("Model ID is null or empty, using default service (OpenAI)");
            return getCachedService("openai", "default");
        }

        // Detect provider from model ID
        ProviderSpec spec = ProviderRegistry.detectProvider(modelId);
        if (spec == null) {
            log.warn("No provider detected for model: {}, using default (OpenAI)", modelId);
            spec = ProviderRegistry.findByName("openai");
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
            log.warn("Provider name is null or empty, using default service (OpenAI)");
            return new OpenAiService();
        }

        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        if (spec == null) {
            log.warn("Provider not found: {}, using OpenAI", providerName);
            spec = ProviderRegistry.findByName("openai");
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
        if (modelId == null || modelId.isEmpty()) {
            log.warn("Model ID is null or empty, using default service (OpenAI)");
            return getCachedService("openai", "default");
        }

        // Detect provider from model ID
        ProviderSpec spec = ProviderRegistry.detectProvider(modelId);
        if (spec == null) {
            log.warn("No provider detected for model: {}, using default (OpenAI)", modelId);
            spec = ProviderRegistry.findByName("openai");
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
     * Create an AI service for a specific provider.
     *
     * @param providerName The provider name
     * @return An AI service instance
     */
    public static AiService createServiceByProvider(String providerName) {
        ProviderSpec spec = ProviderRegistry.findByName(providerName);
        if (spec == null) {
            log.warn("Provider not found: {}, using OpenAI", providerName);
            spec = ProviderRegistry.findByName("openai");
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
                    claudeService.setModel(modelId);
                }
                yield claudeService;
            }
            case "ollama" -> {
                // Use the existing Ollama service
                OllamaAiService ollamaService = new OllamaAiService();
                if (modelId != null) {
                    ollamaService.setModel(modelId);
                }
                yield ollamaService;
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
     * Get or create a cached service.
     */
    private static AiService getCachedService(String providerName, String modelId) {
        String cacheKey = providerName + ":" + (modelId != null ? modelId : "default");
        return SERVICE_CACHE.computeIfAbsent(cacheKey, k -> {
            ProviderSpec spec = ProviderRegistry.findByName(providerName);
            if (spec == null) {
                return new OpenAiService();
            }
            return createServiceForSpec(spec, modelId);
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
