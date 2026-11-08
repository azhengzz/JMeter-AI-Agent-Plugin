package org.gitee.jmeter.ai.agent.run;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式武装门控：hook 请求 + {@code jmeter.ai.streaming.enabled}（默认 true）
 * 联合判定。纯单测里 JMeterUtils.appProperties 为 null，getPropDefault 吞异常
 * 回默认值，故默认路径无需初始化；关闭路径须反射注入 appProperties。
 */
class AgentRunnerStreamingGateTest {

    @AfterEach
    void restoreJMeterProperties() throws Exception {
        Field field = JMeterUtils.class.getDeclaredField("appProperties");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void setStreamingEnabled(Boolean value) throws Exception {
        Properties props = new Properties();
        if (value != null) {
            props.setProperty("jmeter.ai.streaming.enabled", value.toString());
        }
        Field field = JMeterUtils.class.getDeclaredField("appProperties");
        field.setAccessible(true);
        field.set(null, props);
    }

    private static AgentHook hookWantingStreaming(boolean want) {
        return new AgentHook() {
            @Override
            public boolean wantsStreaming() {
                return want;
            }
        };
    }

    @Test
    void armedOnlyWhenHookWantsStreaming() {
        assertTrue(AgentRunner.streamingArmedFor(hookWantingStreaming(true)));
        assertFalse(AgentRunner.streamingArmedFor(hookWantingStreaming(false)));
        assertFalse(AgentRunner.streamingArmedFor(null), "consolidator-style direct calls carry no hook");
    }

    @Test
    void defaultSwitchIsOn() throws Exception {
        setStreamingEnabled(null); // appProperties present but key absent -> default true
        assertTrue(AgentRunner.streamingArmedFor(hookWantingStreaming(true)));
    }

    @Test
    void switchOffDisarmsEvenWhenHookWantsStreaming() throws Exception {
        setStreamingEnabled(false);
        assertFalse(AgentRunner.streamingArmedFor(hookWantingStreaming(true)));
    }

    @Test
    void switchOnArms() throws Exception {
        setStreamingEnabled(true);
        assertTrue(AgentRunner.streamingArmedFor(hookWantingStreaming(true)));
    }
}
