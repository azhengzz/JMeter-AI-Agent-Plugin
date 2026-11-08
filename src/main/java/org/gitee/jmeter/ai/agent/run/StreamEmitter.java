package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.gitee.jmeter.ai.utils.AiConfig;

import java.util.function.LongSupplier;

/**
 * 一次 LLM 尝试的流段发射器：实现 {@link StreamDeltaListener} 挂进
 * {@code LlmCallOptions}（provider 唯一流式闸门），消费原始增量分片，
 * 经 {@link ThinkTagSegmenter} 分段与 ~100ms 节流后以<b>累计文本</b>载荷
 * 发射 STREAM_* 事件（经 hook.onStreamDelta → ProgressUpdate → PROGRESS 通道）。
 *
 * <p>行为要点（设计共识 D3/D5/D7 与 P1-10）：
 * <ul>
 *   <li>STREAM_STARTED 懒发射：不早于首个可见增量（首字等待期 loading 不消失）；</li>
 *   <li>attempt 1 实时发射；attempt &gt; 1 屏外缓冲（不实时显示，终态载荷携带
 *       最新累计——重试期间屏幕保留上一次的部分内容）；</li>
 *   <li>结构化 reasoning 直入思考通道（不经标签分段），与内联 think 片段合流
 *       为同一累计（结构化在前）；showThinking 每次逻辑调用快照一次，关闭时
 *       思考增量在发射端抑制（分段照常运转，正文无标签痕迹）；</li>
 *   <li>end() 恒发 STREAM_ENDED（含两通道最终累计与 SUCCESS/FAILED/ABORTED），
 *       并先做终冲刷（残余半标签归正文）。</li>
 * </ul>
 */
final class StreamEmitter implements StreamDeltaListener {

    private final long streamId;
    private final int attemptSeq;
    private final AgentHook hook;
    private final AgentHookContext context;
    private final boolean live;
    private final boolean showThinking;
    private final long throttleIntervalMs;
    private final LongSupplier clock;

    private final ThinkTagSegmenter segmenter;
    private final StringBuilder structuredReasoning = new StringBuilder();
    private int emittedStructuredLen;
    private long lastEmitMs = -1L;
    private boolean startedEmitted;
    private boolean ended;
    /** 本尝试是否向消费者实时发射过增量（粘滞 streamed 的事实源之一）。 */
    private boolean deliveredLiveDelta;
    /**
     * 本尝试是否产出过<b>可见</b>增量（分段后非空正文，或 showThinking 开启时
     * 的思考增量；由 flush/end 的分段结果置位）——零可见增量的调用不构成流段
     * （含被隐藏的纯 reasoning、内联 think 全被剥离的正文：不伪造没有起点的
     * 裸 ENDED，防消费端误标"已流式"）。
     */
    private boolean segmentVisible;

    StreamEmitter(long streamId, int attemptSeq, AgentHook hook, AgentHookContext context,
                  long throttleIntervalMs, LongSupplier clock) {
        this.streamId = streamId;
        this.attemptSeq = attemptSeq;
        this.hook = hook;
        this.context = context;
        this.live = attemptSeq == 1;
        this.showThinking = AiConfig.isChatShowThinking();
        this.throttleIntervalMs = throttleIntervalMs;
        this.clock = clock;
        this.segmenter = new ThinkTagSegmenter();
    }

    @Override
    public void onContentDelta(String text) {
        if (ended) return;
        segmenter.offer(text);
        maybeFlush(false);
    }

    @Override
    public void onReasoningDelta(String text) {
        if (ended || text == null || text.isEmpty()) return;
        structuredReasoning.append(text);
        maybeFlush(false);
    }

    private void maybeFlush(boolean force) {
        if (!live) return;
        long now = clock.getAsLong();
        if (force || lastEmitMs < 0 || now - lastEmitMs >= throttleIntervalMs) {
            flush(now);
        }
    }

