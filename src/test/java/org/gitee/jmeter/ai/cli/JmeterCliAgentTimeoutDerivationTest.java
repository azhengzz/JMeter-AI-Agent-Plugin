package org.gitee.jmeter.ai.cli;

import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CLI {@code /agent} 等待上限的派生契约:显式 {@code --timeout} 最优先;否则按目标
 * 端口文件发布的 {@code agentTimeoutMs} + 10s 派生(余量覆盖服务器超时分支 5s 取消
 * 收尾,结构化 504 先于本端 HTTP 超时);目标未发布(旧版本)回退内置缺省。
 */
class JmeterCliAgentTimeoutDerivationTest {

    private static InstanceInfo published(long ms) {
        InstanceInfo info = new InstanceInfo();
        info.setAgentTimeoutMs(ms);
        return info;
    }

    @Test
    void explicitTimeoutAlwaysWins() {
        Map<String, String> opts = new HashMap<>();
        opts.put("timeout", "120000");
        assertEquals(120_000L, JmeterCli.effectiveAgentTimeoutMs(opts, published(900_000L)),
                "explicit --timeout must override any derived value");
    }

    @Test
    void publishedValueDerivedWithGrace() {
        Map<String, String> opts = new HashMap<>();
        assertEquals(910_000L, JmeterCli.effectiveAgentTimeoutMs(opts, published(900_000L)),
                "published server bound + 10s grace (covers the server's 5s cancel wait before 504)");
    }

    @Test
    void unpublishedServerFallsBackToBundledDefault() {
        Map<String, String> opts = new HashMap<>();
        assertEquals(310_000L, JmeterCli.effectiveAgentTimeoutMs(opts, published(0L)),
                "old server without the field falls back to the bundled constant");
    }

    @Test
    void defaultServerPublishes300s_derivesExactlyTodayDefault() {
        // 新服务器默认 300s → 派生 310s,与今日 DEFAULT 逐字节等价(零行为漂移)
        Map<String, String> opts = new HashMap<>();
        assertEquals(310_000L, JmeterCli.effectiveAgentTimeoutMs(opts, published(300_000L)));
    }
}
