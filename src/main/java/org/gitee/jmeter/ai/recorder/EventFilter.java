package org.gitee.jmeter.ai.recorder;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 采集期过滤器：resourceType 白名单 + 只采域名；以及 body 截断。
 *
 * <p>非白名单/非命中域名的请求不登记 requestId —— 引擎据此连带丢弃其响应与失败事件
 * （spec「白名单外响应事件连带丢弃」）。密码输入只记动作由注入脚本负责（2.1）。
 */
public final class EventFilter {

    private final Set<String> resourceTypes;
    private final Set<String> includeDomains;
    public final int maxBodyBytes;

    public EventFilter(Set<String> resourceTypes, Set<String> includeDomains, int maxBodyBytes) {
        this.resourceTypes = Set.copyOf(resourceTypes);
        Set<String> normalized = new LinkedHashSet<>();
        if (includeDomains != null) {
            for (String raw : includeDomains) {
                String d = normalizeDomainEntry(raw);
                if (!d.isEmpty()) {
                    normalized.add(d);
                }
            }
        }
        this.includeDomains = Set.copyOf(normalized);
        this.maxBodyBytes = maxBodyBytes;
    }

    /** 只采域名入口归一化：小写、剥 scheme/path/端口/前导点/尾点（example.com:8080 按主机匹配）。 */
    static String normalizeDomainEntry(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains("/") || s.contains(":")) {
            String host = hostOf(s.contains("://") ? s : "http://" + s);
            if (host != null) {
                s = host.toLowerCase(Locale.ROOT);
            }
        }
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** resourceType 是否命中白名单（CDP type 大小写不定，如 "XHR"/"Fetch"）。 */
    public boolean resourceTypeAllowed(String resourceType) {
        if (resourceType == null) {
            return false;
        }
        return resourceTypes.contains(resourceType.toLowerCase(Locale.ROOT));
    }

    /** URL 域名是否命中只采域名集合（空集合 = 全采；尾点 FQDN 等价归一）。 */
    public boolean domainAllowed(String url) {
        if (includeDomains.isEmpty()) {
            return true;
        }
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        String h = stripTrailingDot(host.toLowerCase(Locale.ROOT));
        for (String d : includeDomains) {
            String dl = stripTrailingDot(d.toLowerCase(Locale.ROOT));
            // example.com 命中 example.com 与 *.example.com（等价于后缀匹配）
            if (h.equals(dl) || h.endsWith("." + dl)) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    public boolean shouldCaptureRequest(String resourceType, String url) {
        return resourceTypeAllowed(resourceType) && domainAllowed(url);
    }

    /** 截断结果：值 + 是否截断（按 UTF-8 **字节**上限截断，回退到合法序列边界——spec 明文字节口径）。 */
    public record Truncated(String value, boolean truncated) {
    }

    public Truncated truncateBody(String body) {
        if (maxBodyBytes <= 0 || body == null) {
            return new Truncated(body, false);
        }
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBodyBytes) {
            return new Truncated(body, false);
        }
        int len = maxBodyBytes;
        while (len > 0) {
            try {
                String cut = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes, 0, len)).toString();
                return new Truncated(cut, true);
            } catch (java.nio.charset.CharacterCodingException e) {
                len--; // 截点落在多字节序列中间：回退到合法边界
            }
        }
        return new Truncated("", true);
    }

    public static String hostOf(String url) {
        try {
            String u = url.startsWith("http://") || url.startsWith("https://") ? url : "http://" + url;
            return URI.create(u).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
