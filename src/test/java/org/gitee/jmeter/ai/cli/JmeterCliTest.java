package org.gitee.jmeter.ai.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JmeterCliTest {

    @Test
    void buildRunParametersIncludesProperties() throws Exception {
        Map<String, Object> params = JmeterCli.buildRunParameters(Map.of(
                "ignoreTimers", "true",
                "properties", "{\"p_env\":\"test\",\"p_concurrent\":100,\"p_enabled\":true}"));

        assertEquals("start", params.get("action"));
        assertEquals(true, params.get("ignore_timers"));
        assertEquals(Map.of(
                "p_env", "test",
                "p_concurrent", 100,
                "p_enabled", true), params.get("properties"));
    }

    @Test
    void buildRunParametersKeepsPropertiesOptional() throws Exception {
        Map<String, Object> params = JmeterCli.buildRunParameters(Map.of());

        assertEquals("start", params.get("action"));
        assertFalse(params.containsKey("properties"));
    }
}
