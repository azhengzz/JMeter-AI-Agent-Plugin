package org.gitee.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/**
 * Collapsible card in the transcript that shows the model's reasoning. The body
 * is collapsed by default: while thinking streams in, a spinner animates next
 * to a "Thinking" header and the text accumulates (hidden until the user clicks
 * the header); when thinking finishes the header switches to a "Thoughts"
 * summary-with-preview line. Modelled on {@link ToolActivityGroup}.
 */
class ThinkingCard extends JPanel {

    /** ASCII spinner frames (braille spinners have spotty font coverage). */
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
    private static final int SPINNER_DELAY_MS = 120;
    private static final int MAX_HEADER_CHARS = 60;

    private final JLabel headerLabel;
    private final JTextArea bodyArea;
    private final JPanel bodyWrapper;
    private final Timer spinnerTimer;

    private int spinnerFrame = 0;
    private boolean running = true;
    private boolean collapsed = true;
    /**
     * 用户手动折叠/展开过（手势位）：自动收尾（finish）与整段替换都不得覆盖
     * 用户的选择——自动折叠只作用于未被碰过的卡；替换可重新展开的仅是
     * 「自动折叠」的卡（双向保护：手动折叠不被强展、手动展开不被强收）。
     */
    private boolean userToggled = false;

    ThinkingCard() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_1, UiTokens.SPACE_2));

        headerLabel = LabelUtils.plain("");
        headerLabel.setFont(UiTokens.label(headerLabel.getFont()));
        headerLabel.setForeground(ThemeColors.secondaryText());
        headerLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_2, UiTokens.SPACE_3));
        headerLabel.addMouseListener(
            new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    setCollapsed(!collapsed);
                }
            }
        );
        add(headerLabel, BorderLayout.NORTH);

        bodyArea = new JTextArea();
        bodyArea.setEditable(false);
        // Reasoning streams into this read-only area; its caret must never
        // drag the transcript away from the user's chosen reading position.
        ((javax.swing.text.DefaultCaret) bodyArea.getCaret())
                .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        bodyArea.setOpaque(false);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        bodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bodyArea.setForeground(ThemeColors.secondaryText());
        bodyArea.setBorder(BorderFactory.createEmptyBorder(
                0, UiTokens.SPACE_4, UiTokens.SPACE_3, UiTokens.SPACE_3));

        bodyWrapper = new JPanel(new BorderLayout());
        bodyWrapper.setOpaque(false);
        bodyWrapper.add(bodyArea, BorderLayout.CENTER);
        // 默认收起（2026-11-08 需求）：流式思考期间正文闭合，运行态经头部
        // spinner + "Thinking…" 呈现；思考完毕头部切换为 Thoughts 预览行。
        // 用户可随时点击头部展开查看实时思考。
        bodyWrapper.setVisible(false);
        add(bodyWrapper, BorderLayout.CENTER);

        spinnerTimer = new Timer(SPINNER_DELAY_MS, e -> {
            spinnerFrame = (spinnerFrame + 1) % SPINNER_FRAMES.length;
            updateHeader();
        });
        spinnerTimer.start();
        updateHeader();
    }

    /** Appends streamed reasoning text to the card body. */
    void appendText(String text) {
        bodyArea.append(text);
        updateHeader();
        revalidate();
    }

    /**
     * 整段替换（流式重试成功/失败收敛）：原地重写正文，卡片组件不变。
     * 折叠态不因替换改变（默认收起口径下替换后保持闭合，用户手动展开/折叠
     * 的选择同样保留）；替换即本段终态：停 spinner，头部切 Thoughts 预览。
     */
    void replaceText(String text) {
        running = false;
        spinnerTimer.stop();
        bodyArea.setText(text != null ? text : "");
        updateHeader();
        revalidate();
    }

    /** 清空正文并收敛为折叠终态（组件复用、不销毁；用户手动展开的选择保留）。 */
    void clear() {
        replaceText("");
        if (!userToggled) {
            collapsed = true;
            bodyWrapper.setVisible(false);
            updateHeader();
        }
    }

    /**
     * Marks thinking as finished: stops the spinner and auto-collapses the
     * body (除非用户手动展开过——手势位保护), leaving a clickable "Thoughts"
     * summary. Idempotent.
     */
    void finish() {
        if (!running) {
            return;
        }
        running = false;
        spinnerTimer.stop();
        if (!userToggled) {
            // 自动折叠不经手势位（setCollapsed 会置 userToggled，误把自动态钉成手动态）
            collapsed = true;
            bodyWrapper.setVisible(false);
            updateHeader();
            revalidate();
        }
    }

    /** True while reasoning is still streaming (spinner active). */
    boolean isRunning() {
        return running;
    }

    /** Toggles body visibility and updates the header affordance (user gesture). */
    void setCollapsed(boolean collapsed) {
        userToggled = true;
        this.collapsed = collapsed;
        bodyWrapper.setVisible(!collapsed);
        updateHeader();
        revalidate();
    }

    boolean isCollapsed() {
        return collapsed;
    }

    /** The accumulated reasoning text. */
    String getText() {
        return bodyArea.getText();
    }

    /** The current header text (for tests). */
    String getHeaderText() {
        return headerLabel.getText();
    }

    /** True while the spinner timer is running (for tests / dispose checks). */
    boolean isSpinnerRunning() {
        return spinnerTimer.isRunning();
    }

    void applyTheme() {
        bodyArea.setForeground(ThemeColors.secondaryText());
        updateHeader();
        repaint();
    }

    /** Stops the spinner timer; call when discarding the card. */
    void dispose() {
        spinnerTimer.stop();
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int x = UiTokens.SPACE_2;
            int width = Math.max(0, getWidth() - UiTokens.SPACE_4 - 1);
            int height = Math.max(0, getHeight() - UiTokens.SPACE_2 - 1);
            int arc = UiTokens.RADIUS_MEDIUM * 2;
            g2.setColor(ThemeColors.subtleSurface());
            g2.fillRoundRect(x, UiTokens.SPACE_1, width, height, arc, arc);
            g2.setColor(ThemeColors.separator());
            g2.drawRoundRect(x, UiTokens.SPACE_1, width, height, arc, arc);
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }

    private void updateHeader() {
        String chevron = collapsed ? "▸ " : "▾ ";
        headerLabel.setForeground(running ? ThemeColors.accent() : ThemeColors.secondaryText());
        if (running) {
            headerLabel.setText(chevron + SPINNER_FRAMES[spinnerFrame] + " Thinking…");
        } else {
            headerLabel.setText(chevron + "✦ Thoughts" + previewSuffix());
        }
    }

    /** A short single-line preview of the reasoning for the collapsed header. */
    private String previewSuffix() {
        String text = bodyArea.getText().replace('\n', ' ').trim();
        if (text.isEmpty()) {
            return "";
        }
        String preview = text.length() > MAX_HEADER_CHARS
                ? cutWithoutSplittingSurrogate(text, MAX_HEADER_CHARS) + "…"
                : text;
        return " — " + preview;
    }

    /** Cuts at {@code limit} chars, backing off one rather than splitting a surrogate pair. */
    private static String cutWithoutSplittingSurrogate(String text, int limit) {
        int end = Math.min(limit, text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
