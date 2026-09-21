package org.gitee.jmeter.ai.agent.tools.ipc;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.tools.JMeterToolRegistry;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code read_instance_session} 的注册门控矩阵:per-instance 两态 × IPC 两态。
 *
 * <p>门控语义:数据源是每实例会话文件,故只随 {@code agent.session.per-instance} 门控;纯本地
 * 文件读不消费 IPC 传输,IPC 关闭时仍注册(list/delegate 则仍随 IPC 关闭消失)。
 * JMeter 属性经反射初始化 {@code appProperties} 后写/清(AiConfigTest 同款配方)。
 */
class ReadInstanceSessionRegistrationTest {

    private static final String PER_INSTANCE_KEY = "agent.session.per-instance";
    private static final String IPC_KEY = "jmeter.ai.ipc.enabled";

    @BeforeEach
    @AfterEach
    void resetProps() {
        ensureJMeterProps();
        JMeterUtils.getJMeterProperties().remove(PER_INSTANCE_KEY);
        JMeterUtils.getJMeterProperties().remove(IPC_KEY);
    }

    @Test
    void registeredInPerInstanceModeRegardlessOfIpc() {
        JMeterUtils.getJMeterProperties().setProperty(PER_INSTANCE_KEY, "true");
        for (String ipc : new String[]{"true", "false"}) {
            JMeterUtils.getJMeterProperties().setProperty(IPC_KEY, ipc);
            ToolRegistry registry = new ToolRegistry();
            JMeterToolRegistry.registerInstanceCoordinationTools(registry);
            assertTrue(registry.has(ReadInstanceSessionTool.NAME), "ipc=" + ipc);
            // IPC 组仍随传输开关走(不因本工具的独立门控而波及)
            assertEquals("true".equals(ipc), registry.has("list_instances"), "ipc=" + ipc);
            assertEquals("true".equals(ipc), registry.has("delegate_to_instance"), "ipc=" + ipc);
        }
    }

    @Test
    void notRegisteredInLegacyGlobalSessionMode() {
        JMeterUtils.getJMeterProperties().setProperty(PER_INSTANCE_KEY, "false");
        JMeterUtils.getJMeterProperties().setProperty(IPC_KEY, "true");
        ToolRegistry registry = new ToolRegistry();
        JMeterToolRegistry.registerInstanceCoordinationTools(registry);
        assertFalse(registry.has(ReadInstanceSessionTool.NAME));
        assertTrue(registry.has("delegate_to_instance"), "legacy 模式只挡本工具,不波及 IPC 组");
    }

    /** 反射确保 {@code JMeterUtils.appProperties} 非空(否则 setProperty NPE)。 */
    private static void ensureJMeterProps() {
        try {
            Field f = JMeterUtils.class.getDeclaredField("appProperties");
            f.setAccessible(true);
            if (f.get(null) == null) {
                f.set(null, new Properties());
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
