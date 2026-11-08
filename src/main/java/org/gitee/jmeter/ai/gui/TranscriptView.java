package org.gitee.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/**
 * The chat transcript as a vertical list of message cards (component-per-message,
 * replacing the single-JTextPane HTML document model). Each message is a
 * {@link MessageCard} (bubble for the user, flat full-width for the assistant),
 * agent tool calls collect inside a collapsible {@link ToolActivityGroup},
 * reasoning streams into a {@link ThinkingCard}, and system lines (errors,
 * cancellations, injected echoes) render as small colored notes.
 *
 * <p>Every insert follows the smart-scroll protocol internally: the pinned
 * state is captured <em>before</em> mutating the component tree, and restored
 * after, so new content is revealed only while the user is at the tail.
 *
 * <p>All methods must be called on the EDT (asserted); callers route through
 * runOnEdt/invokeLater.
 */
class TranscriptView extends JPanel implements javax.swing.Scrollable {

    private final List<MessageCard> cards = new ArrayList<>();
    private final Component glue = Box.createVerticalGlue();

    private Font baseFont;
    private ThinkingRow thinkingRow;
    private ThinkingCard thinkingCard;

    /** 无回合身份（命令/杂项）的工具活动路由键——面板口径中 turnId 0L 即"无回合"。 */
    private static final long LEGACY_ACTIVITY_TURN_ID = 0L;

    /** 按回合键控的活动组（重叠活回合互不串组；spec「GUI 流式渲染与卡片生命周期」）。 */
    private final Map<Long, ToolActivityGroup> activityGroups = new HashMap<>();
    /** 最近一次写入的活动组（测试观测面）。 */
    private ToolActivityGroup lastActivityGroup;

    // --- 流式状态（按回合键控；旧单槽路径不涉足，互不串卡） ---

    /** 流式合并 flush 调度器（可注入手动步进；默认 Swing Timer one-shot）。 */
    interface StreamFlushScheduler {
        void schedule(Runnable flush);
        void cancel();
    }

    /** 默认调度器：one-shot Swing Timer（repeats=false，fire 自停）。 */
    private static final class SwingFlushScheduler implements StreamFlushScheduler {
        private Timer timer;

        @Override public void schedule(Runnable flush) {
            if (timer != null) {
                return;   // 已排队：合并
            }
            timer = new Timer(STREAM_FLUSH_DELAY_MS, e -> {
                timer = null;
                flush.run();
            });
            timer.setRepeats(false);
            timer.start();
        }

