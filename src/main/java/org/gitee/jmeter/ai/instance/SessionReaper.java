package org.gitee.jmeter.ai.instance;

import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 启动期 best-effort 回收孤立的每实例会话文件。
 *
 * <p>每实例会话启用后,异常退出(kill -9 / 强杀 / 崩溃)的实例会留下
 * {@code sessions/{pid}-{startedAtMs}.jsonl} 残留。本类在每次启动时扫描 {@code sessions/},
 * 对<b>注册表确认已失活</b>且<b>最后修改超过 TTL</b>的孤立文件保守删除;活跃实例的文件绝不触碰。
 *
 * <p>"已失活"判定(保守,经 PID 复用安全):
 * <ol>
 *   <li>从文件名解析 {@code instanceId}({@code {pid}-{startedAtMs}}),不匹配模式(如遗留
 *       {@code jmeter-ai-chat.jsonl})的跳过——它们是迁移/回退源,不归本类管。</li>
 *   <li>取 pid 查注册表 {@link InstanceRegistry#findInstance};若返回的实例存活<b>且</b>其
 *       {@code instanceId} 与文件名一致 → 归属实例仍活,跳过。其余情形(findInstance 为 null、或
 *       instanceId 不一致即 PID 已被新进程复用)→ 归属实例确已退出,列为回收候选。</li>
 *   <li>候选仅在 {@code now - lastModified > ttlMs} 时删除(会话文件使用中被追加,lastModified
 *       反映最近活动;旧孤立文件不再被触碰,自然超龄)。</li>
 * </ol>
 *
 * <p>当前实例自身的会话文件(文件名 == 当前 instanceId)恒跳过,即便其端口文件尚未写入。
 * 全程 best-effort,任何异常吞掉(不影响启动)。
 */
public final class SessionReaper {
    private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);

    /** 每实例会话文件名模式:{pid}-{startedAtMs}。不匹配者(如遗留 jmeter-ai-chat)跳过。 */
    private static final Pattern INSTANCE_ID_PATTERN = Pattern.compile("\\d+-\\d+");
    private static final String JSONL = ".jsonl";

    private SessionReaper() {
    }

    /**
     * 扫描 {@code sessionsDir},回收失活且超 TTL 的孤立会话文件。
     *
     * @param sessionsDir       会话目录({@code <workspace>/sessions})
     * @param ipcDir            IPC 目录(端口文件所在,可判活);为 null 时仅按文件名+TTL 回收
     * @param ttlMs             孤立文件最后修改距今需超过的毫秒数
     * @param currentInstanceId 当前实例的 instanceId,其会话文件恒不回收
     * @return 实际回收的文件数
     */
    public static int reap(Path sessionsDir, File ipcDir, long ttlMs, String currentInstanceId) {
        if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int reaped = 0;
        int scanned = 0;

        File[] files = sessionsDir.toFile().listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (f.isDirectory() || !f.getName().endsWith(JSONL)) {
                continue;
            }
            String instanceId = stripJsonl(f.getName());
            if (!INSTANCE_ID_PATTERN.matcher(instanceId).matches()) {
                continue; // 遗留/未知命名,交由 LegacySessionMigrator / 回退模式处理
            }
            scanned++;
            if (instanceId.equals(currentInstanceId)) {
                continue; // 自身会话,无论端口文件是否就绪都不碰
            }
            if (isLiveOwner(ipcDir, instanceId)) {
                continue; // 归属实例仍活,保留
            }
            // 归属实例已失活:再校 TTL
            if (now - f.lastModified() < ttlMs) {
                continue;
            }
            if (f.delete()) {
                reaped++;
            }
        }
        if (scanned > 0) {
            log.info("Session reap: scanned {} per-instance session file(s), removed {} stale orphan(s)",
                    scanned, reaped);
        }
        return reaped;
    }

    /**
     * 归属实例是否仍存活(且 instanceId 一致,防 PID 复用误判)。
     * {@code ipcDir} 为 null 或端口文件缺失/不可达均视为不存活(可回收,仍受 TTL 门控)。
     */
    private static boolean isLiveOwner(File ipcDir, String instanceId) {
        if (ipcDir == null) {
            return false;
        }
        String pid = instanceId.split("-", 2)[0];
        InstanceRegistry.InstanceInfo live = InstanceRegistry.findInstance(ipcDir, pid);
        return live != null && instanceId.equals(live.getInstanceId());
    }

    private static String stripJsonl(String fileName) {
        return fileName.substring(0, fileName.length() - JSONL.length());
    }
}
