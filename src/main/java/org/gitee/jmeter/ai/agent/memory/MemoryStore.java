package org.gitee.jmeter.ai.agent.memory;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;


/**
 * Two-layer memory storage for Agent.
 * - MEMORY.md: Long-term facts and knowledge
 * - HISTORY.md: Searchable conversation log
 */
public class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;

    /** 同 JVM 串行化:FileLock 是 JVM 级持有,同 JVM 内对同一区域重复 lock 抛
     *  {@code OverlappingFileLockException};故 JVM 锁须从获取 OS 锁一直持到 handle 关闭
     *  (与 OS 锁同生命周期)。static 而非实例级,保证同一 JVM 内多个 MemoryStore 也串行。 */
    private static final ReentrantLock INTRA_JVM_LOCK = new ReentrantLock();
    private static final String LOCK_FILE = "memory.lock";

    /** 等锁轮询间隔:abort 感知的最坏放弃延迟。轮询式 {@code tryLock} 而非阻塞
     *  {@code lock()}——原生文件锁等待对中断/abort 均不可达(对抗复核确认:
     *  {@code channel.lock()} 在 Windows 上不可中断,CompletableFuture.cancel/join 中断
     *  都够不到等锁的 commonPool 载体线程),会把 commonPool 载体饿死并泄漏。 */
    private static final long LOCK_POLL_MILLIS = 50L;

    public MemoryStore() {
        this(WorkspacePaths.resolveWorkspace());
    }

    public MemoryStore(Path workspace) {
        this.memoryDir = workspace.resolve("memory");
        this.memoryFile = memoryDir.resolve("MEMORY.md");
        this.historyFile = memoryDir.resolve("HISTORY.md");
        ensureDirectories();
    }

    private void ensureDirectories() {
        try {
            if (!Files.exists(memoryDir)) {
                Files.createDirectories(memoryDir);
                log.info("Created memory directory: {}", memoryDir);
            }
        } catch (IOException e) {
            log.error("Failed to create memory directory", e);
        }
    }

    /**
     * Read long-term memory from MEMORY.md
     */
    public String readLongTermMemory() {
        try {
            if (Files.exists(memoryFile)) {
                String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
                log.debug("Read long-term memory: {} characters", content.length());
                return content;
            }
        } catch (IOException e) {
            log.error("Error reading memory file", e);
        }
        return "";
    }

    /**
     * Write long-term memory to MEMORY.md
     *
     * @return {@code true} 写入成功;IO 故障(盘满/权限/只读目录)返回 {@code false}——
     *         调用方据此决定是否把整合标记为成功(F10:写失败被吞会让 MEMORY.md 未更新
     *         却向用户报"整合完成"并清会话,对抗复核 2/2 CONFIRMED)
     */
    public boolean writeLongTermMemory(String content) {
        try {
            String safe = sanitizeForUtf8(content != null ? content : "");
            // 全量替换写:先写临时文件再原子移动,进程在写中途被杀(如 Skip & Exit 后 JVM 退出
            // 恰逢深度提炼落盘)不留半截 MEMORY.md——移动前的旧文件始终完整。
            Path tmp = Files.createTempFile(memoryDir, "MEMORY-", ".tmp");
            try {
                Files.writeString(tmp, safe, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, memoryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, memoryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    // 清理失败(move 已成功、MEMORY.md 已是最新):不得让清理异常把
                    // "写入成功"翻成 false——否则 F10 布尔契约被破坏,调用方报"未整合"
                    // 而内容已落盘,重蒸馏出重复 HISTORY 条目。仅记录,返回值走成功路径。
                    log.warn("Failed to delete temp memory file after move (content already persisted)", e);
                }
            }
            log.info("Updated long-term memory: {} characters", safe.length());
            return true;
        } catch (IOException e) {
            log.error("Error writing memory file", e);
            return false;
        }
    }

    /**
     * 获取 MEMORY.md 的跨进程写锁,覆盖整个 read-modify-write 事务(read → LLM → write)。
     * 两个共享同一默认 workspace({@code {jmeter.home}/bin/jmeter-agent})的 JMeter 实例
     * 并发深度提炼时,后写者基于陈旧读覆盖前者、整份蒸馏静默丢失(lost-update,对抗复核
     * fix-adversarial#2 确认真实可达)。锁把读改写串行化:后写者重读到前者结果再提炼。
     *
     * <p><b>等锁可中止、可中断:</b>等锁不阻塞在原生锁调用上,而是以 {@code tryLock()}
     * 轮询(同 JVM 锁与跨进程 OS 锁皆然),每轮先检查 {@code aborted}——关闭期 Stop / 蒸馏
     * 超时置位后立即放弃(返回 {@code null},调用方按"未执行"处理),不占住 commonPool
     * 载体线程、不泄漏;线程中断同样立即放弃。改用轮询是因为阻塞式 {@code channel.lock()}
     * 对中断无响应、且 {@code cancelActiveTask} 的 interrupt / {@code CF.cancel(true)} 都
     * 够不到等锁线程(对抗复核确认,会饿死并泄漏载体线程)。
     *
     * @param aborted 等锁期间为 true 即放弃;无中止语义的调用方传 {@code () -> false}
     * @return 写锁句柄;等锁期间被中止/中断返回 {@code null}
     * @throws IOException 锁文件无法创建/打开等真实 IO 故障(调用方按 best-effort 决定)
     */
    public MemoryWriteLock lockLongTermMemory(BooleanSupplier aborted) throws IOException {
        if (!awaitJvmLock(aborted)) {
            return null;
        }
        Path lockFile = memoryDir.resolve(LOCK_FILE);
        // 通道打开放进 try 内(F1):open 抛 IOException(只读目录/锁文件不可创建)时 finally
        // 仍执行,释放刚获的 INTRA_JVM_LOCK——否则静态锁永久泄漏,后续所有 MEMORY.md 写
        // 永久轮询 tryLock 至 abort/死锁(对抗复核 F1,2/2 CONFIRMED)。
        FileChannel channel = null;
        boolean handedOff = false;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                if (aborted.getAsBoolean()) {
                    return null; // finally 释放 JVM 锁并关闭通道
                }
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    handedOff = true;
                    return new MemoryWriteLock(INTRA_JVM_LOCK, channel, lock);
                }
                try {
                    Thread.sleep(LOCK_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null; // finally 释放 JVM 锁并关闭通道
                }
            }
        } finally {
            if (!handedOff) {
                if (channel != null) { // open 抛 IOException 时通道未建,无需关闭(但要释放 JVM 锁)
                    try {
                        channel.close();
                    } catch (IOException e) {
                        log.debug("Failed to close lock channel after abandon", e);
                    }
                }
                INTRA_JVM_LOCK.unlock();
            }
        }
    }

    /** 同 JVM 锁 {@code tryLock} 轮询,直到成功或 {@code aborted}/中断。 */
    private static boolean awaitJvmLock(BooleanSupplier aborted) {
        while (true) {
            if (aborted.getAsBoolean()) {
                return false;
            }
            try {
                if (INTRA_JVM_LOCK.tryLock(LOCK_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** {@link #lockLongTermMemory(BooleanSupplier)} 返回的写锁句柄;{@link #close()} 释放
     *  OS 锁、关闭通道,再释放同 JVM 的 {@code ReentrantLock}(与获取顺序相反)。幂等:
     *  重复 {@code close()} 无副作用,{@code release()} 抛未检查异常也不会跳过
     *  {@code jvmLock.unlock()}(防静态锁永久泄漏)。 */
    public static class MemoryWriteLock implements AutoCloseable {
        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final FileLock fileLock;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private MemoryWriteLock(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                if (fileLock != null && fileLock.isValid()) {
                    fileLock.release();
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Failed to release memory write lock", e);
            }
            try {
                channel.close();
            } catch (IOException | RuntimeException e) {
                log.debug("Failed to close memory write lock channel", e);
            }
            jvmLock.unlock();
        }
    }

    /**
     * Append an entry to HISTORY.md.
     * Callers are responsible for including a [YYYY-MM-DD HH:MM] timestamp prefix.
     *
     * @return {@code true} 追加成功;IO 故障(盘满/权限/HISTORY.md 只读)返回 {@code false}——
     *         调用方据此把整合判定为失败,而非向用户报成功却丢记录(F10 契约的历史侧)。
     */
    public boolean appendHistory(String entry) {
        try {
            String formattedEntry = sanitizeForUtf8(entry) + "\n\n";
            Files.writeString(historyFile, formattedEntry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Appended to history: {} characters", formattedEntry.length());
            return true;
        } catch (IOException e) {
            log.error("Error appending to history file", e);
            return false;
        }
    }

    /**
     * Strip isolated UTF-16 surrogate chars that would make Files.writeString throw.
     * JDK's Files.writeString(path, str, UTF_8) routes through String.getBytesNoRepl(),
     * which throws UnmappableCharacterException on half surrogate pairs (common in
     * corrupted LLM output) instead of replacing them.
     */
    private static String sanitizeForUtf8(String s) {
        if (s == null || s.isEmpty()) return s;
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                sb.append(c).append(s.charAt(i + 1));
                i += 2;
            } else if (Character.isSurrogate(c)) {
                // isolated high/low surrogate — drop
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Get memory context for system prompt
     */
    public String getMemoryContext() {
        String longTerm = readLongTermMemory();
        if (!longTerm.isEmpty()) {
            return "## Long-term Memory\n" + longTerm;
        }
        return "";
    }

    /**
     * Check if memory system is enabled
     */
    public boolean isEnabled() {
        return AiConfig.getBoolean("agent.memory.enabled", true);
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    public Path getMemoryFile() {
        return memoryFile;
    }

    public Path getHistoryFile() {
        return historyFile;
    }
}
