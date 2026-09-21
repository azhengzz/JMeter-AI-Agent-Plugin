package org.gitee.jmeter.ai.ipc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IpcResponse} 取消/超时载荷的序列化形状与未知字段容忍:
 * 正常响应不得带出新字段(与旧版本逐字节兼容),取消响应显式携带三件套。
 */
class IpcResponseProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void normalSuccessResponseOmitsCancelFields() throws Exception {
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(true);
        resp.setContent("done");
        resp.setDurationMs(123L);

        String json = MAPPER.writeValueAsString(resp);
        assertFalse(json.contains("cancelled"), "cancelled must not appear: " + json);
        assertFalse(json.contains("cancelReason"), "cancelReason must not appear: " + json);
        assertFalse(json.contains("partialContent"), "partialContent must not appear: " + json);

        // 与旧版本(无新字段类)响应形状逐字节一致(新字段未污染正常路径)
        assertEquals("{\"success\":true,\"content\":\"done\",\"error\":null,\"durationMs\":123,"
                + "\"toolsUsed\":null,\"iterations\":null,\"errorMessage\":null}", json);
    }

    @Test
    void cancelledResponseCarriesCancelFields() throws Exception {
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(false);
        resp.setError("turn cancelled before completion");
        resp.setCancelled(true);
        resp.setCancelReason("cancelled_by_target_user");
        resp.setPartialContent("已产生的部分内容");

        IpcResponse back = MAPPER.readValue(MAPPER.writeValueAsString(resp), IpcResponse.class);
        assertTrue(back.isCancelled());
        assertEquals("cancelled_by_target_user", back.getCancelReason());
        assertEquals("已产生的部分内容", back.getPartialContent());
        assertFalse(back.isSuccess());
        assertEquals("turn cancelled before completion", back.getError());
    }

    @Test
    void deserializationToleratesUnknownFields() throws Exception {
        // 未来/对端新增字段不得让本端 strict Jackson 反序列化失败
        String json = "{\"success\":false,\"error\":\"e\",\"cancelled\":true,"
                + "\"cancelReason\":\"timeout\",\"partialContent\":\"p\",\"someFutureField\":42}";
        IpcResponse resp = MAPPER.readValue(json, IpcResponse.class);
        assertTrue(resp.isCancelled());
        assertEquals("timeout", resp.getCancelReason());
        assertEquals("p", resp.getPartialContent());
        assertEquals("e", resp.getError());
    }

    @Test
    void absentCancelFieldsDeserializeToDefaults() throws Exception {
        // 旧版本(或正常成功)响应体:无取消字段 → 默认值
        IpcResponse resp = MAPPER.readValue(
                "{\"success\":true,\"content\":\"ok\",\"durationMs\":5}", IpcResponse.class);
        assertFalse(resp.isCancelled());
        assertNull(resp.getCancelReason());
        assertNull(resp.getPartialContent());
    }
}
