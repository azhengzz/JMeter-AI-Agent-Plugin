package org.gitee.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gitee.jmeter.ai.intellisense.InputBoxIntellisense;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.ToolEvent;
import org.gitee.jmeter.ai.agent.swing.AgentSwingWorker;
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
        // Use the system default font with larger size
        Font defaultFont = UIManager.getFont("TextField.font");
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), defaultFont.getSize() + 2);
        largerFont = ensureCjkSupport(largerFont);
        chatArea.setFont(largerFont);

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
        FlowLayout flowLayout = new FlowLayout(FlowLayout.LEFT);
        JPanel modelPanel = new JPanel(flowLayout);
        JLabel modelLabel = new JLabel("Model: ");
        modelPanel.add(modelLabel);
        modelPanel.add(modelSelector);
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

        // Add focus listener to store selected text when clicking in the chat box
        // messageField.addFocusListener(new FocusAdapter() {
        // @Override
        // public void focusGained(FocusEvent e) {
        // log.info("Message field gained focus, storing selected text");
        // CodeCommandHandler.storeSelectedText();
        // }
        // });

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);

        // Initialize send button
        sendButton = new JButton("Send");
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.setOpaque(true);
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

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
        headerPanel.add(titleLabel, BorderLayout.WEST);

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

        // String welcomeMessage = "# Welcome to Gitee Ai - JMeter Agent\n\n" +
        //         "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
        //         "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
        //         "**Slash commands:**\n" +
        //         "- `/new` — Start a new conversation\n" +
        //         "- `/status` — Show agent status\n" +
        //         "- `/help` — Show available commands\n\n" +
        //         "**Agent commands:**\n" +
        //         "- Use `@this` to get information about the currently selected element\n" +
        //         "- Use `@optimize` to get optimization suggestions for your test plan\n" +
        //         "- Use `@lint` to rename elements in your test plan with meaningful names\n" +
        //         "- Use `@wrap` to group HTTP request samplers under Transaction Controllers\n" +
        //         "- Use `@usage` to view usage statistics for your AI interactions\n\n" +
        //         "How can I assist you today?";

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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "AI is thinking...", getThemeColor("Label.disabledForeground", Color.GRAY), false);
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator", e);
        }

        // Check for special commands that don't go through AgentLoop
        if (message.trim().startsWith("@code")) {
            // @code command is disabled - use right-click context menu instead
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "The @code command is disabled. Please use the right-click context menu in the JSR223 editor instead.",
                        Color.RED, false);
                removeLoadingIndicator();
                setButtonToSendMode();
                messageField.requestFocusInWindow();
            } catch (BadLocationException e) {
                log.error("Error displaying message", e);
            }
            return;
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
                    default -> renderProgress(update.getMessage());
                }
            } catch (BadLocationException e) {
                log.error("Error displaying progress", e);
            }
        });
    }

    private void renderThinking(String text) throws BadLocationException {
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, new Color(120, 120, 120));
        StyleConstants.setItalic(style, true);
        doc.insertString(doc.getLength(), text.stripTrailing() + "\n", style);
    }

    private void renderToolHint(String hint) throws BadLocationException {
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, new Color(100, 100, 150));
        StyleConstants.setBold(style, true);
        doc.insertString(doc.getLength(), "\n" + hint.stripTrailing() + "\n", style);
    }

    private void renderProgress(String text) throws BadLocationException {
        messageProcessor.appendMessage(chatArea.getStyledDocument(), text, Color.GRAY, false);
    }

    private void renderError(String text) throws BadLocationException {
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, Color.RED);
        doc.insertString(doc.getLength(), text.stripTrailing() + "\n", style);
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
        StyledDocument doc = chatArea.getStyledDocument();
        int maxToolResultLength = Integer.parseInt(
            org.gitee.jmeter.ai.utils.AiConfig.getProperty("ai.chat.tool.result.max.length", "500"));

        // Tool call header
        SimpleAttributeSet headerStyle = new SimpleAttributeSet();
        StyleConstants.setBold(headerStyle, true);
        StyleConstants.setForeground(headerStyle, new Color(100, 100, 150));
        doc.insertString(doc.getLength(), "\n🔧 ", headerStyle);

        SimpleAttributeSet toolStyle = new SimpleAttributeSet();

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

        StyleConstants.setForeground(toolStyle, statusColor);
        String toolLine = String.format("  %s %s [%dms]", statusIcon, event.getToolName(), event.getDurationMs());
        doc.insertString(doc.getLength(), toolLine.stripTrailing() + "\n", toolStyle);

        if (event.getArguments() != null && !event.getArguments().isEmpty()) {
            SimpleAttributeSet argStyle = new SimpleAttributeSet();
            StyleConstants.setForeground(argStyle, new Color(70, 130, 180));
            StyleConstants.setItalic(argStyle, true);

            String argsStr = formatArguments(event.getArguments());
            String displayArgs = argsStr.stripTrailing();
            if (argsStr.length() > maxToolResultLength) {
                displayArgs = argsStr.substring(0, maxToolResultLength) + "...(truncated, total " + argsStr.length() + " chars)";
            }
            doc.insertString(doc.getLength(), "    Args: " + displayArgs + "\n", argStyle);
        }

        String detail = event.getDetail();
        if (detail != null && !detail.isEmpty()) {
            SimpleAttributeSet detailStyle = new SimpleAttributeSet();
            StyleConstants.setForeground(detailStyle, new Color(100, 100, 100));
            StyleConstants.setItalic(detailStyle, true);

            String displayDetail = detail.stripTrailing();
            if (detail.length() > maxToolResultLength) {
                displayDetail = detail.substring(0, maxToolResultLength) + "...(truncated, total " + detail.length() + " chars)";
            }
            doc.insertString(doc.getLength(), "    Result: " + displayDetail + "\n\n", detailStyle);
        }
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
        sendButton.setText("■ Stop");
        sendButton.setForeground(new Color(180, 40, 40));
        sendButton.setBackground(new Color(255, 210, 210));
        sendButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 80, 80), 1, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        sendButton.setEnabled(true);
        for (ActionListener al : sendButton.getActionListeners()) {
            sendButton.removeActionListener(al);
        }
        sendButton.addActionListener(e -> stopActiveTask());
    }

    private void setButtonToSendMode() {
        sendButton.setText("Send");
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
        log.info("Attempting to remove loading indicator");
        try {
            StyledDocument doc = chatArea.getStyledDocument();

            // Find the loading indicator text
            String text = doc.getText(0, doc.getLength());
            int index = text.lastIndexOf("AI is thinking...");

            log.info("Loading indicator found at index: {}", index);

            if (index != -1) {
                // Remove the loading indicator
                doc.remove(index, "AI is thinking...".length());
                log.info("Loading indicator removed");
            } else {
                log.warn("Loading indicator not found in chat text");
            }
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
            // AI response header
            StyledDocument doc = chatArea.getStyledDocument();
            SimpleAttributeSet headerStyle = new SimpleAttributeSet();
            StyleConstants.setBold(headerStyle, true);
            StyleConstants.setForeground(headerStyle, new Color(0, 102, 204));
            doc.insertString(doc.getLength(), "🤖 ", headerStyle);

            messageProcessor.appendMessage(doc, response, getThemeColor("TextPane.foreground", Color.BLACK), true);
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

    private static Font ensureCjkSupport(Font font) {
        if (font.canDisplay('中')) {
            return font;
        }
        String[] cjkFonts = {"Microsoft YaHei", "SimHei", "SimSun", "PingFang SC", "Dialog"};
        for (String name : cjkFonts) {
            Font candidate = new Font(name, font.getStyle(), font.getSize());
            if (candidate.canDisplay('中')) {
                return candidate;
            }
        }
        return font;
    }
}