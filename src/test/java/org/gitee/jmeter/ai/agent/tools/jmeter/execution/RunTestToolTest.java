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

    @Test
    void frozenFileExistsPropertyIsRejectedAtValidation() {
        // resultcollector.action_if_file_exists 被 JMeter 类加载期冻结（AbstractAction
        // 静态初始化）：运行时注入对本回合无效，且值永久留在进程属性里——后续运行
        // 的文件人闸预检（实时读属性）将与 JMeter 冻结判定不同源，预检放行后弹窗
        // 照旧卡死 EDT。必须在参数校验层拒掉，且早于 GUI guard（headless 可测）
        ToolResult result = new RunTestTool().execute(Map.of(
                "action", "start",
                "properties", Map.of("resultcollector.action_if_file_exists", "APPEND")));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Validation failed"));
        assertTrue(result.getError().contains("resultcollector.action_if_file_exists"));
        assertTrue(result.getError().contains("freezes it at startup"));
    }

    @Test
    void descriptionDocumentsExistingFileGate() {
        // Agent 规划阶段的静态知识：存在「监听器写已存在输出文件」的计划会被拒跑，
        // 避免到运行时才撞上
        String description = new RunTestTool().getDescription();
        assertTrue(description.contains("existing output file"));
        assertTrue(description.contains("resultcollector.action_if_file_exists"));
    }

    // --- smoke mode parameter contracts ---

    @Test
    void unknownModeIsRejected() {
        ToolResult result = new RunTestTool().execute(Map.of(
                "action", "start", "mode", "turbo"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("'mode'"));
    }

    @Test
    void smokeModeRequiresStartAction() {
        ToolResult result = new RunTestTool().execute(Map.of(
                "action", "stop", "mode", "smoke"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("smoke"));
    }

    @Test
    void smokeModeWithoutGuiReturnsGuiError() {
        // headless unit test: validation passes, the GUI guard fires next
        ToolResult result = new RunTestTool().execute(Map.of(
                "action", "start", "mode", "smoke"));

        assertFalse(result.isSuccess());
        assertEquals("JMeter GUI is not available", result.getError());
    }

    @Test
    void schemaDocumentsSmokeMode() {
        String schema = new RunTestTool().getParameterSchema();
        assertTrue(schema.contains("\"smoke\""));
        assertTrue(schema.contains("1 thread x 1 iteration"));
    }

    @Test
    void timeoutGuardIsModeAware() {
        RunTestTool tool = new RunTestTool();
        // smoke = 完成等待常量（180s）+ 30s 派发余量；其余动作回到 15s 守卫——
        // 不再是为冒烟抬到 120s 的一刀切
        assertEquals(210_000L, tool.getTimeoutMs(Map.of("action", "start", "mode", "smoke")));
        assertEquals(15_000L, tool.getTimeoutMs(Map.of("action", "start")));
        assertEquals(15_000L, tool.getTimeoutMs(Map.of("action", "stop")));
    }

    // --- smoke verdict ---

    @Test
    void nullPropertiesReturnMutableMapForSmokeForcing() {
        // 冒烟模式向 normalizeRunProperties 的返回值无条件写入 testplan_validation
        // 强制键——调用方省略 properties（规范冒烟调法）时同样必须拿到可变 Map，
        // 否则每次冒烟调用在启动前就抛 UnsupportedOperationException
        Map<String, String> runProperties = RunTestTool.normalizeRunProperties(null);
        runProperties.put("testplan_validation.nb_threads_per_thread_group", "1");
        assertEquals("1", runProperties.get("testplan_validation.nb_threads_per_thread_group"));
    }

    @Test
    void zeroSampleSmokeRunIsNotAPass() {
        // 零样本 = 什么都没验证（禁用采样器/条件为假/空线程组），不得记 PASS
        // 解锁 goal 完成；只有「发过流量且零 error」才是硬标准
        assertFalse(RunTestTool.isSmokePass(
                new AgentResultCollector.SummaryStats(0, 0, 0, 0, 0, 0, 0, 0, false, null)));
        assertTrue(RunTestTool.isSmokePass(
                new AgentResultCollector.SummaryStats(3, 0, 0, 0, 0, 0, 0, 0, false, null)));
        assertFalse(RunTestTool.isSmokePass(
                new AgentResultCollector.SummaryStats(3, 2, 0, 0, 0, 0, 0, 0, false, null)));
    }
}
