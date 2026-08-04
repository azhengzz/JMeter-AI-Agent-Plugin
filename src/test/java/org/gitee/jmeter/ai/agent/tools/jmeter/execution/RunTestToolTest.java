package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTestToolTest {

    @Test
    void parameterSchemaDefinesStartupProperties() {
        String schema = new RunTestTool().getParameterSchema();

        assertTrue(schema.contains("\"properties\""));
        assertTrue(schema.contains("\"additionalProperties\""));
        assertTrue(schema.contains("\"number\""));
        assertTrue(schema.contains("\"boolean\""));
    }

    @Test
    void normalizeRunPropertiesConvertsScalarValuesToStrings() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("p_env", "test");
        input.put("p_concurrent", 100);
        input.put("p_ratio", 0.75);
        input.put("p_enabled", true);

        Map<String, String> result = RunTestTool.normalizeRunProperties(input);

        assertEquals(Map.of(
                "p_env", "test",
                "p_concurrent", "100",
                "p_ratio", "0.75",
                "p_enabled", "true"), result);
    }

    @Test
    void invalidRunPropertiesAreReturnedAsToolError() {
        ToolResult result = new RunTestTool().execute(Map.of(
                "action", "start",
                "properties", Map.of("p_hosts", List.of("host-1"))));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Validation failed"));
        assertTrue(result.getError().contains("p_hosts"));
    }
}
