package org.gitee.jmeter.ai.gui.render;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.gitee.jmeter.ai.gui.ActionIcons;
import org.gitee.jmeter.ai.gui.LabelUtils;
import org.gitee.jmeter.ai.gui.QuietButton;
import org.gitee.jmeter.ai.gui.theme.SlimScrollBarUI;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/**
 * Renders a fenced code block as an embedded panel inside the chat transcript:
 * a slim header bar (language label + Copy button) above the monospaced code,
 * wrapped in a subtle theme-aware rounded border. The panel is embedded into
 * the document as a live component via {@link StyleConstants#setComponent}, so
 * the Copy button stays clickable inside the text flow.
 *
 * <p>Long lines scroll horizontally inside the panel instead of being cut off
 * (JTextPane never wraps embedded components and the transcript has no
 * horizontal scrollbar); the wrapper's width is clamped to the card by
 * {@code MessageCard} once it is laid out.
 */
final class CodeBlockRenderer {

    private CodeBlockRenderer() {
    }

    /**
     * Inserts a styled code block panel at the end of the document.
     *
     * @param doc      the transcript document
     * @param code     the code to render (leading/trailing blank lines trimmed)
     * @param language the fence language label, may be empty
     */
    static void render(StyledDocument doc, String code, String language)
        throws BadLocationException {
        Color codeBg = ThemeColors.codeBackground();

        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBackground(codeBg);
        codePanel.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator(), 1, true),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_1,
                        UiTokens.SPACE_1, UiTokens.SPACE_1)
            )
        );

        codePanel.add(createHeader(code, language, codeBg), BorderLayout.NORTH);
        codePanel.add(createCodeArea(code, codeBg), BorderLayout.CENTER);

        JScrollPane scroller = SlimScrollBarUI.scroller(codePanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroller.setBorder(null);
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        SlimScrollBarUI.install(scroller);

        // Insert the code panel into the document as a live component
        SimpleAttributeSet panelStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(panelStyle, scroller);
        doc.insertString(doc.getLength(), " ", panelStyle);

        // Add extra spacing after the code block
        SimpleAttributeSet spacer = new SimpleAttributeSet();
        StyleConstants.setFontFamily(spacer, Font.MONOSPACED);
        doc.insertString(doc.getLength(), "\n", spacer);
    }

    /** Header bar: language label on the left, Copy button on the right. */
    private static JPanel createHeader(String code, String language, Color codeBg) {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(codeBg);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_1, UiTokens.SPACE_1));

        JLabel languageLabel = LabelUtils.plain(
            language.isEmpty() ? "code" : language.toLowerCase()
        );
        languageLabel.setFont(UiTokens.label(languageLabel.getFont()));
        languageLabel.setForeground(ThemeColors.secondaryText());
        headerPanel.add(languageLabel, BorderLayout.WEST);

        JButton copyButton = new QuietButton("Copy");
        copyButton.setIcon(ActionIcons.copy(12));
        copyButton.setIconTextGap(UiTokens.SPACE_1);
        copyButton.setToolTipText("Copy code to clipboard");
        copyButton.setFont(UiTokens.caption(copyButton.getFont()));
        copyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyButton.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(
                    new java.awt.datatransfer.StringSelection(code),
                    null
                );

            // Provide visual feedback
            copyButton.setText("Copied ✓");
            Timer timer = new Timer(2000, event -> copyButton.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        });

        headerPanel.add(copyButton, BorderLayout.EAST);
        return headerPanel;
    }

    /** Read-only monospaced code body sized to the current UI font. */
    private static Component createCodeArea(String code, Color codeBg) {
        JTextArea codeArea = new JTextArea(code.trim()); // Trim to remove extra lines
        Font base = UIManager.getFont("TextField.font");
        float size = base != null ? base.getSize2D() : 12f;
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size)));
        codeArea.setEditable(false);
        codeArea.setBackground(codeBg);
        codeArea.setForeground(ThemeColors.themeColor("TextArea.foreground", ThemeColors.foreground()));
        codeArea.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_1, UiTokens.SPACE_2,
                UiTokens.SPACE_2, UiTokens.SPACE_2));
        return codeArea;
    }
}