    private void flush(long now) {
        ThinkTagSegmenter.Diff diff = segmenter.takeNew(false);
        String newStructured = structuredReasoning.substring(
                Math.min(emittedStructuredLen, structuredReasoning.length()));
        emittedStructuredLen = structuredReasoning.length();

        boolean hasContent = !diff.newContent().isEmpty();
        boolean hasReasoning = !diff.newReasoning().isEmpty() || !newStructured.isEmpty();
        boolean hasVisible = hasContent || (hasReasoning && showThinking);

        // 可见性事实源 = 分段实际产出的可见增量（offer 只入缓冲，分段发生在
        // takeNew——原始分片到达不构成可见：内联 think 全被剥离的正文在此
        // 之后 contentCumulative 仍为空）
        if (hasVisible) {
            segmentVisible = true;
        }

        if (!startedEmitted && hasVisible) {
            emit(StreamDelta.started(streamId, attemptSeq));
            startedEmitted = true;
        }
        if (hasContent) {
            emit(StreamDelta.contentDelta(streamId, attemptSeq, segmenter.contentCumulative()));
            deliveredLiveDelta = true;
        }
        if (hasReasoning && showThinking) {
            emit(StreamDelta.reasoningDelta(streamId, attemptSeq, reasoningCumulative()));
            deliveredLiveDelta = true;
        }
        if (hasContent || hasReasoning) {
            lastEmitMs = now;
        }
    }

    /**
     * 终态：终冲刷（残余半标签归正文）+ STREAM_ENDED（两通道最终累计）。幂等。
     * 零<b>可见</b>增量调用（非流式回退、被 showThinking 隐藏的纯 reasoning、
     * 或内联 think 全部被剥离的正文）不构成流段——不发任何事件，面板不会把
     * 该回合误标"已流式"而吞掉全文回放。可见但从未实时冲刷过的尝试（重试屏外
     * 缓冲、节流窗口内终局）在此补发 STARTED——流段协议恒为
     * STARTED → delta* → ENDED，重试经 attemptSeq 表达。
     * showThinking 关闭时 ENDED 的思考载荷置空（抑制在发射端完成——消费端无
     * 快照可过滤，携带即泄漏）。
     */
    void end(StreamDelta.Status status) {
        if (ended) return;
        ended = true;
        ThinkTagSegmenter.Diff diff = segmenter.takeNew(true);
        String newStructured = structuredReasoning.substring(
                Math.min(emittedStructuredLen, structuredReasoning.length()));
        // 终冲刷可能首次产出可见内容（如整段被扣留的半标签归正文）
        if (!diff.newContent().isEmpty()
                || (showThinking && (!diff.newReasoning().isEmpty() || !newStructured.isEmpty()))) {
            segmentVisible = true;
        }
        if (!segmentVisible) {
            return;
        }
        if (!startedEmitted) {
            emit(StreamDelta.started(streamId, attemptSeq));
            startedEmitted = true;
        } else if (live) {
            boolean deliveredTail = false;
            if (!diff.newContent().isEmpty()) {
                emit(StreamDelta.contentDelta(streamId, attemptSeq, segmenter.contentCumulative()));
                deliveredLiveDelta = true;
                deliveredTail = true;
            }
            if (showThinking && (!diff.newReasoning().isEmpty() || !newStructured.isEmpty())) {
                emit(StreamDelta.reasoningDelta(streamId, attemptSeq, reasoningCumulative()));
                deliveredLiveDelta = true;
                deliveredTail = true;
            }
            if (deliveredTail) {
                emittedStructuredLen = structuredReasoning.length();
            }
        }
        emit(StreamDelta.ended(streamId, attemptSeq, status,
                segmenter.contentCumulative(), showThinking ? reasoningCumulative() : ""));
    }

    /** 思考通道累计 = 结构化（前）+ 内联 think 片段（后）。 */
    private String reasoningCumulative() {
        if (structuredReasoning.length() == 0) {
            return segmenter.reasoningCumulative();
        }
        return structuredReasoning + segmenter.reasoningCumulative();
    }

    private void emit(StreamDelta delta) {
        try {
            hook.onStreamDelta(delta, context);
        } catch (Exception e) {
            // 发射路径异常不得影响 provider 聚合（与 adapter.publish 同隔离纪律）
        }
    }

    /** 本尝试是否向消费者实时发射过增量（attempt>1 恒 false——屏外缓冲）。 */
    boolean deliveredLiveDelta() {
        return deliveredLiveDelta;
    }
}
