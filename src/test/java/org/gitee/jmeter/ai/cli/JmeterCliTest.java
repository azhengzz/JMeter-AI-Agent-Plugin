package org.gitee.jmeter.ai.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void friendlyDistinguishesReadTimeoutFromUnreachableServer() {
        // 读超时(--timeout 到点回合未完):loopback 上服务器可达——不得再报 "cannot reach";
        // 且回显实际生效的超时值(显式传过 --timeout 的用户看到的必须是自己的数)
        String msg = JmeterCli.friendly(
                new java.net.http.HttpTimeoutException("request timed out"), 15_000L);
        assertTrue(msg.contains("after 15000ms"), "应回显实际生效超时: " + msg);
        assertTrue(msg.contains("--timeout"), "应提示调大 --timeout: " + msg);
        assertFalse(msg.contains("cannot reach"), msg);

        // parse 阶段即抛(无从解析参数)时回退缺省常量,不 NPE
        String fallback = JmeterCli.friendly(new java.net.http.HttpTimeoutException("x"), null);
        assertTrue(fallback.contains("after " + JmeterCli.DEFAULT_REQUEST_TIMEOUT_MS + "ms"), fallback);
    }

    @Test
    void friendlyKeepsUnreachableHintForConnectRefused() {
        // 连接拒绝(端口无监听):保留 stale port file / jmeter-cli list 诊断提示
        String msg = JmeterCli.friendly(new java.net.ConnectException("Connection refused"), null);
        assertTrue(msg.contains("cannot reach"), msg);
        assertTrue(msg.contains("jmeter-cli list"), msg);
    }

    // ---------- 新动词参数构建(cli-tool-parity) ----------

    @Test
    void buildBatchMoveParametersFullShape() {
        Map<String, Object> params = JmeterCli.buildBatchMoveParameters(Map.of(
                "elementIds", "5,6",
                "targetParentId", "2",
                "position", "first"));

        assertEquals(java.util.List.of(5, 6), params.get("elementIds"));
        assertEquals(2, params.get("targetParentId"));
        assertEquals("first", params.get("position"));
    }

    @Test
    void buildBatchMoveParametersMissingTargetParentThrowsUsageError() {
        // 必填缺失 → require 抛 IllegalArgumentException(main 捕获后退出码 2,不发 IPC 请求)
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.buildBatchMoveParameters(Map.of("elementIds", "5")));
        assertTrue(ex.getMessage().contains("targetParentId"), ex.getMessage());
    }

    @Test
    void buildBatchToggleParametersOmitsOptionalAction() {
        Map<String, Object> params = JmeterCli.buildBatchToggleParameters(Map.of("elementIds", "5,6"));

        assertEquals(java.util.List.of(5, 6), params.get("elementIds"));
        assertFalse(params.containsKey("action"), "可选缺省不投递,工具默认 toggle 生效");
    }

    @Test
    void buildBatchDeleteParametersParsesCsv() {
        Map<String, Object> params = JmeterCli.buildBatchDeleteParameters(Map.of("elementIds", "10, 20, 30"));

        assertEquals(java.util.List.of(10, 20, 30), params.get("elementIds"));
        assertEquals(1, params.size(), "elementIds 是唯一参数");
    }

    @Test
    void buildCopyParametersOmitsOptionalTargetAndPosition() {
        Map<String, Object> params = JmeterCli.buildCopyParameters(Map.of("elementId", "5"));

        assertEquals(5, params.get("elementId"));
        assertFalse(params.containsKey("targetParentId"), "缺省不投递,工具落为原位复制");
        assertFalse(params.containsKey("position"));
    }

    @Test
    void buildQueryParametersPassesFiltersThrough() {
        Map<String, Object> params = JmeterCli.buildQueryParameters(Map.of(
                "elementType", "httpsampler",
                "propertyName", "HTTPSampler.domain",
                "propertyValue", "example.com",
                "matchMode", "exact",
                "limit", "10"));

        assertEquals("httpsampler", params.get("elementType"));
        assertEquals("HTTPSampler.domain", params.get("propertyName"));
        assertEquals("example.com", params.get("propertyValue"));
        assertEquals("exact", params.get("matchMode"));
        assertEquals(10, params.get("limit"));
        assertFalse(params.containsKey("parentId"), "未传的可选键不投递");
    }

    @Test
    void buildParseParametersMapsFileFlagToFilePath() {
        Map<String, Object> params = JmeterCli.buildParseParameters(Map.of(
                "file", "D:/plans/plan.jmx",
                "elementType", "httpsampler"));

        assertEquals("D:/plans/plan.jmx", params.get("filePath"));
        assertEquals("httpsampler", params.get("elementType"));
    }

    @Test
    void buildRunParametersIncludesMode() throws Exception {
        Map<String, Object> params = JmeterCli.buildRunParameters(Map.of("mode", "smoke"));

        assertEquals("start", params.get("action"));
        assertEquals("smoke", params.get("mode"));
    }

    @Test
    void buildRunParametersOmitsModeByDefault() throws Exception {
        assertFalse(JmeterCli.buildRunParameters(Map.of()).containsKey("mode"));
    }

    @Test
    void runningStateMarkerValueIsPinned() {
        // run --wait 判定运行态的字面标记,来源 get_test_status 输出格式;
        // 改此值必须同步核对工具输出(见 JmeterCli.RUNNING_STATE_MARKER 注释)
        assertEquals("**State**: Running", JmeterCli.RUNNING_STATE_MARKER);
    }

    @Test
    void helpPrintsForAllNewCommands() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        JmeterCli.printGlobalHelp(new java.io.PrintStream(out, true));
        assertTrue(out.toString().contains("Commands:"), "全局帮助应可打印");

        // 每个新动词都有 SPECS 条目且 -h 级帮助打印不抛异常
        for (String cmd : new String[]{"batch-delete", "batch-move", "batch-toggle", "validate",
                "copy", "tree", "script-info", "query", "parse", "log"}) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            assertTrue(JmeterCli.printCommandHelp(new java.io.PrintStream(buf, true), cmd),
                    "缺少 SPECS 帮助条目: " + cmd);
            assertTrue(buf.toString().contains("Usage: jmeter-cli " + cmd), cmd);
        }
    }

    // ---------- 修复钉定(2026-10-30 对抗修复批次) ----------

    @Test
    void strictBoolRejectsGarbageWithFlagName() {
        // RO-01/SL-F3:yes/1/垃圾值曾静默当 false(--merge yes 静默替换计划、--exactMatch yes 变模糊)
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.parseStrictBool("merge", "yes", false));
        assertTrue(ex.getMessage().contains("--merge"), ex.getMessage());
        assertTrue(JmeterCli.parseStrictBool("exactMatch", "True", false), "大小写不敏感接受");
        assertFalse(JmeterCli.parseStrictBool("exactMatch", "FALSE", false));
        // 存在型旗标:无值= true(B-cli-2:--wait 唯一文档形态是无值)
        assertTrue(JmeterCli.parseStrictBool("wait", "", true));
        assertFalse(JmeterCli.parseStrictBool("wait", "false", true), "--wait=false 显式关闭");
    }

    @Test
    void runIgnoreTimersGarbageIsUsageError() {
        // B-cli-3:ignoreTimers 内联解析点不走 putBoolIfPresent,必须同样严格
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.buildRunParameters(Map.of("ignoreTimers", "yes")));
        assertTrue(ex.getMessage().contains("ignoreTimers"), ex.getMessage());
    }

    @Test
    void runModeEnumIsCaseSensitiveClientChecked() {
        assertEquals("smoke", JmeterCli.buildRunParameters(Map.of("mode", "smoke")).get("mode"));
        for (String bad : new String[]{"bogus", "SMOKE", ""}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> JmeterCli.buildRunParameters(Map.of("mode", bad)));
            assertTrue(ex.getMessage().contains("--mode"), bad + ": " + ex.getMessage());
        }
    }

    @Test
    void badJsonFlagIsUsageErrorWithFlagName() {
        // RUN-F4/PAR-001:坏 JSON 曾落 catch(Exception)=exit 1 并泄漏 Jackson Source/行号
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.buildRunParameters(Map.of("properties", "abc")));
        assertTrue(ex.getMessage().contains("--properties"), ex.getMessage());
        assertFalse(ex.getMessage().contains("Source"), "不得泄漏 Jackson 内部定位信息");
    }

    @Test
    void badCsvElementIdCarriesFlagName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.buildBatchDeleteParameters(Map.of("elementIds", "5,x,7")));
        assertTrue(ex.getMessage().contains("--elementIds"), ex.getMessage());
    }

    @Test
    void strictIntErrorCarriesFlagName() {
        assertEquals(5, JmeterCli.parseStrictInt("maxDepth", "5"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.parseStrictInt("maxDepth", "abc"));
        assertTrue(ex.getMessage().contains("--maxDepth"), ex.getMessage());
    }

    @Test
    void friendlyClassifiesNestedTimeoutAsTimeout() {
        // PAR-002:连接期超时的链根因是 ConnectException,先展开根因曾误报 "cannot reach"
        java.net.ConnectException connect = new java.net.ConnectException("connect timed out");
        java.net.http.HttpTimeoutException timeout =
                new java.net.http.HttpTimeoutException("request timed out");
        connect.initCause(timeout);
        String msg = JmeterCli.friendly(new RuntimeException("send failed", connect), 5L);
        assertTrue(msg.contains("after 5ms"), msg);
        assertFalse(msg.contains("cannot reach"), msg);
    }

    @Test
    void parseLimitRequiresElementType() {
        // B-doc-2:树模式静默忽略 limit/offset 的新路径必须在客户端拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.buildParseParameters(Map.of("file", "a.jmx", "limit", "20")));
        assertTrue(ex.getMessage().contains("--elementType"), ex.getMessage());
        java.util.Map<String, Object> ok = JmeterCli.buildParseParameters(
                Map.of("file", "a.jmx", "elementType", "httpsampler", "limit", "50", "offset", "10"));
        assertEquals(50, ok.get("limit"));
        assertEquals(10, ok.get("offset"));
    }

    @Test
    void treeAndLogBuildersHaveShape() {
        // D2 落实:tree/log 参数组装提为构建器并钉形状
        assertEquals(java.util.Map.of(), JmeterCli.buildTreeParameters(Map.of()));
        assertEquals(java.util.Map.of("includeProperties", false, "maxDepth", 2),
                JmeterCli.buildTreeParameters(Map.of("includeProperties", "false", "maxDepth", "2")));
        assertEquals(java.util.Map.of("startLine", 5),
                JmeterCli.buildLogParameters(Map.of("startLine", "5")));
    }

    @Test
    void allowedFlagsCoverAllConsumedFlags() {
        // F3 守卫:关键旗标硬编码映射(独立于 usage 文本,防 usage 手误→误拒合法调用)
        java.util.Map<String, String[]> keyFlags = java.util.Map.ofEntries(
                java.util.Map.entry("agent", new String[]{"message", "session"}),
                java.util.Map.entry("tool", new String[]{"name", "params"}),
                java.util.Map.entry("run", new String[]{"mode", "wait", "ignoreTimers", "properties"}),
                java.util.Map.entry("parse", new String[]{"file", "elementType", "limit", "offset", "maxDepth"}),
                java.util.Map.entry("query", new String[]{"propertyName", "propertyValue", "matchMode", "limit"}),
                java.util.Map.entry("create", new String[]{"elementType", "elementName", "parentId", "properties"}),
                java.util.Map.entry("batch-move", new String[]{"elementIds", "targetParentId", "position"}),
                java.util.Map.entry("find", new String[]{"searchBy", "query", "exactMatch", "parentId", "limit"}));
        keyFlags.forEach((cmd, flags) -> {
            java.util.Set<String> allowed = JmeterCli.allowedFlagsFor(cmd);
            assertNotNull(allowed, cmd);
            for (String f : flags) {
                assertTrue(allowed.contains(f), cmd + " 缺少旗标 --" + f + " (usage 行漂移?)");
            }
        });
        assertNull(JmeterCli.allowedFlagsFor("no-such-command"), "未知命令返回 null 交由 dispatch 处理");
    }

    @Test
    void waitRequestedSemantics() {
        // F1 回归钉定:缺席 = 不等待(裸 run 须保持 prompt-return);裸旗标 = true;=false 显式关闭
        assertFalse(JmeterCli.waitRequested(Map.of()));
        assertTrue(JmeterCli.waitRequested(Map.of("wait", "")));
        assertTrue(JmeterCli.waitRequested(Map.of("wait", "true")));
        assertFalse(JmeterCli.waitRequested(Map.of("wait", "false")));
        assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.waitRequested(Map.of("wait", "yes")));
    }

    @Test
    void timeoutOfValidatesPositiveAndNamed() {
        // G-02 钉定:非数字/非正数带旗标名报错(曾被 list/health 静默放过)
        assertEquals(5000L, JmeterCli.timeoutOf(Map.of("timeout", "5000")));
        assertEquals(JmeterCli.DEFAULT_REQUEST_TIMEOUT_MS, JmeterCli.timeoutOf(Map.of()));
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.timeoutOf(Map.of("timeout", "abc")));
        assertTrue(bad.getMessage().contains("--timeout"), bad.getMessage());
        IllegalArgumentException neg = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.timeoutOf(Map.of("timeout", "-1")));
        assertTrue(neg.getMessage().contains("positive"), neg.getMessage());
    }

    @Test
    void globalFlagValuesValidatedAcrossCommands() {
        // G-02:list --pid abc 曾静默全量列出;校验器对任何命令统一生效
        IllegalArgumentException badPid = assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.validateGlobalFlagValues(Map.of("pid", "abc")));
        assertTrue(badPid.getMessage().contains("--pid"), badPid.getMessage());
        JmeterCli.validateGlobalFlagValues(Map.of("pid", "31908")); // 合法数字通过
        assertThrows(IllegalArgumentException.class,
                () -> JmeterCli.validateGlobalFlagValues(Map.of("timeout", "abc")));
    }

    @Test
    void runHelpDocumentsModeAndParseHelpDocumentsQueryMode() {
        // P2-4③:帮助文案深检(run --mode / parse 模式切换与 limit 守卫)
        java.io.ByteArrayOutputStream runHelp = new java.io.ByteArrayOutputStream();
        JmeterCli.printCommandHelp(new java.io.PrintStream(runHelp, true), "run");
        assertTrue(runHelp.toString().contains("--mode"), "run 帮助应含 --mode");
        assertTrue(runHelp.toString().contains("sends real requests"), "run 帮助应告警 smoke 真实发请求");

        java.io.ByteArrayOutputStream parseHelp = new java.io.ByteArrayOutputStream();
        JmeterCli.printCommandHelp(new java.io.PrintStream(parseHelp, true), "parse");
        assertTrue(parseHelp.toString().contains("--limit"), "parse 帮助应含 --limit");
        assertTrue(parseHelp.toString().contains("require --elementType"), "parse 帮助应说明 --limit 的同现约束");

        java.io.ByteArrayOutputStream queryHelp = new java.io.ByteArrayOutputStream();
        JmeterCli.printCommandHelp(new java.io.PrintStream(queryHelp, true), "query");
        assertTrue(queryHelp.toString().contains("REQUIRED"), "query 帮助应说明 propertyName/propertyValue 至少其一");
    }
}
