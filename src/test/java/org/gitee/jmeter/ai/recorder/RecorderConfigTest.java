package org.gitee.jmeter.ai.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 1.7：属性缺省、覆盖、坏值回退与目录解析。 */
class RecorderConfigTest {

    @Test
    void defaults() {
        RecorderConfig c = RecorderConfig.from(k -> null);
        assertTrue(c.enabled);
        assertEquals("chrome", c.browser);
        assertEquals(Set.of("xhr", "fetch"), c.resourceTypes);
        assertEquals(65536, c.maxBodyBytes);
        assertEquals("", c.outputDir);
    }

    @Test
    void overridesWithTrimming() {
        RecorderConfig c = RecorderConfig.from(k -> switch (k) {
            case RecorderConfig.KEY_ENABLED -> " false ";
            case RecorderConfig.KEY_BROWSER -> "C:/Apps/chrome.exe";
            case RecorderConfig.KEY_RESOURCE_TYPES -> " xhr , Fetch ,XHR";
            case RecorderConfig.KEY_MAX_BODY_BYTES -> "2048";
            case RecorderConfig.KEY_OUTPUT_DIR -> "D:/rec-out";
            default -> null;
        });
        assertFalse(c.enabled);
        assertEquals("C:/Apps/chrome.exe", c.browser);
        assertEquals(Set.of("xhr", "fetch"), c.resourceTypes);
        assertEquals(2048, c.maxBodyBytes);
        assertEquals("D:/rec-out", c.outputDir);
    }

    @Test
    void badIntFallsBackToDefault() {
        RecorderConfig c = RecorderConfig.from(
                k -> RecorderConfig.KEY_MAX_BODY_BYTES.equals(k) ? "not-a-number" : null);
        assertEquals(65536, c.maxBodyBytes);
    }

    @Test
    void outputDirResolution(@TempDir Path ws) {
        RecorderConfig def = RecorderConfig.from(k -> null);
        assertEquals(ws.resolve("recordings"), def.resolveOutputDir(ws));
        // profile 路径跨重启稳定（登录态持久的前提）；并行占用仲裁在 BrowserInstanceFinder 的占用声明
        assertEquals(ws.resolve("browser-profile"), def.resolveProfileDir(ws));

        RecorderConfig custom = RecorderConfig.from(
                k -> RecorderConfig.KEY_OUTPUT_DIR.equals(k) ? ws.resolve("alt").toString() : null);
        assertEquals(ws.resolve("alt"), custom.resolveOutputDir(ws));
    }
}
