package org.gitee.jmeter.ai.selection;

import java.awt.Component;
import java.awt.Container;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;

/**
 * 控件 → 业务描述 翻译器（L2）。
 *
 * <p>把右侧编辑面板中焦点控件翻译成业务可读的 fieldName + value。
 *
 * <p>字段名获取策略（按优先级）：
 * <ol>
 *   <li>{@code JComponent.getClientProperty("labeledBy")} 直接反查关联 JLabel（标准 Swing 用法）</li>
 *   <li>回退：向左扫描父容器中前一个 JLabel 兄弟</li>
 *   <li>回退：JCheckBox / JRadioButton 等 AbstractButton 直接用 {@code getText()}</li>
 *   <li>都失败：fieldName = {@code null}（UI 显示 "(unlabeled field)"）</li>
 * </ol>
 *
 * <p>注意：JMeter 部分老 GUI（如 {@code HttpTestSampleGui}）未调 {@code JLabel.setLabelFor}，
 * 回退扫描是必要的兜底。
 */
public final class ElementDescriptor {

    private ElementDescriptor() {
        // 工具类
    }

    public static ElementInfo describe(Component c) {
        if (c == null) {
            return ElementInfo.empty();
        }

        // 双击 JTable cell 进入编辑模式时，cell editor（通常是 JTextField）
        // 会被加到 JTable 上作为临时子组件。检测这种情况并转而描述 JTable，
        // 这样展示栏会显示 row/col/value 而不是 (unlabeled field) = 单元格内容。
        JTable enclosingTable = findEnclosingEditingJTable(c);
        if (enclosingTable != null) {
            return describeJTable(enclosingTable);
        }

        String controlType = c.getClass().getSimpleName();
        String fieldName = resolveFieldName(c);
        String value = resolveValue(c);

        return new ElementInfo(controlType, fieldName, null, value);
    }

    /**
     * 检测组件是否位于正在编辑的 JTable 内（即用户双击 cell 进入编辑模式）。
     * 返回该 JTable 实例，否则 null。
     */
    private static JTable findEnclosingEditingJTable(Component c) {
        Container p = c.getParent();
        while (p != null) {
            if (p instanceof JTable t && t.isEditing()) {
                return t;
            }
            p = p.getParent();
        }
        return null;
    }

    /**
     * 描述 JTable 的当前选中状态（row/col/value）。
     */
    private static ElementInfo describeJTable(JTable t) {
        int row = t.getSelectedRow();
        int col = t.getSelectedColumn();
        if (row < 0) {
            return new ElementInfo("JTable", null, null, null);
        }
        StringBuilder sb = new StringBuilder("row=").append(row);
        if (col >= 0) {
            sb.append(",col=").append(col);
            Object cellValue = t.getValueAt(row, col);
            if (cellValue != null) {
                String cellStr = String.valueOf(cellValue).trim();
                if (!cellStr.isEmpty()) {
                    sb.append(",value=").append(truncate(cellStr, 60));
                }
            }
        }
        return new ElementInfo("JTable", null, null, sb.toString());
    }

