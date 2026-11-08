package org.gitee.jmeter.ai.service.provider;

import com.sun.net.httpserver.HttpServer;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S10 复现与验收：不支持流式的兼容网关（对一切流式请求 400、非流式正常）——
 * 零增量失败须静默回退非流式（不伪装流式、streamed=false），而非直接报错。
 */
class StreamingSilentFallbackTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 伪网关：请求体含 "stream" 字段 → 400；否则返回可用 completion。 */
    private int startPseudoGateway(AtomicInteger streamingRequests, AtomicInteger nonStreamingRequests) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean streaming = body.contains("\"stream\"");
            byte[] response;
            int status;
            if (streaming) {
                streamingRequests.incrementAndGet();
                status = 400;
                response = "{\"error\":{\"message\":\"streaming is not supported by this gateway\"}}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                nonStreamingRequests.incrementAndGet();
                status = 200;
                response = ("{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"FALLBACK-OK\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void unsupportedStreamingSilentlyFallsBackToNonStreaming() throws Exception {
        AtomicInteger streamingRequests = new AtomicInteger();
        AtomicInteger nonStreamingRequests = new AtomicInteger();
        int port = startPseudoGateway(streamingRequests, nonStreamingRequests);

        LLMResponse response = callProvider(port);

        assertTrue(streamingRequests.get() >= 1, "streaming was attempted first");
        assertEquals(1, nonStreamingRequests.get(),
                "zero-delta streaming failure silently falls back to one non-streaming request");
        assertEquals("FALLBACK-OK", response.getContent());
        assertFalse(response.isStreamed(), "fallback response is not disguised as streamed");
        assertFalse(response.isError());
    }

    @Test
    void fallbackFailureKeepsRetryableClassification() throws Exception {
        // R7 复现：流式 500（零增量）静默回退非流式，非流式同样 500——回退不得
        // 丢失可重试分类（否则一次可恢复的 500 进不了外层三次重试预算）
        AtomicInteger streamingRequests = new AtomicInteger();
        AtomicInteger nonStreamingRequests = new AtomicInteger();
        int port = startStatusGateway(500, streamingRequests, nonStreamingRequests);

        LLMResponse response = callProvider(port);

        assertTrue(nonStreamingRequests.get() >= 1, "fallback request happened");
        assertTrue(response.isError(), "gateway error surfaced");
        assertTrue(response.isRetryable(), "5xx after fallback stays retryable, actual kind="
                + response.getErrorKind());
        assertEquals(500, response.getStatusCode());
    }

    @Test
    void authErrorDoesNotFallBackToNonStreaming() throws Exception {
        // R8 复现：流式 401（鉴权定性错误）不得静默回退——换非流式重发既浪费
        // 请求也可能掩盖配置错误（此处伪网关非流式会答 200，若回退即"成功"）
        AtomicInteger streamingRequests = new AtomicInteger();
        AtomicInteger nonStreamingRequests = new AtomicInteger();
        int port = startStatusGateway(401, streamingRequests, nonStreamingRequests);

        LLMResponse response = callProvider(port);

        assertTrue(streamingRequests.get() >= 1, "streaming was attempted first");
        assertEquals(0, nonStreamingRequests.get(),
                "definitive auth error must not trigger a second (non-streaming) request");
        assertTrue(response.isError(), "401 surfaced as error");
        assertFalse(response.isRetryable(), "auth is not retryable");
        assertEquals(401, response.getStatusCode());
        assertEquals("auth", response.getErrorKind());
    }

    private static LLMResponse callProvider(int port) {
        ProviderSpec spec = new ProviderSpec.Builder()
                .name("fallbackgw")
                .displayName("fallbackgw")
                .defaultApiBase("http://127.0.0.1:" + port)
                .envKey("fallbackgw.api.key")
                .build();
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(spec);
        LlmCallOptions options = LlmCallOptions.builder()
                .streamDeltaListener(new StreamDeltaListener() {
                    @Override public void onContentDelta(String text) { }
                    @Override public void onReasoningDelta(String text) { }
                })
                .build();
        return provider.generateResponseWithTools(
                List.of(Message.user("hi")),
                List.<ToolDefinition>of(),
                options);
    }

    /** 伪网关：流式与非流式请求一律回给定 HTTP 状态（R7/R8 回退资格与分类探针）。 */
    private int startStatusGateway(int status, AtomicInteger streamingRequests,
            AtomicInteger nonStreamingRequests) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (body.contains("\"stream\"")) {
                streamingRequests.incrementAndGet();
            } else {
                nonStreamingRequests.incrementAndGet();
            }
            byte[] response = "{\"error\":{\"message\":\"gateway status probe\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }
}
