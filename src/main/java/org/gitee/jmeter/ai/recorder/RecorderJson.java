package org.gitee.jmeter.ai.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 录制模块共享的 Jackson 实例（只读序列化，无需额外特性配置）。
 */
public final class RecorderJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private RecorderJson() {
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }
}
