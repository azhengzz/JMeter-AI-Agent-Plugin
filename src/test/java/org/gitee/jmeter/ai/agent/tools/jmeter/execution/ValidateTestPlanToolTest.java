package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two pure analyzers of {@link ValidateTestPlanTool} (structure /
 * logic). The analyzers operate on ElementView snapshots so no GUI is
 * needed; the tool's tree walk follows the sibling read-tool convention (caller thread,
 * no EDT marshaling).
 */
class ValidateTestPlanToolTest {

    private static ValidateTestPlanTool.ElementView view(String path, String className,
                                                         boolean enabled, Map<String, Object> props) {
        return new ValidateTestPlanTool.ElementView(path, className, className,
                "org.example.Gui", "widget", enabled, props);
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    // --- 1. structure ---

    @Test
    void structureFailsWithoutEnabledThreadGroup() {
        List<String> errors = ValidateTestPlanTool.analyzeStructure(List.of(
                view("HTTP Request", "HTTPSamplerProxy", true, Map.of())));
        assertTrue(errors.stream().anyMatch(e -> e.contains("No enabled thread group")));
    }

    @Test
    void structurePassesWithEnabledThreadGroup() {
        List<String> errors = ValidateTestPlanTool.analyzeStructure(List.of(
                view("TG", "ThreadGroup", true, Map.of())));
        assertTrue(errors.isEmpty());
    }

    @Test
    void undefinedVarRefsAndBlankSamplerDomainAreNotStaticErrors() {
        // Deliberate non-checks: ${var} definition sites (JSR223 scripts,
        // post-processors) and HTTP Request Defaults merges are not statically
        // enumerable — flagging them produced false failures that stalled the goal
        // loop. The smoke run is the arbiter for both shapes.
        List<String> errors = ValidateTestPlanTool.analyzeStructure(List.of(
                view("TG", "ThreadGroup", true, Map.of()),
                view("TG > API", "HTTPSamplerProxy", true,
                        props("HTTPSampler.domain", "", "HTTPSampler.path", "/api/${userId}"))));
        assertTrue(errors.isEmpty(),
                "undefined variable references and blank sampler domain must not be static errors");
    }

    @Test
    void structureReportsMissingGuiClass() {
        List<String> errors = ValidateTestPlanTool.analyzeStructure(List.of(
                view("TG", "ThreadGroup", true, Map.of()),
                new ValidateTestPlanTool.ElementView("TG > X", "X", "X", "", "widget",
                        true, Map.of())));
        assertTrue(errors.stream().anyMatch(e -> e.contains("no GUI class")));
    }

    @Test
    void structureIgnoresDisabledElements() {
        List<String> errors = ValidateTestPlanTool.analyzeStructure(List.of(
                view("TG (disabled)", "ThreadGroup", false, Map.of())));
        // disabled thread group still means "no enabled TG" at plan level
        assertTrue(errors.stream().anyMatch(e -> e.contains("No enabled thread group")));
    }

    // --- 2. logic ---

    @Test
    void logicReportsEmptyWhileCondition() {
        List<String> errors = ValidateTestPlanTool.analyzeLogic(List.of(
                view("While", "WhileController", true, props("WhileController.condition", ""))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("While controller")));
    }

    @Test
    void logicAcceptsWhileConditionWithReference() {
        List<String> errors = ValidateTestPlanTool.analyzeLogic(List.of(
                view("While", "WhileController", true,
                        props("WhileController.condition", "${JMeterThread.last_sample_ok}"))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void structureAcceptsJsr223ScriptFromFile() {
        List<String> errors = ValidateTestPlanTool.analyzeStructure(List.of(
                view("TG", "ThreadGroup", true, Map.of()),
                view("TG > JSR", "JSR223Sampler", true,
                        props("filename", "scripts/check.groovy"))));
        assertFalse(errors.stream().anyMatch(e -> e.contains("empty script")));
    }

    @Test
    void logicReportsZeroThreadGroupLoops() {
        List<String> errors = ValidateTestPlanTool.analyzeLogic(List.of(
                view("TG", "ThreadGroup", true,
                        props("ThreadGroup.main_controller", Map.of("LoopController.loops", "0")))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("loop count is 0")));
    }

    @Test
    void logicReportsZeroLoops() {
        List<String> errors = ValidateTestPlanTool.analyzeLogic(List.of(
                view("Loop", "LoopController", true, props("LoopController.loops", "0"))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("0 loops")));
    }

    @Test
    void logicAcceptsForeverLoopWithZeroStoredLoops() {
        // Forever 勾选（continue_forever）时运行期忽略 loops 值无限执行，GUI 常态
        // 存储 loops=0——合法浸泡配置不得误报"0 loops"
        List<String> errors = ValidateTestPlanTool.analyzeLogic(List.of(
                view("Loop", "LoopController", true,
                        props("LoopController.loops", "0",
                                "LoopController.continue_forever", true))));
        assertTrue(errors.isEmpty());
    }

    @Test
    void logicAcceptsForeverThreadGroupWithZeroLoops() {
        // 嵌套形态 + 字符串序列化的布尔值同样豁免
        List<String> errors = ValidateTestPlanTool.analyzeLogic(List.of(
                view("TG", "ThreadGroup", true,
                        props("ThreadGroup.main_controller",
                                Map.of("LoopController.loops", "0",
                                        "LoopController.continue_forever", "true")))));
        assertTrue(errors.isEmpty());
    }
}
