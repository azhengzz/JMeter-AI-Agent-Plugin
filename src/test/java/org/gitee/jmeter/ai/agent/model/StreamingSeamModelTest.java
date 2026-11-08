package org.gitee.jmeter.ai.agent.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming seam model contracts: {@link LLMResponse} classification fields
 * default-compatible with legacy construction paths, and {@link LlmCallOptions}
 * carries the single streaming gate (listener presence) plus abort/close plumbing.
 */
class StreamingSeamModelTest {

    @Test
    void legacyFactoriesLeaveStreamingFieldsAtDefaults() {
        LLMResponse text = LLMResponse.text("hello");
        assertFalse(text.isStreamed());
        assertFalse(text.isRetryable());
        assertNull(text.getStatusCode());
        assertNull(text.getErrorKind());

        LLMResponse error = LLMResponse.error("boom");
        assertFalse(error.isStreamed());
        assertFalse(error.isRetryable());
        assertNull(error.getStatusCode());
        assertNull(error.getErrorKind());
        assertTrue(error.isError());

        LLMResponse tools = LLMResponse.withToolCalls(java.util.List.of(), "c");
        assertFalse(tools.isStreamed());
        assertFalse(tools.isRetryable());
    }

    @Test
    void builderCarriesClassificationAndStreamedFlag() {
        LLMResponse response = LLMResponse.builder()
                .content("answer")
                .finishReason("stop")
                .streamed(true)
                .build();
        assertTrue(response.isStreamed());
        assertFalse(response.isError());

        LLMResponse classified = LLMResponse.error("gateway 503", true, 503, "server");
        assertTrue(classified.isError());
        assertTrue(classified.isRetryable());
        assertEquals(503, classified.getStatusCode());
        assertEquals("server", classified.getErrorKind());
    }

    @Test
    void optionsWithoutListenerMeansNonStreaming() {
        LlmCallOptions options = LlmCallOptions.defaults();
        assertFalse(options.wantsStreaming());
        assertNull(options.getStreamDeltaListener());
        assertNull(options.getAbortCheck());
        assertNull(options.getStreamCloseRegistry());
        assertFalse(options.hasOverrides());
    }

    @Test
    void optionsCarryStreamingSeam() {
        StreamDeltaListener listener = new StreamDeltaListener() {
            @Override public void onContentDelta(String text) { }
            @Override public void onReasoningDelta(String text) { }
        };
        BooleanSupplier abort = () -> false;
        StreamCloseRegistry registry = new StreamCloseRegistry();
        LlmCallOptions options = LlmCallOptions.builder()
                .streamDeltaListener(listener)
                .abortCheck(abort)
                .streamCloseRegistry(registry)
                .build();
        assertTrue(options.wantsStreaming());
        assertSame(listener, options.getStreamDeltaListener());
        assertSame(abort, options.getAbortCheck());
        assertSame(registry, options.getStreamCloseRegistry());
    }

    @Test
    void registrationAfterCloseAllIsClosedImmediately() {
        // S7 复现：取消（closeAll）与 provider 开流注册存在交错——取消代际后注册的
        // 句柄必须立即关闭，否则停滞流重新挂上且无人再关
        StreamCloseRegistry registry = new StreamCloseRegistry();
        registry.closeAll();   // 模拟 Stop：先取消

        AtomicInteger lateClosed = new AtomicInteger();
        registry.register(lateClosed::incrementAndGet);
        assertEquals(1, lateClosed.get(), "late handle closed immediately at registration");
    }

    @Test
    void closeRegistryClosesOnceAndIsolatesFailures() {
        StreamCloseRegistry registry = new StreamCloseRegistry();
        AtomicInteger closed = new AtomicInteger();
        registry.register(closed::incrementAndGet);
        registry.register(null); // ignored
        registry.register(() -> { throw new IllegalStateException("dying stream"); });

        assertTrue(registry.isEmpty() == false);
        registry.closeAll();
        assertEquals(1, closed.get(), "handle ran exactly once");
        assertTrue(registry.isEmpty(), "handles dropped after closeAll");

        registry.closeAll(); // idempotent re-close is a no-op
        assertEquals(1, closed.get());

        // 取消代际后：clear 不能复活注册（取消位已置，新注册立即关闭）
        registry.register(closed::incrementAndGet);
        registry.clear();
        assertEquals(2, closed.get(), "post-cancel registration closes immediately");
    }

    @Test
    void rawSinkListenerIsVerboseByContract() {
        AtomicReference<String> seen = new AtomicReference<>();
        StreamDeltaListener listener = new StreamDeltaListener() {
            @Override public void onContentDelta(String text) { seen.set(text); }
            @Override public void onReasoningDelta(String text) { throw new UnsupportedOperationException(); }
        };
        listener.onContentDelta("我用<thi");
        assertEquals("我用<thi", seen.get(), "listener receives raw fragments verbatim");
        assertThrows(UnsupportedOperationException.class, () -> listener.onReasoningDelta("x"));
    }
}
