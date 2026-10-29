package org.gitee.jmeter.ai.recorder.convert;

import com.fasterxml.jackson.databind.JsonNode;
import org.gitee.jmeter.ai.recorder.RecorderJson;
import org.gitee.jmeter.ai.recorder.RecordingStore;
import org.gitee.jmeter.ai.recorder.model.RecordedEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 录制场次解析模型：events.jsonl 重建 + requestId 配对还原（重定向取最终 hop），
 * 连同 cookies.json 三源（baseline/refresh/setCookies）与 summary.json。
 */
public final class RecordingModel {

    /** 单个捕获请求的完整视图（request/response/body/failed 按 requestId 配对）。 */
    public record CapturedRequest(String requestId, String method, String url, String resourceType,
                                  Map<String, String> headers, String postData, boolean postDataTruncated,
                                  Integer status, Map<String, String> responseHeaders,
                                  String body, boolean bodyTruncated, boolean base64Encoded,
                                  String errorText, int hops, int pageId) {
    }

    public final List<RecordedEvent> events;
    public final List<CapturedRequest> requests;
    public final List<RecordedEvent> uiEvents;
    /** cookies.json 三源（缺文件/字段为 null）。 */
    public final JsonNode cookieBaseline;
    public final JsonNode cookieRefresh;
    public final JsonNode cookieSetCookies;
    public final long startedAt;
    public final long endedAt;
    public final Path dir;

    private RecordingModel(List<RecordedEvent> events, List<CapturedRequest> requests,
                           List<RecordedEvent> uiEvents, JsonNode baseline, JsonNode refresh,
                           JsonNode setCookies, long startedAt, long endedAt, Path dir) {
        this.events = events;
        this.requests = requests;
        this.uiEvents = uiEvents;
        this.cookieBaseline = baseline;
        this.cookieRefresh = refresh;
        this.cookieSetCookies = setCookies;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.dir = dir;
    }

    /** 从录制目录加载（events.jsonl 必在；cookies/summary 缺失按空处理）。 */
    public static RecordingModel load(Path recordingDir) throws IOException {
        List<RecordedEvent> events = RecordingStore.readEvents(recordingDir.resolve(RecordingStore.EVENTS_FILE));
        JsonNode cookies = readJson(recordingDir.resolve(RecordingStore.COOKIES_FILE));
        JsonNode summary = readJson(recordingDir.resolve("summary.json"));
        long startedAt = summary == null ? 0 : summary.path("startedAt").asLong(0);
        long endedAt = summary == null ? 0 : summary.path("endedAt").asLong(0);
        JsonNode baseline = cookies == null ? null : cookies.path("baseline");
        JsonNode refresh = cookies == null ? null : cookies.path("refresh");
        JsonNode setCookies = cookies == null ? null : cookies.path("setCookies");
        return new RecordingModel(events, pairRequests(events), filterUi(events),
                isNullNode(baseline) ? null : baseline, isNullNode(refresh) ? null : refresh,
                setCookies == null || !setCookies.isArray() ? null : setCookies,
                startedAt, endedAt, recordingDir);
    }

    /** 供单测/内存事件流直接构造。 */
    public static RecordingModel of(List<RecordedEvent> events, JsonNode cookieBaseline,
                                    JsonNode cookieRefresh, JsonNode cookieSetCookies,
                                    long startedAt, long endedAt) {
        return new RecordingModel(events, pairRequests(events), filterUi(events),
                cookieBaseline, cookieRefresh, cookieSetCookies, startedAt, endedAt, null);
    }

    /** 配对键 = pageId|requestId（requestId 仅在单页面会话内唯一，跨页撞键互不串扰）。 */
    static List<CapturedRequest> pairRequests(List<RecordedEvent> events) {
        Map<String, Builder> byId = new LinkedHashMap<>();
        for (RecordedEvent e : events) {
            String rid = str(e.data().get("requestId"));
            String key = e.pageId() + "|" + rid;
            switch (e.type()) {
                case "request" -> {
                    Builder b = byId.computeIfAbsent(key, k -> new Builder(rid, e.pageId()));
                    b.method = str(e.data().get("method"));
                    b.url = str(e.data().get("url"));
                    b.resourceType = str(e.data().get("resourceType"));
                    b.headers = headersOf(e.data().get("headers"));
                    b.postData = str(e.data().get("postData"));
                    b.postDataTruncated = Boolean.TRUE.equals(e.data().get("postDataTruncated"));
                    b.hops++;
                }
                case "response" -> {
                    Builder b = byId.get(key);
                    if (b != null) {
                        b.status = intOf(e.data().get("status"));
                        b.responseHeaders = headersOf(e.data().get("headers"));
                    }
                }
                case "requestPostData" -> {
                    // CDP 对过大 POST 体不在 requestWillBeSent 内联：引擎异步补拉（宽限窗内同样收）
                    Builder b = byId.get(key);
                    if (b != null) {
                        b.postData = str(e.data().get("postData"));
                        b.postDataTruncated = Boolean.TRUE.equals(e.data().get("postDataTruncated"));
                    }
                }
                case "responseBody" -> {
                    Builder b = byId.get(key);
                    if (b != null) {
                        b.body = str(e.data().get("body"));
                        b.bodyTruncated = Boolean.TRUE.equals(e.data().get("bodyTruncated"));
                        b.base64Encoded = Boolean.TRUE.equals(e.data().get("base64Encoded"));
                    }
                }
                case "requestfailed" -> {
                    Builder b = byId.get(key);
                    if (b != null) {
                        b.errorText = str(e.data().get("errorText"));
                    }
                }
                default -> {
                }
            }
        }
        List<CapturedRequest> out = new ArrayList<>();
        for (Builder b : byId.values()) {
            out.add(new CapturedRequest(b.id, b.method, b.url, b.resourceType, b.headers,
                    b.postData, b.postDataTruncated, b.status, b.responseHeaders, b.body,
                    b.bodyTruncated, b.base64Encoded, b.errorText, b.hops, b.pageId));
        }
        return out;
    }

    private static List<RecordedEvent> filterUi(List<RecordedEvent> events) {
        List<RecordedEvent> ui = new ArrayList<>();
        for (RecordedEvent e : events) {
            if ("click".equals(e.type()) || "change".equals(e.type()) || "submit".equals(e.type())) {
                ui.add(e);
            }
        }
        return ui;
    }

    private static final class Builder {
        final String id;
        final int pageId;
        String method = "GET";
        String url = "";
        String resourceType = "";
        Map<String, String> headers = Map.of();
        String postData;
        boolean postDataTruncated;
        Integer status;
        Map<String, String> responseHeaders = Map.of();
        String body;
        boolean bodyTruncated;
        boolean base64Encoded;
        String errorText;
        int hops;

        Builder(String id, int pageId) {
            this.id = id;
            this.pageId = pageId;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headersOf(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return out;
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static boolean isNullNode(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private static JsonNode readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return RecorderJson.MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            return null;
        }
    }
}
