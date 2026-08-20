package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Typed accessor behavior for {@link AiConfig}.
 *
 * <p>Locks the "non-numeric configured value falls back to default (and does not
 * throw)" contract — the ERROR log line is emitted but the value degrades instead
 * of crashing startup on a mis-configured property.
 */
class AiConfigTest {

    /** 反射确保 {@code JMeterUtils.appProperties} 非空,否则 setProperty NPE。 */
    @BeforeAll
    static void ensureJMeterProps() {
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

    @Test
    void getInt_nonNumericValue_fallsBackToDefault() {
        JMeterUtils.setProperty("test.ai.config.int", "not-an-int");
        assertDoesNotThrow(() -> {
            assertEquals(42, AiConfig.getInt("test.ai.config.int", 42));
        });
    }

    @Test
    void getInt_emptyValue_fallsBackToDefault() {
        JMeterUtils.setProperty("test.ai.config.int.empty", "");
        assertEquals(42, AiConfig.getInt("test.ai.config.int.empty", 42));
    }

    @Test
    void getInt_validValue_parsesNormally() {
        JMeterUtils.setProperty("test.ai.config.int.valid", "7");
        assertEquals(7, AiConfig.getInt("test.ai.config.int.valid", 42));
    }

    @Test
    void getDouble_nonNumericValue_fallsBackToDefault() {
        JMeterUtils.setProperty("test.ai.config.double", "not-a-double");
        assertDoesNotThrow(() -> {
            assertEquals(0.7, AiConfig.getDouble("test.ai.config.double", 0.7), 0.0001);
        });
    }

    @Test
    void getLong_nonNumericValue_fallsBackToDefault() {
        JMeterUtils.setProperty("test.ai.config.long", "not-a-long");
        assertDoesNotThrow(() -> {
            assertEquals(30000L, AiConfig.getLong("test.ai.config.long", 30000L));
        });
    }
}