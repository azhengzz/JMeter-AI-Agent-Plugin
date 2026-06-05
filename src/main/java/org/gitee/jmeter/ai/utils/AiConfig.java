package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

public class AiConfig {
    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
    }

    /**
     * Resolve a configuration value with three-tier fallback:
     * 1. {providerPrefix}.{key}  (per-provider override)
     * 2. jmeter.ai.{key}         (global default)
     * 3. hardcoded default
     */
    public static String getPropertyWithFallback(String providerPrefix, String key, String defaultValue) {
        // 1. Try per-provider override
        String providerKey = providerPrefix + "." + key;
        String value = JMeterUtils.getPropDefault(providerKey, null);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // 2. Try global default
        String globalKey = "jmeter.ai." + key;
        value = JMeterUtils.getPropDefault(globalKey, null);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // 3. Hardcoded default
        return defaultValue;
    }

    /**
     * Get the default model from global configuration.
     */
    public static String getDefaultModel() {
        return JMeterUtils.getPropDefault("jmeter.ai.default.model", "MiniMax-M2.7");
    }

    /**
     * Get the global default provider.
     */
    public static String getDefaultProvider() {
        return JMeterUtils.getPropDefault("jmeter.ai.default.provider", "openai");
    }
}
