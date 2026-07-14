package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

public class AiConfig {

    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
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
        return Boolean.parseBoolean(JMeterUtils.getPropDefault("jmeter.ai.ipc.enabled", "false"));
    }

    /**
     * IPC server 监听端口,0 = 自动分配(推荐)。
     */
    public static int getIpcPort() {
        return Integer.parseInt(JMeterUtils.getPropDefault("jmeter.ai.ipc.port", "0"));
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
        return Long.parseLong(JMeterUtils.getPropDefault("jmeter.ai.ipc.agent.timeout.ms", "120000"));
    }
}
