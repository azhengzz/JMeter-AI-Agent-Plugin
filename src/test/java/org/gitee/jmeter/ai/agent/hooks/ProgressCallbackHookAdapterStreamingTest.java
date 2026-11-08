package org.gitee.jmeter.ai.agent.hooks;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式事件经 adapter 转发为 PROGRESS 载荷；已流式调用的 THINKING 全文回放被
 * 按调用精确抑制（非流式回退路径保留），工具结果照常发布。
 */
class ProgressCallbackHookAdapterStreamingTest {

    private static final class RecordingCallback implements AgentLoop.ProgressCallback {
        final List<ProgressUpdate> updates = new ArrayList<>();

        @Override
        public void onProgress(ProgressUpdate update) {
            updates.add(update);
        }
    }

    @Test
    void wantsStreamingTracksCallbackPresence() {
        assertTrue(new ProgressCallbackHookAdapter(new RecordingCallback()).wantsStreaming());
    }

    @Test
    void streamDeltasForwardedAsProgressPayloads() {
        RecordingCallback callback = new RecordingCallback();
        ProgressCallbackHookAdapter adapter = new ProgressCallbackHookAdapter(callback);
        AgentHookContext context = new AgentHookContext("run-1", null, "hi");

        adapter.onStreamDelta(StreamDelta.started(7L, 1), context);
        adapter.onStreamDelta(StreamDelta.contentDelta(7L, 1, "我"), context);
        adapter.onStreamDelta(StreamDelta.reasoningDelta(7L, 1, "想"), context);
        adapter.onStreamDelta(StreamDelta.ended(7L, 1, StreamDelta.Status.SUCCESS, "我", "想"), context);

        assertEquals(4, callback.updates.size());
        assertEquals(ProgressUpdate.Type.STREAM_STARTED, callback.updates.get(0).getType());
        assertEquals(ProgressUpdate.Type.CONTENT_DELTA, callback.updates.get(1).getType());
        assertEquals(ProgressUpdate.Type.REASONING_DELTA, callback.updates.get(2).getType());
        assertEquals(ProgressUpdate.Type.STREAM_ENDED, callback.updates.get(3).getType());
        StreamDelta ended = (StreamDelta) callback.updates.get(3).getPayload();
        assertEquals(7L, ended.getStreamId());
        assertEquals(1, ended.getAttemptSeq());
        assertEquals(StreamDelta.Status.SUCCESS, ended.getStatus());
        assertEquals("我", ended.getContentText());
        assertEquals("想", ended.getReasoningText());
        assertEquals("", callback.updates.get(1).getMessage(), "流式事件 message 恒空");
    }

    @Test
    void toolStartPublishedBeforeExecutionWithToolCallList() {
        // 工具开始信号：beforeExecuteTools 对非空工具列表额外发布 TOOL_STARTED
        // （载荷 = 本批 ToolCall），GUI 先行呈现运行态行；空列表不发布
        RecordingCallback callback = new RecordingCallback();
        ProgressCallbackHookAdapter adapter = new ProgressCallbackHookAdapter(callback);
        AgentHookContext context = new AgentHookContext("run-1", null, "hi");
        context.setLastLlmResponse(LLMResponse.builder()
                .content("answer").reasoningContent("reason").finishReason("tool_calls")
                .streamed(true)
                .build());
        java.util.List<org.gitee.jmeter.ai.agent.model.ToolCall> calls =
                java.util.List.of(new org.gitee.jmeter.ai.agent.model.ToolCall("noop_tool", java.util.Map.of()));

        adapter.beforeExecuteTools(calls, context);

        assertEquals(2, callback.updates.size(), "THINKING 全文回放 + TOOL_STARTED");
        assertEquals(ProgressUpdate.Type.TOOL_STARTED, callback.updates.get(1).getType());
        assertSame(calls, callback.updates.get(1).getPayload(), "载荷 = 本批工具调用列表");

        // 空列表：只发 THINKING，不发 TOOL_STARTED
        RecordingCallback emptyCallback = new RecordingCallback();
        new ProgressCallbackHookAdapter(emptyCallback).beforeExecuteTools(
                java.util.List.of(), context);
        assertTrue(emptyCallback.updates.stream()
                        .noneMatch(u -> u.getType() == ProgressUpdate.Type.TOOL_STARTED),
                "空工具列表不发布开始信号");
    }

    @Test
    void thinkingReplayStillPublishedForStreamedCall() {
        // S9 复现与修正口径：adapter 对已流式调用<b>照常发布</b> THINKING 全文——
        // 抑制只发生在面板渲染端；程序化回调（IPC/发起方消费者）口径不因流式改变
        RecordingCallback callback = new RecordingCallback();
        ProgressCallbackHookAdapter adapter = new ProgressCallbackHookAdapter(callback);
        AgentHookContext context = new AgentHookContext("run-1", null, "hi");
        context.setLastLlmResponse(LLMResponse.builder()
                .content("answer").reasoningContent("reason").finishReason("tool_calls")
                .streamed(true)
                .build());

        adapter.beforeExecuteTools(List.of(), context);
        assertEquals(1, callback.updates.size(),
                "programmatic callback still receives full-text THINKING");
        assertEquals(ProgressUpdate.Type.THINKING, callback.updates.get(0).getType());
    }

    @Test
    void thinkingReplayKeptForNonStreamedCall() {
        RecordingCallback callback = new RecordingCallback();
        ProgressCallbackHookAdapter adapter = new ProgressCallbackHookAdapter(callback);
        AgentHookContext context = new AgentHookContext("run-1", null, "hi");
        context.setLastLlmResponse(LLMResponse.builder()
                .content("answer").reasoningContent("reason").finishReason("tool_calls")
                .build());

        adapter.beforeExecuteTools(List.of(), context);
        assertEquals(1, callback.updates.size());
        assertEquals(ProgressUpdate.Type.THINKING, callback.updates.get(0).getType());
        // showThinking 默认 false：非流式回放走 stripThink 形态（既有行为）
        assertEquals("answer", callback.updates.get(0).getMessage());
    }
}
