package org.gitee.jmeter.ai.service.provider;

import com.openai.models.chat.completions.ChatCompletionChunk;
import org.gitee.jmeter.ai.agent.model.StreamCloseRegistry;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 停滞流 + Stop 关闭机制：阻塞读不响应线程中断，取消路径只能靠 close 句柄打断。
 * 以阻塞迭代器模拟停滞的 SSE 流，closeAll 打开后读取循环及时退出，已聚合的
 * 部分内容保留（截断流经缺终止标记路径判可重试失败）。
 */
class StalledStreamCloseTest {

    /** hasNext() 阻塞直至 close 句柄打开的停滞流迭代器。 */
    private static final class StalledIterator implements java.util.Iterator<ChatCompletionChunk> {
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean released;
        private boolean servedContent;

        void release() {
            release.countDown();
        }

        @Override
        public boolean hasNext() {
            if (!released) {
                try {
                    // 模拟阻塞读：只有 close 才能打断（不会等到中断）
                    release.await(10, TimeUnit.SECONDS);
                    released = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !servedContent;
        }

        @Override
        public ChatCompletionChunk next() {
            // close 后流以截断告终：不再有终止 chunk
            servedContent = true;
            return contentChunk("partial answer");
        }
    }

    private static ChatCompletionChunk contentChunk(String text) {
        return ChatCompletionChunk.builder()
                .id("c1")
                .created(0L)
                .model("test-model")
                .addChoice(ChatCompletionChunk.Choice.builder()
                        .index(0L)
                        .finishReason(java.util.Optional.empty())
                        .delta(ChatCompletionChunk.Choice.Delta.builder()
                                .content(text)
                                .build())
                        .build())
                .build();
    }

    @Test
    void closeAllUnblocksStalledReadAndPreservesPartialContent() throws Exception {
        StalledIterator stalled = new StalledIterator();
        StreamCloseRegistry registry = new StreamCloseRegistry();
        registry.register(stalled::release);

        List<String> seen = new ArrayList<>();
        StreamDeltaListener listener = new StreamDeltaListener() {
            @Override public void onContentDelta(String text) { seen.add(text); }
            @Override public void onReasoningDelta(String text) { }
        };

        AtomicBoolean done = new AtomicBoolean(false);
        OpenAICompatibleProvider.StreamAggregation[] result = new OpenAICompatibleProvider.StreamAggregation[1];
        Thread reader = new Thread(() -> {
            Stream<ChatCompletionChunk> stalledStream = StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(stalled, Spliterator.ORDERED), false);
            try {
                result[0] = OpenAICompatibleProvider.aggregateStream(stalledStream, listener, null);
            } finally {
                done.set(true);
            }
        });
        reader.start();

        // 等读取循环进入停滞（内容 chunk 在 release 后才到）
        Thread.sleep(200);
        assertTrue(reader.isAlive(), "reader is stalled in blocking read");

        // 取消路径：closeAll 打开闸门
        registry.closeAll();
        reader.join(5000);
        assertTrue(done.get(), "read loop exited promptly after closeAll");

        assertNotNull(result[0]);
        assertEquals("partial answer", result[0].content, "already-received partial content preserved");
        assertTrue(!result[0].sawFinishReason(), "truncated stream lacks finish_reason -> caller classifies retryable eof");
    }
}
