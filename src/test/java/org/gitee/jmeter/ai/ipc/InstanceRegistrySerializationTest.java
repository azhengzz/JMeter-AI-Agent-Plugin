package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InstanceRegistry#writeInstance}/{@link InstanceRegistry#updateJmxPath} 的字段序列化与
 * 读改写保持性(直接以 Jackson 读回端口文件,绕过 TCP 探活)。
 */
class InstanceRegistrySerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    File tmp;

    @Test
    void writeInstanceRoundTripsAllFields() throws Exception {
        File ipcDir = new File(tmp, "ipc");
        InstanceRegistry.writeInstance(ipcDir, "12345", 6000, "tok-xyz",
                "127.0.0.1", "12345-1700000000000", "/path/a.jmx");

        File pf = InstanceRegistry.portFile(ipcDir, "12345");
        assertTrue(pf.exists());
        InstanceInfo read = MAPPER.readValue(pf, InstanceInfo.class);
        assertEquals("12345", read.getPid());
        assertEquals(6000, read.getPort());
        assertEquals("tok-xyz", read.getToken());
        assertEquals("127.0.0.1", read.getBind());
        assertEquals("12345-1700000000000", read.getInstanceId());
        assertEquals("/path/a.jmx", read.getJmxPath());
        assertTrue(read.getStartedAt() > 0);
    }

    @Test
    void nullJmxPathSerializesAsEmpty() throws Exception {
        File ipcDir = new File(tmp, "ipc");
        InstanceRegistry.writeInstance(ipcDir, "1", 1, "t", "127.0.0.1", "1-1", null);
        InstanceInfo read = MAPPER.readValue(InstanceRegistry.portFile(ipcDir, "1"), InstanceInfo.class);
        assertEquals("", read.getJmxPath());
    }

    @Test
    void updateJmxPathPreservesOtherFields() throws Exception {
        File ipcDir = new File(tmp, "ipc");
        InstanceRegistry.writeInstance(ipcDir, "777", 7000, "tok-keep",
                "127.0.0.1", "777-111", "/old.jmx");

        boolean ok = InstanceRegistry.updateJmxPath(ipcDir, "777", "/new.jmx");
        assertTrue(ok);

        InstanceInfo read = MAPPER.readValue(InstanceRegistry.portFile(ipcDir, "777"), InstanceInfo.class);
        assertEquals("/new.jmx", read.getJmxPath(), "jmxPath must be updated");
        assertEquals(7000, read.getPort(), "port must be preserved");
        assertEquals("tok-keep", read.getToken(), "token must be preserved");
        assertEquals("777-111", read.getInstanceId(), "instanceId must be preserved");
    }

    @Test
    void updateJmxPathReturnsFalseWhenNoPortFile() {
        File ipcDir = new File(tmp, "ipc");
        assertFalse(InstanceRegistry.updateJmxPath(ipcDir, "999", "/x.jmx"),
                "updateJmxPath must return false when no port file exists");
    }
}
