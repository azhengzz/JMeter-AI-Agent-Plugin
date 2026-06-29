package org.gitee.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.net.URI;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.gitee.jmeter.ai.intellisense.InputBoxIntellisense;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.swing.AgentSwingWorker;
import org.gitee.jmeter.ai.gui.render.MarkdownParserHolder;
import org.gitee.jmeter.ai.gui.render.UiThemeUtil;
import org.gitee.jmeter.ai.selection.SelectionListener;
import org.gitee.jmeter.ai.selection.SelectionSnapshot;
import org.gitee.jmeter.ai.selection.SelectionTracker;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.ClaudeService;

import com.openai.models.models.Model;
import org.apache.jorphan.gui.JMeterUIDefaults;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.Models;
import org.gitee.jmeter.ai.utils.VersionUtils;
import org.gitee.jmeter.ai.service.OpenAiService;
import org.gitee.jmeter.ai.service.OllamaAiService;
import org.gitee.jmeter.ai.service.provider.ProviderRegistry;
import org.gitee.jmeter.ai.service.provider.AiServiceFactory;
import org.gitee.jmeter.ai.tracing.TracedAiService;

import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelListPage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * Now uses AgentLoop for full agent capabilities (tools, memory, skills).
 */
public class AiChatPanel extends JPanel implements PropertyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);
    private static final String CHAT_SESSION_KEY = "jmeter-ai-chat";
    private static final String REPO_URL = "https://github.com/azhengzz/JMeter-AI-Agent-Plugin";

    // UI components (kept for backward compatibility)
    private JTextPane chatArea;
    private JTextArea messageField;
    private JButton sendButton;
    private JComboBox<String> modelSelector;
    // Agent components
    private AgentLoop agentLoop;
    private ClaudeService claudeService; // Keep for model loading
    private OpenAiService openAiService; // Keep for model loading
    private OllamaAiService ollamaService; // Keep for model loading
    private AiService currentAiService; // Track current service

    // Store the base font sizes for scaling
    private float baseChatFontSize;
    private float baseMessageFontSize;

    // Component managers
    private final MessageProcessor messageProcessor;

    // Vertical split pane for drag-to-resize between chat area and input area
    private JSplitPane verticalSplitPane;

    // Track active worker for Stop button support
    private AgentSwingWorker activeWorker;
    // Track whether tool calls were displayed progressively during the loop
    private boolean toolCallsDisplayedProgressively;
    // Separate Stop button (visible during agent processing)
    private JButton stopButton;

    // Selection context bar (current JMeter element + focused control)
    private SelectionContextBar selectionContextBar;
    private JCheckBox injectContextCheckBox;
    private SelectionListener selectionTrackerListener;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        // Initialize services (keep for model loading)
        claudeService = new ClaudeService();
        openAiService = new OpenAiService();
        ollamaService = new OllamaAiService();

        // Initialize AgentLoop with ClaudeService as the default AI service
        initializeAgentLoop();

        messageProcessor = new MessageProcessor();

        // Register for UI refresh events (for zoom functionality)
        UIManager.addPropertyChangeListener(this);

        // Set up the panel layout
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));
        setMinimumSize(new Dimension(350, 400));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // Initialize model selector with loading state
        modelSelector = new JComboBox<>();
        modelSelector.addItem(null); // Add empty item while loading
        modelSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                if (value == null) {
                    return super.getListCellRendererComponent(list, "Loading models...", index, isSelected,
                            cellHasFocus);
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });

        // Load models in background
        loadModelsInBackground();

        // Add a listener to handle model changes
        modelSelector.addActionListener(e -> {
            String selectedModel = (String) modelSelector.getSelectedItem();
            if (selectedModel != null) {
                log.info("Model selected from dropdown: {}", selectedModel);

                // Parse the model ID to extract provider and model name
                // Format: "provider:model" or just "model"
                String modelName = selectedModel;
                if (selectedModel.contains(":")) {
                    String[] parts = selectedModel.split(":", 2);
                    String provider = parts[0];
                    modelName = parts[1];

                    // Set the model in the appropriate service
                    // Note: We pass the FULL model ID (with prefix) so OpenAiService can detect the provider
                    switch (provider) {
                        case "openai", "deepseek", "zhipu", "moonshot", "minimax" -> {
                            openAiService.setModel(selectedModel);  // Pass full ID with prefix
                            log.info("Using {} provider for model: {}", provider, modelName);
                        }
                        case "ollama" -> {
                            ollamaService.setModel(modelName);  // Ollama doesn't need prefix
                            log.info("Using ollama provider for model: {}", modelName);
                        }
                        default -> {
                            // For Anthropic (no prefix) and others
                            claudeService.setModel(selectedModel);
                            log.info("Using Anthropic provider for model: {}", selectedModel);
                        }
                    }
                } else {
                    // No provider prefix, assume Anthropic
                    claudeService.setModel(selectedModel);
                    log.info("Using Anthropic provider for model: {}", selectedModel);
                }

                // Switch the AI service based on the selected model
                switchAiService();
            }
        });

        // Create a panel for the chat area with header
        JPanel chatPanel = new JPanel(new BorderLayout());
        Color borderColor = getThemeColor("Component.borderColor", UIManager.getColor("Separator.foreground"));
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, borderColor));

        // Create a header panel for the title and new chat button
        JPanel headerPanel = createHeaderPanel();
        chatPanel.add(headerPanel, BorderLayout.NORTH);

        // Initialize chat area
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setContentType("text/html");
        // Use configured font size if set, otherwise use system default font size
        Font defaultFont = UIManager.getFont("TextField.font");
        int configuredFontSize = Integer.parseInt(AiConfig.getProperty("ai.chat.font.size", "0"));
        int fontSize = configuredFontSize > 0 ? configuredFontSize : defaultFont.getSize();
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), fontSize);
        largerFont = UiThemeUtil.ensureCjkSupport(largerFont);
        chatArea.setFont(largerFont);
        messageProcessor.setBaseFont(largerFont);
        chatArea.setBackground(Color.WHITE);

        // Base CSS for the HTML render path (theme-aware colors via UIManager).
        HTMLEditorKit htmlKit = (HTMLEditorKit) chatArea.getEditorKit();
        StyleSheet ss = htmlKit.getStyleSheet();
        Color textFg = getThemeColor("TextPane.foreground", Color.BLACK);
        Color codeBg = UiThemeUtil.getCodeBlockBackground();
        int fontPt = largerFont.getSize();
        ss.addRule("body { font-family:" + largerFont.getFamily() + "; font-size:" + fontPt
                + "pt; color:" + UiThemeUtil.toHex(textFg) + "; background:#ffffff; }");
        ss.addRule("p { margin:5px 0; }");
        ss.addRule("div { margin:5px 0; }");
        // Headings scale with the base font size (browser-standard ratios) so they follow
        // ai.chat.font.size instead of Swing's built-in fixed heading sizes.
        ss.addRule("h1 { font-size:" + Math.round(fontPt * 1.50f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h2 { font-size:" + Math.round(fontPt * 1.30f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h3 { font-size:" + Math.round(fontPt * 1.17f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h4 { font-size:" + fontPt + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h5 { font-size:" + Math.round(fontPt * 0.83f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("h6 { font-size:" + Math.round(fontPt * 0.67f) + "pt; font-weight:bold; margin:6px 0; }");
        ss.addRule("ul,ol { margin:4px 0; padding-left:22px; }");
        ss.addRule("li { margin:1px 0; }");
        // font-size is required here: once font-family is set, Swing's CSS engine no longer
        // inherits the body font size and would fall back to a default — the same applies
        // to any rule that specifies a font-family.
        ss.addRule("pre, code, kbd, samp { font-family: Monospaced; font-size:" + fontPt + "pt; }");
        // Inline code/kbd/samp: light background + padding so they read as distinct "code chips"
        // instead of bare monospaced text. Background reuses codeBg (theme-aware, guaranteed
        // contrast vs the panel); font stays at fontPt so it scales with ai.chat.font.size.
        ss.addRule("code, kbd, samp { background:" + UiThemeUtil.toHex(codeBg) + "; color:"
                + UiThemeUtil.toHex(textFg) + "; padding:1px 3px; }");
        ss.addRule("pre { background:" + UiThemeUtil.toHex(codeBg) + "; padding:4px 6px; margin:4px 0; }");
        // Inside <pre><code>, drop the inline "chip" so the code block stays one solid panel.
        // Harmless even if Swing ignores the descendant selector: both backgrounds are codeBg.
        ss.addRule("pre code { background: transparent; padding:0; }");
        ss.addRule("table { border-collapse:collapse; margin:4px 0; }");
        ss.addRule("th, td { border:1px solid #999; padding:2px 6px; }");
        ss.addRule("th { background:" + UiThemeUtil.toHex(codeBg) + "; }");
        ss.addRule("blockquote { border-left:3px solid #bbb; margin:4px 0; padding-left:8px; color:#666; }");

        // Store the base font size for scaling
        baseChatFontSize = largerFont.getSize2D();

        // Set default paragraph attributes for left alignment
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);

        // Add keyboard shortcut for undo (Cmd+Z on Mac, Ctrl+Z on Windows/Linux)
        InputMap inputMap = chatArea.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = chatArea.getActionMap();

        // Define the key stroke based on the platform - using modern API instead of
        // deprecated Event.META_MASK
        KeyStroke undoKeyStroke;
        KeyStroke redoKeyStroke;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            // Mac (Cmd+Z for undo, Cmd+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else if (osName.contains("linux")) {
            // Linux (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else {
            // Windows (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        }

        inputMap.put(undoKeyStroke, "undoAction");
        actionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Undo/Redo functionality is now handled by AgentLoop tools
                // Use the agent's undo capability or type @undo in the chat
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Undo is available through AgentLoop. Type 'undo' in the chat or use the appropriate tool.",
                            Color.BLUE, false);
                } catch (BadLocationException ex) {
                    log.error("Error displaying message", ex);
                }
            }
        });

        // Add keyboard shortcut for redo
        inputMap.put(redoKeyStroke, "redoAction");
        actionMap.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Undo/Redo functionality is now handled by AgentLoop tools
                // Use the agent's redo capability or type @redo in the chat
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Redo is available through AgentLoop. Type 'redo' in the chat or use the appropriate tool.",
                            Color.BLUE, false);
                } catch (BadLocationException ex) {
                    log.error("Error displaying message", ex);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(scrollPane, BorderLayout.CENTER);

        // Create the bottom panel with model selector and input controls
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        // Add model selector to the bottom panel
        // modelPanel uses BorderLayout: WEST holds "Model: " + selector,
        // CENTER holds the selection context bar so it stretches to the right.
        JPanel modelPanel = new JPanel(new BorderLayout(8, 0));
        JPanel modelLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel modelLabel = new JLabel("Model: ");
        modelLeft.add(modelLabel);
        modelLeft.add(modelSelector);
        modelPanel.add(modelLeft, BorderLayout.WEST);

        // Selection context bar: shows current JMeter element + focused control
        selectionContextBar = new SelectionContextBar();
        modelPanel.add(selectionContextBar, BorderLayout.CENTER);

        // Toggle: whether to inject current selection into UserMessage sent to LLM
        injectContextCheckBox = new JCheckBox("ToAI", SelectionTracker.isInjectToContextEnabled());
        injectContextCheckBox.setToolTipText("When checked, each message automatically appends the currently selected JMeter element info (type/name/id/focused field) to the context so the AI is aware of it.");
        injectContextCheckBox.setMargin(new Insets(0, 4, 0, 0));
        injectContextCheckBox.addItemListener(e ->
                SelectionTracker.setInjectToContextEnabled(e.getStateChange() == ItemEvent.SELECTED));
        modelPanel.add(injectContextCheckBox, BorderLayout.EAST);

        bottomPanel.add(modelPanel, BorderLayout.NORTH);

        // Create the input panel with message field and send button
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));

        // Initialize message field
        messageField = new JTextArea(3, 20);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(largerFont);

        // Store the base font size for scaling
        baseMessageFontSize = largerFont.getSize2D();
        Color inputBorderColor = getThemeColor("Component.borderColor", Color.LIGHT_GRAY);
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(inputBorderColor),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        // Setup intellisense for command suggestions
        new InputBoxIntellisense(messageField);

        // Add key listener for Enter to send message, Shift+Enter for newline
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    if (e.isShiftDown()) {
                        messageField.insert("\n", messageField.getCaretPosition());
                    } else {
                        sendMessage();
                    }
                }
            }
        });

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);

        // Initialize send button
        sendButton = new JButton("Send");
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.setOpaque(true);
        sendButton.addActionListener(e -> sendMessage());

        // Initialize stop button (hidden by default, shown during agent processing)
        stopButton = new JButton("■");  // ■ character
        stopButton.setFont(new Font(stopButton.getFont().getName(), Font.BOLD, 10));
        stopButton.setFocusPainted(false);
        stopButton.setOpaque(true);
        stopButton.setForeground(new Color(180, 40, 40));
        stopButton.setBackground(new Color(255, 210, 210));
        stopButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 80, 80), 1, true),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        stopButton.setToolTipText("Stop the current AI task");
        stopButton.setVisible(false);
        stopButton.addActionListener(e -> stopActiveTask());

        // Button panel: Send (top) + Stop (bottom) vertical layout
        // Buttons expand to fill full height and stretch with split pane drag
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        Dimension maxButton = new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        sendButton.setMaximumSize(maxButton);
        stopButton.setMaximumSize(maxButton);
        sendButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        stopButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        buttonPanel.add(sendButton);
        buttonPanel.add(Box.createVerticalStrut(4));
        buttonPanel.add(stopButton);
        inputPanel.add(buttonPanel, BorderLayout.EAST);

        bottomPanel.add(inputPanel, BorderLayout.CENTER);

        // Create vertical split pane to allow resizing between chat area and input area
        chatPanel.setMinimumSize(new Dimension(0, 100));
        bottomPanel.setMinimumSize(new Dimension(0, 80));

        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatPanel, bottomPanel);
        verticalSplitPane.setResizeWeight(0.9);
        verticalSplitPane.setDividerLocation(0.9);
        verticalSplitPane.setContinuousLayout(true);
        verticalSplitPane.setBorder(null);
        add(verticalSplitPane, BorderLayout.CENTER);

        // Display welcome message
        displayWelcomeMessage();

        // Subscribe to selection tracker (L1: tree node, L2: focused control)
        installSelectionTracker();
    }

    /**
     * Subscribe to {@link SelectionTracker} so the {@link SelectionContextBar}
     * reflects the current JMeter element selection and editor-panel focus.
     *
     * <p>{@code SelectionTracker.install()} is idempotent: it is normally installed
     * by {@code SelectionInitCommand} on JMeter's ADD_ALL event, but we call it
     * here as a fallback in case the panel is created before that event fires.
     */
    private void installSelectionTracker() {
        SelectionTracker.install();
        selectionTrackerListener = new SelectionListener() {
            @Override
            public void onComponentSelected(SelectionSnapshot snapshot) {
                SwingUtilities.invokeLater(() -> selectionContextBar.update(snapshot));
            }

            @Override
            public void onElementFocused(SelectionSnapshot snapshot) {
                SwingUtilities.invokeLater(() -> selectionContextBar.update(snapshot));
            }
        };
        SelectionTracker.addListener(selectionTrackerListener);
        // Sync current selection state immediately so the bar isn't empty on first open
        SelectionTracker.fireInitialSnapshot(selectionTrackerListener);
    }

    /**
     * Creates the header panel with title and new chat button.
     * 
     * @return The header panel
     */
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        Color headerBorderColor = getThemeColor("Separator.foreground", Color.LIGHT_GRAY);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, headerBorderColor),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(UIManager.getColor("Panel.background"));

        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("Gitee Ai - JMeter Agent v" + VersionUtils.getVersion());
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 14));

        // Title + Star link grouped on the left so the star sits right of the version.
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titlePanel.setOpaque(false);
        titlePanel.add(titleLabel);
        titlePanel.add(createStarLinkButton());
        headerPanel.add(titlePanel, BorderLayout.WEST);

        // Create the "New Chat" button with a plus icon
        JButton newChatButton = new JButton("+");
        newChatButton.setToolTipText("Start a new conversation");
        newChatButton.setFont(new Font(newChatButton.getFont().getName(), Font.BOLD, 16));
        newChatButton.setFocusPainted(false);
        newChatButton.setMargin(new Insets(0, 8, 0, 8));
        Color buttonBorderColor = getThemeColor("Component.borderColor", Color.LIGHT_GRAY);
        newChatButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(buttonBorderColor, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        // Add action listener to reset the conversation
        newChatButton.addActionListener(e -> startNewConversation());

        // Add the button to the right side of the header panel
        headerPanel.add(newChatButton, BorderLayout.EAST);

        return headerPanel;
    }

    /**
     * Build the "⭐ Star" hyperlink-style button that opens the project repo.
     * Rendered as an inline link: no border, no fill, blue text, hand cursor.
     */
    private static JButton createStarLinkButton() {
        JButton starButton = new JButton("⭐ Star");
        starButton.setBorderPainted(false);
        starButton.setContentAreaFilled(false);
        starButton.setFocusPainted(false);
        starButton.setOpaque(false);
        starButton.setMargin(new Insets(0, 2, 0, 2));
        starButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        starButton.setForeground(new Color(9, 105, 218));
        starButton.setFont(new Font(starButton.getFont().getName(), Font.PLAIN, 13));
        starButton.setToolTipText("Star the project on GitHub");
        starButton.addActionListener(e -> openRepoUrl());
        return starButton;
    }

    private static void openRepoUrl() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(REPO_URL));
            } else {
                log.warn("Desktop browsing is not supported on this platform");
            }
        } catch (Exception ex) {
            log.error("Failed to open repo URL: {}", REPO_URL, ex);
        }
    }

    /**
     * Loads the available models in the background.
     */
    private void loadModelsInBackground() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                String provider = AiConfig.getDefaultProvider();
                String model = AiConfig.getDefaultModel();
                String modelId = provider + ":" + model;
                log.info("Model selector using global config: {}", modelId);
                return List.of(modelId);
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    modelSelector.removeAllItems();

                    String globalModelId = AiConfig.getDefaultProvider() + ":" + AiConfig.getDefaultModel();

                    for (String model : models) {
                        modelSelector.addItem(model);
                    }

                    if (modelSelector.getItemCount() > 0) {
                        modelSelector.setSelectedIndex(0);
                        String selectedModel = (String) modelSelector.getSelectedItem();
                        setModelForProvider(selectedModel);
                        switchAiService();
                        log.info("Model selector set to: {}", selectedModel);
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }.execute();
    }

    /**
     * Initialize the AgentLoop with the appropriate AI service.
     */
    private void initializeAgentLoop() {
        try {
            // Get the default AI service based on current model selection
            // During construction, modelSelector may not be initialized yet
            AiService aiService;
            if (modelSelector == null) {
                // Use default service during construction - wrap with LangSmith tracing
                aiService = TracedAiService.wrap(claudeService);
                currentAiService = claudeService;
            } else {
                aiService = getAiServiceForCurrentModel();
            }

            agentLoop = AgentLoopFactory.getAgentLoop(aiService);

            if (agentLoop == null) {
                log.warn("AgentLoop is disabled or failed to initialize. Some features may not work.");
            } else {
                log.info("AgentLoop initialized successfully");
            }
        } catch (Exception e) {
            log.error("Failed to initialize AgentLoop", e);
        }
    }

    /**
     * Get the appropriate AiService based on the current model selection.
     * NOTE: This method does NOT modify currentAiService to avoid side effects.
     * Uses AiServiceFactory to ensure LangSmith tracing is applied.
     */
    private AiService getAiServiceForCurrentModel() {
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            // For default case, use TracedAiService.wrap() for ClaudeService
            return TracedAiService.wrap(claudeService);
        }

        // Use AiServiceFactory to create the service
        // This will automatically wrap with LangSmith tracing if enabled
        AiService service = AiServiceFactory.createService(selectedModel);

        // Also update the raw service instance for model loading
        updateRawServiceForModel(selectedModel);

        return service;
    }

    /**
     * Update the raw service instance for model loading purposes.
     * This ensures the cached service instances (claudeService, openAiService, etc.)
     * have the correct model set for model loading operations.
     */
    private void updateRawServiceForModel(String modelId) {
        if (modelId == null) return;

        if (modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            String provider = parts[0];

            switch (provider) {
                case "openai", "deepseek", "zhipu", "moonshot", "minimax" -> {
                    openAiService.setModel(modelId);
                }
                case "ollama" -> {
                    ollamaService.setModel(modelId);
                }
                default -> {
                    claudeService.setModel(modelId);
                }
            }
        } else {
            // No provider prefix, default to Claude
            claudeService.setModel(modelId);
        }
    }

    /**
     * Set the model on the appropriate service based on the model ID.
     * Helper method to avoid code duplication.
     */
    private void setModelForProvider(String modelId) {
        if (modelId == null) return;

        if (modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            String provider = parts[0];
            String modelName = parts[1];

            switch (provider) {
                case "openai", "deepseek", "zhipu", "moonshot", "minimax" -> {
                    openAiService.setModel(modelId);  // Pass full ID with prefix
                    log.info("Set {} provider model: {}", provider, modelName);
                }
                case "ollama" -> {
                    ollamaService.setModel(modelName);  // Ollama doesn't need prefix
                    log.info("Set ollama provider model: {}", modelName);
                }
                default -> {
                    claudeService.setModel(modelId);
                    log.info("Set Anthropic provider model: {}", modelId);
                }
            }
        } else {
            // No provider prefix, assume Anthropic
            claudeService.setModel(modelId);
            log.info("Set Anthropic provider model: {}", modelId);
        }
    }

    /**
     * Switch the AI service based on the selected model.
     * This recreates the AgentLoop with the appropriate service.
     */
    private void switchAiService() {
        try {
            AiService newService = getAiServiceForCurrentModel();

            // Only recreate if service changed
            if (newService != currentAiService) {
                log.info("Switching AI service from {} to {}",
                        currentAiService.getName(), newService.getName());

                // Reset and recreate AgentLoop with new service
                AgentLoopFactory.reset();
                agentLoop = AgentLoopFactory.getAgentLoop(newService);

                if (agentLoop == null) {
                    log.warn("AgentLoop failed to initialize after service switch");
                } else {
                    log.info("AI service switched successfully to {}", newService.getName());
                    // Update currentAiService after successful switch
                    currentAiService = newService;
                }
            }
        } catch (Exception e) {
            log.error("Failed to switch AI service", e);
        }
    }

    /**
     * Displays a welcome message in the chat area.
     */
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");

        String welcomeMessage = "# Welcome to Gitee Ai - JMeter Agent\n\n" +
                "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
                "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
                "**Slash commands:**\n" +
                "- `/new` — Start a new conversation\n" +
                "- `/status` — Show agent status\n" +
                "- `/help` — Show available commands\n\n" +
                "How can I assist you today?";

        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, getThemeColor("TextPane.foreground", Color.BLACK), true);
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }

    /**
     * Starts a new conversation by clearing the chat area and AgentLoop session.
     */
    private void startNewConversation() {
        log.info("Starting new conversation");

        // Archive current session messages via AI consolidation (Nanobot alignment)
        if (agentLoop != null) {
            var session = agentLoop.getSessionManager().getOrCreate(CHAT_SESSION_KEY);
            var snapshot = session.getUnconsolidatedMessages();

            session.clear();
            agentLoop.getSessionManager().saveSession(session);
            agentLoop.getSessionManager().invalidate(session.getKey());

            if (!snapshot.isEmpty()) {
                agentLoop.getMemoryConsolidator().archiveMessagesAsync(snapshot);
            }

            log.info("Session archived {} messages", snapshot.size());
        }

        // Clear the chat area
        chatArea.setText("");

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Sends the message from the input field to the chat using AgentLoop.
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        // If there's an active agent run, inject the message instead
        if (agentLoop != null && agentLoop.hasActiveRun(CHAT_SESSION_KEY)) {
            injectMessage();
            return;
        }

        startNormalSend(message);
    }

    /**
     * Start a normal (non-injection) agent run via AgentSwingWorker.
     * Extracted from sendMessage() so injectMessage() can fall back here on race conditions.
     */
    private void startNormalSend(String message) {
        log.info("Sending user message: {}", message);

        // Add the user message to the chat
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "\nYou: " + message, getThemeColor("TextPane.foreground", Color.BLACK), false);
        } catch (BadLocationException e) {
            log.error("Error appending user message to chat", e);
        }

        // Clear the message field
        messageField.setText("");

        // Add "AI is thinking..." indicator
        try {
            messageProcessor.appendLoadingIndicator(chatArea.getStyledDocument(), getThemeColor("Label.disabledForeground", Color.GRAY));
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator", e);
        }

        // Ensure AgentLoop is initialized
        if (agentLoop == null) {
            log.warn("AgentLoop not initialized, attempting to reinitialize");
            initializeAgentLoop();
            if (agentLoop == null) {
                try {
                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            "Agent Loop is not available. Please check your configuration.",
                            Color.RED, false);
                    removeLoadingIndicator();
                    setButtonToSendMode();
                    return;
                } catch (BadLocationException e) {
                    log.error("Error displaying error message", e);
                }
            }
        }

        // Switch button to Stop mode while processing
        setButtonToStopMode();

        // Use AgentSwingWorker to process the message through AgentLoop
        activeWorker = new AgentSwingWorker(
                agentLoop,
                message,
                CHAT_SESSION_KEY,
                this::handleAgentResponse,
                this::handleProgress
        );
        activeWorker.execute();
    }

    /**
     * Inject a follow-up message into the active agent run.
     * Routes through processMessage so dispatchable commands (e.g. /new, /help)
     * are handled immediately rather than queued as user text.
     *
     * Re-checks hasActiveRun() to narrow the race window. If the active run
     * just finished, falls back to the normal send path (AgentSwingWorker).
     */
    private void injectMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        log.info("Injecting follow-up message during active run: {}", message);

        // Clear the message field
        messageField.setText("");

        if (agentLoop == null) {
            return;
        }

        // Re-check: if the active run finished between sendMessage() and here,
        // fall back to normal send path (AgentSwingWorker) for proper UI handling.
        if (!agentLoop.hasActiveRun(CHAT_SESSION_KEY)) {
            log.info("Active run finished during injection, falling back to normal send");
            startNormalSend(message);
            return;
        }

        // Active run confirmed — processMessage will hit Phase 2 (non-blocking)
        CompletableFuture<AgentResponse> future = agentLoop.processMessage(message, CHAT_SESSION_KEY);

        // future should always be done here (Phase 2 returns completedFuture),
        // but guard against an extremely narrow race condition.
        if (future.isDone()) {
            try {
                AgentResponse response = future.get();
                if (response.isSuccess() && response.getContent() != null) {
                    if (response.getContent().startsWith("Message injected")) {
                        // Injection queued — show in green italic
                        messageProcessor.appendStyled(chatArea.getStyledDocument(),
                            "[Injected] You: " + message, new Color(0x00, 0x80, 0x00), Font.ITALIC);
                    } else {
                        // Command dispatch result (e.g. /new, /help) — show normally
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            response.getContent(), getThemeColor("TextPane.foreground", Color.BLACK), false);
                    }
                }
            } catch (Exception e) {
                log.error("Error handling injection response", e);
            }
        } else {
            // Extremely narrow race: run finished right after our hasActiveRun check.
            // The future is a full agent run — connect it to the normal UI handlers.
            log.info("Race condition: future not done, connecting to handleAgentResponse");
            future.thenAccept(response -> SwingUtilities.invokeLater(() -> handleAgentResponse(response)));
        }
    }

    /**
     * Handle AgentLoop response callback.
     */
    private void handleAgentResponse(AgentResponse response) {
        // Clear active worker reference
        activeWorker = null;

        // Remove the loading indicator
        removeLoadingIndicator();

        if (!response.isSuccess()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Error: " + response.getErrorMessage(),
                        Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
        } else {
            // Display tool call information only if not already shown progressively
            if (!toolCallsDisplayedProgressively) {
                boolean showToolCalls = Boolean.parseBoolean(
                    org.gitee.jmeter.ai.utils.AiConfig.getProperty("ai.chat.show.tool.calls", "true"));

                if (showToolCalls && response.getToolEvents() != null && !response.getToolEvents().isEmpty()) {
                    displayToolCallInfo(response.getToolEvents());
                }
            }
            toolCallsDisplayedProgressively = false;

            processAiResponse(response.getContent());
        }

        // Re-enable input
        messageField.setEnabled(true);
        setButtonToSendMode();
        messageField.requestFocusInWindow();
    }

    /**
     * Handle typed progress updates from the agent loop.
     * Renders different types (THINKING, TOOL_CALL, ERROR, PROGRESS) with appropriate styling.
     */
    private void handleProgress(ProgressUpdate update) {
        SwingUtilities.invokeLater(() -> {
            try {
                removeLoadingIndicator();

                switch (update.getType()) {
                    case THINKING -> renderThinking(update.getMessage());
                    case TOOL_CALL -> {
                        toolCallsDisplayedProgressively = true;
                        Object payload = update.getPayload();
                        if (payload instanceof ToolEvent event) {
                            displaySingleToolEvent(event);
                        } else {
                            renderToolHint(update.getMessage());
                        }
                    }
                    case ERROR -> renderError(update.getMessage());
                    case INTERMEDIATE_RESPONSE -> renderIntermediateResponse(update.getMessage());
                    default -> renderProgress(update.getMessage());
                }
            } catch (BadLocationException e) {
                log.error("Error displaying progress", e);
            }
        });
    }

    private void renderThinking(String text) throws BadLocationException {
        if (text == null || text.isEmpty()) {
            return;
        }
        messageProcessor.appendStyled(chatArea.getStyledDocument(), text.stripTrailing(),
                new Color(0x78, 0x78, 0x78), Font.ITALIC);
    }

    private void renderToolHint(String hint) throws BadLocationException {
        messageProcessor.appendStyled(chatArea.getStyledDocument(), hint.stripTrailing(),
                new Color(0x64, 0x64, 0x96), Font.BOLD);
    }

    private void renderProgress(String text) throws BadLocationException {
        messageProcessor.appendMessage(chatArea.getStyledDocument(), text, Color.GRAY, false);
    }

    private void renderError(String text) throws BadLocationException {
        messageProcessor.appendStyled(chatArea.getStyledDocument(), text.stripTrailing(), Color.RED);
    }

    private void renderIntermediateResponse(String text) throws BadLocationException {
        if (text == null || text.isEmpty()) {
            return;
        }
        appendBotResponse(text);
    }

    /** Append an AI (markdown) response block with the inline 🤖 marker and themed foreground. */
    private void appendBotResponse(String markdown) throws BadLocationException {
        String fg = UiThemeUtil.toHex(getThemeColor("TextPane.foreground", Color.BLACK));
        messageProcessor.appendHtml(chatArea.getStyledDocument(), botHeaderHtml(fg, markdown));
    }

    /**
     * Build the AI response HTML with the 🤖 marker injected INSIDE the first block element
     * (e.g. {@code <p><span>🤖 </span>...}) so the bot emoji sits inline with the first line
     * instead of on its own line above the block content.
     */
    private static String botHeaderHtml(String fg, String markdown) {
        String bot = "<span style=\"font-weight:bold;color:#0066cc\">🤖: </span>";
        String md = MarkdownParserHolder.renderToHtml(markdown);
        String injected;
        int gt = md.indexOf('>');
        if (!md.isEmpty() && md.charAt(0) == '<' && gt > 0 && gt <= 4) {
            // md starts with a short opening tag like <p> or <h1> — inject right after it
            injected = md.substring(0, gt + 1) + bot + md.substring(gt + 1);
        } else {
            injected = bot + md;
        }
        return "<div style=\"color:" + fg + "\">" + injected + "</div>";
    }

    /**
     * Display tool call information in the chat area (fallback for non-progressive mode).
     */
    private void displayToolCallInfo(List<ToolEvent> toolEvents) {
        try {
            for (ToolEvent event : toolEvents) {
                displaySingleToolEvent(event);
            }
        } catch (BadLocationException e) {
            log.error("Error displaying tool call info", e);
        }
    }

    /**
     * Display a single tool event with styled output.
     */
    private void displaySingleToolEvent(ToolEvent event) throws BadLocationException {
        int maxToolResultLength = Integer.parseInt(
            org.gitee.jmeter.ai.utils.AiConfig.getProperty("ai.chat.tool.result.max.length", "500"));

        Color statusColor;
        String statusIcon;
        switch (event.getStatus()) {
            case OK -> {
                statusColor = new Color(34, 139, 34);
                statusIcon = "✓";
            }
            case ERROR -> {
                statusColor = new Color(220, 20, 60);
                statusIcon = "✗";
            }
            case TIMEOUT -> {
                statusColor = new Color(255, 140, 0);
                statusIcon = "⏱";
            }
            case NOT_FOUND -> {
                statusColor = new Color(128, 128, 128);
                statusIcon = "?";
            }
            default -> {
                statusColor = Color.BLACK;
                statusIcon = "-";
            }
        }

        StringBuilder sb = new StringBuilder("<div>");
        sb.append("<span style=\"font-weight:bold;color:#646496\">🔧</span> ");
        sb.append("<span style=\"color:").append(UiThemeUtil.toHex(statusColor)).append("\">");
        sb.append(MessageProcessor.escapeHtml(statusIcon + " " + event.getToolName() + " [" + event.getDurationMs() + "ms]"));
        sb.append("</span>");

        if (event.getArguments() != null && !event.getArguments().isEmpty()) {
            String argsStr = formatArguments(event.getArguments());
            String displayArgs = argsStr.stripTrailing();
            if (argsStr.length() > maxToolResultLength) {
                displayArgs = argsStr.substring(0, maxToolResultLength) + "...(truncated, total " + argsStr.length() + " chars)";
            }
            sb.append("<br><span style=\"color:#4682b4;font-style:italic\">Args: ")
              .append(MessageProcessor.escapeHtml(displayArgs)).append("</span>");
        }

        String detail = event.getDetail();
        if (detail != null && !detail.isEmpty()) {
            String displayDetail = detail.stripTrailing();
            if (detail.length() > maxToolResultLength) {
                displayDetail = detail.substring(0, maxToolResultLength) + "...(truncated, total " + detail.length() + " chars)";
            }
            sb.append("<br><span style=\"color:#646464;font-style:italic\">Result: ")
              .append(MessageProcessor.escapeHtml(displayDetail)).append("</span>");
        }
        sb.append("</div>");

        messageProcessor.appendHtml(chatArea.getStyledDocument(), sb.toString());
    }

    /**
     * Format arguments map to a readable string.
     */
    private String formatArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        return arguments.toString();
    }

    /**
     * Stop the active AI task, triggered by the Stop button.
     */
    private void stopActiveTask() {
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
            activeWorker = null;
        }
        if (agentLoop != null) {
            agentLoop.cancelActiveTask(CHAT_SESSION_KEY);
        }
        removeLoadingIndicator();
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(),
                    "Stopped.", getThemeColor("Label.disabledForeground", Color.GRAY), false);
        } catch (BadLocationException e) {
            log.error("Error displaying stop message", e);
        }
        setButtonToSendMode();
        messageField.requestFocusInWindow();
    }

    private void setButtonToStopMode() {
        // Show the separate stop button
        stopButton.setVisible(true);

        // Send button keeps "Send" text but routes to injectMessage()
        sendButton.setToolTipText("Send a follow-up message while AI is processing");
        for (ActionListener al : sendButton.getActionListeners()) {
            sendButton.removeActionListener(al);
        }
        sendButton.addActionListener(e -> injectMessage());
    }

    private void setButtonToSendMode() {
        // Hide the stop button
        stopButton.setVisible(false);

        // Reset send button to normal behavior
        sendButton.setText("Send");
        sendButton.setToolTipText(null);
        sendButton.setForeground(null);
        sendButton.setBackground(null);
        sendButton.setBorder(UIManager.getBorder("Button.border"));
        sendButton.setEnabled(true);
        for (ActionListener al : sendButton.getActionListeners()) {
            sendButton.removeActionListener(al);
        }
        sendButton.addActionListener(e -> sendMessage());
    }

    /**
     * Removes the loading indicator from the chat area.
     */
    private void removeLoadingIndicator() {
        try {
            messageProcessor.removeLoadingIndicator(chatArea.getStyledDocument());
        } catch (BadLocationException e) {
            log.error("Error removing loading indicator", e);
        }
    }

    /**
     * Processes an AI response and displays it in the chat area.
     * 
     * @param response The AI response to process
     */
    private void processAiResponse(String response) {
        if (response == null || response.isEmpty()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "No response from AI. Please try again.", Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
            log.warn("Empty AI response");
            return;
        }

        log.info("Processing AI response: {}", response.substring(0, Math.min(100, response.length())));

        // Add the AI response to the chat
        log.info("Appending AI response to chat");
        try {
            // AI response header + markdown content as one HTML block
            appendBotResponse(response);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }

        // Scroll to the bottom of the chat area to show the latest message
        SwingUtilities.invokeLater(() -> {
            JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatArea);
            if (scrollPane != null) {
                JScrollBar vertical = scrollPane.getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            }
        });
    }

    /**
     * Cleans up resources when the panel is no longer needed.
     */
    public void cleanup() {
        // Unregister property change listener
        UIManager.removePropertyChangeListener(this);

        // Detach from SelectionTracker (other consumers may still be subscribed,
        // so we don't call SelectionTracker.uninstall()).
        if (selectionTrackerListener != null) {
            SelectionTracker.removeListener(selectionTrackerListener);
            selectionTrackerListener = null;
        }
    }

    /**
     * Updates the font sizes of chat components based on JMeter's current scale
     * factor
     */
    private void updateFontSizes() {
        float scale = JMeterUIDefaults.INSTANCE.getScale();

        // Update chat area font
        Font currentChatFont = chatArea.getFont();
        float newChatSize = baseChatFontSize * scale;
        Font newChatFont = currentChatFont.deriveFont(newChatSize);
        chatArea.setFont(newChatFont);
        messageProcessor.setBaseFont(newChatFont);

        // Update message field font
        Font currentMessageFont = messageField.getFont();
        float newMessageSize = baseMessageFontSize * scale;
        Font newMessageFont = currentMessageFont.deriveFont(newMessageSize);
        messageField.setFont(newMessageFont);
    }

    /**
     * Handles property change events, specifically for UI refresh events triggered
     * by zoom actions
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        // Check if this is a UI refresh event
        if ("lookAndFeel".equals(evt.getPropertyName())) {
            // Update font sizes based on the current scale
            updateFontSizes();
        }
    }

    /**
     * Gets a color from the current UIManager theme, falling back to a default if not available.
     *
     * @param key The UIManager color key
     * @param fallback The fallback color if the key is not found
     * @return The theme color or the fallback
     */
    private static Color getThemeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}