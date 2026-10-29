package org.gitee.jmeter.ai.gui;

import org.gitee.jmeter.ai.gui.theme.UiTokens;
import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.gitee.jmeter.ai.recorder.RecorderParams;
import org.gitee.jmeter.ai.recorder.RecorderRuntime;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Set;

/**
 * 录制启动参数窗：起始 URL / 只采域名均选填；资源类型复选与 Body 截断上限以
 * {@code jmeter.ai.recorder.*} 属性为默认值、按次覆盖；记住上次输入。
 */
public final class RecorderStartDialog {

    /** CDP 资源类型全集（展示顺序：业务接口在前，静态资源在后；键为小写协议标识）。 */
    private static final String[] RESOURCE_TYPES = {
            "XHR", "Fetch", "Document", "Script",
            "Stylesheet", "Image", "Font", "Media",
            "WebSocket", "EventSource", "TextTrack", "Manifest",
            "SignedExchange", "Ping", "CSPViolationReport", "Preflight", "Other"};

    public record Result(boolean confirmed, String url, Set<String> domains,
                         Set<String> resourceTypes, int maxBodyBytes) {
    }

    private RecorderStartDialog() {
    }

    public static Result show(Window owner) {
        RecorderConfig cfg = RecorderConfig.load();
        RecorderParams.Start pre = RecorderParams.prefill(
                RecorderRuntime.get().lastParamsFile(), cfg.startUrlPrefill, cfg.includeDomainsPrefill,
                cfg.resourceTypes, cfg.maxBodyBytes);

        JDialog dialog = new JDialog(owner, "开始浏览器录制", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.gridy = 0;
        form.add(fieldLabel("起始 URL（可选）："), gc);
        JTextField urlField = new JTextField(pre.url(), 34);
        urlField.setFont(UiTokens.body(urlField.getFont()));
        gc.gridx = 1;
        gc.weightx = 1;
        form.add(urlField, gc);
        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0;
        form.add(fieldLabel("只采域名（可选，逗号分隔）："), gc);
        JTextField domainsField = new JTextField(String.join(", ", pre.domains()), 34);
        domainsField.setFont(UiTokens.body(domainsField.getFont()));
        gc.gridx = 1;
        form.add(domainsField, gc);
        // 资源类型复选：勾选集合即本次采集过滤（默认勾选 = 上次输入 > 属性默认）
        java.util.Map<String, javax.swing.JCheckBox> typeBoxes = new java.util.LinkedHashMap<>();
        JPanel typesPanel = new JPanel(new java.awt.GridLayout(0, 4, 8, 0));
        for (String t : RESOURCE_TYPES) {
            javax.swing.JCheckBox box = new javax.swing.JCheckBox(t);
            box.setFont(UiTokens.caption(box.getFont()));
            box.setMargin(new java.awt.Insets(1, 0, 1, 0));
            box.setSelected(pre.resourceTypes().contains(t.toLowerCase(java.util.Locale.ROOT)));
            typeBoxes.put(t.toLowerCase(java.util.Locale.ROOT), box);
            typesPanel.add(box);
        }
        gc.anchor = GridBagConstraints.FIRST_LINE_START;
        gc.gridx = 0;
        gc.gridy = 2;
        form.add(fieldLabel("资源类型（勾选作为采集过滤）："), gc);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 1;
        form.add(typesPanel, gc);
        gc.gridx = 1;
        gc.gridy = 3;
        gc.insets = new Insets(0, 4, 2, 4);
        form.add(hintLabel("默认勾选可经 user.properties 的 jmeter.ai.recorder.resource.types 配置"), gc);
        gc.insets = new Insets(3, 4, 2, 4);

        // Body 截断上限：默认值 = 上次输入 > 属性默认；0 = 不截断
        gc.gridx = 0;
        gc.gridy = 4;
        form.add(fieldLabel("Body 截断上限（字节，0 = 不截断）："), gc);
        JTextField bodyField = new JTextField(String.valueOf(pre.maxBodyBytes()), 10);
        bodyField.setFont(UiTokens.body(bodyField.getFont()));
        gc.gridx = 1;
        form.add(bodyField, gc);
        gc.gridx = 1;
        gc.gridy = 5;
        gc.insets = new Insets(0, 4, 2, 4);
        form.add(hintLabel("默认值可经 user.properties 的 jmeter.ai.recorder.max.body.bytes 配置"), gc);
        gc.insets = new Insets(3, 4, 2, 4);

        final Result[] out = new Result[1];
        JButton open = new JButton("打开");
        JButton cancel = new JButton("取消");
        open.addActionListener(e -> {
            Set<String> types = new java.util.LinkedHashSet<>();
            for (java.util.Map.Entry<String, javax.swing.JCheckBox> en : typeBoxes.entrySet()) {
                if (en.getValue().isSelected()) {
                    types.add(en.getKey());
                }
            }
            if (types.isEmpty()) {
                javax.swing.JOptionPane.showMessageDialog(dialog,
                        "至少勾选一种资源类型（全不勾采不到任何请求）。", "参数不完整",
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
            int maxBody = parseNonNegativeInt(bodyField.getText().trim());
            if (maxBody < 0) {
                javax.swing.JOptionPane.showMessageDialog(dialog,
                        "Body 截断上限须为非负整数（0 = 不截断）。", "参数不合法",
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
            String url = urlField.getText().trim();
            Set<String> domains = RecorderParams.parseDomains(domainsField.getText());
            RecorderParams.saveLast(RecorderRuntime.get().lastParamsFile(),
                    new RecorderParams.Start(url, domains, types, maxBody));
            out[0] = new Result(true, url, domains, types, maxBody);
            dialog.dispose();
        });
        cancel.addActionListener(e -> {
            out[0] = new Result(false, "", Set.of(), Set.of(), 0);
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(open);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        buttons.add(cancel);
        buttons.add(open);
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(460, dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(urlField::requestFocusInWindow);
        dialog.setVisible(true); // 模态阻塞至关闭
        return out[0] == null ? new Result(false, "", Set.of(), Set.of(), 0) : out[0];
    }

    private static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.body(label.getFont()));
        return label;
    }

    /** 灰色小字提示（caption 字号随 LaF 缩放；html 换行用，不写死 font-size）。 */
    private static JLabel hintLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.caption(label.getFont()));
        label.setForeground(new Color(0x88, 0x88, 0x88));
        return label;
    }

    /** 非负整数解析；空/非数字/负数/溢出一律返回 -1（非法）。 */
    static int parseNonNegativeInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        try {
            int v = Integer.parseInt(raw);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
