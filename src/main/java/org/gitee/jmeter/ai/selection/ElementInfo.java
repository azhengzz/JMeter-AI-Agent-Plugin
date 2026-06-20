package org.gitee.jmeter.ai.selection;

/**
 * L2 控件描述 DTO。
 *
 * <p>由 {@link ElementDescriptor#describe(java.awt.Component)} 产出，描述右侧编辑面板中
 * 当前获得焦点的控件及其业务语义（字段名 + 当前值）。
 */
public final class ElementInfo {

    /** 控件类型，例如 {@code JTextField} / {@code JComboBox} / {@code JTable}。 */
    public final String controlType;
    /** 字段名（反查 JLabel 得到），可能为 {@code null}（JMeter 老 GUI 未调 setLabelFor）。 */
    public final String fieldName;
    /** 字段 key（用于去重 / i18n），可能为 {@code null}。 */
    public final String fieldKey;
    /** 控件当前值的字符串表示，可能为 {@code null}。 */
    public final String value;

    public ElementInfo(String controlType, String fieldName, String fieldKey, String value) {
        this.controlType = controlType;
        this.fieldName = fieldName;
        this.fieldKey = fieldKey;
        this.value = value;
    }

    /** 构造一个空描述（用于清空状态）。 */
    public static ElementInfo empty() {
        return new ElementInfo(null, null, null, null);
    }

    public boolean isEmpty() {
        return controlType == null && fieldName == null && value == null;
    }
}
