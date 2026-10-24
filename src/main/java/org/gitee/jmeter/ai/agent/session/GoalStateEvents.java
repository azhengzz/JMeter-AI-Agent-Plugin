package org.gitee.jmeter.ai.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内 goal 状态变更广播器。唯一扇出点 = {@link GoalState#store}：每次 goal 持久化
 * 变更（create/replace/终结/暂停/恢复/冒烟计数/续跑轮次/清除）都在写入的同一动作内
 * publish 不可变 {@link GoalState.GoalSnapshot}——这是会话级事实，不依赖任何回合的存在。
 *
 * <p>Listener 契约（对齐 TurnSubscriber.onTurnEvent 的写法）：
 * <ul>
 *   <li><b>回调线程不保证</b>——工具池线程、agent-loop 执行器线程、EDT 均可能，且可能
 *       在 AgentLoop 的 resetFenceLock 内被同步调用；</li>
 *   <li>监听者 <b>MUST NOT 在回调内取得任何锁</b>（违反会卡死 /new 重置链）、
 *       MUST O(μs) 返回；UI 消费者须恰一次 invokeLater 编组到 EDT；</li>
 *   <li>{@code snapshot == null} 表示 goal 被清除。</li>
 * </ul>
 *
 * <p>单个监听者抛出的异常被隔离（仅 warn 留痕），不影响其它监听者与 goal 持久化。
 * 每会话最后一份快照缓存在进程内（{@link #lastKnown}），供懒创建的 UI hydrate；
 * 进程重启后缓存为空，hydrate 真源始终是 session 持久化状态。
 */
public final class GoalStateEvents {

    /** 见类注释的线程/锁契约。 */
    public interface Listener {
        void onGoalStateChanged(String sessionKey, GoalState.GoalSnapshot snapshot);
    }

    private static final Logger log = LoggerFactory.getLogger(GoalStateEvents.class);

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, GoalState.GoalSnapshot> LAST_KNOWN =
            new ConcurrentHashMap<>();

    private GoalStateEvents() {
    }

    /** 幂等注册。 */
    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * 扇出快照并刷新进程内缓存（null = 清除，缓存移除）。
     * 逐监听者 try/catch 隔离；不持有任何锁。
     */
    public static void publish(String sessionKey, GoalState.GoalSnapshot snapshot) {
        if (sessionKey == null) {
            return;
        }
        if (snapshot == null) {
            LAST_KNOWN.remove(sessionKey);
        } else {
            LAST_KNOWN.put(sessionKey, snapshot);
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onGoalStateChanged(sessionKey, snapshot);
            } catch (Throwable t) {
                log.warn("Goal state listener failed (session {}); isolated", sessionKey, t);
            }
        }
    }

    /** 本进程内该会话最后一份快照；无 goal、已清除或重启后为 null。 */
    public static GoalState.GoalSnapshot lastKnown(String sessionKey) {
        return sessionKey == null ? null : LAST_KNOWN.get(sessionKey);
    }

    /** 测试隔离：清空监听者与快照缓存。 */
    public static void resetForTest() {
        LISTENERS.clear();
        LAST_KNOWN.clear();
    }
}
