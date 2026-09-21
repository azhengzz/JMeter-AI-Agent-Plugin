package org.gitee.jmeter.ai.intellisense;

import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天输入框 {@code @} 实例点名的建议来源:后台获取本机其他存活 JMeter 实例
 * (经 {@link InstanceRegistry#listInstances} 的 TCP+PID 双确认,排除自身),
 * 供 {@link InputBoxIntellisense} 在词首 {@code @} 触发时弹出选择。
 *
 * <p><b>线程契约</b>:
 * <ul>
 *   <li>{@link #getSuggestions} 只在 EDT 调用,只读 volatile 快照做内存过滤,绝不阻塞
 *       (注册表探活含 500ms/实例的 TCP 探测,绝不能上 EDT);</li>
 *   <li>快照缺失或超过 TTL 时向单线程 executor 投递一次后台刷新,完成后经
 *       {@link SwingUtilities#invokeLater}(可注入分发器,便于单测)通知刷新回调,
 *       由 {@link InputBoxIntellisense} 决定是否就地更新弹窗;</li>
 *   <li>{@code jmeter.ai.ipc.enabled=false} 时恒返回空建议({@code @} 不触发实例选择)。</li>
 * </ul>
 *
 * <p><b>token 契约</b>:弹窗行 display 为 {@code @{instanceId} · jmx 文件名 · pid},
 * 选中插入 {@code @{instanceId} }(带尾随空格)。发送侧经
 * {@link #parseInstanceMentions} 用同一快照把消息里的 @token 解析回结构化实例引用。
 */
public class InstanceMentionProvider implements MentionSuggestionProvider {

    private static final Logger log = LoggerFactory.getLogger(InstanceMentionProvider.class);

    /** 快照存活期:期内直接复用,过期后触发后台刷新(旧快照照常服务直至新值到达)。 */
    private static final long SNAPSHOT_TTL_MS = 10_000L;

    /** 弹窗行内启动时间的展示格式(本地时区,仅时分秒)。 */
    private static final DateTimeFormatter STARTED_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 词首 @ + 连续非空白片段。词首口径与 InputBoxIntellisense.findTriggerIndex 对齐:
     * {@code \\p{L}\\p{Nd}} == Character.isLetterOrDigit;另含代理项区间——Java 的单字符类
     * lookbehind 只看前一个 code unit,增补平面字母(如 U+20000)以低代理项收尾,
     * 不拦会与触发侧(codePointBefore 判定整对为字母)不一致。
     */
    private static final Pattern MENTION_TOKEN =
            Pattern.compile("(?<![\\p{L}\\p{Nd}\\x{D800}-\\x{DFFF}])@(\\S+)");

    /** 进程共享的单线程刷新 executor;守护线程,不阻断 JVM 退出。 */
    private static final Executor SHARED_FETCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instance-mention-fetch");
        t.setDaemon(true);
        return t;
    });

    private final Supplier<List<InstanceInfo>> instancesSupplier;
    private final String selfInstanceId;
    private final Executor fetchExecutor;
    private final Consumer<Runnable> edtDispatcher;
    private final List<Runnable> refreshCallbacks = new ArrayList<>();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    private volatile List<InstanceInfo> snapshot = List.of();
    private volatile long fetchedAt = 0L;

    /**
     * 生产构造:实例列表来自共享 IPC 注册表,自身 id 取进程单例 {@link InstanceContext}。
     */
    public InstanceMentionProvider() {
        this(() -> InstanceRegistry.listInstances(
                        InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()))),
                InstanceContext.instanceId(),
                SHARED_FETCH_EXECUTOR,
                SwingUtilities::invokeLater);
    }

    /**
     * 测试构造:全部协作者可注入(supplier、自身 id、刷新 executor、EDT 分发器)。
     */
    InstanceMentionProvider(Supplier<List<InstanceInfo>> instancesSupplier, String selfInstanceId,
                            Executor fetchExecutor, Consumer<Runnable> edtDispatcher) {
        this.instancesSupplier = Objects.requireNonNull(instancesSupplier);
        this.selfInstanceId = selfInstanceId == null ? "" : selfInstanceId;
        this.fetchExecutor = Objects.requireNonNull(fetchExecutor);
        this.edtDispatcher = Objects.requireNonNull(edtDispatcher);
    }

    @Override
    public List<IntellisenseSuggestion> getSuggestions(String prefix) {
        if (!AiConfig.isIpcEnabled()) {
            return List.of();
        }
        maybeRefresh();
        String typed = prefix.length() > 1 ? prefix.substring(1) : "";
        String lower = typed.toLowerCase(Locale.ROOT);
        List<IntellisenseSuggestion> result = new ArrayList<>();
        for (InstanceInfo info : snapshot) {
            if (matches(info, lower)) {
                result.add(toSuggestion(info));
            }
        }
        return result;
    }

    @Override
    public synchronized void addRefreshCallback(Runnable callback) {
        refreshCallbacks.add(callback);
    }

    /** 当前快照(排除自身后的存活实例,不可变);发送侧解析 @token 时复用。 */
    public List<InstanceInfo> getSnapshot() {
        return snapshot;
    }

    /** 用自身快照解析消息文本里的 @token(见 {@link #parseInstanceMentions})。 */
    public List<InstanceInfo> parseMentions(String text) {
        return parseInstanceMentions(text, snapshot, selfInstanceId);
    }

    /**
     * 纯函数:扫描文本中词首 {@code @token},精确匹配非自身存活实例的 instanceId,
     * 去重保序返回结构化引用;不匹配(含自身 id、失联实例、邮箱类 {@code @})一律忽略。
     */
    public static List<InstanceInfo> parseInstanceMentions(String text, List<InstanceInfo> liveInstances,
                                                           String selfInstanceId) {
        if (text == null || text.isEmpty() || liveInstances == null || liveInstances.isEmpty()) {
            return List.of();
        }
        String self = selfInstanceId == null ? "" : selfInstanceId;
        Map<String, InstanceInfo> byId = new LinkedHashMap<>();
        for (InstanceInfo info : liveInstances) {
            if (info.getInstanceId() != null && !self.equals(info.getInstanceId())) {
                byId.putIfAbsent(info.getInstanceId(), info);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, InstanceInfo> found = new LinkedHashMap<>();
        Matcher m = MENTION_TOKEN.matcher(text);
        while (m.find()) {
            InstanceInfo hit = byId.get(m.group(1));
            if (hit != null) {
                found.putIfAbsent(m.group(1), hit);
            }
        }
        return List.copyOf(found.values());
    }

    /** 快照缺失/过期时投递一次后台刷新;刷新中不重复投递。 */
    private void maybeRefresh() {
        if (System.currentTimeMillis() - fetchedAt <= SNAPSHOT_TTL_MS
                || !refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        fetchExecutor.execute(() -> {
            List<InstanceInfo> fetched;
            try {
                fetched = instancesSupplier.get().stream()
                        .filter(i -> !selfInstanceId.equals(i.getInstanceId()))
                        .toList();
            } catch (Exception e) {
                // 探活/读取失败:保留旧快照,只推进 fetchedAt 限流重试。
                // 留诊断信号——持续失败(如 jmeterHome 未初始化)与"无对端实例"不可区分时无从排查
                log.debug("Instance mention refresh failed, keeping stale snapshot", e);
                fetched = snapshot;
            }
            snapshot = fetched;
            fetchedAt = System.currentTimeMillis();
            refreshInFlight.set(false);
            fireRefresh();
        });
    }

    private synchronized void fireRefresh() {
        for (Runnable callback : refreshCallbacks) {
            edtDispatcher.accept(callback);
        }
    }

    /** 子串过滤(大小写不敏感,匹配 instanceId 或 jmx 文件名);typed 为空匹配全部。 */
    private static boolean matches(InstanceInfo info, String lowerTyped) {
        if (lowerTyped.isEmpty()) {
            return true;
        }
        if (info.getInstanceId() != null
                && info.getInstanceId().toLowerCase(Locale.ROOT).contains(lowerTyped)) {
            return true;
        }
        String jmxName = jmxFileName(info);
        return jmxName.toLowerCase(Locale.ROOT).contains(lowerTyped);
    }

    private static IntellisenseSuggestion toSuggestion(InstanceInfo info) {
        return new IntellisenseSuggestion(
                "@" + info.getInstanceId() + " · " + jmxFileName(info) + " · pid " + info.getPid()
                        + " · since " + formatStarted(info.getStartedAt()),
                "@" + info.getInstanceId() + " ");
    }

    private static String formatStarted(long startedAtMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(startedAtMs), ZoneId.systemDefault())
                .format(STARTED_FORMAT);
    }

    /** jmx 文件名(无路径);未打开计划时显示占位。 */
    private static String jmxFileName(InstanceInfo info) {
        String jmx = info.getJmxPath();
        if (jmx == null || jmx.isEmpty()) {
            return "(no plan open)";
        }
        String name = new File(jmx).getName();
        return name.isEmpty() ? "(no plan open)" : name;
    }
}
