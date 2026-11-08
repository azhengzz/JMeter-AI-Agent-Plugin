package org.gitee.jmeter.ai.service;

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.InputJsonDelta;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RawMessageStopEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.ThinkingDelta;
import com.anthropic.models.messages.ToolUseBlock;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClaudeService 流式聚合与错误分类的离线单测（脚本化事件流，无网络）。
 */
class ClaudeServiceStreamingTest {

    private static RawMessageStreamEvent textDelta(long index, String text) {
        return RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                .index(index)
                .delta(RawContentBlockDelta.ofText(TextDelta.builder().text(text).build()))
                .build());
    }

    private static RawMessageStreamEvent thinkingDelta(long index, String text) {
        return RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                .index(index)
                .delta(RawContentBlockDelta.ofThinking(ThinkingDelta.builder().thinking(text).build()))
                .build());
    }

    private static RawMessageStreamEvent jsonDelta(long index, String partial) {
        return RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                .index(index)
                .delta(RawContentBlockDelta.ofInputJson(InputJsonDelta.builder().partialJson(partial).build()))
                .build());
    }

    private static RawMessageStreamEvent toolBlockStart(long index, String id, String name) {
        return RawMessageStreamEvent.ofContentBlockStart(RawContentBlockStartEvent.builder()
                .index(index)
                .contentBlock(RawContentBlockStartEvent.ContentBlock.ofToolUse(
                        ToolUseBlock.builder()
                                .id(id)
                                .name(name)
                                .caller(com.anthropic.models.messages.DirectCaller.builder().build())
                                .input(JsonValue.from(Map.of()))
                                .build()))
                .build());
    }

    private static RawMessageStreamEvent messageDelta(StopReason stopReason, long outputTokens) {
        return RawMessageStreamEvent.ofMessageDelta(RawMessageDeltaEvent.builder()
                .delta(RawMessageDeltaEvent.Delta.builder()
                        .stopReason(stopReason)
                        .stopDetails(java.util.Optional.empty())
                        .stopSequence(java.util.Optional.empty())
                        .build())
                .usage(com.anthropic.models.messages.MessageDeltaUsage.builder()
                        .outputTokens(outputTokens)
                        .cacheCreationInputTokens(java.util.Optional.empty())
                        .cacheReadInputTokens(java.util.Optional.empty())
                        .inputTokens(java.util.Optional.empty())
                        .outputTokensDetails(java.util.Optional.empty())
                        .serverToolUse(java.util.Optional.empty())
                        .build())
                .build());
    }

    private static RawMessageStreamEvent messageStop() {
        return RawMessageStreamEvent.ofMessageStop(RawMessageStopEvent.builder().build());
    }

    private static final class RecordingListener implements StreamDeltaListener {
        final List<String> content = new ArrayList<>();
        final List<String> reasoning = new ArrayList<>();

        @Override public void onContentDelta(String text) { content.add(text); }
        @Override public void onReasoningDelta(String text) { reasoning.add(text); }
    }

    @Test
    void aggregatesTextThinkingToolsStopAndUsage() {
        RecordingListener listener = new RecordingListener();
        LlmCallOptions options = LlmCallOptions.builder().streamDeltaListener(listener).build();
        ClaudeService.ClaudeStreamAggregation agg = ClaudeService.aggregateClaudeStream(Stream.of(
                thinkingDelta(0L, "先想"),
                textDelta(1L, "我用"),
                textDelta(1L, "HTTP"),
                toolBlockStart(2L, "tu_1", "get_tree"),
                jsonDelta(2L, "{\"pa"),
                jsonDelta(2L, "th\":1}"),
                toolBlockStart(3L, "tu_2", "run"),
                messageDelta(StopReason.TOOL_USE, 42L),
                messageStop()
        ), options);

        assertEquals("我用HTTP", agg.content.toString());
        assertEquals("先想", agg.reasoning.toString());
        assertTrue(agg.sawMessageStop);
        assertEquals(42L, agg.outputTokens);
        assertTrue(agg.usageSeen, "message_delta usage marks usage as seen");
        assertEquals(Optional.of(StopReason.TOOL_USE), agg.stopReason);
        assertEquals(List.of("我用", "HTTP"), listener.content);
        assertEquals(List.of("先想"), listener.reasoning);

        List<org.gitee.jmeter.ai.agent.model.ToolCall> calls =
                ClaudeService.buildToolCallsFromClaudeBuffers(agg.toolBuffers);
        assertEquals(2, calls.size());
        assertEquals("get_tree", calls.get(0).getName());
        assertEquals(Map.of("path", 1), calls.get(0).getArguments());
        assertEquals("tu_1", calls.get(0).getId());
    }

    @Test
    void missingMessageStopLeavesAggregationIncomplete() {
        ClaudeService.ClaudeStreamAggregation agg = ClaudeService.aggregateClaudeStream(
                Stream.of(textDelta(0L, "truncated")), null);
        assertFalse(agg.sawMessageStop, "truncated stream must be classified by caller as retryable eof");
    }

    @Test
    void missingUsageIsNotFabricatedAsZero() {
        // R12 复现：流中无任何 usage 事件（无 message_start/message_delta usage）
        // ——usageSeen=false，调用方以空 map 表达"未收到"，不得以 0/0 冒充
        // （0/0 会被当真实用量清零上下文指示）
        ClaudeService.ClaudeStreamAggregation agg = ClaudeService.aggregateClaudeStream(
                Stream.of(textDelta(0L, "答案"), messageStop()), null);
        assertFalse(agg.usageSeen, "stream without usage events must not count as usage-seen");
    }

    @Test
    void abortBetweenEventsThrowsMarker() {
        RecordingListener listener = new RecordingListener();
        AtomicBoolean abort = new AtomicBoolean(false);
        LlmCallOptions options = LlmCallOptions.builder()
                .streamDeltaListener(listener)
                .abortCheck(abort::get)
                .build();
        Stream<RawMessageStreamEvent> events = Stream.of(
                        textDelta(0L, "a"), textDelta(0L, "b"))
                .peek(e -> { if (!listener.content.isEmpty()) abort.set(true); });
        assertThrows(ClaudeService.StreamingAbortException.class,
                () -> ClaudeService.aggregateClaudeStream(events, options));
        assertEquals(List.of("a"), listener.content);
    }

    @Test
    void toolBufferEdges() {
        TreeMap<Long, ClaudeService.ClaudeToolBuffer> buffers = new TreeMap<>();
        ClaudeService.ClaudeToolBuffer noJson = new ClaudeService.ClaudeToolBuffer();
        noJson.id = "t1";
        noJson.name = "empty";
        buffers.put(0L, noJson);
        ClaudeService.ClaudeToolBuffer malformed = new ClaudeService.ClaudeToolBuffer();
        malformed.id = "t2";
        malformed.name = "broken";
        malformed.partialJson.append("{oops");
        buffers.put(1L, malformed);
        ClaudeService.ClaudeToolBuffer noName = new ClaudeService.ClaudeToolBuffer();
        buffers.put(2L, noName);

        List<org.gitee.jmeter.ai.agent.model.ToolCall> calls =
                ClaudeService.buildToolCallsFromClaudeBuffers(buffers);
        assertEquals(1, calls.size(), "nameless and malformed buffers skipped");
        assertEquals("empty", calls.get(0).getName());
        assertEquals(Map.of(), calls.get(0).getArguments(), "empty partial_json defaults to {}");
    }

    // ---- 错误分类 ----

    private static AnthropicServiceException serviceException(int code, String message) {
        return new AnthropicServiceException(message, null) {
            @Override public int statusCode() { return code; }
            @Override public com.anthropic.core.http.Headers headers() { return null; }
            @Override public JsonValue body() { return null; }
        };
    }

    @Test
    void socketTimeoutClassifiesAsRetryableTimeoutBeforeInterrupt() {
        LLMResponse timeout = ClaudeService.classifyClaudeError(
                new RuntimeException(new SocketTimeoutException("read timeout")));
        assertTrue(timeout.isRetryable());
        assertEquals("timeout", timeout.getErrorKind());
    }

    @Test
    void authNotRetryableRateLimitAndServerRetryable() {
        LLMResponse auth = ClaudeService.classifyClaudeError(serviceException(401, "bad key"));
        assertFalse(auth.isRetryable());
        assertEquals("auth", auth.getErrorKind());

        LLMResponse rate = ClaudeService.classifyClaudeError(serviceException(429, "slow"));
        assertTrue(rate.isRetryable());
        assertEquals(429, rate.getStatusCode());

        LLMResponse server = ClaudeService.classifyClaudeError(serviceException(529, "overloaded"));
        assertTrue(server.isRetryable());
        assertEquals("server", server.getErrorKind());
    }

    @Test
    void interruptAndAbortNotRetryable() {
        LLMResponse interrupted = ClaudeService.classifyClaudeError(
                new RuntimeException(new java.io.InterruptedIOException("cancelled")));
        assertFalse(interrupted.isRetryable());
        assertEquals("Interrupted", interrupted.getErrorMessage());

        LLMResponse aborted = ClaudeService.classifyClaudeError(new ClaudeService.StreamingAbortException());
        assertEquals("Interrupted", aborted.getErrorMessage());
        assertEquals("aborted", aborted.getErrorKind());
    }

    @Test
    void genericIoIsRetryableNetwork() {
        LLMResponse network = ClaudeService.classifyClaudeError(
                new RuntimeException(new java.io.IOException("reset")));
        assertTrue(network.isRetryable());
        assertEquals("network", network.getErrorKind());
    }
}
