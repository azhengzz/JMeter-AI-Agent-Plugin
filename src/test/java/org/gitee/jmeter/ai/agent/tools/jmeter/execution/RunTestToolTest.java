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

    // --- turn-deadline budget contracts (IPC /agent turns) ---

    @Test
    void smokeBudgetRefusal_belowFloor_refusesBeforeEngineStart() {
        // 剩余 30s < 60s 地板 → 拒绝;文案明示未启动引擎、不消耗冒烟预算、建议收尾
        org.gitee.jmeter.ai.agent.run.AgentRunContext ctx =
                new org.gitee.jmeter.ai.agent.run.AgentRunContext(
                        "s", "r", false, System.currentTimeMillis() + 30_000);
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(ctx);
        try {
            ToolResult refusal = RunTestTool.smokeBudgetRefusalOrNull();
            assertTrue(refusal != null && !refusal.isSuccess(), "below-floor budget must refuse");
            assertTrue(refusal.getError().contains("NOT started"));
            assertTrue(refusal.getError().contains("no smoke attempt was charged"));
            assertTrue(refusal.getError().contains("Wrap up the turn"));
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }
    }

    @Test
    void smokeBudgetRefusal_aboveFloorOrNullDeadline_passes() {
        // 预算充足(5min)或无截止期(本地回合/直构) → 放行(null)
        org.gitee.jmeter.ai.agent.run.AgentRunContext ctx =
                new org.gitee.jmeter.ai.agent.run.AgentRunContext(
                        "s", "r", false, System.currentTimeMillis() + 300_000);
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(ctx);
        try {
            org.junit.jupiter.api.Assertions.assertNull(RunTestTool.smokeBudgetRefusalOrNull(),
                    "ample budget must pass the precheck");
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }
        org.junit.jupiter.api.Assertions.assertNull(RunTestTool.smokeBudgetRefusalOrNull(),
                "no run context (direct-construction/local turn) must never clamp");
        org.junit.jupiter.api.Assertions.assertNull(RunTestTool.runDeadlineOrNull(),
                "null context guard");
    }

    @Test
    void budgetFloorBoundary_refusesBelow_passesJustAbove() {
        // 地板两侧的边界固化(spec:剩余低于 60s 下限拒绝):59s 拒、61s 放——
        // 过线形态(61s)引擎启动路径法定可耗 ~20s、轮询窗仍薄,属 design「已接受残余」,
        // 预检只管地板以下
        org.gitee.jmeter.ai.agent.run.AgentRunContext below =
                new org.gitee.jmeter.ai.agent.run.AgentRunContext(
                        "s", "r", false, System.currentTimeMillis() + 59_000);
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(below);
        try {
            ToolResult refusal = RunTestTool.smokeBudgetRefusalOrNull();
            assertTrue(refusal != null && !refusal.isSuccess(), "59s < 60s floor must refuse");
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }

        org.gitee.jmeter.ai.agent.run.AgentRunContext above =
                new org.gitee.jmeter.ai.agent.run.AgentRunContext(
                        "s", "r", false, System.currentTimeMillis() + 61_000);
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(above);
        try {
            org.junit.jupiter.api.Assertions.assertNull(RunTestTool.smokeBudgetRefusalOrNull(),
                    "61s just above the floor must pass (clamped wait handles the thin window)");
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }
    }

    @Test
    void normalModeWithDeadline_doesNotHitBudgetRefusal() {
        // spec 需求级边界:普通(非 smoke)模式不受截止期钳制——预算预检包在 if(smoke)
        // 内。headless 直构下 normal 路径撞 GUI guard("JMeter GUI is not available"),
        // 而非预算拒绝文案;若有人把预检挪出 if(smoke) 或提前到 GUI guard 之前,
        // 本用例的红即暴露 normal 被误钳制
        org.gitee.jmeter.ai.agent.run.AgentRunContext ctx =
                new org.gitee.jmeter.ai.agent.run.AgentRunContext(
                        "s", "r", false, System.currentTimeMillis() + 30_000);
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(ctx);
        try {
            ToolResult result = new RunTestTool().execute(Map.of(
                    "action", "start", "mode", "normal"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("JMeter GUI is not available"),
                    "normal mode must not hit the smoke budget refusal, got: " + result.getError());
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }
    }

    @Test
    void finishSmokeRun_clampsToDeadline_andReportsActualWaitWithBudgetWording() {
        // 截止期 1.5s 后:轮询钳制到 ~1.5s(而非 180s),文案报实际等待时长并以
        // 「回合预算耗尽」措辞区分于「冒烟完成超时」,明示证据已清
        org.gitee.jmeter.ai.agent.run.AgentRunContext ctx =
                new org.gitee.jmeter.ai.agent.run.AgentRunContext(
                        "s", "r", false, System.currentTimeMillis() + 1_500);
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(ctx);
        try {
            long t0 = System.currentTimeMillis();
            ToolResult result = new RunTestTool().finishSmokeRun(1);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed < 10_000, "clamped wait must return near the deadline, took " + elapsed + "ms");
            assertTrue(result.isSuccess(), "inconclusive smoke keeps the informational success shape");
            String content = String.valueOf(result.getContent());
            assertTrue(content.contains("Turn budget exhausted"),
                    "clamped timeout must use the budget-exhausted wording: " + content);
            assertTrue(content.contains("no verdict") || content.contains("No verdict"),
                    "wording must state there is no verdict: " + content);
            assertTrue(content.contains("evidence has been cleared"),
                    "wording must explain why PASS evidence was cleared: " + content);
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }
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
