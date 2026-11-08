package org.gitee.jmeter.ai.agent.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式 think 标签分段器（Nanobot {@code _LoopHook} 差分骨架的流式适配）：
 * 维护内容通道累计缓冲，对<b>完整累计串</b>做整串标签分段，与上次已发射状态
 * 差分产出「新增正文 / 新增思考」。不复用 {@code TextUtils.splitThink}：
 * ① 它按整串正则匹配，累计串尾部的半标签（{@code "<thi"}）不匹配、会作为
 * 正文泄漏——本分段器对尾部可能是 {@code <think}/{@code </think} 前缀的片段
 * 做 holdback（扣留到下一片或流段结束再判定）；② 它对分段 strip+丢空段，前缀
 * 差分会永久丢失段首缩进——本分段器不裁剪。
 *
 * <p>单调性：safe 区域内 span 起点一经确定不会回移，正文/思考通道只增长——
 * 差分（substring(已发射长度)）因此安全。未闭合的尾部 {@code <think} 视作思考
 * span（与既有 stripThink 的 UNCLOSED 语义一致）；流段结束时的终冲刷不再
 * holdback（残余半标签按字面正文处理）。
 */
final class ThinkTagSegmenter {

    private static final String OPEN_TAG = "<think";
    private static final String CLOSE_TAG = "</think";
    private static final Pattern THINK_SPAN =
            Pattern.compile("<think[\\s\\S]*?</think\\s*>|<think[\\s\\S]*$");

    private final StringBuilder raw = new StringBuilder();
    private String contentChannel = "";
    private String reasoningChannel = "";
    private int emittedContentLen;
    private int emittedReasoningLen;

    /** 本次差分产物（新增正文 / 新增思考，可能为空串）。 */
    record Diff(String newContent, String newReasoning) {}

    /** 追加原始内容通道分片（可能含内联 think 标签碎片）。 */
    void offer(String fragment) {
        if (fragment != null && !fragment.isEmpty()) {
            raw.append(fragment);
        }
    }

    /**
     * 重算分段并取差分。非终冲刷时对尾部做 holdback（可能是半标签前缀的
     * 后缀暂不判定）；{@code finalFlush} 时全部判定（残余半标签归正文）。
     */
    Diff takeNew(boolean finalFlush) {
        String buffer = raw.toString();
        int safeEnd = finalFlush ? buffer.length() : holdbackEnd(buffer);

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Matcher matcher = THINK_SPAN.matcher(buffer).region(0, Math.max(safeEnd, 0));
        int last = 0;
        while (matcher.find()) {
            content.append(buffer, last, matcher.start());
            reasoning.append(spanInner(matcher.group()));
            last = matcher.end();
        }
        content.append(buffer, last, safeEnd);

        contentChannel = content.toString();
        reasoningChannel = reasoning.toString();

        String newContent = contentChannel.substring(Math.min(emittedContentLen, contentChannel.length()));
        String newReasoning = reasoningChannel.substring(Math.min(emittedReasoningLen, reasoningChannel.length()));
        emittedContentLen = contentChannel.length();
        emittedReasoningLen = reasoningChannel.length();
        return new Diff(newContent, newReasoning);
    }

    /**
     * 尾部 holdback：① 最长后缀是 {@code <think}/{@code </think} 的真前缀时扣留该
     * 后缀；② 尾部存在<b>未闭合的开标签</b>（{@code <think} 之后同一直线上
     * {@code >} 尚未到达，如带属性标签被分片 {@code A<think type="}）时，从该
     * {@code <think} 起整体扣留——inner 起点未定前不提交任何文本进思考通道
     * （否则属性文本会被当作思考发射，且差分长度已前进、真实思考永远补不回）。
     */
    private static int holdbackEnd(String buffer) {
        int len = buffer.length();
        int maxCheck = Math.min(len, CLOSE_TAG.length());
        for (int k = maxCheck; k >= 1; k--) {
            String suffix = buffer.substring(len - k);
            if ((suffix.length() < OPEN_TAG.length() && OPEN_TAG.startsWith(suffix))
                    || (suffix.length() < CLOSE_TAG.length() && CLOSE_TAG.startsWith(suffix))) {
                len = len - k;
                buffer = buffer.substring(0, len);
                break;
            }
        }
        int open = buffer.lastIndexOf(OPEN_TAG);
        if (open >= 0) {
            for (int i = open + OPEN_TAG.length(); i < buffer.length(); i++) {
                char c = buffer.charAt(i);
                if (c == '>') {
                    return len;   // 开标签已闭合：span 语义可判定
                }
                if (c == '\n' || c == '\r') {
                    return len;   // 跨行：按无括号开标签退化语义，可判定
                }
            }
            return Math.min(len, open);   // 开标签未完：从 <think 起扣留
        }
        return len;
    }

    /**
     * 剥开 span 的起止标签（不 trim）。开标签的 {@code >} 只在同一行内认（真实
     * 标签不跨行）；容忍无括号开标签（{@code <think\n…}）退化为剥字面
     * {@code <think}——与 {@code TextUtils.spanInner} 同语义。
     */
    private static String spanInner(String span) {
        int open = -1;
        for (int i = OPEN_TAG.length(); i < span.length(); i++) {
            char c = span.charAt(i);
            if (c == '>') {
                open = i;
                break;
            }
            if (c == '\n' || c == '\r') {
                break;
            }
        }
        String body = open >= 0 ? span.substring(open + 1) : span.substring(OPEN_TAG.length());
        int close = body.lastIndexOf(CLOSE_TAG);
        return close >= 0 ? body.substring(0, close) : body;
    }

    /** 当前正文通道累计（标签已剥离，含全部已到文本）。 */
    String contentCumulative() {
        return contentChannel;
    }

    /** 当前思考通道累计（内联 think 片段，不含结构化 reasoning——那路由由发射器合流）。 */
    String reasoningCumulative() {
        return reasoningChannel;
    }
}
