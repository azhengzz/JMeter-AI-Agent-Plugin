package org.gitee.jmeter.ai.agent.tools.jmeter.utils;

import org.apache.jmeter.gui.GuiPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;

/**
 * Helper to run Swing/JMeter GUI operations on the EDT with proper ClassLoader context.
 *
 * <p>All {@code JMeterTreeModel} operations (extends {@code DefaultTreeModel}),
 * {@code GuiPackage.refreshCurrentGui/getGui/updateCurrentGui}, and any
 * {@code gui.configure(element)} chain must run on EDT — otherwise the JTree
 * TreeState cache and RSyntaxTextArea Token cache can be corrupted, eventually
 * crashing the EDT with NPE.
 *
 * <p>This helper wraps the {@code invokeAndWait} + ClassLoader hack + exception
 * capture pattern that is otherwise copy-pasted in every JMeter element tool.
 */
public final class EdtRunner {

    private static final Logger log = LoggerFactory.getLogger(EdtRunner.class);

    private EdtRunner() {
    }

    /**
     * Action that can throw any checked exception when run on EDT.
     */
    @FunctionalInterface
    public interface EdtAction {
        void run() throws Exception;
    }

    /**
     * Run {@code action} on EDT synchronously, switching the thread's context
     * ClassLoader to the JMeter GuiPackage's ClassLoader for the duration.
     *
     * <p>The caller thread blocks until the action completes. Safe to call from
     * {@code tool-executor} threads — EDT never calls back into tool-executor,
     * so no deadlock risk.
     *
     * @param guiPackage GuiPackage instance (used for ClassLoader); not null
     * @param action     Action to run on EDT; must not be null
     * @return {@code null} on success, otherwise the exception thrown by the
     *         action (or {@code InvocationTargetException}/{@code InterruptedException}
     *         if dispatch itself failed). The caller decides how to translate
     *         the exception into a {@code ToolResult}.
     */
    public static Exception run(GuiPackage guiPackage, EdtAction action) {
        Exception[] error = new Exception[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                ClassLoader original = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(guiPackage.getClass().getClassLoader());
                    action.run();
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    Thread.currentThread().setContextClassLoader(original);
                }
            });
        } catch (InvocationTargetException | InterruptedException e) {
            return e;
        }
        return error[0];
    }
}
