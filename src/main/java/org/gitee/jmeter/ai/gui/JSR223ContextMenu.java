package org.gitee.jmeter.ai.gui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.CodeRefactorer;
import org.gitee.jmeter.ai.service.provider.ProviderRegistry;
import org.gitee.jmeter.ai.service.provider.ProviderSpec;
import org.gitee.jmeter.ai.utils.AiConfig;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.ActionEvent;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Adds a right-click context menu to JSR223 script areas.
 */
public class JSR223ContextMenu {
    private static final Logger log = LoggerFactory.getLogger(JSR223ContextMenu.class);
    private static boolean initialized = false;
    private static AiService sharedAiService;
    private final AiService aiService;

    public JSR223ContextMenu(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * Initialize the JSR223 context menu functionality.
     * This should be called when JMeter starts.
     */
    public static synchronized void initialize(AiService aiService) {
        if (initialized) {
            return;
        }

        // Store the AI service for later use
        sharedAiService = aiService;

        // Start a background thread to avoid slowing down JMeter startup
        new Thread(() -> {
            try {
                // Wait a bit for JMeter to fully initialize
                Thread.sleep(2000);
                setupContextMenus(aiService);
                initialized = true;
                log.info("JSR223 context menus initialized");
            } catch (Exception e) {
                log.error("Failed to initialize JSR223 context menus", e);
            }
        }).start();
    }

    /**
     * Setup context menus for all JSR223 components.
     * This will be called periodically to catch newly created components.
     */
    private static void setupContextMenus(AiService aiService) {
        // Find all JSR223 script editors and add context menus
        RSyntaxTextArea scriptEditor = findJSR223ScriptEditor();
        if (scriptEditor != null) {
            addContextMenu(scriptEditor, aiService);
        }

        // We're removing the timer-based polling because it can interfere with typing
        // by periodically resetting the cursor position.
        // Instead, we'll rely on the initialization during plugin startup to add the
        // context menu.

        // If you need to ensure that newly created components get context menus,
        // consider integrating with JMeter's component creation lifecycle rather than
        // polling.
    }

    /**
     * Checks if AI refactoring is enabled based on JMeter properties and available
     * service
     * 
     * @return true if refactoring is enabled, false otherwise
     */
    private static boolean isAiRefactoringEnabled() {
        // Quick check if we have an AI service
        if (sharedAiService == null) {
            return false;
        }

        // Check if AI refactoring is explicitly disabled
        String enableRefactoring = AiConfig.getProperty("jmeter.ai.refactoring.enabled", "true");
        if (!Boolean.parseBoolean(enableRefactoring)) {
            return false;
        }

        // Check if the default provider has API key configured
        String provider = AiConfig.getDefaultProvider();
        ProviderSpec spec = ProviderRegistry.findByName(provider);
        if (spec == null) {
            return false;
        }
        String apiKey = AiConfig.getProperty(spec.getEnvKey(), "");
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY");
    }

    /**
     * Adds a context menu to the specified RSyntaxTextArea.
     * 
     * @param textArea  The text area to add the context menu to
     * @param aiService The AI service to use for refactoring
     */
    private static void addContextMenu(RSyntaxTextArea textArea, AiService aiService) {
        // Check if the text area already has a context menu
        if (textArea.getClientProperty("contextMenuAdded") != null) {
            return;
        }

        // Check if AI refactoring is enabled
        if (!isAiRefactoringEnabled()) {
            // If AI refactoring is disabled, don't add our custom context menu
            // This will allow the default JMeter context menu to appear
            log.debug("AI refactoring disabled, not adding custom context menu");
            return;
        }

        // Create the refactorer
        CodeRefactorer refactorer = new CodeRefactorer(aiService);

        JPopupMenu popupMenu = new JPopupMenu();

        // Add menu items
        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.addActionListener(e -> textArea.cut());
        popupMenu.add(cutItem);

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> textArea.copy());
        popupMenu.add(copyItem);

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.addActionListener(e -> textArea.paste());
        popupMenu.add(pasteItem);

        popupMenu.addSeparator();

        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.addActionListener(e -> textArea.selectAll());
        popupMenu.add(selectAllItem);

        popupMenu.addSeparator();

        // Add AI refactoring menu item
        JMenuItem aiHelpItem = new JMenuItem("Refactor Code");
        aiHelpItem.addActionListener(e -> refactorer.refactorSelectedCode(textArea));
        popupMenu.add(aiHelpItem);

        // Add AI try, catch, finally menu item
        JMenuItem aiTryCatchFinallyItem = new JMenuItem("Try, Catch, Finally");
        aiTryCatchFinallyItem.addActionListener(e -> refactorer.refactorTryCatchFinally(textArea));
        popupMenu.add(aiTryCatchFinallyItem);

        // Add format code menu item
        JMenuItem formatCodeItem = new JMenuItem("Format Code");
        formatCodeItem.addActionListener(e -> {
            // This is a placeholder - you'd implement or connect to a code formatter
            String code = textArea.getText();
            if (code != null && !code.trim().isEmpty()) {
                try {
                    // Simple indentation formatting
                    code = formatGroovyCode(code);
                    textArea.setText(code);
                } catch (Exception ex) {
                    log.error("Error formatting code", ex);
                    JOptionPane.showMessageDialog(
                            textArea,
                            "Error formatting code: " + ex.getMessage(),
                            "Format Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(
                        textArea,
                        "No code to format",
                        "Format Code",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        popupMenu.add(formatCodeItem);


        // Add the Functions Dialog menu item
        JMenuItem functionsDialogItem = new JMenuItem("Functions Dialog");
        functionsDialogItem.addActionListener(e -> {
            ActionRouter.getInstance().doActionNow(
                    new ActionEvent(e.getSource(), e.getID(), ActionNames.FUNCTIONS));
        });
        popupMenu.add(functionsDialogItem);

        // Add mouse listener to show the popup menu
        textArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showMenu(e);
                }
            }

            private void showMenu(MouseEvent e) {
                // Update menu items based on selection state
                boolean hasSelection = textArea.getSelectedText() != null && !textArea.getSelectedText().isEmpty();
                cutItem.setEnabled(hasSelection);
                copyItem.setEnabled(hasSelection);

                // Find the AI help item if it exists
                for (int i = 0; i < popupMenu.getComponentCount(); i++) {
                    if (popupMenu.getComponent(i) instanceof JMenuItem) {
                        JMenuItem item = (JMenuItem) popupMenu.getComponent(i);
                        if (item.getText().equals("Refactor Code")) {
                            item.setEnabled(hasSelection);
                        }
                        if (item.getText().equals("Try, Catch, Finally")) {
                            item.setEnabled(hasSelection);
                        }
                    }
                }

                popupMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        // Mark the text area as having a context menu
        textArea.putClientProperty("contextMenuAdded", Boolean.TRUE);
        log.debug("Added context menu to JSR223 script editor");
    }

    /**
     * Very simple code formatter for Groovy scripts
     * This is a basic implementation and could be improved
     */
    private static String formatGroovyCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return code;
        }

        StringBuilder formatted = new StringBuilder();
        String[] lines = code.split("\n");
        int indentLevel = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // Adjust indent level based on closing braces at the start of the line
            if (trimmed.startsWith("}") || trimmed.startsWith(")")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            // Add the appropriate indentation
            if (!trimmed.isEmpty()) {
                for (int i = 0; i < indentLevel; i++) {
                    formatted.append("    "); // 4 spaces per indent level
                }
                formatted.append(trimmed).append("\n");
            } else {
                formatted.append("\n"); // Preserve empty lines
            }

            // Increase indent level for lines ending with opening braces
            if (trimmed.endsWith("{") || trimmed.endsWith("(")) {
                indentLevel++;
            }

            // Decrease indent level for lines ending with closing braces
            if (trimmed.endsWith("}") || trimmed.endsWith(")")) {
                indentLevel = Math.max(0, indentLevel - 1);
            }
        }

        return formatted.toString();
    }

    /**
     * Public method to add a context menu to the current JSR223 editor.
     * This can be called when a user interacts with a JSR223 component to ensure
     * it has a context menu without using timers that might interfere with typing.
     */
    public static void addContextMenuToCurrentEditor() {
        if (!initialized) {
            log.warn("Cannot add context menu - not initialized");
            return;
        }

        RSyntaxTextArea scriptEditor = findJSR223ScriptEditor();
        if (scriptEditor != null) {
            addContextMenu(scriptEditor, sharedAiService);
        }
    }

    /**
     * Finds the RSyntaxTextArea in the currently selected JSR223 element.
     *
     * @return The RSyntaxTextArea, or null if not found
     */
    private static RSyntaxTextArea findJSR223ScriptEditor() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return null;
            }

            JMeterTreeNode node = guiPackage.getTreeListener().getCurrentNode();
            if (node == null) {
                return null;
            }

            // Check if this is a JSR223 element
            String className = node.getTestElement().getClass().getName();
            if (!className.contains("JSR223")) {
                return null;
            }

            // Get the GUI component
            JMeterGUIComponent guiComp = guiPackage.getCurrentGui();
            if (!(guiComp instanceof TestBeanGUI)) {
                return null;
            }

            TestBeanGUI testBeanGUI = (TestBeanGUI) guiComp;

            // First attempt: Find the RSyntaxTextArea in the component hierarchy
            RSyntaxTextArea scriptEditor = findRSyntaxTextArea(testBeanGUI);
            if (scriptEditor != null) {
                return scriptEditor;
            }

            // Second attempt: Try to find it in the parent container
            Container parent = testBeanGUI.getParent();
            if (parent != null) {
                scriptEditor = findRSyntaxTextArea(parent);
                if (scriptEditor != null) {
                    return scriptEditor;
                }
            }

            // Third attempt: Try to find it in the main frame
            if (guiPackage.getMainFrame() != null) {
                scriptEditor = findRSyntaxTextArea(guiPackage.getMainFrame());
                if (scriptEditor != null) {
                    return scriptEditor;
                }
            }

            // Final attempt: Search all windows for the RSyntaxTextArea
            scriptEditor = findRSyntaxTextAreaInAllWindows();
            if (scriptEditor != null) {
                return scriptEditor;
            }

            return null;
        } catch (Exception e) {
            log.error("Error finding JSR223 script editor", e);
            return null;
        }
    }

    /**
     * Recursively searches for RSyntaxTextArea in the component hierarchy.
     *
     * @param container The container to search in
     * @return The RSyntaxTextArea, or null if not found
     */
    private static RSyntaxTextArea findRSyntaxTextArea(Container container) {
        // First, try to find the component by name - often used in JMeter for script
        // areas
        for (Component component : container.getComponents()) {
            String componentName = component.getName();
            if (componentName != null &&
                    (componentName.contains("script") || componentName.contains("Script") ||
                            componentName.contains("code") || componentName.contains("Code"))) {
                if (component instanceof RSyntaxTextArea) {
                    return (RSyntaxTextArea) component;
                }
            }
        }

        // Regular recursive search
        for (Component component : container.getComponents()) {
            if (component instanceof RSyntaxTextArea) {
                return (RSyntaxTextArea) component;
            } else if (component.getClass().getName().contains("JSyntaxTextArea")) {
                // JMeter uses a custom JSyntaxTextArea which extends RSyntaxTextArea
                return (RSyntaxTextArea) component;
            } else if (component instanceof JScrollPane ||
                    component.getClass().getName().contains("JTextScrollPane")) {
                // Special handling for scroll panes, which often contain the text area
                try {
                    // Use reflection to get the viewport and view since JTextScrollPane might not
                    // be in our classpath
                    Container viewport = null;
                    if (component instanceof JScrollPane) {
                        viewport = ((JScrollPane) component).getViewport();
                    } else {
                        // Try to get viewport through reflection
                        java.lang.reflect.Method getViewportMethod = component.getClass().getMethod("getViewport");
                        viewport = (Container) getViewportMethod.invoke(component);
                    }

                    if (viewport != null) {
                        Component viewComponent = viewport.getComponent(0);
                        if (viewComponent instanceof RSyntaxTextArea ||
                                viewComponent.getClass().getName().contains("JSyntaxTextArea")) {
                            return (RSyntaxTextArea) viewComponent;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error getting viewport from scroll pane", e);
                }
            } else if (component instanceof Container) {
                RSyntaxTextArea result = findRSyntaxTextArea((Container) component);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Searches for RSyntaxTextArea in all open windows.
     * This is a more aggressive approach when the standard search fails.
     *
     * @return The first RSyntaxTextArea found, or null if none found
     */
    private static RSyntaxTextArea findRSyntaxTextAreaInAllWindows() {

        // Get all windows
        Window[] windows = Window.getWindows();

        // Search each window
        for (Window window : windows) {
            if (window.isVisible()) {
                RSyntaxTextArea textArea = findRSyntaxTextArea(window);
                if (textArea != null) {
                    log.info("Found RSyntaxTextArea in window: {}", window);
                    return textArea;
                }
            }
        }

        log.info("No RSyntaxTextArea found in any window");
        return null;
    }
}