package org.qainsights.jmeter.ai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized System Prompt management for all AI providers.
 * <p>
 * Configuration priority (highest to lowest):
 * 1. Unified: jmeter.ai.system.prompt (applies to all providers)
 * 2. Built-in default (JMeter expert assistant)
 * <p>
 * Usage:
 * <pre>
 * String prompt = SystemPrompt.get();                          // Use unified config
 * String prompt = SystemPrompt.get("custom prompt");           // Direct override
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
     * @param override  Direct override prompt (takes highest priority)
     * @return The system prompt to use
     */
    public static String get(String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return get();
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
     * Check if unified system prompt is configured.
     *
     * @return true if unified prompt is configured
     */
    public static boolean isUnifiedConfigured() {
        return !AiConfig.getProperty(UNIFIED_PROMPT_KEY, "").isEmpty();
    }
}
