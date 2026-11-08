package org.gitee.jmeter.ai.agent.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * think 标签分段器：碎片标签不泄漏、holdback、不裁剪（缩进保留）、未闭合
 * 尾部 span、终冲刷残余半标签归正文。
 */
class ThinkTagSegmenterTest {

    @Test
    void splitTagAcrossFragmentsDoesNotLeak() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String[] fragments = {"我用<thi", "nk>先分析", "</thi", "nk>答案是42"};
        for (int i = 0; i < fragments.length; i++) {
            s.offer(fragments[i]);
            ThinkTagSegmenter.Diff d = s.takeNew(false);
            content.append(d.newContent());
            reasoning.append(d.newReasoning());
        }
        // 终冲刷：无残余（最后一片已完整判定）
        ThinkTagSegmenter.Diff fin = s.takeNew(true);
        content.append(fin.newContent());
        reasoning.append(fin.newReasoning());

        assertEquals("我用答案是42", content.toString(), "无 <thi/nk> 字面碎片");
        assertEquals("先分析", reasoning.toString());
    }

    @Test
    void leadingIndentationPreserved() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        s.offer("  - item one\n  - item two");
        ThinkTagSegmenter.Diff d = s.takeNew(true);
        assertEquals("  - item one\n  - item two", d.newContent(), "段首缩进与内部空白不丢");
    }

    @Test
    void unclosedTrailingThinkTreatedAsReasoning() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        s.offer("正文<think>还没想完");
        ThinkTagSegmenter.Diff d1 = s.takeNew(false);
        assertEquals("正文", d1.newContent());
        assertEquals("还没想完", d1.newReasoning(), "未闭合尾部 span 语义与 stripThink 一致");

        s.offer("继续想</think>收尾");
        ThinkTagSegmenter.Diff d2 = s.takeNew(true);
        assertEquals("收尾", d2.newContent());
        assertEquals("继续想", d2.newReasoning());
    }

    @Test
    void tagWithAttributes() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        s.offer("a<think type=\"x\">hidden</think>b");
        ThinkTagSegmenter.Diff d = s.takeNew(true);
        assertEquals("ab", d.newContent());
        assertEquals("hidden", d.newReasoning());
    }

    @Test
    void finalFlushTreatsResidualPartialTagAsLiteralContent() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        s.offer("写了半个标签 <thi");
        // 非终冲刷：半标签被扣留，暂不出正文
        assertEquals("写了半个标签 ", s.takeNew(false).newContent());
        // 终冲刷：流已结束，残余按字面正文
        assertEquals("<thi", s.takeNew(true).newContent());
    }

    @Test
    void multipleSpansInOrder() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        s.offer("一<think>r1</think>二<think>r2</think>三");
        ThinkTagSegmenter.Diff d = s.takeNew(true);
        assertEquals("一二三", d.newContent());
        assertEquals("r1r2", d.newReasoning());
    }

    @Test
    void attributeOpenTagSplitAcrossFragmentsDoesNotLeakIntoReasoning() {
        // S5 复现：带属性开标签跨片——属性文本不得先进思考通道再吞掉真实思考
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String[] fragments = {"A<think type=\"", "x\">secret", "</think>B"};
        for (String fragment : fragments) {
            s.offer(fragment);
            ThinkTagSegmenter.Diff d = s.takeNew(false);
            content.append(d.newContent());
            reasoning.append(d.newReasoning());
        }
        ThinkTagSegmenter.Diff fin = s.takeNew(true);
        content.append(fin.newContent());
        reasoning.append(fin.newReasoning());

        org.junit.jupiter.api.Assertions.assertEquals("AB", content.toString(),
                "正文不受属性标签影响");
        org.junit.jupiter.api.Assertions.assertEquals("secret", reasoning.toString(),
                "真实思考完整进入思考通道，无属性文本混入");
    }

    @Test
    void takeNewIsIdempotentWithoutNewInput() {
        ThinkTagSegmenter s = new ThinkTagSegmenter();
        s.offer("hello");
        assertEquals("hello", s.takeNew(false).newContent());
        assertEquals("", s.takeNew(false).newContent());
        assertEquals("", s.takeNew(true).newContent());
    }
}
