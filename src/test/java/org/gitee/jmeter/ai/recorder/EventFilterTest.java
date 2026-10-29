package org.gitee.jmeter.ai.recorder;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 1.5：过滤器单元规则。 */
class EventFilterTest {

    @Test
    void resourceTypeWhitelistIsCaseInsensitive() {
        EventFilter f = new EventFilter(Set.of("xhr", "fetch"), Set.of(), 1024);
        assertTrue(f.resourceTypeAllowed("XHR"));
        assertTrue(f.resourceTypeAllowed("Fetch"));
        assertFalse(f.resourceTypeAllowed("Script"));
        assertFalse(f.resourceTypeAllowed(null));
        assertFalse(f.resourceTypeAllowed(""));
    }

    @Test
    void emptyDomainSetMeansCaptureAll() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of(), 1024);
        assertTrue(f.domainAllowed("https://anything.example.net/x"));
    }

    @Test
    void domainMatchCoversSubdomains() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of("example.com"), 1024);
        assertTrue(f.domainAllowed("https://example.com/a"));
        assertTrue(f.domainAllowed("https://api.example.com/a"));
        assertFalse(f.domainAllowed("https://notexample.com/a"));
        assertFalse(f.domainAllowed("https://bad-example.com/a"));
        assertFalse(f.domainAllowed("::::not-a-url"));
    }

    @Test
    void combinedDecision() {
        EventFilter f = new EventFilter(Set.of("xhr", "fetch"), Set.of("example.com"), 1024);
        assertTrue(f.shouldCaptureRequest("XHR", "https://api.example.com/v1"));
        assertFalse(f.shouldCaptureRequest("Script", "https://api.example.com/app.js"));
        assertFalse(f.shouldCaptureRequest("XHR", "https://cdn.other.io/lib"));
    }

    @Test
    void includeDomainEntriesNormalizedOnEntry() {
        EventFilter f = new EventFilter(Set.of("xhr"),
                Set.of("Example.COM:8080", " https://sub.example.com/path ", ".Dot.IO."), 1024);
        assertTrue(f.domainAllowed("https://example.com/x"), "端口条目应按主机匹配");
        assertTrue(f.domainAllowed("https://api.sub.example.com/x"), "带 scheme/path 条目应归一化");
        assertTrue(f.domainAllowed("https://dot.io/x"), "前导点/尾点应归一化");
        assertFalse(f.domainAllowed("https://other.io/x"));
    }

    @Test
    void bodyTruncation() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of(), 10);
        assertFalse(f.truncateBody(null).truncated());
        assertNull(f.truncateBody(null).value());
        assertFalse(f.truncateBody("short").truncated());
        assertEquals("short", f.truncateBody("short").value());
        EventFilter.Truncated t = f.truncateBody("0123456789ABCDEF");
        assertTrue(t.truncated());
        assertEquals(10, t.value().length());
    }

    @Test
    void hostOfToleratesUrlShapes() {
        assertEquals("a.b.c", EventFilter.hostOf("https://a.b.c/x?y=1"));
        assertEquals("127.0.0.1", EventFilter.hostOf("http://127.0.0.1:8080/x"));
        assertNull(EventFilter.hostOf("::::"));
    }
}
