package org.gitee.jmeter.ai.recorder.convert;

import org.gitee.jmeter.ai.recorder.EventFilter;
import org.gitee.jmeter.ai.recorder.RecordingStore;
import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对抗性测试（解析/过滤/转换层）：撕裂 jsonl 尾行、敌意配置值与 URL、
 * 大小写重复头、畸形 Set-Cookie、孤儿响应、垃圾 URL、未知元素类型。
 */
class AdversarialParseTest {

    @TempDir
    Path dir;

    // ---- jsonl 撕裂尾行（崩溃写一半） ----

    @Test
    void tornTrailingJsonlLineToleratedMidFileCorruptionRejected() throws Exception {
        RecordingStore store = RecordingStore.create(dir, "inst");
        store.append(RecordedEvent.of(1, 1, "request", Map.of("url", "https://a/1")));
        Path file = store.eventsFile();
        store.close();
        Files.writeString(file, Files.readString(file) + "{\"seq\":2,\"ts\":3", // 无换行的半行
                StandardCharsets.UTF_8);
        assertEquals(1, RecordingStore.readEvents(file).size()); // 尾行撕裂被容忍

        Files.writeString(file, "{\"not json at all\"}\n"
                + "{\"seq\":1,\"ts\":1,\"pageId\":1,\"type\":\"request\"}\n");
        assertThrows(IOException.class, () -> RecordingStore.readEvents(file)); // 中间坏行拒绝
    }

    // ---- 敌意配置值 ----

