package org.gitee.jmeter.ai.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
