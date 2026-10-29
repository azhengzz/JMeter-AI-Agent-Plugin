package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 1.6：写读回环、逐行 flush（不 close 也可读）、取消删除、同秒目录去重。 */
class RecordingStoreTest {

    @TempDir
    Path workspace;

    @Test
    void roundtripPreservesEvents() throws Exception {
        Path out = workspace.resolve("recordings");
        try (RecordingStore store = RecordingStore.create(out, "inst-1")) {
            store.append(RecordedEvent.of(1, 1, "request",
                    Map.of("requestId", "R1", "method", "GET", "url", "https://a/b")));
            store.append(RecordedEvent.of(2, 1, "navigate", Map.of("url", "https://a/")));
            List<RecordedEvent> events = RecordingStore.readEvents(store.eventsFile());
            assertEquals(2, events.size());
            assertEquals("request", events.get(0).type());
            assertEquals("R1", events.get(0).data().get("requestId"));
            assertEquals(1, events.get(0).seq());
            assertEquals("navigate", events.get(1).type());
        }
    }

    @Test
    void flushedPerLineReadableWithoutClose() throws Exception {
        // 崩溃韧性：写入后不 close（模拟进程被杀），已写行必须完整可读
        Path out = workspace.resolve("recordings");
        RecordingStore store = RecordingStore.create(out, "inst-1");
        store.append(RecordedEvent.of(1, 1, "request", Map.of("url", "https://a/1")));
        store.append(RecordedEvent.of(2, 1, "request", Map.of("url", "https://a/2")));

        Path dir;
        try (var list = Files.list(out)) {
            dir = list.findFirst().orElseThrow();
        }
        List<RecordedEvent> events = RecordingStore.readEvents(dir.resolve("events.jsonl"));
        assertEquals(2, events.size());
        assertEquals("https://a/2", events.get(1).data().get("url"));
        // 不 close：交由测试 JVM 回收
    }

    @Test
    void deleteQuietlyRemovesWholeDir() throws Exception {
        Path out = workspace.resolve("recordings");
        RecordingStore store = RecordingStore.create(out, "inst-1");
        store.append(RecordedEvent.of(1, 1, "request", Map.of("url", "x")));
        Path dir = store.dir();
        assertTrue(Files.exists(dir));
        store.deleteQuietly();
        assertFalse(Files.exists(dir));
    }

    @Test
    void sameSecondDirsDeduplicated() throws Exception {
        Path out = workspace.resolve("recordings");
        RecordingStore a = RecordingStore.create(out, "inst-1");
        RecordingStore b = RecordingStore.create(out, "inst-1");
        assertNotEquals(a.dir(), b.dir());
        a.deleteQuietly();
        b.deleteQuietly();
    }

    @Test
    void dirNameCarriesInstanceId() throws Exception {
        Path out = workspace.resolve("recordings");
        try (RecordingStore store = RecordingStore.create(out, "12345-678")) {
            String name = store.dir().getFileName().toString();
            assertTrue(name.startsWith("rec_"), name);
            assertTrue(name.endsWith("-12345-678"), name);
        }
    }
}
