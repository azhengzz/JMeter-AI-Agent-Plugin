package org.gitee.jmeter.ai.selection;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceLoader 入口。JMeter 启动时通过 META-INF/services 自动发现本类。
 *
 * <p>仅响应 {@link ActionNames#ADD_ALL}（GUI 初始化完成事件，此时 MainFrame 已 setVisible），
 * 借此作为安全时机调用 {@link SelectionTracker#install()} 注册全局 L1/L2 监听器。
 *
 * <p>用 AtomicBoolean 防止用户重复加载测试计划时多次 install 导致监听器叠加。
 */
public class SelectionInitCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(SelectionInitCommand.class);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    public SelectionInitCommand() {
        // 构造时打日志，证明 ServiceLoader 已经发现并实例化本类
        log.info("SelectionInitCommand instantiated by JMeter ServiceLoader");
    }

    @Override
    public void doAction(ActionEvent e) {
        log.info("SelectionInitCommand.doAction: actionCommand={}", e.getActionCommand());
        if (!ActionNames.ADD_ALL.equals(e.getActionCommand())) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            log.info("SelectionInitCommand: already installed, skipping");
            return;
        }
        log.info("SelectionInitCommand received ADD_ALL, scheduling SelectionTracker.install()");
        EventQueue.invokeLater(SelectionTracker::install);
    }

    @Override
    public Set<String> getActionNames() {
        return Collections.singleton(ActionNames.ADD_ALL);
    }
}
