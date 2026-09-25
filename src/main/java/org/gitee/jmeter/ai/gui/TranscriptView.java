package org.gitee.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import javax.swing.JPanel;
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
    private ToolActivityGroup activityGroup;
    private ThinkingRow thinkingRow;
    private ThinkingCard thinkingCard;

    TranscriptView(Font baseFont) {
        this.baseFont = baseFont;
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
        JTextArea note = new JTextArea(text);
        note.setEditable(false);
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

    /** Routes a tool-activity line into the current (or a new) group. */
    void addToolActivity(String line) {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        // In-card growth changes the transcript height without a component insert —
        // apply the smart-scroll protocol around it so the tail keeps being followed
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(this);
        boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
        if (activityGroup == null || !activityGroup.isRunning()) {
            activityGroup = new ToolActivityGroup();
            activityGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(activityGroup);
        }
        activityGroup.addLine(line);
        relayout(activityGroup);
        ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
    }

    /** Finishes the running activity group, if any (no-op otherwise). */
    void finishActivityIfRunning() {
        assert EventQueue.isDispatchThread() : "TranscriptView must be mutated on the EDT";
        if (activityGroup != null && activityGroup.isRunning()) {
            activityGroup.finish();
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
        hideThinking();
        if (activityGroup != null) {
            activityGroup.dispose();
            activityGroup = null;
        }
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
        if (activityGroup != null) {
            activityGroup.applyTheme();
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

    /** The current activity group, or null (for tests). */
    ToolActivityGroup getActivityGroup() {
        return activityGroup;
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
