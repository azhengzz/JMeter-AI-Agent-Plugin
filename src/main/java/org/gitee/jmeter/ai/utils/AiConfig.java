package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

public class AiConfig {

    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
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
