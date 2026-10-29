package org.gitee.jmeter.ai.recorder.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import org.gitee.jmeter.ai.recorder.RecorderJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * DevTools HTTP 端点探活与发现（{@code GET /json/version}）。
 *
 * <p>「命令行带 {@code --remote-debugging-port} ≠ 端点可达」——Chrome 136+ 在默认
 * user-data-dir 下静默忽略该参数，扫描命中的实例必须探活后才可附着。
 */
public final class DevToolsHttp implements Prober {
    private static final Logger log = LoggerFactory.getLogger(DevToolsHttp.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();

    /** @return 浏览器级 webSocketDebuggerUrl；不可达返回 empty。 */
    public Optional<String> probeWebSocketUrl(String endpoint) {
        return fetchVersion(normalize(endpoint)).map(v -> v.path("webSocketDebuggerUrl").asText(null))
                .filter(s -> s != null && !s.isBlank());
    }

    public Optional<JsonNode> fetchVersion(String endpoint) {
        String url = normalize(endpoint);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(2000))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(RecorderJson.parse(resp.body()));
        } catch (Exception e) {
            log.debug("DevTools endpoint {} not reachable: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /** 兼容 "9222" / "127.0.0.1:9222" / "http://host:port"（大小写不敏感）/ ".../json/version"。 */
    static String normalize(String endpoint) {
        String s = endpoint.trim();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("/json/version")) {
            return s;
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (s.matches("\\d+")) {
                s = "127.0.0.1:" + s; // 纯数字 = 端口
            }
            s = "http://" + s;
        }
        return s.replaceAll("/+$", "") + "/json/version";
    }
}
