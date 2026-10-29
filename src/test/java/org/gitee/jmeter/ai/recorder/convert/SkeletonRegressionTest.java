package org.gitee.jmeter.ai.recorder.convert;

import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务 3.4：复杂样例端到端——多域名 / 重定向链 / 失败请求 / cookie 三源 / UI 事件混杂，
 * 断言完整元素定义序列。
 */
class SkeletonRegressionTest {

    @Test
    void complexRecordingProducesFullSkeleton() {
        Map<String, String> authHeader = new LinkedHashMap<>(Map.of(
                "Authorization", "Bearer tok-1", "X-Biz-Token", "biz"));
        List<RecordedEvent> events = List.of(
                // UI 噪音混杂（不应产生元素，只作上下文）
                new RecordedEvent(1, 1, 1, "click", Map.of("url", "https://app.example.com/", "tag", "button")),
                // R1：主域名 GET 带查询
                new RecordedEvent(2, 1, 2, "request", req("R1", "GET",
                        "https://api.example.com/orders?page=1", Map.of("X-Biz-Token", "biz"), null, null)),
                new RecordedEvent(3, 1, 3, "response", resp("R1", 200, "https://api.example.com/orders?page=1")),
                // R2：重定向链（/search → /results 两跳），只取最终
                new RecordedEvent(4, 1, 4, "request", req("R2", "GET",
                        "https://api.example.com/search?q=x", Map.of(), null, null)),
                new RecordedEvent(5, 1, 4, "request", req("R2", "GET",
                        "https://api.example.com/results?q=x", Map.of(), null, null)),
                new RecordedEvent(6, 1, 4, "response", resp("R2", 200, "https://api.example.com/results?q=x")),
                // R3：异域 POST JSON（带 Authorization → 采样器级头）
                new RecordedEvent(7, 2, 7, "request", req("R3", "POST",
                        "https://v2.example.com/pay", authHeader, "{\"amount\":9}", "application/json")),
                new RecordedEvent(8, 2, 8, "response", resp("R3", 200, "https://v2.example.com/pay")),
                new RecordedEvent(9, 2, 9, "responseBody", Map.of(
                        "requestId", "R3", "body", "{\"ok\":true}", "base64Encoded", false)),
                // R4：失败请求（保留为采样器）
                new RecordedEvent(10, 1, 10, "request", req("R4", "GET",
                        "https://cdn.assets.net/logo.png", Map.of(), null, null)),
                new RecordedEvent(11, 1, 11, "requestfailed", Map.of(
                        "requestId", "R4", "url", "https://cdn.assets.net/logo.png", "errorText", "net::ERR_FAILED")),
                // R5：urlencoded 表单
                new RecordedEvent(12, 1, 12, "request", req("R5", "POST",
                        "https://api.example.com/login", Map.of(), "user=a&pass=b", "application/x-www-form-urlencoded")),
                new RecordedEvent(13, 1, 13, "response", resp("R5", 200, "https://api.example.com/login")),
                // R6：超长 body（引擎已按 maxBodyBytes 截断，事件带截断标记与截断后载荷）
                new RecordedEvent(14, 1, 14, "request", truncatedReq("R6", "https://api.example.com/bulk")),
                new RecordedEvent(15, 1, 15, "response", resp("R6", 200, "https://api.example.com/bulk")));

        var baseline = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        var sess = baseline.addObject();
        sess.put("name", "sess");
        sess.put("value", "login-cookie");
        sess.put("domain", ".example.com");
        sess.put("path", "/");
        var setCookies = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        var sc = setCookies.addObject();
        sc.put("url", "https://api.example.com/login");
        sc.put("value", "sid=fresh; Path=/; Domain=api.example.com");
        var refresh = org.gitee.jmeter.ai.recorder.RecorderJson.MAPPER.createArrayNode();
        var sessFinal = refresh.addObject();
        sessFinal.put("name", "sess");
        sessFinal.put("value", "final-cookie");
        sessFinal.put("domain", ".example.com");
        sessFinal.put("path", "/");
        // refresh = 收官时浏览器真实 jar（权威终态）：录制期种下的 sid 也在其中
        var sidFinal = refresh.addObject();
        sidFinal.put("name", "sid");
        sidFinal.put("value", "fresh");
        sidFinal.put("domain", "api.example.com");
        sidFinal.put("path", "/");

        RecordingModel model = RecordingModel.of(events, baseline, refresh, setCookies, 1761000000000L, 1761000300000L);
        SkeletonConverter.ConversionResult r = new SkeletonConverter().convert(model);

        // 结构：TG → TC → [CookieManager, HeaderManager, Defaults, 采样器×6] = 9
        ElementDef tg = r.tree().get(0);
        assertEquals("threadgroup", tg.elementType());
        assertEquals(1, tg.props().get("numThreads"));
        ElementDef tc = tg.children().get(0);
        assertEquals("transactioncontroller", tc.elementType());
        assertEquals(9, tc.children().size());

        // 计数与域名
        assertEquals(6, r.samplerCount());
        assertEquals(6, r.requestCount());
        assertEquals(Set.of("api.example.com", "v2.example.com", "cdn.assets.net"), r.domains());

        // Defaults：api.example.com 出现 3 次（R1/R2final/R5）为主域名
        ElementDef defaults = tc.children().stream()
                .filter(c -> c.elementType().equals("httpdefaults")).findFirst().orElseThrow();
        assertEquals("api.example.com", defaults.props().get("domain"));

        // CookieManager：refresh 的 final-cookie 覆盖基线，录制期 sid 并入
        ElementDef cm = tc.children().stream()
                .filter(c -> c.elementType().equals("cookiemanager")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cookies = (List<Map<String, Object>>) cm.props().get("cookies");
        assertEquals(2, cookies.size());
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        cookies.forEach(c -> byName.put((String) c.get("name"), c));
        assertEquals("final-cookie", byName.get("sess").get("value"));
        assertEquals("fresh", byName.get("sid").get("value"));

        // HeaderManager：业务头并集（X-Biz-Token），无固有头/凭证头
        ElementDef hm = tc.children().stream()
                .filter(c -> c.elementType().equals("headermanager")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headers = (List<Map<String, String>>) hm.props().get("headers");
        assertEquals(1, headers.size());
        assertEquals("X-Biz-Token", headers.get(0).get("name"));

        // 采样器序列与命名
        List<ElementDef> samplers = tc.children().stream()
                .filter(c -> c.elementType().equals("httpsampler")).toList();
        assertEquals(List.of(
                "GET /orders?page=1#1",
                "GET /results?q=x#2",
                "POST /pay#3",
                "GET /logo.png#4",
                "POST /login#5",
                "POST /bulk#6"), samplers.stream().map(ElementDef::name).toList());
        // 主域名采样器 path-only；异域带完整目标
        assertFalse(samplers.get(0).props().containsKey("domain"));
        assertEquals("v2.example.com", samplers.get(2).props().get("domain"));
        assertEquals("cdn.assets.net", samplers.get(3).props().get("domain"));
        // JSON body + 采样器级头；urlencoded 拆参
        assertEquals("{\"amount\":9}", samplers.get(2).props().get("postData"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> r3Headers = (List<Map<String, String>>) samplers.get(2).children().get(0).props().get("headers");
        assertTrue(r3Headers.stream().anyMatch(h -> "Authorization".equals(h.get("name"))));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> r5Args = (List<Map<String, String>>) samplers.get(4).props().get("arguments");
        assertEquals("user=a&pass=b".split("&").length, r5Args.size());
        // 超长 body：截断后载荷原样进 postData
        assertEquals(100, ((String) samplers.get(5).props().get("postData")).length());

        // UI 事件无元素
        assertTrue(tc.children().stream().noneMatch(c -> c.elementType().equals("threadgroup")));
    }

    /** 模拟引擎截断后的超长 body 事件（100 字符载荷 + 截断标记）。 */
    private static Map<String, Object> truncatedReq(String id, String url) {
        Map<String, Object> data = req(id, "POST", url, Map.of(), "x".repeat(100), "application/json");
        data.put("postDataTruncated", true);
        return data;
    }

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
}
