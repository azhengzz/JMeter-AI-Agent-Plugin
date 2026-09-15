package org.gitee.jmeter.ai.intellisense;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InstanceMentionProvider} 的快照过滤、TTL 刷新、IPC 门控与 @token 解析。
 *
 * <p>刷新 executor 与 EDT 分发器均注入直通实现,使后台刷新在测试线程内同步完成。
 * {@code appProperties} 反射初始化沿用 {@code AiConfigTest} 的既有模式(否则 setProperty NPE)。
 */
class InstanceMentionProviderTest {

    private static final String SELF = "111-1111111111111";
    private static final String B_ID = "222-2222222222222";
    private static final String C_ID = "333-3333333333333";

    @BeforeAll
    static void ensureJMeterProps() {
        try {
            Field f = JMeterUtils.class.getDeclaredField("appProperties");
            f.setAccessible(true);
            if (f.get(null) == null) {
                f.set(null, new Properties());
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static InstanceInfo info(String instanceId, String pid, String jmxPath) {
        InstanceInfo info = new InstanceInfo();
        info.setInstanceId(instanceId);
        info.setPid(pid);
        info.setJmxPath(jmxPath);
        info.setStartedAt(Long.parseLong(instanceId.substring(instanceId.indexOf('-') + 1)));
        return info;
    }

    /** 直通协作者:刷新在调用线程内同步执行、回调同步分发。 */
    private static InstanceMentionProvider provider(List<InstanceInfo> instances) {
        return new InstanceMentionProvider(() -> instances, SELF, Runnable::run, Runnable::run);
    }

    @Test
    void suggestionsListOtherLiveInstancesAndExcludeSelf() {
        InstanceMentionProvider provider = provider(List.of(
                info(SELF, "111", "D:/plans/self.jmx"),
                info(B_ID, "222", "D:/plans/b.jmx"),
                info(C_ID, "333", "")));

        List<IntellisenseSuggestion> suggestions = provider.getSuggestions("@");

        assertEquals(2, suggestions.size());
        assertEquals("@" + B_ID + " ", suggestions.get(0).insert());
        // display 含 spec 要求的启动时间(本地时区 HH:mm:ss),其余片段精确
        assertTrue(suggestions.get(0).display().startsWith("@" + B_ID + " · b.jmx · pid 222 · since "));
        assertTrue(suggestions.get(0).display().matches(".*· since \\d{2}:\\d{2}:\\d{2}"));
        assertTrue(suggestions.get(1).display().startsWith("@" + C_ID + " · (no plan open) · pid 333 · since "));
    }

    @Test
    void suggestionsFilterBySubstringOnInstanceIdAndJmxName() {
        InstanceMentionProvider provider = provider(List.of(
                info(B_ID, "222", "D:/plans/b.jmx"),
                info(C_ID, "333", "D:/plans/c.jmx")));

        assertEquals(1, provider.getSuggestions("@b.jm").size());
        assertEquals(B_ID.substring(0, 8), provider.getSuggestions("@222-222").get(0).insert().substring(1, 9));
        assertTrue(provider.getSuggestions("@no-such").isEmpty());
    }

    @Test
    void freshSnapshotIsNotRefetchedAndExpiryTriggersRefetch() throws Exception {
        AtomicInteger fetchCount = new AtomicInteger();
        AtomicReference<List<InstanceInfo>> source = new AtomicReference<>(List.of(info(B_ID, "222", "b.jmx")));
        InstanceMentionProvider provider = new InstanceMentionProvider(() -> {
            fetchCount.incrementAndGet();
            return source.get();
        }, SELF, Runnable::run, Runnable::run);

        provider.getSuggestions("@");
        provider.getSuggestions("@");
        assertEquals(1, fetchCount.get()); // TTL 内不重复刷新

        // 模拟快照过期:回拨 fetchedAt 后再次触发应重新拉取,并看到 supplier 的新数据
        setFetchedAt(provider, 0L);
        source.set(List.of(info(C_ID, "333", "c.jmx")));
        List<IntellisenseSuggestion> afterExpiry = provider.getSuggestions("@");
        assertEquals(2, fetchCount.get());
        assertEquals(1, afterExpiry.size());
        assertEquals("@" + C_ID + " ", afterExpiry.get(0).insert());
    }

    @Test
    void refreshCallbackIsDispatched() {
        AtomicInteger callbacks = new AtomicInteger();
        List<InstanceInfo> instances = List.of(info(B_ID, "222", "b.jmx"));
        InstanceMentionProvider provider = new InstanceMentionProvider(() -> instances, SELF,
                Runnable::run, Runnable::run);
        provider.addRefreshCallback(callbacks::incrementAndGet);

        provider.getSuggestions("@");

        assertEquals(1, callbacks.get());
    }

    @Test
    void ipcDisabledYieldsNoSuggestionsAndNoFetch() {
        AtomicInteger fetchCount = new AtomicInteger();
        InstanceMentionProvider provider = new InstanceMentionProvider(() -> {
            fetchCount.incrementAndGet();
            return List.of(info(B_ID, "222", "b.jmx"));
        }, SELF, Runnable::run, Runnable::run);

        Properties props = JMeterUtils.getJMeterProperties();
        String prev = props.getProperty("jmeter.ai.ipc.enabled");
        try {
            props.setProperty("jmeter.ai.ipc.enabled", "false");
            assertTrue(provider.getSuggestions("@").isEmpty());
            assertEquals(0, fetchCount.get());
        } finally {
            if (prev == null) {
                props.remove("jmeter.ai.ipc.enabled");
            } else {
                props.setProperty("jmeter.ai.ipc.enabled", prev);
            }
        }
    }

    @Test
    void parseInstanceMentionsResolvesValidTokens() {
        List<InstanceInfo> live = List.of(
                info(B_ID, "222", "b.jmx"),
                info(C_ID, "333", "c.jmx"));

        List<InstanceInfo> mentions = InstanceMentionProvider.parseInstanceMentions(
                "看看 @" + B_ID + " 和 @" + C_ID + " 的计划", live, SELF);

        assertEquals(2, mentions.size());
        assertEquals(B_ID, mentions.get(0).getInstanceId());
        assertEquals(C_ID, mentions.get(1).getInstanceId());
    }

    @Test
    void parseInstanceMentionsIgnoresUnknownSelfAndDuplicatedTokens() {
        List<InstanceInfo> live = List.of(info(B_ID, "222", "b.jmx"));

        assertTrue(InstanceMentionProvider.parseInstanceMentions("@no-such-id", live, SELF).isEmpty());
        assertTrue(InstanceMentionProvider.parseInstanceMentions("hi @" + SELF, live, SELF).isEmpty());
        assertEquals(1, InstanceMentionProvider.parseInstanceMentions(
                "@" + B_ID + " again @" + B_ID, live, SELF).size());
    }

    @Test
    void parseInstanceMentionsIgnoresEmbeddedAtSigns() {
        List<InstanceInfo> live = List.of(info(B_ID, "222", "b.jmx"));

        // foo@bar 的 @ 前是字母:非词首,不解析(与 findTriggerIndex 同口径)
        assertTrue(InstanceMentionProvider.parseInstanceMentions(
                "mail me foo@" + B_ID + " please", live, SELF).isEmpty());
    }

    @Test
    void wordStartParityMatchesTriggerSemantics() {
        List<InstanceInfo> live = List.of(info(B_ID, "222", "b.jmx"));

        // No 类数字(²)不是 isDigit,触发侧算词首 → 解析侧也必须收(\p{Nd} 口径)
        assertEquals(1, InstanceMentionProvider.parseInstanceMentions(
                "²@" + B_ID, live, SELF).size());
        // 增补平面字母(𠀀,U+20000)按 code point 是字母 → 触发侧不弹,解析侧也不收
        assertTrue(InstanceMentionProvider.parseInstanceMentions(
                "𠀀@" + B_ID, live, SELF).isEmpty());
    }

    @Test
    void parseMentionsUsesProviderSnapshot() {
        InstanceMentionProvider provider = provider(List.of(
                info(SELF, "111", "self.jmx"),
                info(B_ID, "222", "b.jmx")));
        provider.getSuggestions("@"); // warm the snapshot

        List<InstanceInfo> mentions = provider.parseMentions("check @" + B_ID);

        assertEquals(1, mentions.size());
        assertEquals(B_ID, mentions.get(0).getInstanceId());
    }

    private static void setFetchedAt(InstanceMentionProvider provider, long value) throws Exception {
        Field f = InstanceMentionProvider.class.getDeclaredField("fetchedAt");
        f.setAccessible(true);
        f.setLong(provider, value);
    }
}
