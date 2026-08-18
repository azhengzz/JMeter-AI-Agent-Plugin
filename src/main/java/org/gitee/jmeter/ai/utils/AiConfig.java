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
     * IPC server 是否启用。默认开启(仅 loopback + token 鉴权)。
     */
    public static boolean isIpcEnabled() {
        return getBoolean("jmeter.ai.ipc.enabled", true);
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

    // ---- Multi-instance session / coordination ----

    /**
     * 每实例独立会话文件(按启动 instanceId)是否启用。默认 true;false 回退到全局 legacy session key。
     */
    public static boolean isSessionPerInstance() {
        return getBoolean("agent.session.per-instance", true);
    }

    /**
     * 关闭整合"深度提炼"(写 MEMORY.md)的有界超时(毫秒)。默认 120s;超时保留已整合部分并继续退出。
     */
    public static long getConsolidateOnExitTimeoutMs() {
        return getLong("agent.memory.consolidate-on-exit.timeout.ms", 120000L);
    }

    /**
     * 孤立会话文件回收的存活 TTL(毫秒):启动期扫描 {@code sessions/} 时,仅回收注册表确认已失活
     * 且最后修改时间超过本 TTL 的 {@code {pid}-{startedAtMs}.jsonl}。读自 {@code agent.session.reap.ttl.days},
     * 默认 7 天。
     */
    public static long getSessionReapTtlMs() {
        int days = getInt("agent.session.reap.ttl.days", 7);
        return days * 24L * 60L * 60L * 1000L;
    }
}
