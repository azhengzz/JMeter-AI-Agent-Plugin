package org.gitee.jmeter.ai.recorder.convert;

import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 任务 3.2/3.3：映射规则逐条与 cookie 三源合并。 */
class SkeletonConverterTest {

    private final SkeletonConverter converter = new SkeletonConverter();

    // ---- fixture helpers ----

    private static Map<String, Object> req(String id, String method, String url, Map<String, String> headers,
                                           String postData, String contentType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", id);
        data.put("method", method);
        data.put("url", url);
        data.put("resourceType", "XHR");
        Map<String, Object> hs = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        if (contentType != null) {
            hs.put("Content-Type", contentType);
        }
        data.put("headers", hs);
        if (postData != null) {
            data.put("postData", postData);
        }
        return data;
    }

    private static Map<String, Object> resp(String id, int status, String url) {
        return Map.of("requestId", id, "status", status, "url", url, "headers", Map.of());
    }

    private static RecordingModel model(List<RecordedEvent> events) {
        return model(events, null, null, null);
    }

    private static RecordingModel model(List<RecordedEvent> events,
                                        com.fasterxml.jackson.databind.JsonNode baseline,
                                        com.fasterxml.jackson.databind.JsonNode refresh,
                                        com.fasterxml.jackson.databind.JsonNode setCookies) {
        return RecordingModel.of(events, baseline, refresh, setCookies, 1761000000000L, 1761000060000L);
    }

    private static ElementDef transaction(SkeletonConverter.ConversionResult r) {
        ElementDef tg = r.tree().get(0);
        assertEquals("threadgroup", tg.elementType());
        return tg.children().get(0);
    }

    private static List<ElementDef> childrenOf(ElementDef tc, String type) {
        return tc.children().stream().filter(c -> c.elementType().equals(type)).toList();
    }

    private static ElementDef firstChild(ElementDef tc, String type) {
        return childrenOf(tc, type).stream().findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> headersOf(ElementDef hm) {
        return (List<Map<String, String>>) hm.props().get("headers");
    }

    // ---- 3.2 映射规则 ----

    @Test
    void threadGroupWrapsTransactionController() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request", req("R1", "GET", "https://a.io/x", Map.of(), null, null)))));
        ElementDef tg = r.tree().get(0);
        assertEquals("threadgroup", tg.elementType());
        assertTrue(tg.name().startsWith("录制 "));
        assertEquals(1, tg.props().get("numThreads"));
        assertEquals(1, tg.props().get("loops"));
        ElementDef tc = tg.children().get(0);
        assertEquals("transactioncontroller", tc.elementType());
        assertEquals(tg.name(), tc.name());
    }

