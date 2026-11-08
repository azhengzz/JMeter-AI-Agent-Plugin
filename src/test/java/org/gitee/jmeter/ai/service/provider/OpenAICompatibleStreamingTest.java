package org.gitee.jmeter.ai.service.provider;

import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
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
 * OpenAI 兼容路径流式聚合与错误分类的离线单测（脚本化 chunk 流，无网络）。
 */
class OpenAICompatibleStreamingTest {

    // ---- chunk 构造 helpers ----

    private static ChatCompletionChunk contentChunk(String text) {
        return ChatCompletionChunk.builder()
                .id("c1").created(0L).model("test-model")
                .addChoice(ChatCompletionChunk.Choice.builder()
                        .index(0L)
                        .finishReason(java.util.Optional.empty())
                        .delta(ChatCompletionChunk.Choice.Delta.builder()
                                .content(text)
                                .build())
                        .build())
                .build();
    }

    private static ChatCompletionChunk reasoningChunk(String text) {
        return ChatCompletionChunk.builder()
                .id("c1").created(0L).model("test-model")
                .addChoice(ChatCompletionChunk.Choice.builder()
                        .index(0L)
                        .finishReason(java.util.Optional.empty())
                        .delta(ChatCompletionChunk.Choice.Delta.builder()
                                .putAdditionalProperty("reasoning_content", JsonValue.from(text))
                                .build())
                        .build())
                .build();
    }

    private static ChatCompletionChunk toolFragmentChunk(int index, String id, String name, String argsPart) {
        ChatCompletionChunk.Choice.Delta.ToolCall.Builder fragBuilder =
                ChatCompletionChunk.Choice.Delta.ToolCall.builder().index((long) index);
        if (id != null) fragBuilder.id(id);
        ChatCompletionChunk.Choice.Delta.ToolCall.Function.Builder fnBuilder =
                ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder();
        if (name != null) fnBuilder.name(name);
        if (argsPart != null) fnBuilder.arguments(argsPart);
        return ChatCompletionChunk.builder()
                .id("c1").created(0L).model("test-model")
                .addChoice(ChatCompletionChunk.Choice.builder()
                        .index(0L)
                        .finishReason(java.util.Optional.empty())
                        .delta(ChatCompletionChunk.Choice.Delta.builder()
                                .addToolCall(fragBuilder.function(fnBuilder.build()).build())
                                .build())
                        .build())
                .build();
    }

    private static ChatCompletionChunk finishChunk(String finishReason) {
        return ChatCompletionChunk.builder()
                .id("c1").created(0L).model("test-model")
                .addChoice(ChatCompletionChunk.Choice.builder()
                        .index(0L)
                        .delta(ChatCompletionChunk.Choice.Delta.builder().build())
                        .finishReason(ChatCompletionChunk.Choice.FinishReason.of(finishReason))
                        .build())
                .build();
    }

    private static ChatCompletionChunk usageOnlyChunk(long prompt, long completion) {
        return ChatCompletionChunk.builder()
                .id("c1").created(0L).model("test-model")
                .choices(List.of())
                .usage(com.openai.models.completions.CompletionUsage.builder()
                        .promptTokens(prompt)
                        .completionTokens(completion)
                        .totalTokens(prompt + completion)
                        .build())
                .build();
    }

    private static final class RecordingListener implements StreamDeltaListener {
        final List<String> content = new ArrayList<>();
        final List<String> reasoning = new ArrayList<>();

        @Override public void onContentDelta(String text) { content.add(text); }
        @Override public void onReasoningDelta(String text) { reasoning.add(text); }
    }

    // ---- aggregateStream ----

