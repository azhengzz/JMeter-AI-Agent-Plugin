package org.gitee.jmeter.ai.instance;

import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.gitee.jmeter.ai.utils.AiConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

/**
 * 进程单例:持有本次 JVM 启动生成的 {@code instanceId},作为每实例 session key 与实例注册表的锚点。
 *
 * <p>{@code instanceId} 格式 {@code {pid}-{startedAtMs}},其中:
 * <ul>
 *   <li>{@code pid} —— {@link InstanceRegistry#currentPid()},跨实例并存区分;</li>
 *   <li>{@code startedAtMs} —— {@link RuntimeMXBean#getStartTime()} JVM 真实启动时间戳,
 *       保证 PID 被 OS 复用时也不继承上一次启动遗留的会话文件。</li>
 * </ul>
 *
 * <p>这是 session key 的<b>单一来源</b>:GUI 聊天与 IPC {@code /agent} 端点都读
 * {@link #currentSessionKey()},消除原先两处各自硬编码的 {@code "jmeter-ai-chat"} 漂移。
 * 关闭期记忆整合、委派寻址等同样经此获取当前实例标识。
 *
 * <p>线程安全:幂等双重检查初始化;生成后只读。{@code get()} 在未显式 {@link #init()} 时
 * 会惰性初始化(取 JVM 启动时间,故无论何时触发,单进程内值恒定),以容忍 Swing 初始化顺序
 * 的不确定性——正常路径由 {@code SelectionInitCommand} 在 {@code ADD_ALL} 显式 {@link #init()}。
 */
public final class InstanceContext {
    /** 旧的全局 session key;{@code agent.session.per-instance=false} 回退时使用。 */
    public static final String LEGACY_SESSION_KEY = "jmeter-ai-chat";

    private static volatile InstanceContext instance;

    private final String instanceId;
    private final String pid;
    private final long startedAtMs;

    private InstanceContext(String instanceId, String pid, long startedAtMs) {
        this.instanceId = instanceId;
        this.pid = pid;
        this.startedAtMs = startedAtMs;
    }

    /**
     * 幂等生成进程单例。正常在 {@code SelectionInitCommand} 处理 {@code ADD_ALL} 时调用一次;
     * 重复调用返回既有实例。
     */
    public static synchronized InstanceContext init() {
        if (instance == null) {
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            String pid = InstanceRegistry.currentPid();
            long startedAt = bean.getStartTime();
            instance = new InstanceContext(pid + "-" + startedAt, pid, startedAt);
        }
        return instance;
    }

    /**
     * 获取已初始化的进程单例;未显式 {@link #init()} 时惰性初始化。
     */
    public static InstanceContext get() {
        InstanceContext ic = instance;
        if (ic != null) {
            return ic;
        }
        return init();
    }

    /** 是否已初始化(便于测试与启动序判断)。 */
    public static boolean isInitialized() {
        return instance != null;
    }

    /** 仅用于测试重置。生产代码不应调用。 */
    static synchronized void resetForTest() {
        instance = null;
    }

    /**
     * 当前实例的进程级标识(原始 instanceId)。
     */
    public static String instanceId() {
        return get().instanceId;
    }

    /**
     * 当前 session key:每实例会话启用(默认)时返回 {@link #instanceId()},否则回退到
     * 全局 {@link #LEGACY_SESSION_KEY}(backcompat)。
     */
    public static String currentSessionKey() {
        if (AiConfig.isSessionPerInstance()) {
            return get().instanceId;
        }
        return LEGACY_SESSION_KEY;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getPid() {
        return pid;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    @Override
    public String toString() {
        return "InstanceContext{instanceId='" + instanceId + "', pid=" + pid + ", startedAtMs=" + startedAtMs + '}';
    }
}
