package org.gitee.jmeter.ai.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨进程写锁回归测试(fix-adversarial#2):两个共享同一 workspace 的"实例"(两个
 * {@link MemoryStore} 对象)并发对 MEMORY.md 做 read-modify-write 时,写锁把整个
 * 读改写串行化——后写者重读到前者结果再追加,双方蒸馏都存活。
 *
 * <p>无锁时两条线程都在对方写入前读走空内容,后写者基于陈旧读覆盖前者,必有
 * 一方蒸馏静默丢失(该测试会失败)。
 */
class MemoryStoreWriteLockTest {

    @TempDir
    Path workspace;

    @Test
    void concurrentReadModifyWriteUnderLockKeepsBothWrites() throws Exception {
        MemoryStore instanceA = new MemoryStore(workspace);
        MemoryStore instanceB = new MemoryStore(workspace); // 第二个实例共享同一 workspace

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread ta = new Thread(() -> distillLikeInstance(instanceA, "# factA\n", start, failure));
        Thread tb = new Thread(() -> distillLikeInstance(instanceB, "# factB\n", start, failure));
        ta.start();
        tb.start();
        start.countDown();
        ta.join(30_000);
        tb.join(30_000);

        assertFalse(ta.isAlive(), "thread A did not finish (lock deadlock?)");
        assertFalse(tb.isAlive(), "thread B did not finish (lock deadlock?)");
        Throwable distilFailure = failure.get();
        assertNull(distilFailure, "unexpected exception during distillation: " + distilFailure);

        String finalContent = Files.readString(workspace.resolve("memory/MEMORY.md"));
        assertTrue(finalContent.contains("# factA"), "instance A distillation lost: " + finalContent);
        assertTrue(finalContent.contains("# factB"), "instance B distillation lost: " + finalContent);
    }

