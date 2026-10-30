package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JmxFileParser.ParsedJmxElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ParseJmxFileTool.findElementsByType 匹配策略钉定(SL-F2 修复):
 * 模型类按 XML tag 的 FQCN 精确匹配 + gui 类按 SimpleName 归一比较(合取)。
 * 三组必须成立:①schema gui FQCN vs 文件 gui SimpleName 能命中(修复本体);
 * ②跨包同名组件(native vs gitee-qa 的 LoopController)靠 tag 消歧;
 * ③监听器家族共享 ResultCollector 模型类,靠 guiSimpleName 消歧。
 */
class ParseJmxElementTypeGuardTest {

    private static ParsedJmxElement el(String tag, String testclass, String gui) {
        ParsedJmxElement e = new ParsedJmxElement();
        e.testClassFqcn = tag;
        e.elementType = testclass;
        e.guiClass = gui;
        return e;
    }

    private static List<ParsedJmxElement> match(ParsedJmxElement target,
                                                String modelFqcn, String guiFqcn) {
        List<ParsedJmxElement> out = new ArrayList<>();
        String simple = modelFqcn.substring(modelFqcn.lastIndexOf('.') + 1);
        ParseJmxFileTool.findElementsByType(target, modelFqcn, simple, guiFqcn, out);
        return out;
    }

    @Test
    void schemaFqcnGuiMatchesFileSimpleNameGui() {
        // SL-F2 本体:注册表 gui 是 FQCN、文件 gui 是 SimpleName,全等比较恒 false → 过滤恒空
        ParsedJmxElement tg = el("org.apache.jmeter.threads.ThreadGroup", "ThreadGroup", "ThreadGroupGui");
        assertEquals(1, match(tg,
                "org.apache.jmeter.threads.ThreadGroup",
                "org.apache.jmeter.threads.gui.ThreadGroupGui").size());
    }

    @Test
    void crossPackageSameSimpleNameDisambiguatedByTag() {
        // native 与 gitee-qa 各有 LoopController/LoopControlPanel(注册表实测撞名对)
        ParsedJmxElement nativeLc = el("org.apache.jmeter.control.LoopController",
                "LoopController", "LoopControlPanel");
        ParsedJmxElement giteeLc = el("com.gitee.qa.jmeter.control.LoopController",
                "LoopController", "LoopControlPanel");

        assertEquals(1, match(nativeLc,
                "org.apache.jmeter.control.LoopController",
                "org.apache.jmeter.control.gui.LoopControlPanel").size());
        assertEquals(0, match(giteeLc,
                "org.apache.jmeter.control.LoopController",
                "org.apache.jmeter.control.gui.LoopControlPanel").size(),
                "gitee-qa LoopController 不得被 native 注册条目命中");
    }

    @Test
    void listenerFamilyDisambiguatedByGuiSimpleName() {
        // 全监听器家族共享 ResultCollector 模型类,gui 是唯一区分源
        ParsedJmxElement vrt = el("org.apache.jmeter.reporters.ResultCollector",
                "ResultCollector", "ViewResultsFullVisualizer");

        assertEquals(1, match(vrt,
                "org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.gui.ViewResultsFullVisualizer").size());
        assertEquals(0, match(vrt,
                "org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.gui.StatGraphVisualizer").size(),
                "aggregatereport 条目不得命中 ViewResultsTree 元素");
    }
}
