package org.gitee.jmeter.ai.intellisense;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/**
 * Popup panel for displaying intellisense suggestions below the input box.
 */
public class IntellisensePopup {
    private final JPopupMenu popupMenu;
    protected final JList<IntellisenseSuggestion> suggestionList; // Changed to protected for testing
    private final JScrollPane scrollPane;

    public IntellisensePopup() {
        popupMenu = new JPopupMenu();
        suggestionList = new JList<>();
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setFocusable(false);
        scrollPane = new JScrollPane(suggestionList);
        scrollPane.setBorder(null);
        Color borderColor = UIManager.getColor("Component.borderColor");
        popupMenu.setBorder(BorderFactory.createLineBorder(borderColor != null ? borderColor : Color.LIGHT_GRAY));
        popupMenu.add(scrollPane);
    }

    public void show(Component parent, int x, int y, List<IntellisenseSuggestion> suggestions) {
        // 后台刷新触发的就地重渲染不得重置键盘选中(用户正 Down 到某行时刷新落地,
        // 高亮跳回第一行会让下一个 Enter 插错实例)——同 display 项尽量保序恢复
        IntellisenseSuggestion previous = suggestionList.getSelectedValue();
        suggestionList.setListData(suggestions.toArray(new IntellisenseSuggestion[0]));
        int restore = 0;
        if (previous != null) {
            for (int i = 0; i < suggestions.size(); i++) {
                if (previous.display().equals(suggestions.get(i).display())) {
                    restore = i;
                    break;
                }
            }
        }
        suggestionList.setSelectedIndex(restore);
        suggestionList.setVisibleRowCount(Math.min(5, suggestions.size()));
        popupMenu.pack();
        popupMenu.show(parent, x, y);
        parent.requestFocusInWindow();
    }

    public void hide() {
        popupMenu.setVisible(false);
    }

    public boolean isVisible() {
        return popupMenu.isVisible();
    }

    public void addSuggestionClickListener(MouseListener listener) {
        suggestionList.addMouseListener(listener);
    }

    public void addSuggestionKeyListener(KeyListener listener) {
        suggestionList.addKeyListener(listener);
    }

    public IntellisenseSuggestion getSelectedValue() {
        return suggestionList.getSelectedValue();
    }

    public void setSelectedIndex(int index) {
        suggestionList.setSelectedIndex(index);
    }

    public int getSuggestionCount() {
        return suggestionList.getModel().getSize();
    }
    
    /**
     * Gets the currently selected index in the suggestion list.
     * 
     * @return The selected index, or 0 if nothing is selected
     */
    public int getSelectedIndex() {
        return suggestionList.getSelectedIndex();
    }
}
