package org.gitee.jmeter.ai.agent.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InjectionManager 句柄语义单元测试（队列归回合所有，map 条目只作路由槽）。
 *
 * <p>必须成立的不变量：
 * <ul>
 *   <li>drain/cleanup 按句柄操作——一个回合抽不干别的回合的队列（防垂死回合偷后继消息）；</li>
 *   <li>cleanup 条件摘槽——只摘自己的路由槽，不误摘后继回合的（防僵尸路由）；</li>
 *   <li>cancelRouting 后 offer 原子失败——不存在「拿到队列引用后被摘、写入悬挂队列
 *       却返回成功」的竞态；句柄仍可抽干（pre-pickup 死任务善后依赖）。</li>
 * </ul>
 */
class InjectionManagerTest {

    @Test
    void registerTwice_drainIsolatedByHandle() {
        InjectionManager manager = new InjectionManager();
        LinkedBlockingQueue<InjectionManager.InjectionItem> q1 = manager.register("s");
        assertTrue(manager.offer("s", "m1"));

        // 后继回合占槽（put 替换）：路由指向新队列，旧队列由旧回合句柄继续持有
        LinkedBlockingQueue<InjectionManager.InjectionItem> q2 = manager.register("s");
        assertNotSame(q1, q2);
        assertTrue(manager.offer("s", "m2"));

        // 按句柄抽干：q1 的消息绝不会被 q2 的回合抽走
        assertEquals(List.of("m1"), texts(manager.drain(q1, 10)));
        assertEquals(List.of("m2"), texts(manager.drain(q2, 10)));
    }

    @Test
    void cleanup_removesOnlyOwnRoutingSlot() {
        InjectionManager manager = new InjectionManager();
        LinkedBlockingQueue<InjectionManager.InjectionItem> q1 = manager.register("s");
        assertTrue(manager.offer("s", "m1"));
        LinkedBlockingQueue<InjectionManager.InjectionItem> q2 = manager.register("s");

        // 前回合收尾：抽干自己的残留，条件摘槽失败（槽是后继的），后继路由不受影响
        List<InjectionManager.InjectionItem> leftover1 = manager.cleanup("s", q1);
        assertEquals(List.of("m1"), texts(leftover1));
        assertTrue(manager.hasActiveRun("s"), "后继回合的路由槽必须存活");

        assertTrue(manager.offer("s", "m2"));
        List<InjectionManager.InjectionItem> leftover2 = manager.cleanup("s", q2);
        assertEquals(List.of("m2"), texts(leftover2));
        assertFalse(manager.hasActiveRun("s"), "最后一个回合收尾后槽应清空");
    }

    @Test
    void cancelRouting_offerFailsAtomically_handleStillDrainable() {
        InjectionManager manager = new InjectionManager();
        LinkedBlockingQueue<InjectionManager.InjectionItem> q = manager.register("s");
        assertTrue(manager.offer("s", "m1"));

        manager.cancelRouting("s");
        assertFalse(manager.hasActiveRun("s"), "取消即摘槽");
        assertFalse(manager.offer("s", "m2"), "槽已摘除，offer 必须失败（computeIfPresent 原子语义）");

        // 死任务善后：句柄仍可抽干残留，且不误摘（槽已不在）
        List<InjectionManager.InjectionItem> leftover = manager.cleanup("s", q);
        assertEquals(List.of("m1"), texts(leftover));
        assertFalse(manager.hasActiveRun("s"));
    }

    @Test
    void announcementFlag_roundTrips() {
        InjectionManager manager = new InjectionManager();
        LinkedBlockingQueue<InjectionManager.InjectionItem> q = manager.register("s");
        assertTrue(manager.offer("s", "user-msg"));
        assertTrue(manager.offer("s", "subagent-announce", true));

        List<InjectionManager.InjectionItem> items = manager.drain(q, 10);
        assertEquals(2, items.size());
        assertFalse(items.get(0).isAnnouncement(), "默认 offer 是用户消息");
        assertEquals("user-msg", items.get(0).getText());
        assertTrue(items.get(1).isAnnouncement(), "公告标记必须随队列条目透传");
        assertEquals("subagent-announce", items.get(1).getText());
    }

    private static List<String> texts(List<InjectionManager.InjectionItem> items) {
        return items.stream().map(InjectionManager.InjectionItem::getText).toList();
    }
}
