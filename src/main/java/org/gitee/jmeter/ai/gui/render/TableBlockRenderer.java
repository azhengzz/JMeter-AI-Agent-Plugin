package org.gitee.jmeter.ai.gui.render;

import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.gitee.jmeter.ai.gui.theme.SlimScrollBarUI;
import org.gitee.jmeter.ai.gui.theme.ThemeColors;
import org.gitee.jmeter.ai.gui.theme.UiTokens;

/**
 * Renders GitHub-flavored markdown tables into a {@link StyledDocument} as an
 * embedded grid component: header row bold on a tinted background, body rows
 * plain, thin borders all around. Without this, table markdown (| col | col |)
 * shows up as literal pipes in the transcript.
 *
 * <p>The grid sits inside a horizontal-scroll wrapper so a table wider than
 * the card scrolls instead of being cut off (JTextPane never wraps embedded
 * components and the transcript has no horizontal scrollbar). The wrapper's
 * width is clamped to the card by {@code MessageCard} once it is laid out.
 */
final class TableBlockRenderer {

    private TableBlockRenderer() {
    }

    /** True when a line can be part of a table block (contains a pipe). */
    static boolean isTableLine(String line) {
        return line != null && line.contains("|") && !line.isBlank();
    }

    /** True when a line is the header/body separator (e.g. {@code | --- | :-: | --- |}). */
    static boolean isTableSeparator(String line) {
        if (line == null) {
            return false;
        }
        String compact = line.replace(" ", "");
        if (compact.length() < 3 || !compact.contains("-")) {
            return false;
        }
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c != '|' && c != '-' && c != ':') {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits a table row into trimmed cell values, dropping the empty slots a
     * leading/trailing pipe produces. A backslash escapes only a pipe
     * ({@code \|} stays literal); any other backslash is ordinary content, so
     * Windows paths keep their separators.
     */
    static List<String> splitRow(String line) {
        String trimmed = line.trim();
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '|') {
                current.append('|');
                i++;
                continue;
            }
            if (c == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        // drop the edge slots from leading/trailing pipes
        if (!cells.isEmpty() && cells.get(0).isEmpty()) {
            cells.remove(0);
        }
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    /** Renders header + rows as an embedded grid component at the document end. */
    static void render(StyledDocument doc, List<String> header, List<List<String>> rows)
            throws BadLocationException {
        int columns = Math.max(1, header.size());
        // GridBagLayout (not GridLayout) so each column hugs its widest cell:
        // equal-width splitting strands short columns (e.g. a mostly-empty
        // category column) with large blank stretches. Cells fill their column
        // horizontally so borders and header tint stay continuous.
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(BorderFactory.createLineBorder(ThemeColors.separator()));
        grid.setOpaque(false);
        GridBagConstraints cellConstraint = new GridBagConstraints();
        cellConstraint.fill = GridBagConstraints.HORIZONTAL;
        cellConstraint.weightx = 0.0;

        List<List<String>> data = new ArrayList<>();
        List<String> headerCells = new ArrayList<>();
        for (int col = 0; col < columns; col++) {
            String value = col < header.size() ? header.get(col) : "";
            headerCells.add(value);
            cellConstraint.gridx = col;
            cellConstraint.gridy = 0;
            grid.add(cell(value, true), cellConstraint);
        }
        data.add(headerCells);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            List<String> rowCells = new ArrayList<>();
            for (int col = 0; col < columns; col++) {
                String value = col < row.size() ? row.get(col) : "";
                rowCells.add(value);
                cellConstraint.gridx = col;
                cellConstraint.gridy = rowIndex + 1;
                grid.add(cell(value, false), cellConstraint);
            }
            data.add(rowCells);
        }

        attachCopyPopup(grid, data);

        JScrollPane scroller = SlimScrollBarUI.scroller(grid,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroller.setBorder(null);
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        SlimScrollBarUI.install(scroller);

        SimpleAttributeSet componentStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(componentStyle, scroller);
        doc.insertString(doc.getLength(), " ", componentStyle);
        doc.insertString(doc.getLength(), "\n", null);
    }

    /**
     * Cell as a read-only text area (never HTML): content stays selectable and
     * Ctrl+C-copyable, unlike the JLabel it replaces.
     */
    private static JTextArea cell(String text, boolean header) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(false);
        Font font = area.getFont();
        area.setFont(header
                ? font.deriveFont(Font.BOLD, font.getSize2D() - 1f)
                : font.deriveFont(font.getSize2D() - 1f));
        area.setForeground(ThemeColors.foreground());
        area.setCaretColor(ThemeColors.foreground());
        if (header) {
            area.setOpaque(true);
            area.setBackground(ThemeColors.codeBackground());
        } else {
            area.setOpaque(false);
        }
        area.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, ThemeColors.separator()),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2)));
        return area;
    }

    /**
     * Right-click menu on the grid: copy one cell's text or the whole table as
     * tab-separated rows. Drag selection cannot span per-cell components, so
     * the whole-table action is the bulk copy path.
     */
    private static void attachCopyPopup(JPanel grid, List<List<String>> data) {
        JPopupMenu popup = buildCopyPopup(data);
        grid.setComponentPopupMenu(popup);
        for (Component c : grid.getComponents()) {
            if (c instanceof JComponent) {
                ((JComponent) c).setInheritsPopupMenu(true);
            }
        }
    }

    /** The shared popup; "Copy Cell" resolves the invoking cell at action time. */
    static JPopupMenu buildCopyPopup(List<List<String>> data) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem cellItem = new JMenuItem("Copy Cell");
        cellItem.addActionListener(e -> {
            if (popup.getInvoker() instanceof JTextArea) {
                copyToClipboard(((JTextArea) popup.getInvoker()).getText());
            }
        });
        JMenuItem tableItem = new JMenuItem("Copy Table");
        tableItem.addActionListener(e -> copyToClipboard(buildTsv(data)));
        popup.add(cellItem);
        popup.add(tableItem);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                cellItem.setEnabled(popup.getInvoker() instanceof JTextArea);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // no per-show state to reset
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // no per-show state to reset
            }
        });
        return popup;
    }

    /** Joins rows into tab-separated text (paste-friendly for spreadsheets). */
    static String buildTsv(List<List<String>> data) {
        StringBuilder tsv = new StringBuilder();
        for (int r = 0; r < data.size(); r++) {
            if (r > 0) {
                tsv.append('\n');
            }
            List<String> row = data.get(r);
            for (int col = 0; col < row.size(); col++) {
                if (col > 0) {
                    tsv.append('\t');
                }
                tsv.append(row.get(col));
            }
        }
        return tsv.toString();
    }

    private static void copyToClipboard(String text) {
        java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null);
    }
}
