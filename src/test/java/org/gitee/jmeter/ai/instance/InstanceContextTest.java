package org.gitee.jmeter.ai.instance;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InstanceContext} 的进程单例 idempotency、instanceId 格式与会话键回退行为。
 *
 * <p>JMeter 的 {@code appProperties} 在纯单测中未初始化,反射置一个空 {@link Properties} 以支持
 * {@code agent.session.per-instance} 的写入/回退断言。
 */
class InstanceContextTest {

    private static final String PER_INSTANCE_KEY = "agent.session.per-instance";

    @BeforeEach
    void reset() {
        InstanceContext.resetForTest();
        ensureJMeterProps();
        JMeterUtils.getJMeterProperties().remove(PER_INSTANCE_KEY);
    }

    @AfterEach
    void cleanup() {
        InstanceContext.resetForTest();
        ensureJMeterProps();
        JMeterUtils.getJMeterProperties().remove(PER_INSTANCE_KEY);
    }

    @Test
    void initIsIdempotent() {
        InstanceContext a = InstanceContext.init();
        InstanceContext b = InstanceContext.init();
        assertSame(a, b, "init() twice must return the same singleton");
    }

    @Test
    void instanceIdMatchesPidStartedAtPattern() {
        String id = InstanceContext.instanceId();
        assertNotNull(id);
        assertTrue(id.matches("\\d+-\\d+"), "instanceId should be {pid}-{startedAtMs}: " + id);
        assertEquals(InstanceRegistry.currentPid(), id.split("-", 2)[0],
                "instanceId prefix must be the current JVM pid");
    }

    @Test
    void currentSessionKeyDefaultsToInstanceId() {
        JMeterUtils.getJMeterProperties().setProperty(PER_INSTANCE_KEY, "true");
        assertEquals(InstanceContext.instanceId(), InstanceContext.currentSessionKey());
    }

    @Test
    void currentSessionKeyFallsBackToLegacyWhenDisabled() {
        JMeterUtils.getJMeterProperties().setProperty(PER_INSTANCE_KEY, "false");
        assertEquals(InstanceContext.LEGACY_SESSION_KEY, InstanceContext.currentSessionKey());
    }

    @Test
    void gettersExposeComponents() {
        InstanceContext ic = InstanceContext.init();
        assertEquals(InstanceContext.instanceId(), ic.getInstanceId());
        assertEquals(InstanceRegistry.currentPid(), ic.getPid());
        assertTrue(ic.getStartedAtMs() > 0);
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
