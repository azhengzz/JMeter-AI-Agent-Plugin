package org.gitee.jmeter.ai.agent.turn;

import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;

import java.util.List;

/**
 * 注入队列的单个条目（原 {@code InjectionManager.InjectionItem}，Phase 4 路由槽合并
 * 随迁至 {@code agent.turn}）。携带条目来源，收尾清理据此区分用户消息（可 re-publish
 * 成新回合）与子代理公告（取消/收尾时丢弃；其结果仍可经 {@code subagent_status} 查询）。
 *
 * <p>用户消息可携带发送时解析出的 @-实例结构化引用（busy 期注入不降级——对齐 Nanobot
 * 排空 pending 消息逐条解析 runtime context 的语义）；排空侧把引用合并渲染进注入块。
 * 公告与旧路径的引用恒为空表。
 */
public final class InjectionItem {
    private final String text;
    private final boolean announcement;
    private final List<InstanceInfo> mentions;

    public InjectionItem(String text, boolean announcement) {
        this(text, announcement, List.of());
    }

    public InjectionItem(String text, boolean announcement, List<InstanceInfo> mentions) {
        this.text = text;
        this.announcement = announcement;
        this.mentions = mentions == null ? List.of() : List.copyOf(mentions);
    }

    public String getText() {
        return text;
    }

    public boolean isAnnouncement() {
        return announcement;
    }

    /** 本条消息 @-点名的对端实例引用（不可变；无则为空表）。 */
    public List<InstanceInfo> getMentions() {
        return mentions;
    }
}