        @Override public void cancel() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
        }
    }

    private static final int STREAM_FLUSH_DELAY_MS = 100;

    /** 单个流段（一次 LLM 调用）的状态：独立卡片对 + 待渲累计 + 终态位。 */
    final static class StreamSegment {
        /** 本段全部思考卡（按创建序）：首条可见正文后的新到思考增量开新卡（spec 口径）。 */
        final List<ThinkingCard> thinkingCards = new ArrayList<>();
        /** 当前（最新）思考卡指针——追加/收尾的目标。 */
        ThinkingCard thinkingCard;
        MessageCard contentCard;
        String contentCumulative = "";
        String reasoningCumulative = "";
        int appendedReasoningLen;
        boolean ended;
        boolean cardCreated;
        /** 首条可见正文已到（其后新到的思考增量属"正文后思考"，开新卡收敛）。 */
        boolean contentStarted;
    }

    /** 单回合流式状态：按 streamId 建段（多次调用互不串卡）+「曾见过流式事件」标记。 */
    final static class TurnStreamState {
        final Map<Long, StreamSegment> segments = new HashMap<>();
        boolean sawStreamEvents;

        StreamSegment segment(long streamId) {
            return segments.computeIfAbsent(streamId, k -> new StreamSegment());
        }
    }

    private final Map<Long, TurnStreamState> streamStates = new HashMap<>();
    private final StreamFlushScheduler flushScheduler;
    /** 流式增长跟随（手势代际粘滞；见 StreamFollowTracker）。 */
    private final StreamFollowTracker streamFollow = new StreamFollowTracker();

    TranscriptView(Font baseFont) {
        this(baseFont, new SwingFlushScheduler());
    }

    TranscriptView(Font baseFont, StreamFlushScheduler flushScheduler) {
        this.baseFont = baseFont;
        this.flushScheduler = flushScheduler;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, 0, UiTokens.SPACE_2, 0));
        add(glue);
    }

    // --- Messages -----------------------------------------------------------

    /** Adds a user message bubble (plain text, no markdown parsing). */
    void addUserMessage(String text) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        finishActivityIfRunning();
        MessageCard card = new MessageCard(MessageCard.Role.USER, baseFont);
        card.setPlainContent(text);
        addCard(card);
    }

    /** Adds a complete assistant message (markdown-rendered). */
    void addAssistantMarkdown(String markdown) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        finishActivityIfRunning();
        finishReasoningIfRunning();
        MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, baseFont);
        card.setMarkdownContent(markdown);
        addCard(card);
    }

    /** Adds a small colored system note (errors, cancellations, status). */
    void addSystemMessage(String text, Color color) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        JTextArea note = new JTextArea();
        note.setEditable(false);
        // The note never changes after creation. Its caret's queued repaint
        // runs once the note is inside the transcript and would drag the chat
        // viewport to the freshly inserted bottom row — disable that here,
        // BEFORE the text insert queues the repaint.
        ((javax.swing.text.DefaultCaret) note.getCaret())
                .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        note.setText(text);
        note.setOpaque(true);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        Color tone = color != null ? color : ThemeColors.secondaryText();
        note.putClientProperty(SYSTEM_NOTE_TONE_KEY, tone);
        applySystemNoteTheme(note, tone);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(note);
        relayout(note);
    }

    /** Client-property key carrying a system note's semantic tone for re-theming. */
    private static final String SYSTEM_NOTE_TONE_KEY = "transcript.systemNoteTone";

    private static void applySystemNoteTheme(JTextArea note, Color tone) {
        note.setForeground(tone);
        note.setBackground(ThemeColors.subtleSurface());
        note.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, tone),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_2, UiTokens.SPACE_3,
                        UiTokens.SPACE_2, UiTokens.SPACE_3)));
    }

    // --- Agent tool activity -------------------------------------------------

    /** Routes a tool-activity line into the current (or a new) group (no turn identity). */
    void addToolActivity(String line) {
        addToolActivity(LEGACY_ACTIVITY_TURN_ID, line);
    }

    /**
     * Routes a tool-activity line into the given turn's (or a new) group.
     * 按回合键控：重叠活回合的工具行各归各组，互不串组、互不收尾。
     */
    void addToolActivity(long turnId, String line) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        // In-card growth changes the transcript height without a component insert —
        // apply the smart-scroll protocol around it so the tail keeps being followed
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
        boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
        ToolActivityGroup group = activityGroups.get(turnId);
        if (group == null || !group.isRunning()) {
            group = new ToolActivityGroup();
            group.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(group);
            activityGroups.put(turnId, group);
        }
        group.addLine(line);
        lastActivityGroup = group;
        relayout(group);
        ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
    }

    /**
     * Finishes ALL running activity groups（转录边界语义：用户消息/完整助手卡插入
     * 时收尾一切在跑组——单组视角下与旧行为等价）。
     */
    void finishActivityIfRunning() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        for (ToolActivityGroup group : activityGroups.values()) {
            if (group.isRunning()) {
                group.finish();
            }
        }
    }

    /**
     * 工具<b>开始运行</b>的占位行（先于执行呈现——长工具执行期间不再空白）：
     * key=工具名，完成事件经 {@link #completeToolActivity} 原位替换为终态行。
     */
    void addRunningToolActivity(long turnId, String key, String line) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
        boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
        ToolActivityGroup group = activityGroups.get(turnId);
        if (group == null || !group.isRunning()) {
            group = new ToolActivityGroup();
            group.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(group);
            activityGroups.put(turnId, group);
        }
        group.addPendingLine(key, line);
        lastActivityGroup = group;
        relayout(group);
        ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
    }

    /**
     * 工具完成：终态行原位替换该工具名最早一条运行态占位行（同名并行按开始序
     * FIFO 配对）；无配对占位（开始信号被过滤/迟领养/既有路径）按普通行追加。
     */
    void completeToolActivity(long turnId, String key, String finalLine) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        ToolActivityGroup group = activityGroups.get(turnId);
        if (group != null && group.completePendingLine(key, finalLine)) {
            JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
            boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
            relayout(group);
            ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
            return;
        }
        addToolActivity(turnId, finalLine);
    }

    /** Finishes the given turn's running activity group, if any (no-op otherwise). */
    void finishActivityIfRunning(long turnId) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        ToolActivityGroup group = activityGroups.get(turnId);
        if (group != null && group.isRunning()) {
            group.finish();
        }
    }

    // --- Reasoning (thinking) card -------------------------------------------

    /** Appends a streamed reasoning token to the current (or a new) thinking card. */
    void appendReasoningToken(String token) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        // In-card growth: same smart-scroll treatment as addToolActivity
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
        boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
        if (thinkingCard == null || !thinkingCard.isRunning()) {
            finishReasoningIfRunning();
            thinkingCard = new ThinkingCard();
            thinkingCard.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(thinkingCard);
        }
        thinkingCard.appendText(token);
        relayout(thinkingCard);
        ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
    }

    /** Finishes the current thinking card (auto-collapses it). No-op when none. */
    void finishReasoning() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        finishReasoningIfRunning();
        finishStreamAnimations();
    }

    /**
     * 收尾全部流式段的思考卡 spinner（Stop 即时复位/回合终态共用；幂等）。
     * 覆盖 STREAM_ENDED(ABORTED) 事件尚未到达或被过滤（Stop 后 /new 代数翻转）
     * 的窗口——流式段卡不经旧单槽 {@code thinkingCard}，须在此显式收尾。
     */
    void finishStreamAnimations() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        for (TurnStreamState state : streamStates.values()) {
            for (StreamSegment segment : state.segments.values()) {
                if (segment.thinkingCard != null && segment.thinkingCard.isRunning()) {
                    segment.thinkingCard.finish();
                }
            }
        }
    }

    /**
     * 回合终态收敛：该回合全部流段置 ended + 收尾思考卡 spinner。终态后到达的
     * STREAM_ENDED 会被面板活回合过滤丢弃（Stop 后尤其），流段的 ended 不能
     * 依赖它置位——否则已排队的合并 flush 会在 "Stopped." 行之下为缓冲中的
     * 思考增量<b>新建运行中的孤儿卡</b>（无人再收尾，spinner 永转）。与
     * {@code clearTranscript} 的清屏失效同族防御，粒度为单回合。
     */
    void settleStreamTurn(long turnId) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        TurnStreamState state = streamStates.get(turnId);
        if (state == null) {
            return;
        }
        for (StreamSegment segment : state.segments.values()) {
            segment.ended = true;
            if (segment.thinkingCard != null && segment.thinkingCard.isRunning()) {
                segment.thinkingCard.finish();
            }
        }
    }

    /** Adds an already-collapsed thinking card (non-streamed responses). */
    void addReasoningBlock(String reasoning) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        finishReasoningIfRunning();
        ThinkingCard card = new ThinkingCard();
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(card);
        card.appendText(reasoning);
        card.finish();
        thinkingCard = card;
        relayout(card);
    }

    private void finishReasoningIfRunning() {
        if (thinkingCard != null && thinkingCard.isRunning()) {
            thinkingCard.finish();
        }
    }

    // --- 流式事件处理（按流段键控 + 合并渲染 + 终态权威收敛 + 清屏失效） --------

    /**
     * 流段事件入口（EDT；面板 PROGRESS 分发后转投）。每个 streamId（一次 LLM
     * 调用）独立建段：卡片对、待渲累计、终态位互不串卡。delta 载荷为累计文本，
     * 经 {@code ~100ms} 合并 flush（幂等重渲）；<b>乱序防护</b>：更短或同长不同
     * 内容的旧累计快照直接丢弃（累计通道单调只增）。STREAM_ENDED 载荷是两通道
     * 的<b>权威最终累计</b>——立即收敛渲染（正文卡终渲升级内嵌组件；思考卡补齐
     * 尾部或按重试整段替换），此后迟到 delta 丢弃。
     */
    void streamDelta(long turnId, StreamDelta delta) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        TurnStreamState state = streamStates.computeIfAbsent(turnId, k -> new TurnStreamState());
        state.sawStreamEvents = true;
        long streamId = delta.getStreamId();
        StreamSegment segment = state.segment(streamId);

        switch (delta.getKind()) {
            case STREAM_STARTED -> {
                if (segment.ended) {
                    return;   // 已结束流段的迟到事件
                }
                // 新流段（此前未见）＝新助手回复开始：收尾本回合上一轮的活动组与
                // 仍在旋转的思考卡——否则无正文的工具轮（思考+tool_calls 无 text 块）
                // 不触发正文卡路径的收尾，下一轮工具行会路由进上方仍在运行的
                // 旧活动组，破坏"思考→工具→思考→工具"的呈现顺序（按回合收尾：
                // 兄弟回合的组/卡不受本回合新流段影响）
                if (!segment.cardCreated) {
                    finishActivityIfRunning(turnId);
                    for (StreamSegment other : state.segments.values()) {
                        if (other != segment && other.thinkingCard != null
                                && other.thinkingCard.isRunning()) {
                            other.thinkingCard.finish();
                        }
                    }
                }
            }
            case CONTENT_DELTA, REASONING_DELTA -> {
                if (segment.ended) {
                    return;   // 迟到 delta 丢弃
                }
                if (delta.getKind() == StreamDelta.Kind.CONTENT_DELTA) {
                    segment.contentCumulative = monotonicallyNewer(
                            segment.contentCumulative, delta.getContentText());
                } else {
                    segment.reasoningCumulative = monotonicallyNewer(
                            segment.reasoningCumulative, delta.getReasoningText());
                }
                flushScheduler.schedule(this::flushPendingStreams);
            }
            case STREAM_ENDED -> {
                segment.ended = true;
                // 流段结束即收尾本段思考卡 spinner（成功/失败/中止一致——否则
                // 纯思考流或 Stop 中止后 "Thinking…" 无限旋转）
                if (segment.thinkingCard != null && segment.thinkingCard.isRunning()) {
                    segment.thinkingCard.finish();
                }
                // ended 载荷 = 两通道权威最终累计：直接收敛（成功终答终渲、重试/
                // 全败整段替换、思考尾部补齐——不再依赖节流缓冲）。空通道在
                // 整段替换（attemptSeq>1）语义下 = 新尝试无该通道内容——清理旧
                // 内容，不得静默保留上一尝试的残留
                String endContent = orEmpty(delta.getContentText());
                String endReasoning = orEmpty(delta.getReasoningText());
                boolean replaceWhole = delta.getAttemptSeq() > 1;
                if (!endContent.isEmpty()) {
                    ensureStreamContentCard(turnId, segment);
                    segment.contentCard.setMarkdownContent(endContent);
                    relayout(segment.contentCard);
                } else if (replaceWhole && segment.contentCard != null) {
                    removeStreamContentCard(segment);
                }
                if (!endReasoning.isEmpty()) {
                    convergeSegmentThinking(segment, endReasoning, replaceWhole);
                } else if (replaceWhole && segment.thinkingCard != null) {
                    disposeSegmentThinkingCards(segment);
                }
                followStreamGrowth();
            }
        }
    }

    /**
     * 乱序防护：载荷不比现有累计"更新"则丢弃（更短必旧；同长不同内容无法定序、
     * 保守取先到者）。返回应保留的累计值。
     */
    private static String monotonicallyNewer(String current, String incoming) {
        String candidate = orEmpty(incoming);
        if (candidate.length() < current.length()) {
            return current;   // 旧快照：丢弃
        }
        if (candidate.length() == current.length() && !candidate.equals(current)) {
            return current;   // 同长异文：无法定序，保守保持现状
        }
        return candidate;
    }

    /** 冲刷全部回合的待渲累计（EDT；一次 pass 渲染所有脏流段，已结束段跳过）。 */
    private void flushPendingStreams() {
        for (Map.Entry<Long, TurnStreamState> entry : List.copyOf(new ArrayList<>(streamStates.entrySet()))) {
            for (StreamSegment segment : List.copyOf(new ArrayList<>(entry.getValue().segments.values()))) {
                if (segment.ended) {
                    continue;
                }
                renderSegmentDeltas(entry.getKey(), segment);
            }
        }
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    /** 流式 tick 渲染：思考卡差分追加（正文后新到思考开新卡）、正文卡幂等全量重渲。 */
    private void renderSegmentDeltas(long turnId, StreamSegment segment) {
        if (!segment.reasoningCumulative.isEmpty()
                && segment.reasoningCumulative.length() > segment.appendedReasoningLen) {
            ensureStreamThinkingCard(segment);
            segment.thinkingCard.appendText(
                    segment.reasoningCumulative.substring(segment.appendedReasoningLen));
            segment.appendedReasoningLen = segment.reasoningCumulative.length();
            relayout(segment.thinkingCard);
        }
        if (!segment.contentCumulative.isEmpty()) {
            ensureStreamContentCard(turnId, segment);
            segment.contentCard.setMarkdownContent(segment.contentCumulative);
            relayout(segment.contentCard);
        }
        followStreamGrowth();
    }

    /**
     * 流式增长的滚动跟随（手势代际粘滞）：贴底起播挂基线，渲染落定滞后不脱离，
     * 真实用户手势即刻脱离——全量重渲的布局抖动不再被误判为用户上滚
     * （2026-11-07 手测问题 2：逐帧精确贴底捕获会让跟随停在某一轮输出）。
     */
    private void followStreamGrowth() {
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
        if (scrollPane == null) {
            return;
        }
        if (streamFollow.shouldFollow(ChatScroller.isPinnedToBottom(scrollPane),
                ChatScroller.gestureGeneration(scrollPane))) {
            ChatScroller.scrollToBottomIfPinned(scrollPane, true);
        }
    }

    /**
     * 懒建/换新该流段的当前思考卡：无卡时新建；当前卡已被收尾（本段首条可见
     * 正文触发折叠）后新到思考增量<b>开新卡</b>——同流段思考-正文-思考交错时
     * 保持时间顺序（spec「其后新到思考增量开新思考卡」）。
     */
    private void ensureStreamThinkingCard(StreamSegment segment) {
        if (segment.thinkingCard == null || !segment.thinkingCard.isRunning()) {
            newStreamThinkingCard(segment);
        }
    }

    /**
     * ENDED 思考通道权威收敛。形态判别用 {@code contentStarted}（首条可见正文
     * 已到）而非卡片的运行态——ENDED 入口先把指针卡收尾，运行态区分不了
     * "被本 ENDED 收尾"与"正文后收尾"：
     * ① 整段替换（attemptSeq&gt;1）→ 收敛回首卡整段重写；② 从未有卡 → 整段
     * 写入新卡；③ 正文后仍有尾部思考 → 尾部开新卡（前段已在旧卡）；④ 其余 →
     * 尾部追加/回退重写当前卡（纯思考流的 ENDED 收尾属此形态）。
     */
    private void convergeSegmentThinking(StreamSegment segment, String endReasoning, boolean replaceWhole) {
        if (replaceWhole) {
            collapseSegmentThinkingCards(segment);
        }
        if (segment.thinkingCards.isEmpty()) {
            ThinkingCard card = newStreamThinkingCard(segment);
            card.replaceText(endReasoning);
        } else if (!replaceWhole && segment.contentStarted
                && endReasoning.length() > segment.appendedReasoningLen) {
            ThinkingCard card = newStreamThinkingCard(segment);
            card.appendText(endReasoning.substring(segment.appendedReasoningLen));
        } else if (replaceWhole || endReasoning.length() < segment.appendedReasoningLen) {
            segment.thinkingCard.replaceText(endReasoning);
        } else if (endReasoning.length() > segment.appendedReasoningLen) {
            segment.thinkingCard.appendText(
                    endReasoning.substring(segment.appendedReasoningLen));
        }
        segment.appendedReasoningLen = endReasoning.length();
        relayout(segment.thinkingCard);
    }

    /** 建新思考卡并入段（创建序队尾、更新指针）。 */
    private ThinkingCard newStreamThinkingCard(StreamSegment segment) {
        ThinkingCard card = new ThinkingCard();
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(card);
        segment.thinkingCards.add(card);
        segment.thinkingCard = card;
        segment.cardCreated = true;
        return card;
    }

    /** 整段替换时把段的全部思考卡收敛回首卡（dispose 其余；无卡则无操作）。 */
    private void collapseSegmentThinkingCards(StreamSegment segment) {
        if (segment.thinkingCards.isEmpty()) {
            return;
        }
        ThinkingCard first = segment.thinkingCards.get(0);
        for (int i = 1; i < segment.thinkingCards.size(); i++) {
            ThinkingCard extra = segment.thinkingCards.get(i);
            extra.dispose();
            remove(extra);
        }
        segment.thinkingCards.clear();
        segment.thinkingCards.add(first);
        segment.thinkingCard = first;
        revalidate();
        repaint();
    }

    /** 移除段的全部思考卡（整段替换的新尝试无思考通道内容）。 */
    private void disposeSegmentThinkingCards(StreamSegment segment) {
        for (ThinkingCard card : segment.thinkingCards) {
            card.dispose();
            remove(card);
        }
        segment.thinkingCards.clear();
        segment.thinkingCard = null;
        segment.appendedReasoningLen = 0;
        revalidate();
        repaint();
    }

    /** 移除段的正文卡（整段替换的新尝试无正文——旧正文不得残留）。 */
    private void removeStreamContentCard(StreamSegment segment) {
        cards.remove(segment.contentCard);
        remove(segment.contentCard);
        segment.contentCard = null;
        revalidate();
        repaint();
    }

    /** 懒建该流段的正文卡（首条非空 CONTENT_DELTA 时；纯思考流不出空卡）。 */
    private void ensureStreamContentCard(long turnId, StreamSegment segment) {
        if (segment.contentCard == null) {
            // 首条可见正文触发既有不变式：折叠本段思考卡 + 收尾本回合活动组
            // （旧路径由 addAssistantMarkdown 隐式保证，流式路径在此显式接线；
            // 按回合收尾——兄弟回合的活动组不受影响）
            if (segment.thinkingCard != null && segment.thinkingCard.isRunning()) {
                segment.thinkingCard.finish();
            }
            finishActivityIfRunning(turnId);
            MessageCard card = new MessageCard(MessageCard.Role.ASSISTANT, baseFont);
            cards.add(card);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(card);
            segment.contentCard = card;
            segment.cardCreated = true;
            segment.contentStarted = true;
        }
    }

    /** 该回合是否见过流式事件（渲染端抑制 THINKING/INTERMEDIATE 全文回放的事实源）。 */
    boolean hasStreamedEvents(long turnId) {
        TurnStreamState state = streamStates.get(turnId);
        return state != null && state.sawStreamEvents;
    }

    /**
     * 终答原位重渲：按最终流段 id 查该段的正文卡，以权威终答全量重渲
     * （goal 后缀/标签剥离差异自动并入同卡）。查无卡片（终答为空/纯思考流/
     * 面板晚领养）返回 false——调用方回退既有新增卡路径。
     */
    boolean renderStreamedFinal(long turnId, long finalStreamId, String finalContent) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        TurnStreamState state = streamStates.get(turnId);
        if (state == null) {
            return false;
        }
        StreamSegment segment = state.segments.get(finalStreamId);
        if (finalContent == null || finalContent.isEmpty()) {
            return false;   // 空终答走既有占位路径
        }
        if (segment == null || segment.contentCard == null) {
            return false;   // 纯思考流/晚领养：无卡可覆盖
        }
        // 终答覆盖 = 该流段终态：置 ended 使已排队的合并 flush 失效（旧累计不得
        // 回写权威终答——与 STREAM_ENDED 同一防御），并收尾思考卡 spinner
        segment.ended = true;
        if (segment.thinkingCard != null && segment.thinkingCard.isRunning()) {
            segment.thinkingCard.finish();
        }
        segment.contentCard.setMarkdownContent(finalContent);
        relayout(segment.contentCard);
        followStreamGrowth();
        return true;
    }

    // --- Thinking indicator ---------------------------------------------------

    /** Shows the animated "AI is thinking" row at the bottom of the transcript. */
    void showThinking() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        if (thinkingRow != null) {
            return;
        }
        thinkingRow = new ThinkingRow();
        insertBeforeGlue(thinkingRow);
        relayout(thinkingRow);
    }

    /** Removes the thinking row (no-op when not showing). */
    void hideThinking() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        if (thinkingRow != null) {
            thinkingRow.dispose();
            remove(thinkingRow);
            thinkingRow = null;
            revalidate();
            repaint();
        }
    }

    // --- Lifecycle -------------------------------------------------------------

    /** Clears the whole transcript (new conversation / remote reset / consolidation). */
    void clearTranscript() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        // 清屏失效（同 EDT 步骤）：取消已排队的合并 flush 并弃全部流式缓冲/状态——
        // 代数过滤只挡新事件，挡不住已排队任务；不取消会在清屏后插入旧会话孤儿卡
        flushScheduler.cancel();
        streamFollow.reset();
        for (TurnStreamState state : streamStates.values()) {
            for (StreamSegment segment : state.segments.values()) {
                for (ThinkingCard card : segment.thinkingCards) {
                    card.dispose();
                }
            }
        }
        streamStates.clear();
        hideThinking();
        for (ToolActivityGroup group : activityGroups.values()) {
            group.dispose();
        }
        activityGroups.clear();
        lastActivityGroup = null;
        if (thinkingCard != null) {
            thinkingCard.dispose();
            thinkingCard = null;
        }
        cards.clear();
        removeAll();
        add(glue);
        revalidate();
        repaint();
    }

    /** Propagates a new base font to all message cards (zoom support). */
    void applyFont(Font font) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        if (font == null) {
            return;
        }
        this.baseFont = font;
        for (MessageCard card : cards) {
            card.applyFont(font);
        }
        revalidate();
        repaint();
    }

    /** Re-applies theme-derived colors on look-and-feel changes. */
    void refreshTheme() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        for (MessageCard card : cards) {
            card.applyTheme();
        }
        for (ToolActivityGroup group : activityGroups.values()) {
            group.applyTheme();
        }
        if (thinkingCard != null) {
            thinkingCard.applyTheme();
        }
        if (thinkingRow != null) {
            thinkingRow.applyTheme();
        }
        // System notes carry their semantic tone as a client property so the
        // re-theme can re-derive surface/matte colors without extra bookkeeping
        for (int i = 0; i < getComponentCount(); i++) {
            Component c = getComponent(i);
            if (c instanceof JTextArea) {
                Color tone = (Color) ((JTextArea) c).getClientProperty(SYSTEM_NOTE_TONE_KEY);
                if (tone != null) {
                    applySystemNoteTheme((JTextArea) c, tone);
                }
            }
        }
        revalidate();
        repaint();
    }

    // --- Scrollable --------------------------------------------------------------

    /**
     * Track the viewport width so cards wrap text instead of overflowing -
     * without this the scroll pane shows a horizontal scrollbar whenever a
     * message is wider than the visible area.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** Height stays content-driven so the vertical scrollbar appears. */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
        java.awt.Rectangle visibleRect,
        int orientation,
        int direction
    ) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
        java.awt.Rectangle visibleRect,
        int orientation,
        int direction
    ) {
        return Math.max(visibleRect.height - 16, 16);
    }

    // --- Test hooks -----------------------------------------------------------------

    /** Number of message cards currently shown (for tests). */
    int getCardCount() {
        return cards.size();
    }

    /** The card at the given index (for tests). */
    MessageCard getCard(int index) {
        return cards.get(index);
    }

    /** The current thinking card, or null when no reasoning was shown (for tests). */
    ThinkingCard getThinkingCard() {
        return thinkingCard;
    }

    /** 该回合最新流段的思考卡（流式路径专用，独立于旧单槽；测试观测面）。 */
    ThinkingCard getStreamThinkingCard(long turnId) {
        TurnStreamState state = streamStates.get(turnId);
        if (state == null || state.segments.isEmpty()) {
            return null;
        }
        ThinkingCard latest = null;
        long latestId = Long.MIN_VALUE;
        for (Map.Entry<Long, StreamSegment> e : state.segments.entrySet()) {
            if (e.getKey() >= latestId) {
                latestId = e.getKey();
                latest = e.getValue().thinkingCard;
            }
        }
        return latest;
    }

    /** The most recently written activity group, or null (for tests). */
    ToolActivityGroup getActivityGroup() {
        return lastActivityGroup;
    }

    /** True while the "AI is thinking" row is showing (for tests). */
    boolean isThinkingShowing() {
        return thinkingRow != null;
    }

    /**
     * 全转录可见文本（测试观测面，EDT 读取）：按插入序拼接各组件文本。用户卡合成
     * {@code "You: "} 前缀——对齐旧 HTML 路线 "You: xxx" 的断言口径，使既有回合
     * 呈现测试的 contains/计数断言无需逐条改写；思考行以字面 "AI is thinking"
     * 计入（loading 指示计数观测面）。
     */
    String visibleTextForTests() {
        StringBuilder sb = new StringBuilder();
        for (Component c : getComponents()) {
            if (c instanceof MessageCard) {
                MessageCard card = (MessageCard) c;
                if (card.getRole() == MessageCard.Role.USER) {
                    sb.append("You: ");
                }
                sb.append(card.getText()).append('\n');
            } else if (c instanceof ThinkingCard) {
                sb.append(((ThinkingCard) c).getText()).append('\n');
            } else if (c instanceof ToolActivityGroup) {
                sb.append(((ToolActivityGroup) c).getText()).append('\n');
            } else if (c instanceof ThinkingRow) {
                sb.append("AI is thinking").append('\n');
            } else if (c instanceof JTextArea) {
                // System notes render as plain JTextAreas
                sb.append(((JTextArea) c).getText()).append('\n');
            }
        }
        return sb.toString();
    }

    // --- Internals ----------------------------------------------------------------------

    private void addCard(MessageCard card) {
        cards.add(card);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(card);
        relayout(card);
    }

    /**
     * Inserts a component before the bottom glue (i.e. appends to the visible
     * end of the transcript), applying the smart-scroll protocol: capture the
     * pinned state before the mutation, reveal the new content afterwards only
     * if the user was at the tail.
     */
    private void insertBeforeGlue(Component c) {
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
        boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
        remove(glue);
        add(c);
        add(glue);
        revalidate();
        repaint();
        ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
    }

    /**
     * BoxLayout only stretches a component up to its maximum size; re-pin the
     * maximum to the current preferred size so cards fill the width but keep
     * their natural (content-driven) height.
     */
    private static void relayout(Component c) {
        if (c instanceof javax.swing.JComponent) {
            javax.swing.JComponent jc = (javax.swing.JComponent) c;
            jc.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, jc.getPreferredSize().height)
            );
        }
        c.revalidate();
    }
}
