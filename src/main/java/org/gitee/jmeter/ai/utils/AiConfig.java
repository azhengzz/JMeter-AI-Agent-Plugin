package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

public class AiConfig {

    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
    }

    /**
     * Typed accessors. Empty/unset values return {@code defaultValue}; non-numeric
     * values still throw {@code NumberFormatException} (same as the previous inline
     * parsing). Unlike {@code Integer.parseInt(getProperty(key, "x"))}, an empty
     * configured value returns the default instead of throwing.
     */
    public static int getInt(String key, int defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        return (v == null || v.isEmpty()) ? defaultValue : Integer.parseInt(v);
    }

    public static long getLong(String key, long defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        return (v == null || v.isEmpty()) ? defaultValue : Long.parseLong(v);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        return (v == null || v.isEmpty()) ? defaultValue : Boolean.parseBoolean(v);
    }

    public static double getDouble(String key, double defaultValue) {
        String v = JMeterUtils.getPropDefault(key, null);
        return (v == null || v.isEmpty()) ? defaultValue : Double.parseDouble(v);
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

    // ---- IPC server (CLI 驱动运行中 GUI) ----

    /**
     * IPC server 是否启用。默认关闭(安全优先,需显式开启)。
     */
    public static boolean isIpcEnabled() {
        return getBoolean("jmeter.ai.ipc.enabled", false);
    }

    /**
     * IPC server 监听端口,0 = 自动分配(推荐)。
     */
    public static int getIpcPort() {
        return getInt("jmeter.ai.ipc.port", 0);
    }

    /**
     * IPC server 绑定地址,仅接受 loopback。默认 127.0.0.1。
     */
    public static String getIpcBind() {
        return JMeterUtils.getPropDefault("jmeter.ai.ipc.bind", "127.0.0.1");
    }

    /**
     * IPC 鉴权 token,空 = 启动时随机生成并写入端口文件。
     */
    public static String getIpcToken() {
        return JMeterUtils.getPropDefault("jmeter.ai.ipc.token", "");
    }

    /**
     * Agent 路由同步等待超时(毫秒),默认 120s。
     */
    public static long getIpcAgentTimeoutMs() {
        return getLong("jmeter.ai.ipc.agent.timeout.ms", 120000);
    }

    // ---- Run result capture (GUI-initiated runs) ----

    /**
     * Whether to capture results of user-initiated (GUI Run button) test runs via a global
     * Start.class pre-action listener. Default true. Only gates Start-listener registration;
     * the Save.class strip-listener (anti-jmx-leak) and RunTestTool's own injection are
     * unaffected, so {@code run_test} always captures regardless of this toggle.
     */
    public static boolean isRunCaptureEnabled() {
        return getBoolean("agent.runcapture.enabled", true);
    }
}
