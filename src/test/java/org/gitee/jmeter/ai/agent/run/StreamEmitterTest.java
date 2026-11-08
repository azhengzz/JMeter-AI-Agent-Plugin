package org.gitee.jmeter.ai.agent.run;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发射器单元级验证：节流（注入时钟）、懒 STREAM_STARTED、attempt>1 屏外缓冲、
 * 终态载荷双通道、showThinking 快照（反射注入 appProperties 切换）。
 */
class StreamEmitterTest {

    private static final class RecordingHook implements AgentHook {
        final List<StreamDelta> deltas = new ArrayList<>();

        @Override public boolean wantsStreaming() { return true; }

        @Override public void onStreamDelta(StreamDelta delta, AgentHookContext ctx) {
            deltas.add(delta);
        }
    }

    @AfterEach
    void restoreJMeterProperties() throws Exception {
        Field field = JMeterUtils.class.getDeclaredField("appProperties");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static void setShowThinking(Boolean value) throws Exception {
        Properties props = new Properties();
        if (value != null) {
            props.setProperty("ai.chat.show.thinking", value.toString());
        }
        Field field = JMeterUtils.class.getDeclaredField("appProperties");
        field.setAccessible(true);
        field.set(null, props);
    }

    @Test
    void lazyStartCumulativeDeltasAndEndSuccess() {
        RecordingHook hook = new RecordingHook();
        AtomicLong clock = new AtomicLong(1000);
        StreamEmitter emitter = new StreamEmitter(1L, 1, hook, null, 0, clock::get);

        emitter.onContentDelta("我");
        clock.incrementAndGet();
        emitter.onContentDelta("用");
        clock.incrementAndGet();
        emitter.onReasoningDelta("想");   // 结构化思考（不经标签分段）
        emitter.end(StreamDelta.Status.SUCCESS);

        // 节流 0：每片即冲。STARTED 懒发射于首个可见增量（首片正文）。
        assertEquals(StreamDelta.Kind.STREAM_STARTED, hook.deltas.get(0).getKind());
        assertEquals(1L, hook.deltas.get(0).getStreamId());
        assertEquals(1, hook.deltas.get(0).getAttemptSeq());

        List<StreamDelta> contentDeltas = hook.deltas.stream()
                .filter(d -> d.getKind() == StreamDelta.Kind.CONTENT_DELTA).toList();
        assertEquals(List.of("我", "我用"), contentDeltas.stream().map(StreamDelta::getContentText).toList(),
                "CONTENT 载荷为累计文本");
        // showThinking 默认 false：思考增量发射端抑制
        assertTrue(hook.deltas.stream().noneMatch(d -> d.getKind() == StreamDelta.Kind.REASONING_DELTA));

        StreamDelta ended = hook.deltas.get(hook.deltas.size() - 1);
        assertEquals(StreamDelta.Kind.STREAM_ENDED, ended.getKind());
        assertEquals(StreamDelta.Status.SUCCESS, ended.getStatus());
        assertEquals("我用", ended.getContentText());
        assertEquals("", ended.getReasoningText(),
                "showThinking=false 时 ENDED 思考载荷置空（抑制在发射端完成——消费端无快照可过滤，携带即泄漏）");
        assertTrue(emitter.deliveredLiveDelta());
    }

    @Test
    void thinkTagFragmentsSegmentedBeforeEmission() throws Exception {
        setShowThinking(true);
        RecordingHook hook = new RecordingHook();
        AtomicLong clock = new AtomicLong(0);
        StreamEmitter emitter = new StreamEmitter(2L, 1, hook, null, 0, clock::get);

        emitter.onContentDelta("我用<thi");
        emitter.onContentDelta("nk>思考</thi");
        emitter.onContentDelta("nk>答案");
        emitter.end(StreamDelta.Status.SUCCESS);

        List<StreamDelta> contentDeltas = hook.deltas.stream()
                .filter(d -> d.getKind() == StreamDelta.Kind.CONTENT_DELTA).toList();
        String cumulative = contentDeltas.get(contentDeltas.size() - 1).getContentText();
        assertEquals("我用答案", cumulative, "碎片标签经分段器，正文无标签字节");

        List<StreamDelta> reasoningDeltas = hook.deltas.stream()
                .filter(d -> d.getKind() == StreamDelta.Kind.REASONING_DELTA).toList();
        assertEquals("思考", reasoningDeltas.get(reasoningDeltas.size() - 1).getReasoningText());
    }

    @Test
    void throttlingDefersDeltasUntilWindowOrEnd() {
        RecordingHook hook = new RecordingHook();
        AtomicLong clock = new AtomicLong(0);
        StreamEmitter emitter = new StreamEmitter(3L, 1, hook, null, 10_000, clock::get);

        emitter.onContentDelta("第一段");
        clock.addAndGet(1);
        emitter.onContentDelta("第二段");   // 窗口内：缓冲
        assertEquals(1, hook.deltas.stream()
                        .filter(d -> d.getKind() == StreamDelta.Kind.CONTENT_DELTA).count(),
                "窗口内不重复发射");

        emitter.end(StreamDelta.Status.SUCCESS);   // 终态强制冲刷剩余差分
        List<StreamDelta> contentDeltas = hook.deltas.stream()
                .filter(d -> d.getKind() == StreamDelta.Kind.CONTENT_DELTA).toList();
        assertEquals("第一段第二段", contentDeltas.get(contentDeltas.size() - 1).getContentText(),
                "终态冲刷补齐窗口内缓冲的差分");
    }

    @Test
    void secondAttemptBuffersOffScreen() {
        RecordingHook hook = new RecordingHook();
        AtomicLong clock = new AtomicLong(0);
        StreamEmitter emitter = new StreamEmitter(4L, 2, hook, null, 0, clock::get);

        emitter.onContentDelta("重试的部分内容");
        emitter.end(StreamDelta.Status.FAILED);

        // attempt>1 不实时发射增量（无 CONTENT_DELTA），但流段协议完整：
        // STARTED(attemptSeq=2) → ENDED（载荷携带最新累计）
        assertEquals(2, hook.deltas.size(), "attempt>1：STARTED + ENDED，无实时 delta");
        assertEquals(StreamDelta.Kind.STREAM_STARTED, hook.deltas.get(0).getKind());
        StreamDelta ended = hook.deltas.get(1);
        assertEquals(StreamDelta.Kind.STREAM_ENDED, ended.getKind());
        assertEquals(2, ended.getAttemptSeq(), "streamId 不变、attemptSeq 表达重试");
        assertEquals(StreamDelta.Status.FAILED, ended.getStatus());
        assertEquals("重试的部分内容", ended.getContentText(), "失败载荷携带最新累计（最新非空部分交付载体）");
        assertFalse(emitter.deliveredLiveDelta(), "屏外缓冲不实时交付");
    }

    @Test
    void retryAttemptEmitsStartedWithAttemptSeqBeforeEnded() {
        // S6 复现：重试尝试（屏外缓冲）仍须发完整流段协议 STARTED(attemptSeq=2) → ENDED
        RecordingHook hook = new RecordingHook();
        StreamEmitter emitter = new StreamEmitter(9L, 2, hook, null, 0, () -> 0L);

        emitter.onContentDelta("重试内容");
        emitter.end(StreamDelta.Status.SUCCESS);

        org.junit.jupiter.api.Assertions.assertEquals(2, hook.deltas.size(),
                "retry attempt with deltas: STARTED + ENDED, actual=" + hook.deltas);
        org.junit.jupiter.api.Assertions.assertEquals(StreamDelta.Kind.STREAM_STARTED,
                hook.deltas.get(0).getKind());
        org.junit.jupiter.api.Assertions.assertEquals(2, hook.deltas.get(0).getAttemptSeq());
        org.junit.jupiter.api.Assertions.assertEquals(StreamDelta.Kind.STREAM_ENDED,
                hook.deltas.get(1).getKind());
    }

    @Test
    void hiddenReasoningOnlyEmitsNothing() {
        // S11 复现：showThinking=false（默认）的纯 reasoning 调用零可见增量——不构成流段
        RecordingHook hook = new RecordingHook();
        StreamEmitter emitter = new StreamEmitter(9L, 1, hook, null, 0, () -> 0L);

        emitter.onReasoningDelta("secret reasoning");
        emitter.end(StreamDelta.Status.SUCCESS);

        org.junit.jupiter.api.Assertions.assertEquals(0, hook.deltas.size(),
                "no visible delta → no segment events (no bare ENDED), actual=" + hook.deltas);
    }

    @Test
    void hiddenInlineThinkOnlyEmitsNothing() {
        // R5 复现：showThinking=false（默认）的纯内联 think 正文——原始分片到达
        // 但分段后可见正文为空，不构成流段（可见性以分段结果为准，非原始到达）
        RecordingHook hook = new RecordingHook();
        StreamEmitter emitter = new StreamEmitter(11L, 1, hook, null, 0, () -> 0L);

        emitter.onContentDelta("<think>secret reasoning</think>");
        emitter.end(StreamDelta.Status.SUCCESS);

        org.junit.jupiter.api.Assertions.assertEquals(0, hook.deltas.size(),
                "inline think fully stripped → no visible delta → no segment events, actual=" + hook.deltas);
    }

    @Test
    void retryAttemptWithOnlyHiddenThinkEmitsNothing() {
        // R5 屏外（attempt>1）变体：重试尝试全部内容是隐藏 think——零可见增量，
        // 不补发 STARTED/ENDED（否则面板会把空流段当权威替换信号）
        RecordingHook hook = new RecordingHook();
        StreamEmitter emitter = new StreamEmitter(12L, 2, hook, null, 0, () -> 0L);

        emitter.onContentDelta("<think>hidden retry reasoning</think>");
        emitter.end(StreamDelta.Status.SUCCESS);

        org.junit.jupiter.api.Assertions.assertEquals(0, hook.deltas.size(),
                "off-screen hidden-think attempt emits nothing, actual=" + hook.deltas);
    }

    @Test
    void visibleContentAfterHiddenThinkStillConstitutesSegment() {
        // R5 边界：隐藏 think + 标签外正文 → 正文可见即构成流段，正文载荷无标签痕迹
        RecordingHook hook = new RecordingHook();
        StreamEmitter emitter = new StreamEmitter(13L, 1, hook, null, 0, () -> 0L);

        emitter.onContentDelta("<think>hidden</think>答案");
        emitter.end(StreamDelta.Status.SUCCESS);

        StreamDelta ended = hook.deltas.get(hook.deltas.size() - 1);
        org.junit.jupiter.api.Assertions.assertEquals(StreamDelta.Kind.STREAM_ENDED, ended.getKind());
        org.junit.jupiter.api.Assertions.assertEquals("答案", ended.getContentText());
        org.junit.jupiter.api.Assertions.assertEquals("", ended.getReasoningText());
    }

    @Test
    void zeroDeltasEmitNothing() {
        RecordingHook hook = new RecordingHook();
        StreamEmitter emitter = new StreamEmitter(5L, 1, hook, null, 0, () -> 0L);

        emitter.end(StreamDelta.Status.SUCCESS);
        emitter.end(StreamDelta.Status.FAILED);   // 幂等

        assertEquals(0, hook.deltas.size(),
                "零增量调用不构成流段——无事件，面板不会误标已流式而吞掉 THINKING 回放");
    }
}