    @Test
    void negativeBodyCapDisablesTruncationInsteadOfCrashing() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of(), -5);
        EventFilter.Truncated t = f.truncateBody("0123456789");
        assertFalse(t.truncated());
        assertEquals("0123456789", t.value());
    }

    @Test
    void zeroBodyCapAlsoSafe() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of(), 0);
        assertFalse(f.truncateBody("abc").truncated());
    }

    @Test
    void hostileUrlsInDomainFilter() {
        EventFilter f = new EventFilter(Set.of("xhr"), Set.of("example.com"), 1024);
        assertTrue(f.domainAllowed("https://user:pass@example.com/x"));
        assertTrue(f.domainAllowed("https://API.EXAMPLE.COM:8443/x"));
        assertTrue(f.domainAllowed("https://sub.example.com./x"));
        assertFalse(f.domainAllowed("https://example.com.evil.io/x"));
        assertFalse(f.domainAllowed("https://[::1]:8080/x"));
        assertFalse(f.domainAllowed("::::"));
        assertFalse(f.domainAllowed(""));
    }

    @Test
    void nullResourceTypeAndEmptyUrlHandledSafely() {
        EventFilter f = new EventFilter(Set.of("xhr", "fetch"), Set.of(), 1024);
        assertFalse(f.shouldCaptureRequest(null, "https://a/x"));
        assertTrue(f.domainAllowed("")); // 全采模式：空 URL 放行（转换器有原始串兜底）
        EventFilter filtered = new EventFilter(Set.of("xhr"), Set.of("a.io"), 1024);
        assertFalse(filtered.domainAllowed("")); // 域名过滤模式：空 URL 拒绝
    }

    // ---- 转换器敌意输入 ----

    private static RecordedEvent reqEvent(int seq, int pageId, String rid, String url) {
        return RecordedEvent.of(seq, pageId, "request", Map.of(
                "requestId", rid, "method", "GET", "url", url,
                "resourceType", "XHR", "headers", Map.of()));
    }

    @Test
    void duplicateHeadersDifferingOnlyInCaseCollapse() {
        // Map.of 不保序，用 LinkedHashMap 钉住首见拼写为 X-Biz
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Biz", "first");
        headers.put("x-biz", "second");
        List<RecordedEvent> events = List.of(
                new RecordedEvent(1, 1, 1, "request", Map.of(
                        "requestId", "R1", "method", "GET", "url", "https://a.io/x",
                        "resourceType", "XHR", "headers", headers)));
        Map<String, String> union = SkeletonConverter.businessHeaderUnion(RecordingModel.pairRequests(events));
        assertEquals(1, union.size());
        assertEquals("first", union.get("X-Biz"));
    }

    @Test
    void malformedSetCookiesIgnored() {
        var arr = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        arr.addObject().put("url", "https://a.io/").put("value", "=novalue; Path=/");
        arr.addObject().put("url", "https://a.io/").put("value", "novalue-at-all");
        arr.addObject().put("url", "https://a.io/").put("value", "good=1; Domain=a.io");
        var model = RecordingModel.of(List.of(), null, null, arr, 1, 2);
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(model);
        assertEquals(1, merged.size());
        assertEquals("good", merged.get(0).get("name"));
    }

    @Test
    void unpairedResponseAndBodyIgnoredInModel() {
        List<RecordedEvent> events = List.of(
                new RecordedEvent(1, 1, 1, "response", Map.of(
                        "requestId", "GHOST", "status", 200, "url", "https://a/x", "headers", Map.of())),
                new RecordedEvent(2, 1, 2, "responseBody", Map.of(
                        "requestId", "GHOST", "body", "x")));
        assertTrue(RecordingModel.pairRequests(events).isEmpty());
    }

    @Test
    void garbageUrlStillProducesSamplerWithRawFallback() {
        SkeletonConverter.ConversionResult r = new SkeletonConverter().convert(
                RecordingModel.of(List.of(reqEvent(1, 1, "R1", "::::")), null, null, null, 1, 2));
        assertEquals(1, r.samplerCount());
        ElementDef sampler = r.tree().get(0).children().get(0).children().get(0);
        assertEquals("::::", sampler.props().get("path"));
        assertFalse(sampler.props().containsKey("domain"));
    }

    @Test
    void unparseableSamplerNameFallsBack() {
        RecordingModel.CapturedRequest r = RecordingModel
                .pairRequests(List.of(reqEvent(1, 1, "R1", "::::"))).get(0);
        assertTrue(SkeletonConverter.samplerName(r, 1).startsWith("GET"));
    }

    // ---- 评审 P1-3：多字节 UTF-8 撕裂尾行不得毁掉整场录制 ----

    @Test
    void tornUtf8TailTolerated() throws Exception {
        RecordingStore store = RecordingStore.create(dir, "inst");
        store.append(RecordedEvent.of(1, 1, "request", Map.of("url", "https://a/1")));
        Path file = store.eventsFile();
        store.close();
        byte[] valid = Files.readAllBytes(file);
        // 追加一条被截断在多字节字符中间的 JSON 行（"中" = E4 B8 AD，只剩 E4 B8）
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(valid);
        bos.write("{\"seq\":2,\"ts\":3,\"pageId\":1,\"type\":\"request\",\"url\":\""
                .getBytes(StandardCharsets.UTF_8));
        bos.write(new byte[]{(byte) 0xE4, (byte) 0xB8});
        Files.write(file, bos.toByteArray());
        assertEquals(1, RecordingStore.readEvents(file).size());
    }

    // ---- 评审 P1-7：cookie 身份含 path / 默认路径 / 删除语义 / 多值拆分 ----

    @Test
    void cookieIdentityIncludesPath() {
        var baseline = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        baseline.addObject().put("name", "sid").put("value", "a").put("domain", "x.io").put("path", "/a");
        baseline.addObject().put("name", "sid").put("value", "b").put("domain", "x.io").put("path", "/b");
        var setCookies = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        setCookies.addObject().put("url", "https://x.io/a/x").put("value", "sid=c; Path=/a");
        var model = RecordingModel.of(List.of(), baseline, null, setCookies, 1, 2);
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(model);
        assertEquals(2, merged.size(), "同 domain+name 不同 path 不得合并: " + merged);
        Map<String, String> byPath = new java.util.HashMap<>();
        merged.forEach(c -> byPath.put(String.valueOf(c.get("path")), String.valueOf(c.get("value"))));
        assertEquals("c", byPath.get("/a"));
        assertEquals("b", byPath.get("/b"));
    }

    @Test
    void setCookieDefaultPathPerRfc6265() {
        var setCookies = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        setCookies.addObject().put("url", "https://x.io/a/b/login").put("value", "tok=1");
        var model = RecordingModel.of(List.of(), null, null, setCookies, 1, 2);
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(model);
        assertEquals("/a/b", merged.get(0).get("path"));
    }

    @Test
    void setCookieDeletionRemovesEntry() {
        var baseline = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        baseline.addObject().put("name", "sess").put("value", "v").put("domain", ".x.io").put("path", "/");
        var setCookies = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        setCookies.addObject().put("url", "https://x.io/bye").put("value", "sess=; Max-Age=0; Path=/");
        var model = RecordingModel.of(List.of(), baseline, null, setCookies, 1, 2);
        assertTrue(SkeletonConverter.mergeCookies(model).isEmpty(), "删除语义须生效");
    }

    @Test
    void refreshShortCircuitsMerge() {
        // refresh = 收官真实 jar：权威终态，不被 baseline/setCookies 污染
        var baseline = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        baseline.addObject().put("name", "gone").put("value", "1").put("domain", "x.io").put("path", "/");
        var refresh = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        refresh.addObject().put("name", "alive").put("value", "2").put("domain", "x.io").put("path", "/");
        var model = RecordingModel.of(List.of(), baseline, refresh, null, 1, 2);
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(model);
        assertEquals(1, merged.size());
        assertEquals("alive", merged.get(0).get("name"));
    }

    // ---- 评审 P2-16：Origin.matches 必须比较 protocol ----

    @Test
    void originProtocolMismatchNotInherited() {
        var events = List.of(
                reqEvent(1, 1, "R1", "https://a.io:8080/x1"),
                reqEvent(2, 1, "R2", "https://a.io:8080/x2"),
                reqEvent(3, 1, "R3", "http://a.io:8080/y1"));
        var result = new SkeletonConverter().convert(RecordingModel.of(events, null, null, null, 1, 2));
        var defaultDef = result.tree().get(0).children().get(0).children().stream()
                .filter(c -> c.elementType().equals("httpdefaults")).findFirst().orElseThrow();
        assertEquals("https", defaultDef.props().get("protocol"));
        ElementDef httpSampler = result.tree().get(0).children().get(0).children().stream()
                .filter(c -> c.elementType().equals("httpsampler"))
                .filter(c -> String.valueOf(c.name()).contains("/y1")).findFirst().orElseThrow();
        // http origin 不命中 https defaults：必须写完整目标（含 protocol）
        assertEquals("http", httpSampler.props().get("protocol"));
        assertEquals("a.io", httpSampler.props().get("domain"));
    }

    @Test
    void unknownElementTypePassthroughInTranslate() {
        // 未知类型 translate 直通（build 阶段由 registry 拒绝并给可读异常）
        ElementDef def = ElementDef.of("nosuchtype", "x", Map.of("k", "v"));
        assertEquals(Map.of("k", "v"), JmeterElementBridge.translate(def));
    }
}
