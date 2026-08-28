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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.gitee.jmeter.ai.intellisense.InputBoxIntellisense;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnPresenter;
import org.gitee.jmeter.ai.agent.swing.AgentSwingWorker;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.gitee.jmeter.ai.gui.render.MarkdownParserHolder;
import org.gitee.jmeter.ai.gui.render.UiThemeUtil;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.selection.SelectionListener;
import org.gitee.jmeter.ai.selection.SelectionSnapshot;
import org.gitee.jmeter.ai.selection.SelectionTracker;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.ClaudeService;

import org.apache.jorphan.gui.JMeterUIDefaults;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.VersionUtils;
import org.gitee.jmeter.ai.service.OpenAiService;
import org.gitee.jmeter.ai.service.provider.ProviderRegistry;
import org.gitee.jmeter.ai.service.provider.AiServiceFactory;
import org.gitee.jmeter.ai.tracing.TracedAiService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * Now uses AgentLoop for full agent capabilities (tools, memory, skills).
 */
public class AiChatPanel extends JPanel implements PropertyChangeListener, TurnPresenter {
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);
    private static final String REPO_URL = "https://github.com/azhengzz/JMeter-AI-Agent-Plugin";

    /**
     * 当前面板实例(单实例,由 {@link AiMenuItem} 懒创建)。供关闭整合在深度提炼成功后
     * 清空消息区使用——面板未创建时为 null(此时 agentLoop 也未初始化,提炼链路不会走到清空)。
     */
    private static volatile AiChatPanel INSTANCE;

    // UI components (kept for backward compatibility)
    private JTextPane chatArea;
    // Wraps chatArea; held as a field so the smart-scroll helpers can read/set the vertical
    // scrollbar (auto-scroll-to-bottom while the user is pinned to the tail).
    private JScrollPane chatScrollPane;
    private JTextArea messageField;
    private JButton sendButton;
    private JComboBox<String> modelSelector;
    // Agent components
    private AgentLoop agentLoop;
    private ClaudeService claudeService; // Keep for model loading
    private OpenAiService openAiService; // Keep for model loading
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

    /**
     * 会话渲染代数：/new、"+" 重置时 +1。订阅渲染回调（worker 回调、republishListener、
     * 进度回调、注入回退 future）时捕获当前值，投递时比对——不符即旧会话的迟到渲染，
     * 丢弃。关两类窗口：重置恰逢回合完成（signalCancel 对已完成
     * future no-op）时排在其后的结论投递；工具批在跑（join 不响应 interrupt）时
     * 重置后落地的 TOOL_CALL 进度。都在 EDT 上读写，volatile 仅兜底。
     */
    private volatile int conversationGeneration;

    /**
     * IPC 回合（委派/CLI 直连）呈现窗口代数：{@link #onTurnStarted} 的 EDT 运行时
     * 捕获当前 {@link #conversationGeneration} 武装，回合内的进度/终结投递
     * （{@link #onProgress}/{@link #onTurnCompleted}/{@link #onTurnCancelled}）经
     * {@link #runInIpcTurn} 比对放行——武装前到达（IpcServer 的回合提交与首事件间
     * 毫秒级窗口）或 {@code /new} 后迟到的投递一律丢弃。只在 EDT 读写。
     */
    private int ipcTurnGeneration = -1;

    // Selection context bar (current JMeter element + focused control)
    private SelectionContextBar selectionContextBar;
    private JCheckBox injectContextCheckBox;
    private SelectionListener selectionTrackerListener;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        INSTANCE = this; // 单实例注册,供关闭整合提炼成功后清空消息区
        // Initialize services (keep for model loading)
        claudeService = new ClaudeService();
        openAiService = new OpenAiService();

        // Initialize AgentLoop with ClaudeService as the default AI service
        initializeAgentLoop();
        // 面板懒创建：委派/CLI 回合可能先于面板存在——构造完成时领养在跑回合
        //（invokeLater 排队，等 UI 字段全部就绪后执行，见方法注释）
        adoptRunningIpcTurnIfNeeded();

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
                        case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama" -> {
                            openAiService.setModel(selectedModel);  // Pass full ID with prefix
                            log.info("Using {} provider for model: {}", provider, modelName);
                        }
                        default -> {
                            // Anthropic (provider tag "anthropic:"): needs the bare model id.
                            claudeService.setModel(modelName);
                            log.info("Using Anthropic provider for model: {}", modelName);
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
        int configuredFontSize = AiConfig.getChatFontSize();
        int fontSize = configuredFontSize > 0 ? configuredFontSize : defaultFont.getSize();
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), fontSize);
        largerFont = UiThemeUtil.ensureCjkSupport(largerFont);
        chatArea.setFont(largerFont);
        messageProcessor.setBaseFont(largerFont);
        // Store the base font size for scaling
        baseChatFontSize = largerFont.getSize2D();

        // Apply theme-aware background + StyleSheet. Also re-applied on Look and Feel change
        // (see propertyChange) so the chat follows JMeter's light/dark themes. The body rule
        // deliberately omits a CSS "background" — the JTextPane component background set here
        // provides the chat area's base color, which repaint applies instantly on theme switch
        // without re-parsing the HTML view tree (Swing caches parsed view attributes).
        applyChatTheme();

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

        chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        // Wire smart auto-scroll: while the viewport is pinned to the bottom, each appended
        // message scrolls the latest content into view; once the user scrolls up, appends leave
        // their position untouched until they return to the bottom.
        messageProcessor.setAutoScroll(this::isChatAtBottom, this::scrollToBottom);

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
                        updateRawServiceForModel(selectedModel);
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
                // 构造期取默认模型服务：走 AiServiceFactory 缓存（含 LangSmith 包装），
                // 与 IpcServer.resolveAgentLoop 的预热用同一裸 model → 同一缓存实例 →
                // 同一 AgentLoop 单例。面板是懒创建的（用户首次打开才构造），若此处换掉
                // 单例，IPC 正在跑的委派/CLI 回合会被孤儿化：其通知发在旧 loop 上
                // （旧 loop 的 presenter 为 null，全部静默丢失），STOP 经新 loop 恒
                // hasActiveRun=false 无法终止。currentAiService 同指工厂实例：模型加载
                // 完成后 switchAiService 比对 newService != currentAiService 时，选中
                // 默认模型则不重建。
                AiService factoryService = null;
                try {
                    factoryService = AiServiceFactory.createService(AiConfig.getDefaultModel());
                } catch (Exception e) {
                    log.warn("Default model service unavailable, falling back to panel-local ClaudeService", e);
                }
                if (factoryService != null) {
                    aiService = factoryService;
                    currentAiService = factoryService;
                } else {
                    aiService = TracedAiService.wrap(claudeService);
                    currentAiService = claudeService;
                }
            } else {
                aiService = getAiServiceForCurrentModel();
            }

            agentLoop = AgentLoopFactory.getAgentLoop(aiService);

            if (agentLoop == null) {
                log.warn("AgentLoop is disabled or failed to initialize. Some features may not work.");
            } else {
                registerRepublishListener();
                // IPC 回合（委派/CLI）呈现：与 republishListener 同点位绑定本 loop 实例。
                // 构造期（UI 字段未就绪）即可注册——回调一律 invokeLater，排队到构造完成后
                agentLoop.setTurnPresenter(this);
                log.info("AgentLoop initialized successfully");
            }
        } catch (Exception e) {
            log.error("Failed to initialize AgentLoop", e);
        }
    }

    /**
     * 注册 re-publish 孤儿回合监听器（见 {@link AgentLoop#setRepublishListener}）。
     *
     * <p>AgentLoop 在回合收尾时把注入队列残留用户消息重新发布成新回合（触发路径：
     * Stop 后垂死回合残留注入消息、注入周期超限残留、pre-pickup 取消善后），
     * 但该回合的 future 没有调用方持有——原 SwingWorker 早已终止。不在此接管，
     * 最终回复将不被渲染（回合跑完面板却无输出）。接管动作对齐
     * {@code startNormalSend} 的 UI 语义：切 Stop 模式 + loading 指示，
     * future 完成后走 {@link #handleAgentResponse} 统一渲染与复位。
     */
    private void registerRepublishListener() {
        if (agentLoop == null) {
            return;
        }
        agentLoop.setRepublishListener(future -> {
            // 捕获订阅时的会话代数：重置后到达的孤儿 UI 武装/渲染全部过期
            final int generation = conversationGeneration;
            SwingUtilities.invokeLater(() -> {
                if (generation != conversationGeneration || future.isDone()) {
                    // 会话已重置，或孤儿已终结（含被重置取消——取消路径无人复位 UI）：
                    // 不再武装，否则新会话留下常驻 Stop 按钮与幽灵 loading
                    return;
                }
                setButtonToStopMode();
                try {
                    messageProcessor.appendLoadingIndicator(chatArea.getStyledDocument(),
                            getThemeColor("Label.disabledForeground", Color.GRAY));
                } catch (BadLocationException e) {
                    log.error("Error adding loading indicator for republished turn", e);
                }
            });
            future.whenComplete((response, ex) -> SwingUtilities.invokeLater(() -> {
                if (generation != conversationGeneration) {
                    return; // 会话已重置：旧会话回合的结论不得渲染进新聊天区
                }
                if (ex != null) {
                    // 孤儿回合被 Stop 取消属正常路径：UI 已由 stopActiveTask 复位，跳过
                    if (ex instanceof java.util.concurrent.CancellationException
                            || ex.getCause() instanceof java.util.concurrent.CancellationException) {
                        return;
                    }
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    handleAgentResponse(AgentResponse.error("Processing failed: " + cause.getMessage()), generation);
                    return;
                }
                handleAgentResponse(response, generation);
            }));
        });
    }

    // ==== TurnPresenter：IPC 回合（委派 / CLI 直连）的领养呈现 ====
    // 契约见 TurnPresenter：回调已在非 EDT 线程（ipc-worker / agent-loop / commonPool），
    // 一律 invokeLater；会话键过滤（非当前实例会话不派发）已由 AgentLoop 完成。

    /** IPC 回合内投递的公共 EDT 入口：仅在回合窗口（已武装且未过 /new）内放行。 */
    private void runInIpcTurn(Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (conversationGeneration != ipcTurnGeneration) {
                log.debug("Dropping IPC turn delivery outside its turn window");
                return;
            }
            action.run();
        });
    }

    /** IPC 来源消息开跑：与本地回合对等的 "You:" 行 + loading + Stop 模式（消息文本已带来源前缀）。 */
    @Override
    public void onTurnStarted(String sessionKey, String message) {
        // 通知时快照代数（对齐 onTurnRejectedBusy/onInjected 的既有模式）：若 /new 已先
        // 落地（EDT 队列里排在本 runnable 之前），本回合属于被放弃的旧会话——不得在
        // EDT 执行时按新代数武装，否则幽灵 "You:" 行与取消回执会渗入刚清空的新会话
        final int generation = conversationGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != conversationGeneration) {
                return; // /new 先落地：本回合属于旧会话，整段放弃
            }
            // 武装本回合呈现代数：早于本 runnable 入队的进度/终结投递按旧代数被丢弃
            ipcTurnGeneration = generation;
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "\nYou: " + message, null, false);
            } catch (BadLocationException e) {
                log.error("Error appending IPC turn user message", e);
            }
            try {
                messageProcessor.appendLoadingIndicator(chatArea.getStyledDocument(),
                        getThemeColor("Label.disabledForeground", Color.GRAY));
            } catch (BadLocationException e) {
                log.error("Error adding loading indicator for IPC turn", e);
            }
            setButtonToStopMode();
        });
    }

    /** IPC 回合进度（思考/工具事件/中间回复）：接入与本地回合相同的渲染链。 */
    @Override
    public void onProgress(String sessionKey, ProgressUpdate update) {
        // handleProgress 自带 invokeLater + 代数过滤，此处外层窗口过滤保持 EDT 顺序
        runInIpcTurn(() -> handleProgress(update, conversationGeneration));
    }

    /** IPC 回合终结（完成/失败）：走本地回合的统一收尾（渲染 + 按 hasActiveRun 复位按钮）。 */
    @Override
    public void onTurnCompleted(String sessionKey, AgentResponse response) {
        runInIpcTurn(() -> handleAgentResponse(response, conversationGeneration));
    }

    /** IPC 回合终结（取消）：一行系统提示 + 复位。取消路径无人回调 handleAgentResponse。 */
    @Override
    public void onTurnCancelled(String sessionKey, String reason) {
        runInIpcTurn(() -> {
            removeLoadingIndicator();
            String text;
            if (IpcResponse.CANCEL_REASON_USER_STOP.equals(reason)) {
                text = "Task cancelled: stopped from this instance. "
                        + "Partial results (if any) have been returned to the caller.";
            } else if (IpcResponse.CANCEL_REASON_TIMEOUT.equals(reason)) {
                text = "Task cancelled: the caller's wait timed out and the turn was cancelled here.";
            } else {
                text = "Task cancelled" + (reason != null ? ": " + reason : ".");
            }
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(), text,
                        getThemeColor("Label.disabledForeground", Color.GRAY), false);
            } catch (BadLocationException e) {
                log.error("Error appending cancellation notice", e);
            }
            // 与 handleAgentResponse 同判据：仍有后续回合（如 re-publish）在跑则保持 Stop 模式
            if (agentLoop == null || !agentLoop.hasActiveRun(InstanceContext.currentSessionKey())) {
                setButtonToSendMode();
            }
        });
    }

    /** 委派撞上会话忙被快拒：一行系统提示（委派方收到既有 busy 错误）。 */
    @Override
    public void onTurnRejectedBusy(String sessionKey) {
        final int generation = conversationGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != conversationGeneration) {
                return; // /new 后迟到：不得渲染进新聊天区
            }
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Delegation rejected: a turn is already running; the caller was told to retry later.",
                        getThemeColor("Label.disabledForeground", Color.GRAY), false);
            } catch (BadLocationException e) {
                log.error("Error appending busy-reject notice", e);
            }
        });
    }

    /** IPC 消息在会话忙时注入正在跑的回合：与本地注入一致的绿色斜体回显（消息已带来源前缀）。 */
    @Override
    public void onInjected(String sessionKey, String message) {
        final int generation = conversationGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != conversationGeneration) {
                return;
            }
            try {
                messageProcessor.appendStyled(chatArea.getStyledDocument(),
                        "[Injected] You: " + message, new Color(0x00, 0x80, 0x00), Font.ITALIC);
            } catch (BadLocationException e) {
                log.error("Error appending injected IPC message", e);
            }
        });
    }

    /**
     * 面板懒创建场景领养在跑的 IPC 回合（design.md「面板创建时机」边界）。
     *
     * <p>面板是懒构造的：委派/CLI 回合可能在面板存在之前就已开跑，其 onTurnStarted
     * 回调发在旧 presenter（null）上而丢失——本实例会话上仍有活跃回合时，构造完成即
     * 领养之：武装呈现窗口（后续 onProgress/onTurnCompleted/onTurnCancelled 照常渲染）、
     * 追加提示行 + loading + 切 Stop 模式。已错过的中途进度不补放（Q12 决策：无事件
     * 缓冲）。仅构造路径调用；invokeLater 保证等 UI 字段就绪后才执行。
     */
    private void adoptRunningIpcTurnIfNeeded() {
        SwingUtilities.invokeLater(() -> {
            AgentLoop loop = agentLoop;
            if (loop == null || !loop.hasActiveRun(InstanceContext.currentSessionKey())) {
                return;
            }
            ipcTurnGeneration = conversationGeneration;
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "An IPC turn (delegation or CLI) is already running - it started "
                                + "before this panel was opened; live activity follows.",
                        getThemeColor("Label.disabledForeground", Color.GRAY), false);
                messageProcessor.appendLoadingIndicator(chatArea.getStyledDocument(),
                        getThemeColor("Label.disabledForeground", Color.GRAY));
            } catch (BadLocationException e) {
                log.error("Error adopting running IPC turn", e);
            }
            setButtonToStopMode();
        });
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

        // 剥掉 UI 路由用的 "provider:" 前缀再进工厂：选择器条目是 provider+":"+model，
        // 连前缀进 createService 会命中另一条 cache key（spec+":"+前缀串），与构造期/
        // IpcServer 预热用裸 model 的同一逻辑服务分裂成两个实例 → switchAiService 里
        // newService != currentAiService → 重建 AgentLoop 单例、孤儿化 IPC 在跑回合。
        // 出向 API 请求不受影响：openai_compat 在 provider 内部、anthropic 在
        // createServiceForSpec 内部各自再剥一次前缀
        String modelId = selectedModel;
        int colon = selectedModel.indexOf(':');
        if (colon >= 0) {
            modelId = selectedModel.substring(colon + 1);
        }

        // Use AiServiceFactory to create the service
        // This will automatically wrap with LangSmith tracing if enabled
        AiService service = AiServiceFactory.createService(modelId);

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
            String modelName = parts[1];

            switch (provider) {
                case "openai", "deepseek", "zhipu", "moonshot", "minimax", "langcat", "ollama" -> {
                    openAiService.setModel(modelId);  // Pass full ID with prefix
                    log.info("Set {} provider model: {}", provider, modelName);
                }
                default -> {
                    claudeService.setModel(modelName);  // Anthropic API needs the bare model id (rejects a "provider:" prefix)
                    log.info("Set Anthropic provider model: {}", modelName);
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
                    // 监听器注册在 AgentLoop 实例上而非工厂——实例重建后必须重注册，
                    // 否则 re-publish 孤儿回合的最终回复静默丢失（GUI 无渲染）
                    registerRepublishListener();
                    agentLoop.setTurnPresenter(this);
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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, null, true);
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }

    /**
     * Starts a new conversation by clearing the chat area and AgentLoop session.
     */
    private void startNewConversation() {
        log.info("Starting new conversation");

        // 渲染代数 +1：旧会话的迟到渲染（回合结论、工具批进度、孤儿 UI 武装）从此
        // 全部丢弃
        conversationGeneration++;

        // 重置核心（与 cmdNew 共用）：中止在跑回合与子代理、代数 +1、归档/清空/落盘
        if (agentLoop != null) {
            agentLoop.resetConversation(InstanceContext.currentSessionKey());
        }

        // Clear the chat area
        chatArea.setText("");

        // Display welcome message
        displayWelcomeMessage();

        // 取消后无人回调 handleAgentResponse 复位 UI（被取消回合的 SwingWorker 静默
        // 结束、republishListener 跳过 CancellationException），须自行复位，否则
        // Stop 按钮常驻、Send 按钮停留在注入模式。
        removeLoadingIndicator();
        setButtonToSendMode();

        // A new chat is an explicit "back to the start" action: always re-pin to the bottom so
        // the welcome message is in view regardless of where the previous (now-cleared) log was
        // scrolled.
        scrollToBottom();
    }

    /**
     * Sends the message from the input field to the chat using AgentLoop.
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        // /new starts a fresh conversation with a clean chat area — handle it
        // specially (covers Enter key and the default Send button).
        if ("/new".equals(message)) {
            handleNewCommand();
            return;
        }

        // If there's an active agent run, inject the message instead
        if (agentLoop != null && agentLoop.hasActiveRun(InstanceContext.currentSessionKey())) {
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

        // 关闭 IPC 回合呈现窗口：本地新回合一开即为硬边界（对齐 /new 的代数翻转）。
        // 垂死 IPC 回合迟到的终结通知（如超时分支 cancelActiveTask 阻塞至多 5s 后才发）
        // 不得渗进新回合流——否则会误删其 loading 指示、插入过期的 "Task cancelled" 行
        ipcTurnGeneration = -1;

        // Add the user message to the chat
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "\nYou: " + message, null, false);
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

        // 捕获订阅时的会话代数：本回合一切渲染（结论/进度）经其过滤，重置后到达即丢弃
        final int generation = conversationGeneration;

        // Use AgentSwingWorker to process the message through AgentLoop
        activeWorker = new AgentSwingWorker(
                agentLoop,
                message,
                InstanceContext.currentSessionKey(),
                r -> handleAgentResponse(r, generation),
                u -> handleProgress(u, generation)
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

        // /new starts a fresh conversation with a clean chat area. The Stop-mode Send
        // button is rewired to call injectMessage() directly (bypassing sendMessage),
        // so /new must be intercepted here too, not only in sendMessage.
        if ("/new".equals(message)) {
            handleNewCommand();
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
        if (!agentLoop.hasActiveRun(InstanceContext.currentSessionKey())) {
            log.info("Active run finished during injection, falling back to normal send");
            startNormalSend(message);
            return;
        }

        // Active run confirmed — processMessage will hit Phase 2 (non-blocking)
        CompletableFuture<AgentResponse> future = agentLoop.processMessage(message, InstanceContext.currentSessionKey());

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
                        // Command dispatch result (e.g. /help, /status) — show normally
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                            response.getContent(), null, false);
                    }
                }
            } catch (Exception e) {
                log.error("Error handling injection response", e);
            }
        } else {
            // Extremely narrow race: run finished right after our hasActiveRun check.
            // The future is a full agent run — connect it to the normal UI handlers.
            // handle（而非 thenAccept，对齐 republishListener/handleNewCommand）：真实
            // 失败也要渲染并复位，否则 loading 与 Stop 模式悬挂；取消则静默（重置/Stop
            // 路径自行复位）
            log.info("Race condition: future not done, connecting to handleAgentResponse");
            final int generation = conversationGeneration;
            future.handle((response, ex) -> {
                final AgentResponse r;
                if (ex != null) {
                    if (ex instanceof java.util.concurrent.CancellationException
                            || ex.getCause() instanceof java.util.concurrent.CancellationException) {
                        return null;
                    }
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    r = AgentResponse.error("Processing failed: " + cause.getMessage());
                } else {
                    r = response;
                }
                SwingUtilities.invokeLater(() -> handleAgentResponse(r, generation));
                return null;
            });
        }
    }

    /**
     * Handle the {@code /new} command: clear the chat area, then show the
     * "You: /new" / bot response exchange. This is the single owner of the /new UI
     * behavior — both the idle path (sendMessage) and the mid-run path
     * (injectMessage, including the Stop-mode Send button that bypasses sendMessage)
     * route here, so /new always clears the chat consistently.
     *
     * <p>cmdNew clears the session (and signals the active run to stop if one is
     * running); the cancelled run's SwingWorker ends silently via
     * {@code AgentSwingWorker.done}, so the response shows exactly once.
     */
    private void handleNewCommand() {
        // 渲染代数 +1：/new 即重置，旧会话的迟到渲染从此过期。必须在订阅本命令
        // 自身的回执 future 之前完成——回执以新代数订阅，正常渲染。
        conversationGeneration++;

        // Clear the chat area for a fresh session.
        chatArea.setText("");

        // Echo the user's command. No leading "\n": the document was just cleared,
        // so there is no prior block to separate from (a leading \n would render as
        // a stray blank line above "You:").
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "You: /new", null, false);
        } catch (BadLocationException e) {
            log.error("Error appending /new user message", e);
        }

        messageField.setText("");

        if (agentLoop == null) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Agent Loop is not available. Please check your configuration.",
                        Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
            return;
        }

        // Dispatch /new: clears the session; mid-run, cmdNew signals the active run
        // to stop. Mid-run returns a completedFuture (Phase 2); idle completes on the
        // agent-loop thread (Phase 3). Show the response via the normal handler, which
        // also resets the UI the prior run created (loading indicator, Stop button,
        // worker ref). handle (not thenAccept) so a cmdNew failure still surfaces.
        CompletableFuture<AgentResponse> future = agentLoop.processMessage("/new", InstanceContext.currentSessionKey());
        final int generation = conversationGeneration;
        future.handle((response, ex) -> {
            final AgentResponse r;
            if (ex != null) {
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                        ? ex.getCause() : ex;
                r = AgentResponse.error("Processing failed: " + cause.getMessage());
            } else {
                r = response;
            }
            SwingUtilities.invokeLater(() -> handleAgentResponse(r, generation));
            return null;
        });
    }

    /**
     * 深度提炼记忆整合成功后,清空消息区并显示欢迎信息(视觉效果对齐「开启新会话」按钮)。
     * 由 {@link org.gitee.jmeter.ai.gui.CloseConsolidationDialog} 在提炼完成的 EDT 回调里调用;
     * 须在 EDT。面板未创建时 no-op。配套的数据层清空见
     * {@link org.gitee.jmeter.ai.agent.memory.CloseConsolidationCoordinator#clearCurrentSession()}。
     */
    public static void resetAfterConsolidation() {
        AiChatPanel panel = INSTANCE;
        if (panel == null) {
            return;
        }
        // 对齐 /new、"+"：渲染代数 +1 让旧会话迟到渲染过期；取消路径
        // 无人回调复位，UI 须自行复位（退出取消后继续使用时不得留常驻 Stop 模式）
        panel.conversationGeneration++;
        panel.chatArea.setText("");
        panel.displayWelcomeMessage();
        panel.removeLoadingIndicator();
        panel.setButtonToSendMode();
    }

    /**
     * Handle AgentLoop response callback.
     *
     * @param generation 订阅时捕获的会话渲染代数：与当前不符即旧会话的迟到投递，
     *                   整体丢弃（不渲染、不复位）——防其污染重置后的新聊天区
     */
    private void handleAgentResponse(AgentResponse response, int generation) {
        if (generation != conversationGeneration) {
            log.debug("Dropping stale response render from a previous conversation");
            return;
        }

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
                boolean showToolCalls = org.gitee.jmeter.ai.utils.AiConfig.isChatShowToolCalls();

                if (showToolCalls && response.getToolEvents() != null && !response.getToolEvents().isEmpty()) {
                    displayToolCallInfo(response.getToolEvents());
                }
            }
            toolCallsDisplayedProgressively = false;

            processAiResponse(response.getContent());
        }

        // Re-enable input
        messageField.setEnabled(true);
        // 仍有回合在跑（如紧随其后的 re-publish 孤儿回合）时保持 Stop 模式，由该回合
        // 完成时复位；否则本回合的复位会把孤儿接管设置的 Stop 模式错误翻回 Send 模式
        if (agentLoop == null || !agentLoop.hasActiveRun(InstanceContext.currentSessionKey())) {
            setButtonToSendMode();
        }
        messageField.requestFocusInWindow();
    }

    /**
     * Handle typed progress updates from the agent loop.
     * Renders different types (THINKING, TOOL_CALL, ERROR, PROGRESS) with appropriate styling.
     *
     * @param generation 订阅时捕获的会话渲染代数：与当前不符即旧会话的迟到进度
     *                   （典型：重置时仍在跑的工具批完成后发布的 TOOL_CALL），丢弃
     */
    private void handleProgress(ProgressUpdate update, int generation) {
        SwingUtilities.invokeLater(() -> {
            if (generation != conversationGeneration) {
                log.debug("Dropping stale progress render from a previous conversation");
                return;
            }
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

    /** Append an AI (markdown) response block with the inline 🤖 marker (foreground inherits the themed body color). */
    private void appendBotResponse(String markdown) throws BadLocationException {
        messageProcessor.appendHtml(chatArea.getStyledDocument(), botHeaderHtml(markdown));
    }

    /**
     * Build the AI response HTML with the 🤖 marker injected INSIDE the first block element
     * (e.g. {@code <p><span>🤖 </span>...}) so the bot emoji sits inline with the first line
     * instead of on its own line above the block content.
     */
    private static String botHeaderHtml(String markdown) {
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
        return "<div>" + injected + "</div>";
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
        int maxToolResultLength = org.gitee.jmeter.ai.utils.AiConfig.getChatToolResultMaxLength();

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
     *
     * <p>signalCancel（非阻塞：置 abort / interrupt / cancel future / 摘注入路由槽）
     * 保留在 EDT 同步执行——维持「UI 复位 ⇒ 路由槽必已摘除」不变式：STOP 后立即
     * 输入走正常发送，而非被注入垂死回合遭静默作废。垂死回合的收尾等待（≤5s）挪到
     * 后台线程（原 {@code cancelActiveTask} 在 EDT 上同步 await 最坏 5 秒无响应）。
     */
    private void stopActiveTask() {
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
            activeWorker = null;
        }
        if (agentLoop != null) {
            final String sessionKey = InstanceContext.currentSessionKey();
            agentLoop.signalCancel(sessionKey);
            CompletableFuture.runAsync(
                    () -> agentLoop.waitForCancellation(sessionKey, 5, TimeUnit.SECONDS));
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
     * Whether the chat viewport is pinned to the bottom (within ~one line of the maximum).
     * Used as the smart-scroll gate: auto-scroll follows new content only while the user is at
     * the tail; scrolling up (by any means — drag, wheel, button, keyboard) leaves the view in
     * place, and scrolling back to the bottom re-enables following. The tolerance is the
     * scrollbar's unit increment (about one text line) so it adapts to font size / DPI instead
     * of a brittle fixed pixel count.
     */
    private boolean isChatAtBottom() {
        JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
        int tolerance = vertical.getUnitIncrement();
        if (tolerance <= 0) {
            tolerance = 16;
        }
        return vertical.getValue() + vertical.getVisibleAmount() >= vertical.getMaximum() - tolerance;
    }

    /**
     * Scroll the chat viewport to the very bottom. Invoked on the EDT via {@code invokeLater} so
     * it runs after the document layout pass has updated the scrollbar's maximum for the just
     * appended content. {@code setValue(max)} is clamped by the model to {@code max - extent}
     * (the true bottom) since the extent (viewport height) is stable.
     */
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
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
        // Scrolling is handled inside appendHtml (smart auto-scroll: only when pinned to bottom).
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
     * Apply the current JMeter theme to the chat area: the component background (the chat's
     * base color) and the HTML StyleSheet rules (themed foreground + code/table backgrounds).
     * Called once during construction and again whenever the Look and Feel changes
     * (see {@link #propertyChange}).
     *
     * <p>The body rule intentionally omits a CSS {@code background}; the JTextPane component
     * background fills the viewport and repaint applies it instantly on theme switch (no HTML
     * view re-parse needed, which Swing would otherwise cache). {@code JViewport} inherits the
     * child component background, so the scroll pane area stays consistent without extra setup.
     */
    private void applyChatTheme() {
        Color bg = getThemeColor("TextPane.background", getThemeColor("Panel.background", Color.WHITE));
        chatArea.setOpaque(true);
        chatArea.setBackground(bg);

        Font font = chatArea.getFont();
        int fontPt = font.getSize();
        Color textFg = getThemeColor("TextPane.foreground", Color.BLACK);
        Color codeBg = UiThemeUtil.getCodeBlockBackground();
        StyleSheet ss = ((HTMLEditorKit) chatArea.getEditorKit()).getStyleSheet();
        ss.addRule("body { font-family:" + font.getFamily() + "; font-size:" + fontPt
                + "pt; color:" + UiThemeUtil.toHex(textFg) + "; }");
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
        // Inline code/kbd/samp: themed background + padding so they read as distinct "code chips"
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

        chatArea.repaint();
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
            // Re-apply themed background + StyleSheet so the chat follows the new Look and Feel
            applyChatTheme();
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