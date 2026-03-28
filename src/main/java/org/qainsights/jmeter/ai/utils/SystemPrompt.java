package org.qainsights.jmeter.ai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized System Prompt management for all AI providers.
 * <p>
 * Configuration priority (highest to lowest):
 * 1. Provider-specific: {provider}.system.prompt (e.g., claude.system.prompt)
 * 2. Unified: jmeter.ai.system.prompt (applies to all providers)
 * 3. Built-in default (JMeter expert assistant)
 * <p>
 * Usage:
 * <pre>
 * String prompt = SystemPrompt.get();                          // Use unified config
 * String prompt = SystemPrompt.get("claude");                  // With provider hint
 * String prompt = SystemPrompt.get("claude", "custom prompt"); // Direct override
 * </pre>
 */
public class SystemPrompt {
    private static final Logger log = LoggerFactory.getLogger(SystemPrompt.class);

    /**
     * The default JMeter system prompt used when no custom prompt is configured.
     */
    public static final String DEFAULT_JMETER_SYSTEM_PROMPT =
            "You are a JMeter expert assistant embedded in a JMeter plugin called 'Feather Wand - JMeter Agent'. " +
            "Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans. " +
            "Provide concise, accurate information about JMeter elements and best practices. " +
            "Use proper JMeter terminology and element names. " +
            "Version: JMeter 5.6+ (Also support questions about older versions from 3.0+)";

    /**
     * Multi-line version of the default prompt with detailed guidelines.
     * This can be referenced in documentation or used as a template for customization.
     */
    public static final String DEFAULT_JMETER_SYSTEM_PROMPT_DETAILED =
            "You are a JMeter expert assistant embedded in a JMeter plugin called 'Feather Wand - JMeter Agent'. " +
            "Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans.\n" +
            "\n" +
            "## CAPABILITIES:\n" +
            "- Provide detailed information about JMeter elements, their properties, and how they work together\n" +
            "- Suggest appropriate elements based on the user's testing needs\n" +
            "- Explain best practices for performance testing with JMeter\n" +
            "- Help troubleshoot and optimize test plans\n" +
            "- Recommend configurations for different testing scenarios\n" +
            "- Analyze test results and provide actionable insights\n" +
            "- Generate script snippets in Groovy or Java for specific testing requirements\n" +
            "- Explain JMeter's distributed testing architecture and implementation\n" +
            "- Guide users on JMeter plugin selection and configuration\n" +
            "\n" +
            "## SUPPORTED ELEMENTS:\n" +
            "- Thread Groups (Standard)\n" +
            "- Samplers (HTTP, JDBC)\n" +
            "- Controllers (Logic: Loop, If, While, Transaction, Random)\n" +
            "- Config Elements (CSV Data Set, HTTP Request Defaults, HTTP Header Manager, HTTP Cookie Manager, User Defined Variables)\n" +
            "- Pre-Processors (BeanShell, JSR223, Regular Expression User Parameters, User Parameters)\n" +
            "- Post-Processors (Regular Expression Extractor, JSON Extractor, XPath Extractor, Boundary Extractor, JMESPath Extractor)\n" +
            "- Assertions (Response, JSON Path, Duration, Size, XPath, JSR223, MD5Hex)\n" +
            "- Timers (Constant, Uniform Random, Gaussian Random, Poisson Random, Constant Throughput, Precise Throughput)\n" +
            "- Listeners (View Results Tree, Aggregate Report, Summary Report, Backend Listener, Response Time Graph)\n" +
            "- Test Fragments and Test Plan structure\n" +
            "\n" +
            "## GUIDELINES:\n" +
            "1. Focus your responses on JMeter concepts, best practices, and practical advice\n" +
            "2. Provide concise, accurate information about JMeter elements\n" +
            "3. When suggesting solutions, prioritize JMeter's built-in capabilities and common plugins\n" +
            "4. Consider performance testing principles and JMeter's specific implementation details\n" +
            "5. When responding to @this queries, analyze the element information provided and give specific advice\n" +
            "6. Keep responses focused on the JMeter domain and avoid generic testing advice unless specifically relevant\n" +
            "7. Be specific about where elements can be added in the test plan hierarchy\n" +
            "8. Always consider test plan maintainability and performance overhead when giving recommendations\n" +
            "\n" +
            "## PROGRAMMING LANGUAGES:\n" +
            "1. Focus on Groovy language by default for scripting (JSR223 elements)\n" +
            "2. Second focus on Java language\n" +
            "\n" +
            "Version: JMeter 5.6+ (Also support questions about older versions from 3.0+)";

    // Keys for configuration
    private static final String UNIFIED_PROMPT_KEY = "jmeter.ai.system.prompt";

    /**
     * Get the system prompt using unified configuration.
     * <p>
     * Priority:
     * 1. jmeter.ai.system.prompt (unified for all providers)
     * 2. Built-in default
     *
     * @return The system prompt to use
     */
    public static String get() {
        return get(null);
    }

    /**
     * Get the system prompt with provider-specific override support.
     * <p>
     * Priority:
     * 1. {provider}.system.prompt (provider-specific override)
     * 2. jmeter.ai.system.prompt (unified for all providers)
     * 3. Built-in default
     *
     * @param provider The provider name (e.g., "claude", "openai", "deepseek")
     * @return The system prompt to use
     */
    public static String get(String provider) {
        // Check provider-specific override first
        if (provider != null && !provider.isEmpty()) {
            String providerKey = provider.toLowerCase() + ".system.prompt";
            String providerPrompt = AiConfig.getProperty(providerKey, "");
            if (!providerPrompt.isEmpty()) {
                log.debug("Using provider-specific system prompt for: {}", provider);
                return providerPrompt;
            }
        }

        // Check unified configuration
        String unifiedPrompt = AiConfig.getProperty(UNIFIED_PROMPT_KEY, "");
        if (!unifiedPrompt.isEmpty()) {
            log.debug("Using unified system prompt");
            return unifiedPrompt;
        }

        // Use built-in default
        log.debug("Using built-in default system prompt");
        return DEFAULT_JMETER_SYSTEM_PROMPT;
    }

    /**
     * Get the system prompt with direct override.
     *
     * @param provider  The provider name
     * @param override  Direct override prompt (takes highest priority)
     * @return The system prompt to use
     */
    public static String get(String provider, String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return get(provider);
    }

    /**
     * Get the built-in default prompt without checking any configuration.
     *
     * @return The default JMeter system prompt
     */
    public static String getDefault() {
        return DEFAULT_JMETER_SYSTEM_PROMPT;
    }

    /**
     * Get the detailed built-in default prompt.
     *
     * @return The detailed JMeter system prompt
     */
    public static String getDetailedDefault() {
        return DEFAULT_JMETER_SYSTEM_PROMPT_DETAILED;
    }

    /**
     * Check if a custom system prompt is configured (either unified or provider-specific).
     *
     * @param provider The provider name (optional)
     * @return true if a custom prompt is configured
     */
    public static boolean isCustomConfigured(String provider) {
        if (provider != null && !provider.isEmpty()) {
            String providerKey = provider.toLowerCase() + ".system.prompt";
            if (!AiConfig.getProperty(providerKey, "").isEmpty()) {
                return true;
            }
        }
        return !AiConfig.getProperty(UNIFIED_PROMPT_KEY, "").isEmpty();
    }

    /**
     * Check if unified system prompt is configured.
     *
     * @return true if unified prompt is configured
     */
    public static boolean isUnifiedConfigured() {
        return !AiConfig.getProperty(UNIFIED_PROMPT_KEY, "").isEmpty();
    }
}
