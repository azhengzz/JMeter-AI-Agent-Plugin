package org.gitee.jmeter.ai.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 单场录制的落盘：{@code {outputDir}/rec_{MMdd_HH-mm-ss}-{instanceId}/} 下
 * {@code events.jsonl}（逐行 flush 追加，崩溃不丢已写行）与 {@code cookies.json}
 * （收官时一次写入：基线快照 ∪ 录制期 Set-Cookie 累积 ∪ 停止时刷新，三者由引擎合并后传入）。
 *
 * <p>instanceId 段照抄 SnapshotNames 先例，隔离双实例共享 workspace 的同秒开局。
 */
public final class RecordingStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RecordingStore.class);
    /** 定宽 yyyyMMdd 前缀保证字典序 = 时间序（跨年不失效，对齐 SnapshotNames 先例）。 */
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static final String EVENTS_FILE = "events.jsonl";
    public static final String COOKIES_FILE = "cookies.json";

    private final Path dir;
    private final Path eventsFile;
    private final BufferedWriter writer;
    private boolean closed;

    private RecordingStore(Path dir, BufferedWriter writer) {
        this.dir = dir;
        this.eventsFile = dir.resolve(EVENTS_FILE);
        this.writer = writer;
    }

    /** 创建新的录制目录（含已存在同秒目录时的 -1/-2 递增去重）。 */
    public static RecordingStore create(Path outputDir, String instanceId) throws IOException {
        Files.createDirectories(outputDir);
        String base = "rec_" + LocalDateTime.now().format(DIR_FMT) + "-" + instanceId;
        Path dir = outputDir.resolve(base);
        for (int i = 1; Files.exists(dir); i++) {
            dir = outputDir.resolve(base + "-" + i);
        }
        Files.createDirectories(dir);
        BufferedWriter w = Files.newBufferedWriter(dir.resolve(EVENTS_FILE), StandardCharsets.UTF_8);
        log.info("Recording store created: {}", dir);
        return new RecordingStore(dir, w);
    }

    public synchronized void append(RecordedEvent event) throws IOException {
        if (closed) {
            return;
        }
        writer.write(event.toJsonLine());
        writer.newLine();
        writer.flush();
    }

    /** 收官时写 cookie 产物（幂等：覆盖式）。 */
    public synchronized void writeCookies(JsonNode cookies) {
        try {
            if (closed) {
                return;
            }
            RecorderJson.MAPPER.writeValue(dir.resolve(COOKIES_FILE).toFile(), cookies);
        } catch (IOException e) {
            log.warn("Failed to write cookies.json: {}", e.getMessage());
        }
    }

    public synchronized void writeSummary(JsonNode summary) {
        try {
            if (closed) {
                return;
            }
            RecorderJson.MAPPER.writeValue(dir.resolve("summary.json").toFile(), summary);
        } catch (IOException e) {
            log.warn("Failed to write summary.json: {}", e.getMessage());
        }
    }

    public Path dir() {
        return dir;
    }

    public Path eventsFile() {
        return eventsFile;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            writer.close();
        }
    }

    /** 取消路径：关闭并删除整场目录（预备态取消不产生任何产物）。 */
    public synchronized void deleteQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // best-effort 删除
        }
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        } catch (IOException e) {
            log.warn("Failed to delete cancelled recording dir {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 读取 jsonl（测试与转换器共用；空行跳过）。
     *
     * <p>按 {@code \n} 字节切行后**逐行解码**：崩溃若把最后一行截断在多字节 UTF-8 字符中间，
     * 整文件解码（readAllLines）会抛 MalformedInputException 导致整场不可读；
     * 逐行解码让撕裂尾行按既有策略丢弃，中间行解码/JSON 失败仍拒绝。
     */
    public static List<RecordedEvent> readEvents(Path eventsFile) throws IOException {
        byte[] bytes = Files.readAllBytes(eventsFile);
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        List<byte[]> lines = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                lines.add(java.util.Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            lines.add(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        }
        List<RecordedEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            boolean last = i == lines.size() - 1;
            byte[] raw = lines.get(i);
            String line;
            try {
                line = decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                if (last) {
                    log.debug("Dropping torn trailing jsonl line (invalid UTF-8) in {}", eventsFile);
                    continue;
                }
                throw new IOException("Corrupt jsonl line " + (i + 1) + " (invalid UTF-8) in "
                        + eventsFile, e);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                events.add(RecordedEvent.parse(line));
            } catch (Exception e) {
                if (last) {
                    // 崩溃窗口内写了一半的尾行（无完整换行即进程被杀）：静默丢弃
                    log.debug("Dropping torn trailing jsonl line in {}", eventsFile);
                } else {
                    throw new IOException("Corrupt jsonl line " + (i + 1) + " in " + eventsFile
                            + ": " + e.getMessage(), e);
                }
            }
        }
        return events;
    }
}
