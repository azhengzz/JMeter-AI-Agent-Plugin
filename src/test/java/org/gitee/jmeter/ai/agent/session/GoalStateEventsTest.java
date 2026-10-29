package org.gitee.jmeter.ai.agent.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoalStateEvents 广播器契约：送达 + lastKnown 缓存、逐监听者异常隔离、
 * 并发 publish 一致性、null=清除缓存、add/remove 幂等、resetForTest 隔离。
 */
class GoalStateEventsTest {

    /** 简单录音监听者：记录 (key, snapshot) 对。 */
    private static final class RecordingListener implements GoalStateEvents.Listener {
        final List<String> keys = new ArrayList<>();
        final List<GoalState.GoalSnapshot> snapshots = new ArrayList<>();
        final AtomicInteger deliveries = new AtomicInteger();

        @Override
        public void onGoalStateChanged(String sessionKey, GoalState.GoalSnapshot snapshot) {
            synchronized (this) {
                keys.add(sessionKey);
                snapshots.add(snapshot);
            }
            deliveries.incrementAndGet();
        }
    }

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        GoalStateEvents.resetForTest();
    }

    @Test
    void publishDeliversToAllListenersAndUpdatesLastKnown() {
        RecordingListener a = new RecordingListener();
        RecordingListener b = new RecordingListener();
        GoalStateEvents.addListener(a);
        GoalStateEvents.addListener(b);

        GoalState goal = GoalState.create("objective text", "summary");
        GoalStateEvents.publish("s1", goal.snapshot("s1"));

        assertEquals(1, a.deliveries.get());
        assertEquals(1, b.deliveries.get());
        assertEquals("s1", a.keys.get(0));
        assertEquals(GoalState.Status.ACTIVE, a.snapshots.get(0).status());
        assertEquals("summary", a.snapshots.get(0).uiSummary());
        assertEquals("objective text", a.snapshots.get(0).objective());
        assertEquals("s1", GoalStateEvents.lastKnown("s1").sessionKey());
        assertNull(GoalStateEvents.lastKnown("s2"), "other session keys are not cached");
    }

    @Test
    void listenerExceptionDoesNotBlockOtherListeners() {
        GoalStateEvents.Listener failing = (k, s) -> {
            throw new IllegalStateException("boom");
        };
        RecordingListener healthy = new RecordingListener();
        GoalStateEvents.addListener(failing);
        GoalStateEvents.addListener(healthy);

        GoalStateEvents.publish("s1", GoalState.create("o", null).snapshot("s1"));

        assertEquals(1, healthy.deliveries.get(), "exception in one listener must not block others");
        assertEquals(GoalState.Status.ACTIVE, GoalStateEvents.lastKnown("s1").status());
    }

    @Test
    void nullSnapshotClearsCache() {
        GoalStateEvents.publish("s1", GoalState.create("o", null).snapshot("s1"));
        assertEquals(GoalState.Status.ACTIVE, GoalStateEvents.lastKnown("s1").status());

        GoalStateEvents.publish("s1", null);
        assertNull(GoalStateEvents.lastKnown("s1"), "null snapshot clears the cached entry");
    }

    @Test
    void addRemoveListenerIdempotent() {
        RecordingListener listener = new RecordingListener();
        GoalStateEvents.addListener(listener);
        GoalStateEvents.addListener(listener); // 幂等：重复注册不重复投递

        GoalStateEvents.publish("s1", null);
        assertEquals(1, listener.keys.size(), "duplicate addListener must not double-deliver");
        assertNull(listener.snapshots.get(0), "null snapshot is delivered as-is");

        GoalStateEvents.removeListener(listener);
        GoalStateEvents.removeListener(listener); // 幂等摘除

        GoalStateEvents.publish("s1", null);
        assertEquals(1, listener.keys.size(), "removed listener receives nothing");
    }

    @Test
    void concurrentPublishHammerStaysConsistent() throws Exception {
        RecordingListener listener = new RecordingListener();
        GoalStateEvents.addListener(listener);

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int idx = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        GoalState goal = GoalState.create("o" + i, null);
                        if (i % 2 == 0) {
                            goal.pauseFromActive();
                        }
                        GoalStateEvents.publish("hammer", goal.snapshot("hammer"));
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, listener.deliveries.get(), "every publish delivers exactly once");
        GoalState.GoalSnapshot last = GoalStateEvents.lastKnown("hammer");
        Set<GoalState.Status> legal = Set.of(GoalState.Status.ACTIVE, GoalState.Status.PAUSED);
        assertTrue(last != null && legal.contains(last.status()),
                "cache holds a consistent final snapshot: " + last);
    }

    @Test
    void resetForTestClearsListenersAndCache() {
        RecordingListener listener = new RecordingListener();
        GoalStateEvents.addListener(listener);
        GoalStateEvents.publish("s1", GoalState.create("o", null).snapshot("s1"));

        GoalStateEvents.resetForTest();

        GoalStateEvents.publish("s2", null);
        assertEquals(1, listener.keys.size(), "listener detached by resetForTest");
        assertNull(GoalStateEvents.lastKnown("s1"), "cache cleared by resetForTest");
        assertNull(GoalStateEvents.lastKnown("s2"), "post-reset publish leaves no cache entry");
    }
}
