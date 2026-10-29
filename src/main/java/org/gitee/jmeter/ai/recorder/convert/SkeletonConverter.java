package org.gitee.jmeter.ai.recorder.convert;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 确定性骨架转换器：录制场次 → JMeter 元素定义树。
 *
 * <p>映射规则（spec「确定性骨架转换与产物落点」+ 方案文档 6.1）：
 * ThreadGroup「录制 {ts}」(1 线程 1 迭代起步) → 内层同名 TransactionController →
 * CookieManager（三源合并；身份 = 规范化 domain + path + name，含删除语义）/
 * HeaderManager（剥浏览器固有头后的业务头并集）/ HTTPRequestDefaults（高频 origin，
 * 其余采样器完整 URL）/ HTTPSamplerProxy ×N（query 与表单 body 均拆进 arguments、
 * 其余 body 进 postData、重定向取最终、{@code 方法 路径?查询#序号} 命名、
 * UI 事件不生成元素、authorization/content-type 落采样器级头）。
 */
public final class SkeletonConverter {

    /** 浏览器固有/上下文头（小写；sec-ch-/sec-fetch- 按前缀剥离）——不进任何 HeaderManager。 */
    private static final Set<String> FIXED_HEADERS = Set.of(
            // accept 不在此列：XHR/axios 常显式设定 application/json，剥除会让回放拿到 HTML/406；
            // 浏览器默认 accept 值无害，保留进业务头并集
            "host", "content-length", "user-agent", "accept-encoding",
            "accept-language", "connection", "upgrade", "cookie",
            // 浏览器管理/请求上下文头：Origin 因目标而异，全局化会改变 CORS 行为
            "origin", "cache-control", "pragma", "if-none-match", "if-modified-since");
    private static final List<String> FIXED_PREFIXES = List.of("sec-ch-", "sec-fetch-");
    /** 凭证/内容类型头：不进全局 HeaderManager，落采样器级（cookie 归 CookieManager）。 */
    private static final Set<String> SAMPLER_LEVEL_HEADERS = Set.of("authorization", "content-type");

    public record ConversionResult(List<ElementDef> tree, int samplerCount, int requestCount,
                                   Set<String> domains, long startedAt, long endedAt) {
    }

    public ConversionResult convert(RecordingModel model) {
        List<RecordingModel.CapturedRequest> reqs = model.requests;
        String name = "录制 " + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(model.startedAt > 0 ? model.startedAt : System.currentTimeMillis()),
                        ZoneId.systemDefault()));

        ElementDef tg = ElementDef.of("threadgroup", name, Map.of(
                "numThreads", 1, "rampUp", 1, "loops", 1));
        ElementDef tc = ElementDef.of("transactioncontroller", name);

        // CookieManager：refresh（收官权威快照）优先；无 refresh 时 baseline ⊕ setCookies（含删除语义）
        List<Map<String, Object>> cookies = mergeCookies(model);
        if (!cookies.isEmpty()) {
            tc.child(ElementDef.of("cookiemanager", "录制 Cookie", Map.of(
                    "clearEachIteration", false, "cookies", cookies)));
        }

        // HeaderManager：业务头并集（剥固有头；authorization/content-type/cookie 除外）
        Map<String, String> globalHeaders = businessHeaderUnion(reqs);
        if (!globalHeaders.isEmpty()) {
            tc.child(headerManager("录制 Headers", globalHeaders));
        }

        // HTTPRequestDefaults：高频 origin（出现 ≥2 次才抽取）
        Origin top = topOrigin(reqs);
        boolean useDefaults = top != null && top.count() >= 2;
        if (useDefaults) {
            tc.child(ElementDef.of("httpdefaults", "录制 默认值", Map.of(
                    "protocol", top.protocol(), "domain", top.host(), "port", top.port())));
        }

        int seq = 0;
        Set<String> domains = new LinkedHashSet<>();
        for (RecordingModel.CapturedRequest r : reqs) {
            seq++;
            domains.add(hostOf(r.url()));
            tc.child(sampler(r, useDefaults ? top : null, seq));
        }
        tg.child(tc);

        return new ConversionResult(List.of(tg), seq, reqs.size(), domains,
                model.startedAt, model.endedAt);
    }

    // ---- 采样器 ----

    private ElementDef sampler(RecordingModel.CapturedRequest r, Origin defaults, int seq) {
        String method = r.method() == null ? "GET" : r.method();
        String contentType = headerValue(r.headers(), "content-type");
        boolean urlEncodedBody = r.postData() != null && contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("application/x-www-form-urlencoded");
        boolean getLike = "GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);

        // JMeter 语义：POST 的 arguments 会被编码成表单体，query 必须留在路径上；
        // 只有「无体的 GET 类」请求才把 query 拆进 arguments（JMeter 拼成查询串）。
        // 原始 body（JSON 等）与 query 并存时：path 保留 query、body 走 postData。
        List<Map<String, String>> arguments = new ArrayList<>();
        String path;
        if (urlEncodedBody) {
            arguments.addAll(parseUrlEncoded(r.postData()));
            path = pathWithQuery(r.url());
        } else if (r.postData() != null) {
            path = pathWithQuery(r.url());
        } else if (getLike) {
            arguments.addAll(parseQuery(r.url()));
            path = pathOf(r.url());
        } else {
            path = pathWithQuery(r.url());
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("method", method);
        props.put("path", path);
        if (defaults == null || !defaults.matches(r.url())) {
            Target t = targetOf(r.url());
            if (t != null) {
                props.put("protocol", t.protocol);
                props.put("domain", t.host);
                props.put("port", t.port);
            }
        }
        props.put("followRedirects", true);
        props.put("useKeepalive", true);
        if (!arguments.isEmpty()) {
            props.put("arguments", arguments);
        }
        if (r.postData() != null && !urlEncodedBody) {
            props.put("postData", r.postData());
        }

        ElementDef sampler = ElementDef.of("httpsampler", samplerName(r, seq), props);
        // 采样器级头：authorization（明文保留）+ content-type（随 raw body）
        Map<String, String> samplerHeaders = new LinkedHashMap<>();
        String auth = headerValue(r.headers(), "authorization");
        if (auth != null) {
            samplerHeaders.put("Authorization", auth);
        }
        if (r.postData() != null && contentType != null && !urlEncodedBody) {
            samplerHeaders.put("Content-Type", contentType);
        }
        if (!samplerHeaders.isEmpty()) {
            sampler.child(headerManager(null, samplerHeaders));
        }
        return sampler;
    }

    static String samplerName(RecordingModel.CapturedRequest r, int seq) {
        try {
            URI u = URI.create(r.url() == null ? "" : r.url());
            String p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) {
                p += "?" + u.getRawQuery();
            }
            return r.method() + " " + p + "#" + seq;
        } catch (Exception e) {
            return r.method() + " #" + seq;
        }
    }

    // ---- HeaderManager / 固有头 ----

    private static ElementDef headerManager(String name, Map<String, String> headers) {
        List<Map<String, String>> list = new ArrayList<>();
        headers.forEach((k, v) -> list.add(Map.of("name", k, "value", v)));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("headers", list);
        return ElementDef.of("headermanager", name, props);
    }

    /** 业务头并集（跨请求首见值稳定；固有头与采样器级头剥离；同名不同大小写按首见拼写去重）。 */
    static Map<String, String> businessHeaderUnion(List<RecordingModel.CapturedRequest> reqs) {
        Map<String, String> union = new LinkedHashMap<>();
        Set<String> seenLower = new LinkedHashSet<>();
        for (RecordingModel.CapturedRequest r : reqs) {
            r.headers().forEach((k, v) -> {
                String lk = k.toLowerCase(Locale.ROOT);
                if (isFixedHeader(lk) || SAMPLER_LEVEL_HEADERS.contains(lk) || !seenLower.add(lk)) {
                    return;
                }
                union.putIfAbsent(k, v);
            });
        }
        return union;
    }

    static boolean isFixedHeader(String lowerName) {
        if (FIXED_HEADERS.contains(lowerName)) {
            return true;
        }
        for (String p : FIXED_PREFIXES) {
            if (lowerName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String headerValue(Map<String, String> headers, String lowerName) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ---- URL / origin ----

    private record Target(String protocol, String host, int port) {
    }

    private record Origin(String protocol, String host, int port, int count) {
        boolean matches(String url) {
            Target t = targetOf(url);
            // protocol 必须一致
            return t != null && t.protocol.equals(protocol) && t.host.equals(host) && t.port == port;
        }
    }

    static Target targetOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getHost() == null) {
                return null;
            }
            int port = u.getPort();
            if (port < 0) {
                port = "https".equals(u.getScheme()) ? 443 : 80;
            }
            return new Target(u.getScheme() == null ? "http" : u.getScheme(), u.getHost(), port);
        } catch (Exception e) {
            return null;
        }
    }

    /** raw path（query 已拆进 arguments，路径本身保持 /a/b 形态）。 */
    static String pathOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        } catch (Exception e) {
            return url;
        }
    }

    /** 路径 + 原样查询串（POST 表单/原始体场景 query 必须随路径发送）。 */
    static String pathWithQuery(String url) {
        try {
            URI u = URI.create(url);
            String p = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            return u.getRawQuery() == null ? p : p + "?" + u.getRawQuery();
        } catch (Exception e) {
            return url;
        }
    }

    static String hostOf(String url) {
        // 单一来源：EventFilter.hostOf
        String h = org.gitee.jmeter.ai.recorder.EventFilter.hostOf(url);
        return h == null ? "" : h;
    }

    private Origin topOrigin(List<RecordingModel.CapturedRequest> reqs) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (RecordingModel.CapturedRequest r : reqs) {
            Target t = targetOf(r.url());
            if (t == null) {
                continue;
            }
            String key = t.protocol + "://" + t.host + ":" + t.port;
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            targets.putIfAbsent(key, t);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] > bestCount) {
                best = e.getKey();
                bestCount = e.getValue()[0];
            }
        }
        if (best == null) {
            return null;
        }
        Target t = targets.get(best);
        return new Origin(t.protocol, t.host, t.port, bestCount);
    }

    // ---- body / query ----

    static List<Map<String, String>> parseUrlEncoded(String body) {
        List<Map<String, String>> args = new ArrayList<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String n = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            args.add(Map.of("name", safeDecode(n), "value", safeDecode(v)));
        }
        return args;
    }

    /** URL query 拆成参数（方案文档 6.1）。 */
    static List<Map<String, String>> parseQuery(String url) {
        try {
            String q = URI.create(url).getRawQuery();
            return q == null || q.isEmpty() ? List.of() : parseUrlEncoded(q);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 残缺百分号转义（引擎按字节截断可能切在 %XX 中间）按 pair 降级保留原样，不炸整场转换。 */
    private static String safeDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    // ---- cookie 三源合并 ----

    static List<Map<String, Object>> mergeCookies(RecordingModel model) {
        // refresh = 收官时浏览器真实 jar（唯一权威终态）：有 refresh 时它就是合并结果，
        // 否则被删除（Max-Age=0/登出）的 baseline 条目会残留
        if (model.cookieRefresh != null && model.cookieRefresh.isArray()) {
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
            model.cookieRefresh.forEach(c -> putCookie(merged, c, null));
            return new ArrayList<>(merged.values());
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        if (model.cookieBaseline != null && model.cookieBaseline.isArray()) {
            model.cookieBaseline.forEach(c -> putCookie(merged, c, null));
        }
        if (model.cookieSetCookies != null) {
            model.cookieSetCookies.forEach(n -> {
                String url = n.path("url").asText(null);
                String raw = n.path("value").asText(null);
                if (raw == null) {
                    return;
                }
                for (String line : raw.split("\n")) {
                    if (!line.isBlank()) {
                        putSetCookie(merged, url, line.trim());
                    }
                }
            });
        }
        return new ArrayList<>(merged.values());
    }

    /** 快照 cookie 入库（身份 = 规范化 domain|path|name）。 */
    private static void putCookie(Map<String, Map<String, Object>> merged,
                                  com.fasterxml.jackson.databind.JsonNode c, String urlFallback) {
        String name = c.path("name").asText("");
        String domain = normalizeDomain(c.path("domain").asText(null), urlFallback);
        String path = c.path("path").asText("/");
        if (name.isEmpty()) {
            return;
        }
        Map<String, Object> cookie = new LinkedHashMap<>();
        cookie.put("name", name);
        cookie.put("value", c.path("value").asText());
        cookie.put("domain", domain);
        cookie.put("path", path.isEmpty() ? "/" : path);
        cookie.put("secure", c.path("secure").asBoolean(false));
        merged.put(cookieKey(domain, path, name), cookie);
    }

    /**
     * 解析 Set-Cookie 原始头：多值先按 \n 拆分由调用方做；
     * 无 Domain 回退 URL host、无 Path 按 RFC 6265 默认路径；Max-Age=0 / 过期 Expires = 删除。
     */
    static void putSetCookie(Map<String, Map<String, Object>> merged, String url, String raw) {
        String[] parts = raw.split(";");
        if (parts.length == 0 || !parts[0].contains("=")) {
            return;
        }
        int eq = parts[0].indexOf('=');
        String name = parts[0].substring(0, eq).trim();
        if (name.isEmpty()) {
            return;
        }
        String value = parts[0].substring(eq + 1).trim();
        String domainAttr = null;
        String pathAttr = null;
        boolean secure = false;
        boolean delete = false;
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            String lower = attr.toLowerCase(Locale.ROOT);
            if (lower.startsWith("domain=")) {
                domainAttr = attr.substring(7).trim();
            } else if (lower.startsWith("path=")) {
                pathAttr = attr.substring(5).trim();
            } else if (lower.equals("secure")) {
                secure = true;
            } else if (lower.startsWith("max-age=")) {
                try {
                    if (Long.parseLong(lower.substring(8).trim()) <= 0) {
                        delete = true;
                    }
                } catch (NumberFormatException ignored) {
                    // 非法 Max-Age 忽略
                }
            } else if (lower.startsWith("expires=")) {
                try {
                    ZonedDateTime expires = ZonedDateTime.parse(attr.substring(8).trim(),
                            DateTimeFormatter.RFC_1123_DATE_TIME);
                    if (expires.toInstant().isBefore(Instant.now())) {
                        delete = true;
                    }
                } catch (Exception ignored) {
                    // 非 RFC1123 日期：忽略过期判断
                }
            }
        }
        String domain = normalizeDomain(domainAttr, url);
        String path = pathAttr != null ? pathAttr : defaultPath(url);
        String key = cookieKey(domain, path, name);
        if (delete) {
            merged.remove(key);
            return;
        }
        Map<String, Object> cookie = new LinkedHashMap<>();
        cookie.put("name", name);
        cookie.put("value", value);
        cookie.put("domain", domain);
        cookie.put("path", path);
        cookie.put("secure", secure);
        merged.put(key, cookie);
    }

    private static String cookieKey(String domain, String path, String name) {
        return domain + "|" + (path == null || path.isEmpty() ? "/" : path) + "|" + name;
    }

    private static String normalizeDomain(String domain, String urlFallback) {
        String d = domain;
        if (d == null || d.isEmpty()) {
            d = hostOf(urlFallback == null ? "" : urlFallback);
        }
        if (d.startsWith(".")) {
            d = d.substring(1);
        }
        return d.toLowerCase(Locale.ROOT);
    }

    /** RFC 6265 §5.1.4 默认路径：request-uri path 到最后一个 '/'（无则 "/"）。 */
    static String defaultPath(String url) {
        try {
            String p = URI.create(url).getRawPath();
            if (p == null || p.isEmpty() || !p.startsWith("/")) {
                return "/";
            }
            int lastSlash = p.lastIndexOf('/');
            return lastSlash <= 0 ? "/" : p.substring(0, lastSlash);
        } catch (Exception e) {
            return "/";
        }
    }
}