    @Test
    void aggregatesContentReasoningToolsUsageAndFinishReason() {
        RecordingListener listener = new RecordingListener();
        OpenAICompatibleProvider.StreamAggregation agg = OpenAICompatibleProvider.aggregateStream(Stream.of(
                reasoningChunk("先分析"),
                contentChunk("我用"),
                contentChunk("HTTP"),
                toolFragmentChunk(0, "call_1", "get_tree", "{\"pa"),
                toolFragmentChunk(0, null, null, "th\":1}"),
                toolFragmentChunk(1, "call_2", "run", "{}"),
                finishChunk("tool_calls"),
                usageOnlyChunk(120, 30)
        ), listener, null);

        assertEquals("我用HTTP", agg.content);
        assertEquals("先分析", agg.reasoning);
        assertEquals("tool_calls", agg.finishReason);
        assertEquals(Map.of("prompt_tokens", 120, "completion_tokens", 30), agg.usage);
        assertEquals(List.of("我用", "HTTP"), listener.content, "raw fragments delivered verbatim in order");
        assertEquals(List.of("先分析"), listener.reasoning);

        List<org.gitee.jmeter.ai.agent.model.ToolCall> calls =
                OpenAICompatibleProvider.buildToolCallsFromBuffers(agg.toolBuffers);
        assertEquals(2, calls.size());
        assertEquals("get_tree", calls.get(0).getName());
        assertEquals(Map.of("path", 1), calls.get(0).getArguments());
        assertEquals("call_1", calls.get(0).getId());
        assertEquals("run", calls.get(1).getName());
    }

    @Test
    void usageOnlyTailChunkIsAcceptedNotAnError() {
        OpenAICompatibleProvider.StreamAggregation agg = OpenAICompatibleProvider.aggregateStream(
                Stream.of(contentChunk("hi"), finishChunk("stop"), usageOnlyChunk(10, 5)),
                null, null);
        assertEquals("stop", agg.finishReason);
        assertEquals(Map.of("prompt_tokens", 10, "completion_tokens", 5), agg.usage);
        assertEquals("hi", agg.content);
    }

    @Test
    void missingFinishReasonLeavesAggregationIncomplete() {
        OpenAICompatibleProvider.StreamAggregation agg = OpenAICompatibleProvider.aggregateStream(
                Stream.of(contentChunk("truncated")), null, null);
        assertFalse(agg.sawFinishReason(), "truncated stream must be classified by caller as retryable eof");
    }

    @Test
    void abortPredicateIsConsultedPerChunk() {
        // 3 chunks; abort flips true after first delivered
        RecordingListener listener = new RecordingListener();
        AtomicBoolean abort = new AtomicBoolean(false);
        Stream<ChatCompletionChunk> chunks = Stream.of(contentChunk("a"), contentChunk("b"), contentChunk("c"))
                .peek(c -> { if (!listener.content.isEmpty()) abort.set(true); });
        assertThrows(OpenAICompatibleProvider.StreamingAbortException.class,
                () -> OpenAICompatibleProvider.aggregateStream(chunks, listener, abort::get));
        assertEquals(List.of("a"), listener.content, "first chunk delivered, abort stops before the second");
    }

    // ---- 错误分类 ----

    private static OpenAIServiceException serviceException(int code, String message) {
        return new OpenAIServiceException(message, null) {
            @Override public int statusCode() { return code; }
            @Override public com.openai.core.http.Headers headers() { return null; }
            @Override public JsonValue body() { return null; }
            @Override public Optional<String> code() { return Optional.empty(); }
            @Override public Optional<String> param() { return Optional.empty(); }
            @Override public Optional<String> type() { return Optional.empty(); }
        };
    }

    @Test
    void authAndInvalidRequestAreNotRetryable() {
        LLMResponse auth = OpenAICompatibleProvider.classifyStreamingError(serviceException(401, "bad key"));
        assertFalse(auth.isRetryable());
        assertEquals("auth", auth.getErrorKind());
        assertEquals(401, auth.getStatusCode());

        LLMResponse invalid = OpenAICompatibleProvider.classifyStreamingError(serviceException(400, "bad param"));
        assertFalse(invalid.isRetryable());
        assertEquals("invalid_request", invalid.getErrorKind());
    }

