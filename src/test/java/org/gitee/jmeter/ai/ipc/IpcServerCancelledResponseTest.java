package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IpcServer#cancelledResponse}：409/504 结构化取消响应体形状——
 * cancelled/cancelReason 必带，partialContent 有累积才带；用户 STOP 与超时
 * 两种 cancelReason 可程序化区分。
 */
class IpcServerCancelledResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void userStopCarriesReasonAndPartialContent() throws Exception {
        TurnContentAccumulator acc = new TurnContentAccumulator();
        acc.onProgress(ProgressUpdate.intermediateResponse("中间回复甲"));

        IpcResponse resp = IpcServer.cancelledResponse(
                IpcResponse.CANCEL_REASON_USER_STOP, acc, "turn cancelled before completion");

        assertFalse(resp.isSuccess());
        assertTrue(resp.isCancelled());
        assertEquals(IpcResponse.CANCEL_REASON_USER_STOP, resp.getCancelReason());
        assertEquals("中间回复甲", resp.getPartialContent());
        assertEquals("turn cancelled before completion", resp.getError());

        String json = MAPPER.writeValueAsString(resp);
        assertTrue(json.contains("\"cancelled\":true"));
        assertTrue(json.contains("\"cancelReason\":\"cancelled_by_target_user\""));
        assertTrue(json.contains("\"partialContent\":\"中间回复甲\""));
    }

    @Test
    void timeoutWithoutAccumulationOmitsPartialContent() throws Exception {
        IpcResponse resp = IpcServer.cancelledResponse(
                IpcResponse.CANCEL_REASON_TIMEOUT, new TurnContentAccumulator(),
                "agent timeout after 120000ms (turn cancelled)");

        assertTrue(resp.isCancelled());
        assertEquals(IpcResponse.CANCEL_REASON_TIMEOUT, resp.getCancelReason());
        assertNull(resp.getPartialContent(), "no accumulation → field omitted, not empty string");

        String json = MAPPER.writeValueAsString(resp);
        assertFalse(json.contains("partialContent"), "empty partial must not ship: " + json);
    }

    @Test
    void truncatedAccumulationIsCarried() {
        TurnContentAccumulator acc = new TurnContentAccumulator();
        acc.onProgress(ProgressUpdate.intermediateResponse("y".repeat(TurnContentAccumulator.MAX_PARTIAL_CHARS + 100)));

        IpcResponse resp = IpcServer.cancelledResponse(
                IpcResponse.CANCEL_REASON_USER_STOP, acc, "e");
        assertTrue(resp.getPartialContent().endsWith("\n...(truncated)"));
    }
}
