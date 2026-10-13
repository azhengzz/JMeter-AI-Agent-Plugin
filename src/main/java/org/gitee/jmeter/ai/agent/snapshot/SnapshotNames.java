package org.gitee.jmeter.ai.agent.snapshot;

import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 快照文件命名的纯函数集（唯一来源，服务与 run_test autosave 共用）。
 *
 * <p>回合快照五段格式 {@code {yyyyMMddHHmmssSSS}-{instanceId}-{turnId}-{origin}-{计划文件名}.jmx}：
 * 紧凑定宽时间戳在前保证字典序 = 时间序；instanceId 隔离同 workspace 的双实例；
 * turnId 为进程内回合簿记；origin 标记回合来源；计划文件名（基名去扩展，未保存
 * 计划占位 {@code unsaved}）便于在多脚本工作的目录里一眼识别归属。
 *
 * <p>{@code run_test} 启动前 autosave 沿用既有前缀语义，补入 instanceId：
 * {@code {agent-run|goal-smoke}-{instanceId}-{ts}.jmx}。
 */
public final class SnapshotNames {

    /** workspace 下的快照目录名（回合快照与 run_test autosave 统一存放）。 */
    public static final String AUTOSAVE_DIR = "autosave";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneId.systemDefault());

    /** turnId 缺席（理论上不可达：缺席即不参与快照）时的降级占位段。 */
    static final String NO_TURN_TOKEN = "noturn";

    /** 计划无关联文件（未保存）时的占位段。 */
    static final String UNSAVED_PLAN_TOKEN = "unsaved";

    private SnapshotNames() {
    }

    /**
     * 回合快照文件名。
     *
     * @param time       落盘时刻（决定排序位）
     * @param instanceId 本实例标识（可为 null，降级占位）
     * @param turnId     回合标识（可为 null，降级 {@code noturn}）
     * @param origin     回合来源（可为 null，降级 {@code unknown}）
     * @param planFile   回合起点计划关联文件路径（基名去扩展；null/空白降级 {@code unsaved}）
     */
    public static String turnSnapshotFileName(Instant time, String instanceId, Long turnId,
                                              TurnOrigin origin, String planFile) {
        return STAMP.format(time)
                + "-" + safeInstanceId(instanceId)
                + "-" + (turnId == null ? NO_TURN_TOKEN : turnId.toString())
                + "-" + originToken(origin)
                + "-" + planNameToken(planFile)
                + ".jmx";
    }

    /**
     * 计划文件名段：剥目录与 .jmx 扩展（大小写不敏感）。未保存计划占位
     * {@code unsaved}——快照最常见的恰是从未保存过的计划。
     */
    static String planNameToken(String planFile) {
        if (planFile == null || planFile.isBlank()) {
            return UNSAVED_PLAN_TOKEN;
        }
        String name = planFile.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.length() > 4
                && name.regionMatches(true, name.length() - 4, ".jmx", 0, 4)) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? UNSAVED_PLAN_TOKEN : name;
    }

    /**
     * run_test 启动前 autosave 文件名（既有前缀 + instanceId + 时间戳）。
     *
     * @param prefix     既有前缀（"agent-run-" / "goal-smoke-"，含尾连字符）
     * @param instanceId 本实例标识
     * @param epochMilli 时间戳（沿用既有 System.currentTimeMillis() 语义）
     */
    public static String runAutosaveFileName(String prefix, String instanceId, long epochMilli) {
        return prefix + safeInstanceId(instanceId) + "-" + epochMilli + ".jmx";
    }

    /** 回合来源 → 文件名 token（小写短名；穷尽 switch，新增来源时编译期强制归类）。 */
    static String originToken(TurnOrigin origin) {
        if (origin == null) {
            return "unknown";
        }
        return switch (origin) {
            case LOCAL_PANEL -> "local";
            case IPC_CLI -> "cli";
            case IPC_DELEGATED -> "delegated";
            case REPUBLISH -> "republish";
        };
    }

    private static String safeInstanceId(String instanceId) {
        return (instanceId == null || instanceId.isBlank()) ? "unknown-instance" : instanceId;
    }
}
