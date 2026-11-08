package org.gitee.jmeter.ai.agent.model;

/**
 * 流段事件载荷：挂 {@code ProgressUpdate} 的 STREAM_* 类型经现有 PROGRESS 通道
 * 交付（不新增回合事件 Kind）。一个流段 = 一次逻辑 LLM 调用（含其全部自动重试
 * 尝试）：{@code streamId} 在逻辑调用内稳定，重试经 {@code attemptSeq} 递增表达。
 *
 * <p>载荷携带该通道的<b>累计文本</b>（非增量碎片）——渲染幂等，乱序/重复/懒领养
 * 均以最新累计整段正确显示。CONTENT_DELTA 携带正文通道累计、REASONING_DELTA
 * 携带思考通道累计；STREAM_ENDED 携带两通道最终累计与终态状态（成功/失败/中止
 * ——失败载荷即"最新非空部分"的交付载体）。
 */
public final class StreamDelta {

    /** 流段终态（仅 STREAM_ENDED 携带）。 */
    public enum Status { SUCCESS, FAILED, ABORTED }

    /** 流段事件种类。 */
    public enum Kind { STREAM_STARTED, CONTENT_DELTA, REASONING_DELTA, STREAM_ENDED }

    private final Kind kind;
    private final long streamId;
    private final int attemptSeq;
    private final Status status;
    private final String contentText;
    private final String reasoningText;

    private StreamDelta(Kind kind, long streamId, int attemptSeq, Status status,
                        String contentText, String reasoningText) {
        this.kind = kind;
        this.streamId = streamId;
        this.attemptSeq = attemptSeq;
        this.status = status;
        this.contentText = contentText;
        this.reasoningText = reasoningText;
    }

    public static StreamDelta started(long streamId, int attemptSeq) {
        return new StreamDelta(Kind.STREAM_STARTED, streamId, attemptSeq, null, null, null);
    }

    /** 正文通道累计文本（含已剥离的内联 think 标签语义——正文通道不含标签字节）。 */
    public static StreamDelta contentDelta(long streamId, int attemptSeq, String cumulativeContent) {
        return new StreamDelta(Kind.CONTENT_DELTA, streamId, attemptSeq, null, cumulativeContent, null);
    }

    /** 思考通道累计文本（结构化 reasoning + 内联 think 片段已合流）。 */
    public static StreamDelta reasoningDelta(long streamId, int attemptSeq, String cumulativeReasoning) {
        return new StreamDelta(Kind.REASONING_DELTA, streamId, attemptSeq, null, null, cumulativeReasoning);
    }

    /** 流段终态：携带两通道最终累计（失败时即最新非空部分的交付载体）。 */
    public static StreamDelta ended(long streamId, int attemptSeq, Status status,
                                    String finalContent, String finalReasoning) {
        return new StreamDelta(Kind.STREAM_ENDED, streamId, attemptSeq, status, finalContent, finalReasoning);
    }

    public Kind getKind() { return kind; }
    public long getStreamId() { return streamId; }
    public int getAttemptSeq() { return attemptSeq; }
    public Status getStatus() { return status; }
    public String getContentText() { return contentText; }
    public String getReasoningText() { return reasoningText; }

    @Override
    public String toString() {
        return "StreamDelta{" + kind + ", stream=" + streamId + ", attempt=" + attemptSeq
                + (status != null ? ", status=" + status : "")
                + (contentText != null ? ", contentLen=" + contentText.length() : "")
                + (reasoningText != null ? ", reasoningLen=" + reasoningText.length() : "")
                + '}';
    }
}
