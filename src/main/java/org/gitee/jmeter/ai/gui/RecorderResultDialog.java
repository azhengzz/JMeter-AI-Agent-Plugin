package org.gitee.jmeter.ai.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Set;

/**
 * 产物落点窗（任务 4.1）：录制收官后三选一——插入当前测试计划 / 另存独立 .jmx / 放弃。
 * busy（有活跃 Agent 回合在编辑测试树）时「插入当前测试计划」禁用并提示
 * （录制数据已落盘，可稍后导入或改选另存）。
 */
public final class RecorderResultDialog {

    public enum Choice {INSERT, SAVE, DISCARD}

    public record Result(Choice choice) {
    }

    private RecorderResultDialog() {
    }

    public static Result show(Window owner, boolean busy, int requests, int samplers,
                              Set<String> domains, long durationMs) {
        JDialog dialog = new JDialog(owner, "录制完成", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel summary = new JPanel(new BorderLayout(0, 4));
        summary.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        summary.add(new JLabel("<html><b>已捕获 " + requests + " 个请求</b>（"
                + durationMs / 1000 + " 秒，命中域名：" + String.join(", ", domains) + "）<br>"
                + "将生成 " + samplers + " 个采样器。</html>"), BorderLayout.CENTER);

        final Choice[] out = new Choice[1];
        JButton insert = new JButton("插入当前测试计划");
        insert.setEnabled(!busy);
        insert.setToolTipText(busy
                ? "Agent 回合正在编辑测试计划——录制数据已保存，可稍后导入或改选另存"
                : "骨架将作为「录制 …」线程组挂到测试计划根下，不影响既有内容");
        insert.addActionListener(e -> {
            out[0] = Choice.INSERT;
            dialog.dispose();
        });
        JButton save = new JButton("另存独立 .jmx");
        save.addActionListener(e -> {
            out[0] = Choice.SAVE;
            dialog.dispose();
        });
        JButton discard = new JButton("放弃");
        discard.addActionListener(e -> {
            out[0] = Choice.DISCARD;
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(busy ? save : insert);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        buttons.add(discard);
        buttons.add(save);
        buttons.add(insert);
        if (busy) {
            JPanel hint = new JPanel(new BorderLayout());
            hint.add(new JLabel("Agent 正在编辑测试计划：插入暂不可用，可另存文件或稍后从录制目录导入。"),
                    BorderLayout.CENTER);
            dialog.add(hint, BorderLayout.NORTH);
        }
        dialog.add(summary, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return new Result(out[0] == null ? Choice.DISCARD : out[0]);
    }
}
