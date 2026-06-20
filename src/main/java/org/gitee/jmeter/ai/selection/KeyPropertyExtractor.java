package org.gitee.jmeter.ai.selection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.apache.jmeter.testelement.TestElement;

/**
 * 关键属性提取注册表：elementType → 摘要提取函数。
 *
 * <p>用于在 {@code SelectionContextBar} 第 3 行展示每个组件类型最有信息量的字段
 * （HTTP 的 method+url、ThreadGroup 的 threads+ramp、JDBC 的 query 等）。
 *
 * <p>未注册类型返回空字符串，UI 自动隐藏第 3 行。
 */
public final class KeyPropertyExtractor {

    private static final Map<String, Function<TestElement, String>> REGISTRY = new LinkedHashMap<>();

    static {
        register("HTTPSamplerProxy", te ->
                prop(te, "HTTPSampler.method")
                        + (isNonEmpty(prop(te, "HTTPSampler.domain"))
                                ? " " + prop(te, "HTTPSampler.domain")
                                        + (isNonEmpty(prop(te, "HTTPSampler.port"))
                                                ? ":" + prop(te, "HTTPSampler.port") : "")
                                : "")
                        + (isNonEmpty(prop(te, "HTTPSampler.path")) ? prop(te, "HTTPSampler.path") : ""));

        register("ThreadGroup", te ->
                "threads=" + prop(te, "ThreadGroup.num_threads")
                        + " ramp=" + prop(te, "ThreadGroup.ramp_time")
                        + " loops=" + prop(te, "LoopController.loops"));

        register("SetupThreadGroup", te ->
                "threads=" + prop(te, "ThreadGroup.num_threads")
                        + " ramp=" + prop(te, "ThreadGroup.ramp_time"));

        register("PostThreadGroup", te ->
                "threads=" + prop(te, "ThreadGroup.num_threads")
                        + " ramp=" + prop(te, "ThreadGroup.ramp_time"));

        register("JDBCSampler", te -> "query=" + truncate(prop(te, "query"), 50));

        register("DebugSampler", te -> "");

        register("LoopController", te ->
                "loops=" + prop(te, "LoopController.loops")
                        + (isNonEmpty(prop(te, "LoopController.continue_forever"))
                                ? " forever=" + prop(te, "LoopController.continue_forever") : ""));

        register("WhileController", te ->
                "condition=" + truncate(prop(te, "WhileController.condition"), 40));

        register("IfController", te ->
                "condition=" + truncate(prop(te, "IfController.condition"), 40));

        register("TransactionController", te ->
                "includeTimers=" + prop(te, "TransactionController.includeTimers"));

        register("JSR223Sampler", te ->
                "lang=" + prop(te, "scriptLanguage")
                        + (isNonEmpty(prop(te, "script"))
                                ? " script=" + truncate(prop(te, "script"), 40) : ""));

        register("ResponseAssertion", te ->
                "field=" + prop(te, "Assertion.test_field")
                        + " type=" + prop(te, "Assertion.test_type"));

        register("JSONPathAssertion", te ->
                "path=" + truncate(prop(te, "JSON_PATH"), 40));

        register("HeaderManager", te -> "");

        register("CSVDataSet", te ->
                "file=" + truncate(prop(te, "filename"), 40)
                        + (isNonEmpty(prop(te, "delimiter"))
                                ? " delim=" + ("\\t".equals(prop(te, "delimiter")) ? "TAB" : prop(te, "delimiter")) : ""));
    }

    private KeyPropertyExtractor() {
        // 工具类
    }

    public static void register(String elementType, Function<TestElement, String> fn) {
        REGISTRY.put(elementType, fn);
    }

    /**
     * 提取关键属性摘要。
     *
     * @param element 当前 TestElement；null 返回空串
     * @return 摘要字符串；未注册类型或摘要为空返回空串
     */
    public static String extract(TestElement element) {
        if (element == null) return "";
        String type = element.getClass().getSimpleName();
        Function<TestElement, String> fn = REGISTRY.get(type);
        if (fn == null) return "";
        String result = fn.apply(element);
        return result == null ? "" : result.trim();
    }

    private static String prop(TestElement te, String key) {
        String v = te.getPropertyAsString(key);
        return v == null ? "" : v;
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
