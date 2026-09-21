package org.gitee.jmeter.ai.ipc;

import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnContentAccumulator}：只累积 INTERMEDIATE_RESPONSE、分隔拼接、
 * 8000 截断+省略标记、并发 append 与取消时读取快照不撕裂。
 */
class TurnContentAccumulatorTest {

    @Test
    void accumulatesOnlyIntermediateResponses() {
        TurnContentAccumulator acc = new TurnContentAccumulator();
        acc.onProgress(ProgressUpdate.thinking("<think>x</think>\nthinking"));
        acc.onProgress(ProgressUpdate.toolCall("tool hint"));
        acc.onProgress(ProgressUpdate.progress("progress"));
        acc.onProgress(ProgressUpdate.error("err"));
        assertEquals("", acc.snapshotTruncated(), "non-intermediate events must not accumulate");

        acc.onProgress(ProgressUpdate.intermediateResponse("第一段中间回复"));
        acc.onProgress(ProgressUpdate.intermediateResponse("第二段中间回复"));
        assertEquals("第一段中间回复\n\n第二段中间回复", acc.snapshotTruncated());
    }

    @Test
    void nullAndEmptyEventsAreIgnored() {
        TurnContentAccumulator acc = new TurnContentAccumulator();
        acc.onProgress(null);
        acc.onProgress(ProgressUpdate.intermediateResponse(""));
        assertEquals("", acc.snapshotTruncated());
    }

    @Test
    void truncatesWithMarkerAtLimit() {
        TurnContentAccumulator acc = new TurnContentAccumulator();
        String big = "x".repeat(TurnContentAccumulator.MAX_PARTIAL_CHARS + 500);
        acc.onProgress(ProgressUpdate.intermediateResponse(big));

        String snap = acc.snapshotTruncated();
        assertEquals(TurnContentAccumulator.MAX_PARTIAL_CHARS + "\n...(truncated)".length(), snap.length());
        assertTrue(snap.endsWith("\n...(truncated)"));
    }

    @Test
    void concurrentAppendAndSnapshotAtCancel() throws Exception {
        TurnContentAccumulator acc = new TurnContentAccumulator();
        int threads = 8;
        // 总量须低于 8000 截断上限,否则快照按设计截断、段数对不上
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        acc.onProgress(ProgressUpdate.intermediateResponse("t" + id + "-" + i));
                        // 并发读取方（模拟取消时读取）不得看到撕裂串/抛异常
                        acc.snapshotTruncated();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String snap = acc.snapshotTruncated();
        long segments = snap.split("\n\n").length;
        assertEquals(threads * perThread, segments,
                "every appended segment must be present (no lost update): got " + segments);
    }
}
