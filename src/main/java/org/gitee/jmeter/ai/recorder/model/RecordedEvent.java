package org.gitee.jmeter.ai.recorder.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gitee.jmeter.ai.recorder.RecorderJson;

import java.util.Map;

/**
 * 录制事件（jsonl 每行一条）。事件统一为 {seq, ts, pageId, type, ...载荷}，
 * 载荷平铺进同一对象（对齐 dsh 的事件形态，转换器按 type 取字段）。
 *
 * <p>type ∈ navigate / click / change / submit / request / response / responseBody / requestfailed。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedEvent(long seq, long ts, int pageId, String type, Map<String, Object> data) {

    public static RecordedEvent of(long seq, int pageId, String type, Map<String, Object> data) {
        return new RecordedEvent(seq, System.currentTimeMillis(), pageId, type, data);
    }

    public String toJsonLine() {
        ObjectNode node = RecorderJson.MAPPER.createObjectNode();
        node.put("seq", seq);
        node.put("ts", ts);
        node.put("pageId", pageId);
        node.put("type", type);
        if (data != null) {
            data.forEach((k, v) -> {
                if (v == null) {
                    return;
                }
                if (v instanceof String s) {
                    node.put(k, s);
                } else if (v instanceof Integer i) {
                    node.put(k, i);
                } else if (v instanceof Long l) {
                    node.put(k, l);
                } else if (v instanceof Double d) {
                    node.put(k, d);
                } else if (v instanceof Boolean b) {
                    node.put(k, b);
                } else if (v instanceof Number n) {
                    node.put(k, n.doubleValue());
                } else {
                    node.set(k, RecorderJson.MAPPER.valueToTree(v));
                }
            });
        }
        return node.toString();
    }

    public static RecordedEvent parse(String jsonLine) {
        ObjectNode n = (ObjectNode) RecorderJson.parse(jsonLine);
        long seq = n.path("seq").asLong();
        long ts = n.path("ts").asLong();
        int pageId = n.path("pageId").asInt();
        String type = n.path("type").asText();
        ObjectNode payload = n.deepCopy();
        payload.remove("seq");
        payload.remove("ts");
        payload.remove("pageId");
        payload.remove("type");
        Map<String, Object> data = RecorderJson.MAPPER.convertValue(
                payload, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        return new RecordedEvent(seq, ts, pageId, type, data);
    }
}