    /** 模拟一个实例的深度提炼:锁内 read → (耗时放大并发窗口) → write。 */
    private void distillLikeInstance(MemoryStore store, String fact,
                                     CountDownLatch start, AtomicReference<Throwable> failure) {
        try {
            start.await();
            try (MemoryStore.MemoryWriteLock ignored = store.lockLongTermMemory(() -> false)) {
                String current = store.readLongTermMemory();
                Thread.sleep(100); // 放大 read→write 窗口,无锁时两条线程都会读到旧值
                store.writeLongTermMemory(current + fact);
            }
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    /**
     * 跨进程互斥的真实回归(fix-adversarial#2 依赖的那一半):fork 一个子 JVM 独占
     * memory.lock,本进程等锁时置 abort 谓词——若 OS 级 {@link FileLock} 确实跨进程互斥,
     * 本进程在子进程释放前拿不到锁、abort 后放弃返回 {@code null};若锁退化/回归(如改锁
     * 每实例文件、或整个 OS 锁被移除),本进程 tryLock 立即成功、result 非 null → 测试失败。
     * 同 JVM 测试只验静态 INTRA_JVM_LOCK,验证不了这一半(已确认的测试盲区)。
     */
    @Test
    void osLockIsSharedAcrossProcessesAndAbortIsHonored() throws Exception {
        String javaExe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(javaExe, "-cp", System.getProperty("java.class.path"),
                MemoryStoreWriteLockTest.class.getName() + "$ChildLockHolder",
                workspace.toString(), "5000")
                .redirectErrorStream(true)
                .start();

        try {
            AtomicReference<String> ready = new AtomicReference<>();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;
                    StringBuilder prefix = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().equals("CHILD_READY")) {
                            ready.set("CHILD_READY");
                            return;
                        }
                        prefix.append(line).append('\n'); // 前导日志行:循环跳过而非单次 readLine(F5)
                    }
                    ready.set("<EOF before CHILD_READY; output so far: " + prefix + ">");
                } catch (IOException e) {
                    ready.set("<io-error: " + e + ">");
                }
            });
            readerThread.start();
            readerThread.join(30_000);
            assertFalse(readerThread.isAlive(), "child JVM never reported CHILD_READY");
            assertEquals("CHILD_READY", ready.get());

            AtomicBoolean aborted = new AtomicBoolean(false);
            AtomicBoolean polled = new AtomicBoolean(false); // 握手:waiter 真正进入等锁轮询后置位
            AtomicReference<MemoryStore.MemoryWriteLock> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    result.set(new MemoryStore(workspace).lockLongTermMemory(() -> {
                        polled.set(true);
                        return aborted.get();
                    }));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            waiter.start();
            // 握手而非盲等(F3):等到 waiter 已进入 lockLongTermMemory 轮询(谓词首次被调用)
            // 再断言其被子进程 OS 锁挡住——盲等 300ms 在 waiter 慢启动/被调度延迟时会空真通过。
            long deadline = System.currentTimeMillis() + 10_000;
            while (!polled.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(polled.get(), "waiter never entered lock polling (child JVM may have failed)");
            Thread.sleep(150); // 留一轮 50ms 轮询余量,确保已撞上子进程持有的 OS 锁
            assertNull(failure.get(), "unexpected exception in waiter: " + failure.get());
            assertNull(result.get(),
                    "waiter acquired lock while child holds it — OS FileLock not shared across processes?");
            assertTrue(waiter.isAlive(), "waiter finished while child still holds OS lock");

            aborted.set(true);
            waiter.join(10_000);

            assertFalse(waiter.isAlive(), "lock waiter did not abandon on abort (deadlock?)");
            assertNull(failure.get(), "unexpected exception in waiter: " + failure.get());
            assertNull(result.get(),
                    "expected abort while child holds OS lock — non-null means OS FileLock is not shared across processes");
        } finally {
            child.destroyForcibly();
        }
    }

    /**
     * F1 回归:锁文件 open 抛 IOException(锁文件被目录占位)时,静态 INTRA_JVM_LOCK
     * 必须释放——否则后续所有 lockLongTermMemory 永久轮询 tryLock 至 abort/死锁。
     * 无修复时(open 在 try 外)第二次调用会永久阻塞 → join 超时 → isAlive 断言失败。
     */
    @Test
    void openFailureReleasesIntraJvmLock() throws Exception {
        Path memoryDir = Files.createDirectories(workspace.resolve("memory"));
        Path lockFile = memoryDir.resolve("memory.lock");
        Files.createDirectory(lockFile); // 用目录占位:open(CREATE, WRITE) 抛 IOException

        MemoryStore store = new MemoryStore(workspace);
        try {
            store.lockLongTermMemory(() -> false);
            fail("expected IOException opening lock file occupied by a directory");
        } catch (IOException expected) {
            // 预期:open 失败异常传播;JVM 锁必须已由 finally 释放
        }

        Files.delete(lockFile); // 还原为可写文件

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                try (MemoryStore.MemoryWriteLock lock = store.lockLongTermMemory(() -> false)) {
                    store.writeLongTermMemory("after-fix");
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.start();
        t.join(10_000);

        assertFalse(t.isAlive(), "lock leaked after open failure — INTRA_JVM_LOCK never released (F1)");
        assertNull(failure.get(), "unexpected exception: " + failure.get());
        assertTrue(Files.readString(memoryDir.resolve("MEMORY.md")).contains("after-fix"));
    }

    /**
     * 子进程入口:独占 memory.lock 并打印 {@code CHILD_READY},保持 holdMs 后释放。
     */
    public static final class ChildLockHolder {
        public static void main(String[] args) throws Exception {
            Path workspace = Paths.get(args[0]);
            long holdMs = Long.parseLong(args[1]);
            try (MemoryStore.MemoryWriteLock lock = new MemoryStore(workspace).lockLongTermMemory(() -> false)) {
                System.out.println("CHILD_READY");
                System.out.flush();
                Thread.sleep(holdMs);
            }
        }
    }
}