    /**
     * 判断字段名是否是元素的"名称"字段（Name/名称/元素名）。
     * 这种字段在 L1 行已经显示元素名，L2 不需要重复展示。
     */
    public static boolean isElementNameField(String fieldName) {
        if (fieldName == null) return false;
        String trimmed = fieldName.trim();
        if (trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase();
        return lower.equals("name")
                || trimmed.equals("名称")
                || trimmed.equals("名字")
                || trimmed.equals("元件名称")
                || trimmed.equals("元素名称");
    }

    private static String resolveFieldName(Component c) {
        // 1. setLabelFor 关联（标准 Swing）
        if (c instanceof JComponent jc) {
            Object labeledBy = jc.getClientProperty("labeledBy");
            if (labeledBy instanceof JLabel lbl && lbl.getText() != null && !lbl.getText().isEmpty()) {
                // JMeter AbstractJMeterGuiComponent.java:304 有 bug：
                // 第 304 行 labelFor(nameField, "testplan_comments") 应该传 commentField
                // 但传成了 nameField，导致 nameField 的 labeledBy 被覆盖为 comment label。
                // 检测到这种情况时，回退去找真正的 "name" label（label.getName() == "name"）。
                if ("testplan_comments".equals(lbl.getName())) {
                    JLabel realNameLabel = findSiblingLabelByName(c, "name");
                    if (realNameLabel != null && realNameLabel.getText() != null
                            && !realNameLabel.getText().isEmpty()) {
                        return stripColon(realNameLabel.getText());
                    }
                    // 找不到就用兜底文本
                    return "Name";
                }
                return stripColon(lbl.getText());
            }
        }

        // 2. AbstractButton（JCheckBox / JRadioButton）直接用自身文本
        if (c instanceof AbstractButton btn) {
            String text = btn.getText();
            if (text != null && !text.isEmpty()) {
                return stripColon(text);
            }
        }

        // 3. 回退：向左扫描父容器中前一个 JLabel 兄弟
        JLabel sibling = findPreviousSiblingLabel(c);
        if (sibling != null && sibling.getText() != null && !sibling.getText().isEmpty()) {
            return stripColon(sibling.getText());
        }

        return null;
    }

    /**
     * 在父容器中扫描 JLabel，按 {@link JLabel#getName()} 匹配。
     * 用于绕过 JMeter 的 labelFor bug — 多个 label 错误关联到同一个 component 时，
     * 通过 label.getName() 精确找到正确的那一个。
     */
    private static JLabel findSiblingLabelByName(Component c, String name) {
        Container parent = c.getParent();
        if (parent == null) return null;
        for (Component sibling : parent.getComponents()) {
            if (sibling instanceof JLabel lbl && name.equals(lbl.getName())) {
                return lbl;
            }
        }
        return null;
    }

    private static String resolveValue(Component c) {
        if (c instanceof JTextField tf) {
            return tf.getText();
        }
        if (c instanceof JTextComponent tc) {
            return describeTextSelection(tc);
        }
        if (c instanceof JComboBox<?> cb) {
            Object sel = cb.getSelectedItem();
            return sel == null ? null : String.valueOf(sel);
        }
        if (c instanceof AbstractButton btn) {
            return String.valueOf(btn.isSelected());
        }
        if (c instanceof JTable t) {
            int row = t.getSelectedRow();
            int col = t.getSelectedColumn();
            if (row < 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder("row=").append(row);
            if (col < 0) {
                return sb.toString();
            }
            sb.append(",col=").append(col);
            // 追加单元格内容（如果非空且非空字符串）
            Object cellValue = t.getValueAt(row, col);
            if (cellValue != null) {
                String cellStr = String.valueOf(cellValue).trim();
                if (!cellStr.isEmpty()) {
                    sb.append(",value=").append(truncate(cellStr, 60));
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * 描述 JTextComponent（JTextArea / JSyntaxTextArea / RSyntaxTextArea 等）的当前状态：
     * <ul>
     *   <li>有选中文本时：显示行号范围（单行 {@code line=N} / 多行 {@code lines=N-M}）+ 选中内容预览</li>
     *   <li>无选中时：显示 caret 所在行号 + 文本预览（兼容旧行为）</li>
     * </ul>
     * <p>模仿 IDE Claude Code 选中代码时的展示效果。
     */
    private static String describeTextSelection(JTextComponent tc) {
        String selected = tc.getSelectedText();
        int selStart = tc.getSelectionStart();
        int selEnd = tc.getSelectionEnd();
        boolean hasSelection = selected != null && !selected.isEmpty() && selEnd > selStart;

        try {
            int lineAtCaret = tc.getDocument().getDefaultRootElement()
                    .getElementIndex(tc.getCaretPosition()) + 1;  // 0-based → 1-based

            if (hasSelection) {
                int startLine = tc.getDocument().getDefaultRootElement()
                        .getElementIndex(selStart) + 1;
                // selEnd 指向选中末尾之后的位置；用 selEnd-1 才是真正选中的最后一个字符
                int endLine = tc.getDocument().getDefaultRootElement()
                        .getElementIndex(selEnd - 1) + 1;
                String preview = selected.replace("\r", "").replace("\n", "\\n").trim();
                if (startLine == endLine) {
                    return "line=" + startLine + ",selected=" + truncate(preview, 60);
                }
                return "lines=" + startLine + "-" + endLine + ",selected=" + truncate(preview, 60);
            }

            String text = tc.getText();
            if (text == null || text.isEmpty()) {
                return "line=" + lineAtCaret;
            }
            String preview = text.replace("\r", "").replace("\n", "\\n").trim();
            return "line=" + lineAtCaret + ",value=" + truncate(preview, 60);
        } catch (Exception e) {
            // 行号计算异常时降级到原始行为
            return tc.getText();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /**
     * 向左（前一个兄弟）/ 向上找邻接的 JLabel。
     * 适用于 GridBagLayout / BoxLayout 等将 label 紧邻控件摆放的容器。
     */
    private static JLabel findPreviousSiblingLabel(Component c) {
        Container parent = c.getParent();
        if (parent == null) {
            return null;
        }
        Component[] siblings = parent.getComponents();
        int idx = -1;
        for (int i = 0; i < siblings.length; i++) {
            if (siblings[i] == c) {
                idx = i;
                break;
            }
        }
        if (idx <= 0) {
            return null;
        }
        // 向左扫描最多 3 个兄弟
        for (int i = idx - 1; i >= Math.max(0, idx - 3); i--) {
            if (siblings[i] instanceof JLabel lbl) {
                return lbl;
            }
        }
        return null;
    }

    /** 去掉字段名末尾的冒号（中英文）。 */
    private static String stripColon(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.endsWith(":") || trimmed.endsWith("：")) {
            return trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
