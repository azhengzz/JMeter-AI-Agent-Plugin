package org.gitee.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.gitee.jmeter.ai.gui.render.MarkdownRenderer;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single chat message rendered as a card in the transcript: a slim header
 * (sender name + per-message Copy button) above a body that shows either
 * markdown-rendered content or plain text.
 *
 * <p>User cards get a softly tinted rounded bubble background; assistant cards
 * stay flat/full-width in the style of modern AI chat tools. The card keeps the
 * raw markdown source so Copy reproduces exactly what the AI returned. Must be
 * mutated on the EDT (enforced by {@link TranscriptView}).
 */
class MessageCard extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(MessageCard.class);

    /** Who sent the message. */
    enum Role {
        USER("You"),
        ASSISTANT("Gitee Ai");

        private final String displayName;

        Role(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    /** Corner radius (px) for the user bubble's rounded background. */
    static final int BUBBLE_ARC = UiTokens.RADIUS_LARGE * 2;

    private final Role role;
    private final JTextPane body;
    private JLabel senderLabel;
    private final StringBuilder rawText = new StringBuilder();

    MessageCard(Role role, Font font) {
        super(new BorderLayout());
        this.role = role;

        // Always non-opaque: the user bubble's rounded background is painted
        // manually in paintComponent (a JPanel background fill is rectangular).
        setOpaque(false);
        applyTheme();
        setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_2, UiTokens.SPACE_3,
                        UiTokens.SPACE_3, UiTokens.SPACE_3)
            )
        );

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.add(createHeader());
        add(top, BorderLayout.NORTH);

        body = new JTextPane();
        body.setEditable(false);
        body.setOpaque(false);
        if (font != null) {
            body.setFont(font);
        }
        // Embedded scrollers (tables, code blocks) are content-sized; their width
        // must follow the body's laid-out width or wide content would be silently
        // clipped: JTextPane never wraps embedded components and the transcript
        // has no horizontal scrollbar.
        body.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fitEmbeddedScrollers();
            }
        });
        add(body, BorderLayout.CENTER);
    }

    /** Header row: bold sender name on the left, Copy button on the right. */
    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        // Sender names are fixed plugin strings, but keep the plain-label rule
        // uniform for every label in the transcript.
        senderLabel = LabelUtils.plain(role.displayName());
        senderLabel.setFont(UiTokens.label(senderLabel.getFont()));
        applySenderTheme();
        header.add(senderLabel, BorderLayout.WEST);

        JButton copy = new QuietButton("Copy");
        copy.setIcon(ActionIcons.copy(12));
        copy.setIconTextGap(UiTokens.SPACE_1);
        copy.setToolTipText("Copy this message");
        copy.setFont(UiTokens.caption(copy.getFont()));
        copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(
                    new java.awt.datatransfer.StringSelection(getText()),
                    null
                );
            copy.setText("Copied ✓");
            Timer timer = new Timer(2000, ev -> copy.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        });

        JPanel copyWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        copyWrap.setOpaque(false);
        copyWrap.add(copy);
        header.add(copyWrap, BorderLayout.EAST);
        return header;
    }

    /** The raw (markdown-source) text of this message. */
    String getText() {
        return rawText.toString();
    }

    Role getRole() {
        return role;
    }

    /**
     * Renders markdown content into the card body, replacing anything shown
     * so far.
     */
    void setMarkdownContent(String markdown) {
        String content = markdown != null ? markdown : "";
        rawText.setLength(0);
        rawText.append(content);
        try {
            body.setText("");
            MarkdownRenderer.process(body.getStyledDocument(), content, body.getFont());
        } catch (BadLocationException e) {
            log.error("Error rendering message markdown", e);
        }
        fitEmbeddedScrollers();
        revalidate();
        repaint();
    }

    /** Shows plain text without markdown parsing (e.g. user messages). */
    void setPlainContent(String text) {
        String content = text != null ? text : "";
        rawText.setLength(0);
        rawText.append(content);
        try {
            body.setText("");
            StyledDocument doc = body.getStyledDocument();
            doc.insertString(0, content, new SimpleAttributeSet());
        } catch (BadLocationException e) {
            log.error("Error rendering plain message", e);
        }
        revalidate();
        repaint();
    }

    /**
     * Pins every embedded scroller (table grid, code block) to the body's
     * current inner width: narrow content keeps its natural size, content wider
     * than the card gets an in-card horizontal scrollbar instead of being cut
     * off at the clip edge. The preferred height compensates for the scrollbar
     * that will appear, so no row or line hides underneath it.
     */
    void fitEmbeddedScrollers() {
        int avail = body.getWidth() - body.getInsets().left - body.getInsets().right;
        if (avail <= 0) {
            return;
        }
        StyledDocument doc = body.getStyledDocument();
        for (int i = 0; i < doc.getLength(); i++) {
            Component c = StyleConstants.getComponent(
                    doc.getCharacterElement(i).getAttributes());
            if (c instanceof JScrollPane) {
                JScrollPane scroller = (JScrollPane) c;
                Component view = scroller.getViewport().getView();
                if (view == null) {
                    continue;
                }
                Dimension content = view.getPreferredSize();
                boolean needsH = content.width > avail;
                int hsbHeight = needsH
                        ? scroller.getHorizontalScrollBar().getPreferredSize().height
                        : 0;
                Dimension size = new Dimension(
                        Math.min(content.width, avail),
                        content.height + hsbHeight);
                scroller.setPreferredSize(size);
                // Pin the maximum to the same size: the text pane's paragraph
                // layout stretches any embedded component whose maximum exceeds
                // its preferred size to consume the whole row width, which is
                // exactly the blank-space stretch this clamp exists to prevent.
                scroller.setMaximumSize(size);
                scroller.revalidate();
            }
        }
    }

    /** The card body's document (for tests). */
    StyledDocument getBodyDocument() {
        return body.getStyledDocument();
    }

    /** Applies a new body font (used by the zoom/font-scale feature). */
    void applyFont(Font font) {
        if (font != null) {
            body.setFont(font);
        }
        // Baked heading/code sizes in the rendered document follow the new font
        // only through a re-render (raw markdown source is kept for exactly this)
        rerenderIfNeeded();
    }

    /** Re-applies theme-derived colors (called on look-and-feel changes). */
    void applyTheme() {
        if (role == Role.USER) {
            setBackground(ThemeColors.userBubbleBackground());
        }
        applySenderTheme();
        if (body != null) {
            body.setForeground(ThemeColors.foreground());
        }
        // Colors baked into the rendered document (inline-code backgrounds, link
        // accents, embedded code/table components) re-derive via re-render
        rerenderIfNeeded();
        repaint();
    }

    /**
     * Re-renders the assistant body from the kept raw markdown so baked-in
     * theme colors and font sizes follow the current theme/zoom. No-op for
     * user cards (plain text carries no baked styling).
     */
    private void rerenderIfNeeded() {
        if (body != null && role == Role.ASSISTANT && rawText.length() > 0) {
            setMarkdownContent(rawText.toString());
        }
    }

    private void applySenderTheme() {
        if (senderLabel != null) {
            senderLabel.setForeground(
                    role == Role.USER ? ThemeColors.secondaryText() : ThemeColors.accent());
        }
    }

    /**
     * BoxLayout stretches a card up to its maximum size; report the live
     * preferred height so the card always fully fits after content reflows
     * (a wide table gaining a scrollbar, theme re-render, font zoom) instead
     * of being clipped by a stale maximum pinned at insert time.
     */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * Paints the user bubble as a rounded card (fill + subtle 1px border)
     * inside the outer spacing margin. Assistant cards paint nothing here
     * and stay flat.
     */
    @Override
    protected void paintComponent(java.awt.Graphics g) {
        if (role == Role.USER) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                );
                Insets outer = new Insets(
                        UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2);
                int x = outer.left;
                int y = outer.top;
                int w = getWidth() - outer.left - outer.right - 1;
                int h = getHeight() - outer.top - outer.bottom - 1;
                g2.setColor(getBackground());
                g2.fillRoundRect(x, y, w, h, BUBBLE_ARC, BUBBLE_ARC);
                g2.setColor(ThemeColors.blend(
                        ThemeColors.accent(), ThemeColors.separator(), 0.22f));
                g2.drawRoundRect(x, y, w, h, BUBBLE_ARC, BUBBLE_ARC);
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }
}
