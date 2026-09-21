package org.gitee.jmeter.ai.intellisense;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Manages intellisense functionality for the input text area in the AI Chat Panel.
 * This class handles detecting when to show command suggestions and inserting
 * selected commands into the text area.
 */
public class InputBoxIntellisense {
    private final JTextArea textArea;
    private final CommandIntellisenseProvider intellisenseProvider;
    private final MentionSuggestionProvider mentionProvider;
    private final IntellisensePopup intellisensePopup;

    /**
     * Creates a new InputBoxIntellisense for the specified text area.
     *
     * @param textArea The text area to add intellisense to
     */
    public InputBoxIntellisense(JTextArea textArea) {
        this(textArea, null);
    }

    /**
     * Creates a new InputBoxIntellisense with an additional '@'-mention provider.
     *
     * @param textArea The text area to add intellisense to
     * @param mentionProvider Provider for '@'-triggered suggestions, or null for none
     */
    public InputBoxIntellisense(JTextArea textArea, MentionSuggestionProvider mentionProvider) {
        this.textArea = textArea;
        this.intellisenseProvider = new CommandIntellisenseProvider();
        this.mentionProvider = mentionProvider;
        this.intellisensePopup = new IntellisensePopup();

        setupKeyListeners();
        setupMouseListeners();
        setupRefreshCallback();
        setupDocumentListener();
    }

    /**
     * Sets up key listeners for the text area to handle intellisense activation and navigation.
     */
    private void setupKeyListeners() {
        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Handle Enter or Tab for intellisense selection
                if (intellisensePopup.isVisible()) {
                    if ((e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) || e.getKeyCode() == KeyEvent.VK_TAB) {
                        e.consume();
                        insertSelectedCommand();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                        int curr = intellisensePopup.getSelectedIndex();
                        int next = (curr + 1) % intellisensePopup.getSuggestionCount();
                        intellisensePopup.setSelectedIndex(next);
                        e.consume();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                        int curr = intellisensePopup.getSelectedIndex();
                        int prev = (curr - 1 + intellisensePopup.getSuggestionCount()) % intellisensePopup.getSuggestionCount();
                        intellisensePopup.setSelectedIndex(prev);
                        e.consume();
                        return;
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        intellisensePopup.hide();
                        e.consume();
                        return;
                    }
                }
            }
            
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.isActionKey() || e.isControlDown() || e.isMetaDown() || e.isAltDown()) {
                    return;
                }
                
                updateIntellisense();
            }
        });
    }
    
    /**
     * Sets up mouse listeners for the intellisense popup.
     */
    private void setupMouseListeners() {
        intellisensePopup.addSuggestionClickListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) {
                    insertSelectedCommand();
                    intellisensePopup.hide();
                }
            }
        });
    }

    /**
     * 文档级刷新:IME 中文提交、粘贴等<b>不产生 keyReleased</b>的文本变化也要重算候选。
     * (拼音敲击有 key 事件,但 IME 的 commit 只发 InputMethodEvent——key 监听器收不到,
     * 中文搜索失效的根因。)invokeLater 让插入路径的 setText→setCaretPosition 先落位,
     * 再以最终 caret 计算 prefix。
     */
    private void setupDocumentListener() {
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleUpdate();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleUpdate();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleUpdate();
            }
        });
    }

    private void scheduleUpdate() {
        SwingUtilities.invokeLater(this::updateIntellisense);
    }

    /**
     * Registers a refresh callback with the mention provider so the popup
     * updates in place when its underlying data refreshes (e.g. background
     * instance discovery completes). updateIntellisense re-derives the current
     * trigger, so a caret no longer in an '@' token simply hides the popup —
     * and a cold first '@' (empty snapshot, popup not yet shown) opens it
     * once discovery lands.
     */
    private void setupRefreshCallback() {
        if (mentionProvider == null) {
            return;
        }
        mentionProvider.addRefreshCallback(this::updateIntellisense);
    }
    
    /**
     * Updates the intellisense popup based on the current text and caret position.
     * Triggers on both @ and / prefix characters.
     */
    private void updateIntellisense() {
        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        int triggerIdx = findTriggerIndex(text, caret);

        if (triggerIdx >= 0) {
            String prefix = text.substring(triggerIdx, caret);
            List<IntellisenseSuggestion> suggestions =
                    suggestionsFor(text.charAt(triggerIdx), prefix);

            if (!suggestions.isEmpty()) {
                Point pt;
                try {
                    Rectangle2D rect = textArea.modelToView2D(triggerIdx);
                    pt = new Point((int)rect.getX(), (int)(rect.getY() + rect.getHeight()));
                } catch (Exception ex) {
                    pt = new Point(0, textArea.getHeight());
                }
                intellisensePopup.show(textArea, pt.x, pt.y, suggestions);
            } else {
                intellisensePopup.hide();
            }
        } else {
            intellisensePopup.hide();
        }
    }

    /**
     * Routes the trigger character to its suggestion provider:
     * '/' routes to command suggestions, '@' to the mention provider (if any).
     * Package-private for testability.
     */
    List<IntellisenseSuggestion> suggestionsFor(char trigger, String prefix) {
        if (trigger == '@' && mentionProvider != null) {
            return mentionProvider.getSuggestions(prefix);
        }
        if (trigger == '/') {
            // insert 带尾随空格(与 @-mention 同模式):接受补全后 prefix 变为 "/new ",
            // 不再匹配任何命令 → 弹窗关闭,下一个 Enter 走发送路径。无空格时 prefix
            // "/new" 仍精确匹配,弹窗复弹,Enter 被弹窗分支反复消费,消息永远发不出。
            return intellisenseProvider.getSuggestions(prefix).stream()
                    .map(cmd -> new IntellisenseSuggestion(cmd, cmd + " "))
                    .toList();
        }
        return List.of();
    }

    /**
     * Find the trigger character index (@ or /) closest to the caret.
     * Returns -1 if no valid trigger is found.
     */
    private int findTriggerIndex(String text, int caret) {
        int atIdx = text.lastIndexOf("@", caret - 1);
        int slashIdx = text.lastIndexOf("/", caret - 1);

        // Pick the closest trigger to the caret. 词首判定按 code point(代理对整体判字母),
        // 与发送侧 parseInstanceMentions 的正则 lookbehind 同口径,防"弹窗收了、解析丢了"。
        int best = -1;
        if (atIdx >= 0 && (atIdx == 0 || !Character.isLetterOrDigit(text.codePointBefore(atIdx)))) {
            best = atIdx;
        }
        if (slashIdx >= 0 && (slashIdx == 0 || !Character.isLetterOrDigit(text.codePointBefore(slashIdx)))) {
            if (best < 0 || slashIdx > best) {
                best = slashIdx;
            }
        }
        return best;
    }

    /**
     * Inserts the currently selected command from the intellisense popup into the text area.
     */
    private void insertSelectedCommand() {
        IntellisenseSuggestion selected = intellisensePopup.getSelectedValue();
        if (selected != null) {
            try {
                int pos = textArea.getCaretPosition();
                String text = textArea.getText();
                int triggerIdx = findTriggerIndex(text, pos);
                if (triggerIdx >= 0) {
                    String before = text.substring(0, triggerIdx);
                    String after = text.substring(pos);
                    String replacement = selected.insert();
                    textArea.setText(before + replacement + after);
                    textArea.setCaretPosition((before + replacement).length());
                }
            } catch (Exception ex) {
                // fallback: do nothing
            }
        }
    }
}