    @Test
    void defaultsExtractedForDominantOrigin() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request", req("R1", "GET", "https://api.example.com/a", Map.of(), null, null)),
                new RecordedEvent(2, 1, 2, "request", req("R2", "GET", "https://api.example.com/b", Map.of(), null, null)),
                new RecordedEvent(3, 1, 3, "request", req("R3", "GET", "https://cdn.other.net/lib", Map.of(), null, null)))));
        ElementDef defaults = firstChild(transaction(r), "httpdefaults");
        assertNotNull(defaults);
        assertEquals("api.example.com", defaults.props().get("domain"));
        assertEquals("https", defaults.props().get("protocol"));
        assertEquals(443, defaults.props().get("port"));
        // 主域名采样器 path-only；异域采样器带完整目标
        ElementDef s1 = childrenOf(transaction(r), "httpsampler").get(0);
        assertEquals("/a", s1.props().get("path"));
        assertFalse(s1.props().containsKey("domain"));
        ElementDef s3 = childrenOf(transaction(r), "httpsampler").get(2);
        assertEquals("cdn.other.net", s3.props().get("domain"));
    }

    @Test
    void singleRequestYieldsNoDefaults() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request", req("R1", "GET", "https://api.example.com/a", Map.of(), null, null)))));
        assertNull(firstChild(transaction(r), "httpdefaults"));
        ElementDef s = childrenOf(transaction(r), "httpsampler").get(0);
        assertEquals("api.example.com", s.props().get("domain"));
        assertEquals("/a", s.props().get("path"));
    }

    @Test
    void fixedHeadersStrippedFromHeaderManager() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Accept-Encoding", "gzip");
        headers.put("Origin", "https://a.io");
        headers.put("sec-ch-ua", "\"x\"");
        headers.put("sec-fetch-mode", "cors");
        headers.put("X-Biz-Token", "abc");
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request", req("R1", "GET", "https://a.io/x", headers, null, null)))));
        ElementDef hm = firstChild(transaction(r), "headermanager");
        assertNotNull(hm);
        List<String> names = headersOf(hm).stream().map(h -> h.get("name")).toList();
        assertEquals(List.of("X-Biz-Token"), names);
    }

    @Test
    void acceptHeaderSurvivesAsBusinessHeader() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request",
                        req("R1", "GET", "https://a.io/api", headers, null, null)))));
        ElementDef hm = firstChild(transaction(r), "headermanager");
        assertNotNull(hm, "显式 Accept 不应被当作浏览器固有头剥掉");
        assertTrue(headersOf(hm).stream().anyMatch(h -> "Accept".equals(h.get("name"))
                && String.valueOf(h.get("value")).contains("application/json")));
    }

    @Test
    void urlEncodedBodySplitIntoArguments() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request",
                        req("R1", "POST", "https://a.io/login",
                                Map.of(), "user=tom&pwd=%31%32%33", "application/x-www-form-urlencoded")))));
        ElementDef s = childrenOf(transaction(r), "httpsampler").get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> args = (List<Map<String, String>>) s.props().get("arguments");
        assertEquals(2, args.size());
        assertEquals("user", args.get(0).get("name"));
        assertEquals("tom", args.get(0).get("value"));
        assertEquals("pwd", args.get(1).get("name"));
        assertEquals("123", args.get(1).get("value")); // URL decode
        assertNull(s.props().get("postData"));
        assertTrue(s.children().isEmpty(), "urlencoded 无采样器级 Content-Type 头");
    }

    @Test
    void postWithJsonBodyAndQueryKeepsBoth() {
        // 真实场景：POST JSON + URL 查询串——body 不得因 query 拆参而丢失
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request",
                        req("R1", "POST", "https://a.io/repo/ui/tree-browser?compacted=true",
                                Map.of(), "{\"type\":\"root\",\"byRepoKey\":\"QA\"}",
                                "application/json")))));
        ElementDef s = childrenOf(transaction(r), "httpsampler").get(0);
        assertEquals("/repo/ui/tree-browser?compacted=true", s.props().get("path"),
                "POST 的 query 必须留在路径上（arguments 会被编码成表单体）");
        assertEquals("{\"type\":\"root\",\"byRepoKey\":\"QA\"}", s.props().get("postData"));
        assertNull(s.props().get("arguments"), "raw body 不得与 arguments 混用");
        ElementDef hm = s.children().get(0);
        assertTrue(headersOf(hm).stream().anyMatch(h -> "Content-Type".equals(h.get("name"))
                && "application/json".equals(h.get("value"))));
    }

    @Test
    void jsonBodyGoesToPostDataWithSamplerContentType() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request",
                        req("R1", "POST", "https://a.io/api", Map.of("Authorization", "Bearer t1"),
                                "{\"x\":1}", "application/json")))));
        ElementDef s = childrenOf(transaction(r), "httpsampler").get(0);
        assertEquals("{\"x\":1}", s.props().get("postData"));
        assertNull(s.props().get("arguments"));
        // 采样器级头：Authorization + Content-Type（D9 明文保留）
        ElementDef hm = s.children().get(0);
        assertEquals("headermanager", hm.elementType());
        Map<String, String> byName = new LinkedHashMap<>();
        headersOf(hm).forEach(h -> byName.put(h.get("name"), h.get("value")));
        assertEquals("Bearer t1", byName.get("Authorization"));
        assertEquals("application/json", byName.get("Content-Type"));
        // 全局 HeaderManager 不含 Authorization（本例无其他业务头时全局 HM 不生成）
        ElementDef global = firstChild(transaction(r), "headermanager");
        if (global != null) {
            headersOf(global).forEach(h -> assertNotEquals("Authorization", h.get("name")));
        }
    }

    @Test
    void redirectCollapsesToOneSamplerWithFinalUrl() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request", req("R1", "GET", "https://a.io/old", Map.of(), null, null)),
                new RecordedEvent(2, 1, 1, "request", req("R1", "GET", "https://a.io/new?k=1", Map.of(), null, null)),
                new RecordedEvent(3, 1, 1, "response", resp("R1", 200, "https://a.io/new?k=1")))));
        assertEquals(1, r.samplerCount());
        ElementDef s = childrenOf(transaction(r), "httpsampler").get(0);
        // query 已拆进 arguments（方案文档 6.1），path 只保留路径本身
        assertEquals("/new", s.props().get("path"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> redirectArgs = (List<Map<String, String>>) s.props().get("arguments");
        assertEquals("k", redirectArgs.get(0).get("name"));
        assertEquals("1", redirectArgs.get(0).get("value"));
        assertEquals("GET /new?k=1#1", s.name());
    }

    @Test
    void querySplitIntoArguments() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "request",
                        req("R1", "GET", "https://api.example.com/v1/q?a=1&b=2", Map.of(), null, null)))));
        ElementDef s = childrenOf(transaction(r), "httpsampler").get(0);
        assertEquals("/v1/q", s.props().get("path"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> args = (List<Map<String, String>>) s.props().get("arguments");
        assertEquals(2, args.size());
        assertEquals("a", args.get(0).get("name"));
        assertEquals("2", args.get(1).get("value"));
        assertEquals("GET /v1/q?a=1&b=2#1", s.name());
    }

    @Test
    void uiEventsGenerateNoElements() {
        var r = converter.convert(model(List.of(
                new RecordedEvent(1, 1, 1, "click", Map.of("url", "https://a.io/p", "tag", "button")),
                new RecordedEvent(2, 1, 2, "submit", Map.of("url", "https://a.io/p")),
                new RecordedEvent(3, 1, 3, "request", req("R1", "GET", "https://a.io/x", Map.of(), null, null)))));
        assertEquals(1, childrenOf(transaction(r), "httpsampler").size());
        assertTrue(childrenOf(transaction(r), "threadgroup").isEmpty());
        assertTrue(childrenOf(transaction(r), "transactioncontroller").isEmpty());
    }

    @Test
    void emptyRecordingProducesBareSkeleton() {
        var r = converter.convert(model(List.of()));
        ElementDef tc = transaction(r);
        assertEquals(0, tc.children().size());
        assertEquals(0, r.samplerCount());
    }

    // ---- 3.3 cookie 三源合并 ----

    private static com.fasterxml.jackson.databind.JsonNode cookies(String... nameValues) {
        var arr = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        for (int i = 0; i < nameValues.length; i += 2) {
            var c = arr.addObject();
            c.put("name", nameValues[i]);
            c.put("value", nameValues[i + 1]);
            c.put("domain", "example.com");
            c.put("path", "/");
        }
        return arr;
    }

    private static com.fasterxml.jackson.databind.JsonNode setCookies(String url, String raw) {
        var arr = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        var n = arr.addObject();
        n.put("url", url);
        n.put("value", raw);
        return arr;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cookieByName(List<Map<String, Object>> cookies, String name) {
        return cookies.stream().filter(c -> c.get("name").equals(name)).findFirst().orElse(null);
    }

    @Test
    void closeChannelBaselinePlusSetCookies() {
        // 关窗通道：无 refresh；基线（预备态登录）∪ 录制期 Set-Cookie
        RecordingModel m = model(List.of(), cookies("sess", "v1"), null,
                setCookies("https://example.com/api", "sid=abc; Path=/; Domain=example.com"));
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(m);
        assertEquals(2, merged.size());
        assertEquals("v1", cookieByName(merged, "sess").get("value"));
        assertEquals("abc", cookieByName(merged, "sid").get("value"));
        assertEquals("example.com", cookieByName(merged, "sid").get("domain"));
    }

    @Test
    void refreshOverridesEarlierSources() {
        RecordingModel m = model(List.of(),
                cookies("sess", "v1"),
                cookies("sess", "v3"),
                setCookies("https://example.com/api", "sess=v2; Domain=example.com"));
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(m);
        assertEquals(1, merged.size());
        assertEquals("v3", cookieByName(merged, "sess").get("value"));
    }

    @Test
    void setCookieWithoutDomainAttrFallsBackToUrlHost() {
        RecordingModel m = model(List.of(), null, null,
                setCookies("https://api.example.com/login", "tok=xyz; Path=/"));
        List<Map<String, Object>> merged = SkeletonConverter.mergeCookies(m);
        assertEquals("api.example.com", cookieByName(merged, "tok").get("domain"));
    }

    @Test
    void cookieManagerEmittedOnlyWhenCookiesExist() {
        var withCookies = converter.convert(model(List.of(), cookies("sess", "v1"), null, null));
        assertNotNull(firstChild(transaction(withCookies), "cookiemanager"));
        assertEquals(false, firstChild(transaction(withCookies), "cookiemanager").props().get("clearEachIteration"));

        var without = converter.convert(model(List.of()));
        assertNull(firstChild(transaction(without), "cookiemanager"));
    }
}
