package org.qainsights.jmeter.ai.service.provider;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Central registry for LLM provider specifications.
 * Single source of truth for all provider metadata.
 */
public class ProviderRegistry {
    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private static final List<ProviderSpec> PROVIDERS = new ArrayList<>();

    static {
        // =====================================================
        // Chinese LLM Providers
        // =====================================================

        // DeepSeek
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("deepseek")
                .displayName("DeepSeek")
                .defaultApiBase("https://api.deepseek.com")
                .envKey("deepseek.api.key")
                .keywords("deepseek")
                .build());

        // Zhipu AI (GLM)
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("zhipu")
                .displayName("Zhipu AI")
                .defaultApiBase("https://open.bigmodel.cn/api/paas/v4")
                .envKey("zhipu.api.key")
                .keywords("zhipu", "glm", "zai")
                .build());

        // Moonshot (Kimi)
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("moonshot")
                .displayName("Moonshot")
                .defaultApiBase("https://api.moonshot.ai/v1")
                .envKey("moonshot.api.key")
                .keywords("moonshot", "kimi")
                .addModelOverride("kimi-k2.5", "temperature", 1.0)
                .build());

        // MiniMax
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("minimax")
                .displayName("MiniMax")
                .defaultApiBase("https://api.minimax.io/v1")
                .envKey("minimax.api.key")
                .keywords("minimax")
                .rawHttpClientOnly(true)  // MiniMax returns extra fields not compatible with OpenAI SDK
                .build());

        // =====================================================
        // Existing Providers (for backward compatibility)
        // =====================================================

        // OpenAI
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("openai")
                .displayName("OpenAI")
                .defaultApiBase("https://api.openai.com/v1")
                .envKey("openai.api.key")
                .keywords("openai", "gpt")
                .backend("openai_compat")
                .build());

        // Anthropic (Claude) - uses different SDK
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("anthropic")
                .displayName("Anthropic")
                .defaultApiBase("https://api.anthropic.com")
                .envKey("anthropic.api.key")
                .keywords("anthropic", "claude")
                .backend("anthropic")
                .build());

        // Ollama (local models)
        PROVIDERS.add(new ProviderSpec.Builder()
                .name("ollama")
                .displayName("Ollama")
                .defaultApiBase("http://localhost:11434/v1")
                .envKey("ollama.api.key")
                .keywords("ollama", "llama", "mistral", "codellama")
                .backend("openai_compat")
                .build());

        log.info("Loaded {} provider specifications", PROVIDERS.size());
    }

    /**
     * Get all registered providers.
     *
     * @return Unmodifiable list of all providers
     */
    public static List<ProviderSpec> getAllProviders() {
        return Collections.unmodifiableList(PROVIDERS);
    }

    /**
     * Find a provider by name.
     *
     * @param name The provider name
     * @return The provider spec, or null if not found
     */
    public static ProviderSpec findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        String normalizedName = name.toLowerCase();
        for (ProviderSpec spec : PROVIDERS) {
            if (spec.getName().equals(normalizedName)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * Find a provider by model name.
     * Checks if the model name contains any provider keywords.
     *
     * @param model The model name (e.g., "glm-4-plus", "deepseek-chat")
     * @return The provider spec, or null if not found
     */
    public static ProviderSpec findByModel(String model) {
        if (model == null || model.isEmpty()) {
            return null;
        }

        String lowerModel = model.toLowerCase();

        // Check each provider's keywords
        for (ProviderSpec spec : PROVIDERS) {
            for (String keyword : spec.getKeywords()) {
                if (lowerModel.contains(keyword)) {
                    log.debug("Detected provider '{}' from model '{}'", spec.getName(), model);
                    return spec;
                }
            }
        }

        return null;
    }

    /**
     * Detect provider from model ID or API key.
     * This is the main entry point for provider detection.
     *
     * @param modelIdOrKey Model ID or API key string
     * @return The detected provider spec, or null if not found
     */
    public static ProviderSpec detectProvider(String modelIdOrKey) {
        if (modelIdOrKey == null || modelIdOrKey.isEmpty()) {
            return null;
        }

        // First try to find by model name
        ProviderSpec spec = findByModel(modelIdOrKey);
        if (spec != null) {
            return spec;
        }

        // If no match, return default (OpenAI)
        log.debug("No provider detected for '{}', using default (openai)", modelIdOrKey);
        return findByName("openai");
    }

    /**
     * Get models for a specific provider.
     * Reads from properties file, with fallback to default models.
     *
     * @param providerName The provider name
     * @return List of model names, or empty list if provider not found
     */
    public static List<String> getModelsForProvider(String providerName) {
        ProviderSpec spec = findByName(providerName);
        if (spec == null) {
            return Collections.emptyList();
        }

        // Try to read models from properties first
        String modelsConfig = AiConfig.getProperty(providerName + ".models", "");
        if (!modelsConfig.isEmpty()) {
            List<String> models = Arrays.asList(modelsConfig.split(","));
            // Trim whitespace from each model name
            List<String> trimmedModels = new ArrayList<>();
            for (String model : models) {
                trimmedModels.add(model.trim());
            }
            log.info("Loaded {} models from properties for provider: {}", trimmedModels.size(), providerName);
            return trimmedModels;
        }

        // Fallback to default models if not configured
        List<String> defaultModels = getDefaultModels(spec.getName());
        log.info("Using default models for provider {}: {}", providerName, defaultModels);
        return defaultModels;
    }

    /**
     * Get default models for a provider (fallback when not configured in properties).
     */
    private static List<String> getDefaultModels(String providerName) {
        return switch (providerName) {
            // DeepSeek default models
            case "deepseek" -> List.of(
                    "deepseek-chat",
                    "deepseek-coder"
            );

            // Zhipu GLM default models
            case "zhipu" -> List.of(
                    "glm-5",
                    "glm-4.7"
            );

            // Moonshot Kimi default models
            case "moonshot" -> List.of(
                    "kimi-k2.5"
            );

            // MiniMax default models
            case "minimax" -> List.of(
                    "MiniMax-M2.7"
            );

            // OpenAI models (loaded dynamically from API)
            case "openai" -> List.of(
                    "gpt-4o",
                    "gpt-4o-mini",
                    "gpt-4-turbo",
                    "gpt-3.5-turbo"
            );

            // Default: empty list
            default -> Collections.emptyList();
        };
    }
}
