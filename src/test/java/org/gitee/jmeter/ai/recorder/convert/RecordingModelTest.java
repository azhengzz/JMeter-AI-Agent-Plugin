package org.gitee.jmeter.ai.recorder.convert;

import org.gitee.jmeter.ai.recorder.RecordingStore;
import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 3.1：目录加载、requestId 配对还原、重定向取最终 hop、UI 事件分离。 */
class RecordingModelTest {

    @TempDir
    Path dir;

    @Test
    void loadPairsRequestResponseAndBody() throws Exception {
        Path rec = fixture(events -> {
            events.add(RecordedEvent.of(1, 1, "request", Map.of(
                    "requestId", "R1", "method", "GET", "url", "https://a.io/x",
                    "resourceType", "XHR", "headers", Map.of("accept", "*/*"))));
            events.add(RecordedEvent.of(2, 1, "response", Map.of(
                    "requestId", "R1", "status", 200, "url", "https://a.io/x", "headers", Map.of())));
            events.add(RecordedEvent.of(3, 1, "responseBody", Map.of(
                    "requestId", "R1", "body", "HELLO", "base64Encoded", false)));
        });
        RecordingModel m = RecordingModel.load(rec);
        assertEquals(1, m.requests.size());
        RecordingModel.CapturedRequest r = m.requests.get(0);
        assertEquals(200, r.status());
        assertEquals("HELLO", r.body());
        assertFalse(r.base64Encoded());
        assertNull(r.errorText());
    }

    @Test
    void loadFailedRequestPairsErrorText() throws Exception {
        Path rec = fixture(events -> {
            events.add(RecordedEvent.of(1, 1, "request", Map.of(
                    "requestId", "R1", "method", "GET", "url", "https://a.io/x",
                    "resourceType", "XHR", "headers", Map.of())));
            events.add(RecordedEvent.of(2, 1, "requestfailed", Map.of(
                    "requestId", "R1", "url", "https://a.io/x", "errorText", "net::ERR_FAILED")));
        });
        RecordingModel m = RecordingModel.load(rec);
        assertEquals("net::ERR_FAILED", m.requests.get(0).errorText());
        assertNull(m.requests.get(0).status());
    }

    @Test
    void redirectHopsCollapseToFinalRequest() throws Exception {
        Path rec = fixture(events -> {
            events.add(RecordedEvent.of(1, 1, "request", Map.of(
                    "requestId", "R1", "method", "GET", "url", "https://a.io/old",
                    "resourceType", "XHR", "headers", Map.of())));
            events.add(RecordedEvent.of(2, 1, "request", Map.of(
                    "requestId", "R1", "method", "GET", "url", "https://a.io/new",
                    "resourceType", "XHR", "headers", Map.of())));
            events.add(RecordedEvent.of(3, 1, "response", Map.of(
                    "requestId", "R1", "status", 200, "url", "https://a.io/new", "headers", Map.of())));
        });
        RecordingModel m = RecordingModel.load(rec);
        assertEquals(1, m.requests.size());
        assertEquals("https://a.io/new", m.requests.get(0).url());
        assertEquals(2, m.requests.get(0).hops());
    }

    @Test
    void uiEventsSeparatedFromRequests() throws Exception {
        Path rec = fixture(events -> {
            events.add(RecordedEvent.of(1, 1, "click", Map.of("url", "https://a.io/p", "tag", "button")));
            events.add(RecordedEvent.of(2, 1, "navigate", Map.of("url", "https://a.io/")));
            events.add(RecordedEvent.of(3, 1, "request", Map.of(
                    "requestId", "R1", "method", "GET", "url", "https://a.io/x",
                    "resourceType", "XHR", "headers", Map.of())));
        });
        RecordingModel m = RecordingModel.load(rec);
        assertEquals(1, m.uiEvents.size());
        assertEquals("click", m.uiEvents.get(0).type());
        assertEquals(1, m.requests.size());
    }

    @Test
    void cookiesAndSummaryOptional() throws Exception {
        Path rec = fixture(events ->
                events.add(RecordedEvent.of(1, 1, "request", Map.of(
                        "requestId", "R1", "method", "GET", "url", "https://a.io/x",
                        "resourceType", "XHR", "headers", Map.of()))));
        RecordingModel m = RecordingModel.load(rec);
        assertNull(m.cookieBaseline);
        assertNull(m.cookieRefresh);
        assertEquals(0, m.startedAt);
    }

    // ---- fixture ----

    private Path fixture(java.util.function.Consumer<List<RecordedEvent>> writer) throws Exception {
        RecordingStore store = RecordingStore.create(dir.resolve("recordings"), "test-inst");
        List<RecordedEvent> events = new java.util.ArrayList<>();
        writer.accept(events);
        for (RecordedEvent e : events) {
            store.append(e);
        }
        store.close();
        try (var list = java.nio.file.Files.list(dir.resolve("recordings"))) {
            return list.findFirst().orElseThrow();
        }
    }
}