    @Test
    void rateLimitAndServerAreRetryable() {
        LLMResponse rate = OpenAICompatibleProvider.classifyStreamingError(serviceException(429, "slow down"));
        assertTrue(rate.isRetryable());
        assertEquals("rate_limit", rate.getErrorKind());

        LLMResponse server = OpenAICompatibleProvider.classifyStreamingError(serviceException(503, "unavailable"));
        assertTrue(server.isRetryable());
        assertEquals("server", server.getErrorKind());
    }

    @Test
    void socketTimeoutClassifiesAsRetryableTimeoutBeforeInterrupt() {
        // SocketTimeoutException extends InterruptedIOException — must classify as timeout, not "Interrupted"
        LLMResponse timeout = OpenAICompatibleProvider.classifyStreamingError(
                new RuntimeException(new SocketTimeoutException("read timed out")));
        assertTrue(timeout.isRetryable());
        assertEquals("timeout", timeout.getErrorKind());
    }

    @Test
    void interruptAndAbortAreNotRetryable() {
        LLMResponse interrupted = OpenAICompatibleProvider.classifyStreamingError(
                new RuntimeException(new java.io.InterruptedIOException("timeout... cancelled")));
        assertFalse(interrupted.isRetryable());
        assertEquals("Interrupted", interrupted.getErrorMessage());

        LLMResponse aborted = OpenAICompatibleProvider.classifyStreamingError(
                new OpenAICompatibleProvider.StreamingAbortException());
        assertFalse(aborted.isRetryable());
        assertEquals("Interrupted", aborted.getErrorMessage());
        assertEquals("aborted", aborted.getErrorKind());
    }

    @Test
    void genericIoIsRetryableNetwork() {
        LLMResponse network = OpenAICompatibleProvider.classifyStreamingError(
                new RuntimeException(new java.io.IOException("connection reset")));
        assertTrue(network.isRetryable());
        assertEquals("network", network.getErrorKind());
    }

    // ---- stream_options 拒绝判定 ----

    @Test
    void streamOptionsRejectionDetection() {
        assertTrue(OpenAICompatibleProvider.isStreamOptionsRejected(
                serviceException(400, "Unknown parameter: 'stream_options.include_usage'")));
        assertFalse(OpenAICompatibleProvider.isStreamOptionsRejected(
                serviceException(400, "model not found")));
        assertFalse(OpenAICompatibleProvider.isStreamOptionsRejected(
                serviceException(500, "stream_options blew up")));
    }

    // ---- 工具缓冲解析边界 ----

    @Test
    void toolBufferEdges() {
        TreeMap<Integer, OpenAICompatibleProvider.StreamingToolCall> buffers = new TreeMap<>();
        OpenAICompatibleProvider.StreamingToolCall noName = new OpenAICompatibleProvider.StreamingToolCall();
        noName.args.append("{}");
        buffers.put(0, noName);
        OpenAICompatibleProvider.StreamingToolCall malformed = new OpenAICompatibleProvider.StreamingToolCall();
        malformed.name = "broken";
        malformed.args.append("{not json");
        buffers.put(1, malformed);
        OpenAICompatibleProvider.StreamingToolCall noId = new OpenAICompatibleProvider.StreamingToolCall();
        noId.name = "ok";
        noId.args.append("");
        buffers.put(2, noId);

        List<org.gitee.jmeter.ai.agent.model.ToolCall> calls =
                OpenAICompatibleProvider.buildToolCallsFromBuffers(buffers);
        assertEquals(1, calls.size(), "nameless and malformed fragments skipped");
        assertEquals("ok", calls.get(0).getName());
        assertEquals(Map.of(), calls.get(0).getArguments(), "empty args default to {}");
        assertTrue(calls.get(0).getId().startsWith("call_stream_"));
    }
}
