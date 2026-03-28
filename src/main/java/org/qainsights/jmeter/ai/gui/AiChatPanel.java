package org.qainsights.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.intellisense.InputBoxIntellisense;
import org.qainsights.jmeter.ai.agent.AgentLoop;
import org.qainsights.jmeter.ai.agent.AgentLoopFactory;
import org.qainsights.jmeter.ai.agent.model.AgentResponse;
import org.qainsights.jmeter.ai.agent.swing.AgentSwingWorker;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;

import com.openai.models.models.Model;
import org.apache.jorphan.gui.JMeterUIDefaults;

import org.qainsights.jmeter.ai.utils.Models;
import org.qainsights.jmeter.ai.utils.VersionUtils;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.provider.ProviderRegistry;

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
    private TreeNavigationButtons treeNavigationButtons;
    private JPanel navigationPanel; // Added field for navigation panel

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
    private final ElementSuggestionManager elementSuggestionManager;

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

        // Initialize tree navigation buttons with action listeners
        treeNavigationButtons = new TreeNavigationButtons();
        treeNavigationButtons.setUpButtonActionListener();
        treeNavigationButtons.setDownButtonActionListener();

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

        // Add the chat panel to the center of the main panel
        add(chatPanel, BorderLayout.CENTER);

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

        // Create the navigation panel for tree navigation and element buttons
        navigationPanel = new JPanel();
        navigationPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        navigationPanel.setBorder(BorderFactory.createTitledBorder("Element Suggestions"));

        // Add navigation buttons to the panel
        navigationPanel.add(treeNavigationButtons.getUpButton());
        navigationPanel.add(treeNavigationButtons.getDownButton());

        // Add a separator
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 30));
        navigationPanel.add(separator);

        // Set minimum height to ensure buttons are visible
        navigationPanel.setMinimumSize(new Dimension(100, 70));
        navigationPanel.setPreferredSize(new Dimension(500, 70));

        // Initialize element suggestion manager with the navigation panel
        elementSuggestionManager = new ElementSuggestionManager(navigationPanel);

        // Make sure the navigation panel is visible
        navigationPanel.setVisible(true);

        bottomPanel.add(navigationPanel, BorderLayout.CENTER);

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

        // Add key listener for Enter to send message
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume(); // Prevent newline from being added
                    sendMessage();
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
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        bottomPanel.add(inputPanel, BorderLayout.SOUTH);

        // Add the bottom panel to the main panel
        add(bottomPanel, BorderLayout.SOUTH);

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
        JLabel titleLabel = new JLabel("Feather Wand - JMeter Agent v" + VersionUtils.getVersion());
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
                // Get models from both services
                List<String> allModels = new ArrayList<>();

                // Get Anthropic models (only if API key is configured)
                String anthropicApiKey = org.qainsights.jmeter.ai.utils.AiConfig.getProperty("anthropic.api.key", "");
                if (anthropicApiKey != null && !anthropicApiKey.isEmpty() && !anthropicApiKey.equals("YOUR_API_KEY")) {
                    try {
                        ModelListPage anthropicModels = Models.getAnthropicModels(claudeService.getClient());
                        if (anthropicModels != null && anthropicModels.data() != null) {
                            for (ModelInfo model : anthropicModels.data()) {
                                allModels.add(model.id());
                                log.debug("Added Anthropic model: {}", model.id());
                            }
                            log.info("Added {} Anthropic models", anthropicModels.data().size());
                        }
                    } catch (Exception e) {
                        log.error("Error loading Anthropic models: {}", e.getMessage(), e);
                    }
                } else {
                    log.debug("Skipping Anthropic models - API key not configured");
                }

                // Add OpenAI models (only if API key is configured)
                String openaiApiKey = org.qainsights.jmeter.ai.utils.AiConfig.getProperty("openai.api.key", "");
                if (openaiApiKey != null && !openaiApiKey.isEmpty() && !openaiApiKey.equals("YOUR_OPENAI_API_KEY")) {
                    try {
                        com.openai.models.models.ModelListPage openAiModels = Models.getOpenAiModels(openAiService.getClient());
                        if (openAiModels != null && openAiModels.data() != null) {
                            // Convert OpenAI models to string IDs
                            for (Model openAiModel : openAiModels.data()) {
                                // Only include GPT models and filter out specific model types
                                if (openAiModel.id().startsWith("gpt") &&
                                        !openAiModel.id().contains("audio") &&
                                        !openAiModel.id().contains("tts") &&
                                        !openAiModel.id().contains("whisper") &&
                                        !openAiModel.id().contains("davinci") &&
                                        !openAiModel.id().contains("search") &&
                                        !openAiModel.id().contains("transcribe") &&
                                        !openAiModel.id().contains("realtime") &&
                                        !openAiModel.id().contains("instruct")) {

                                    String modelId = "openai:" + openAiModel.id();
                                    allModels.add(modelId);
                                    log.debug("Added OpenAI model to selector: {}", openAiModel.id());
                                }
                            }
                            log.info("Added OpenAI models to selector");
                        }
                    } catch (Exception e) {
                        log.error("Error adding OpenAI models: {}", e.getMessage(), e);
                    }
                } else {
                    log.debug("Skipping OpenAI models - API key not configured");
                }

                // Add Ollama models (check if enabled and service is reachable)
                boolean ollamaEnabled = Boolean.parseBoolean(org.qainsights.jmeter.ai.utils.AiConfig.getProperty("ollama.enabled", "true"));
                if (ollamaEnabled && ollamaService.isReachable()) {
                    try {
                        List<io.github.ollama4j.models.response.Model> ollamaModels = ollamaService.listModels();
                        if (ollamaModels != null) {
                            for (io.github.ollama4j.models.response.Model ollamaModel : ollamaModels) {
                                String modelId = "ollama:" + ollamaModel.getName();
                                allModels.add(modelId);
                                log.debug("Added Ollama model to selector: {}", ollamaModel.getName());
                            }
                            log.info("Added {} Ollama models to selector", ollamaModels.size());
                        }
                    } catch (Exception e) {
                        log.error("Error adding Ollama models: {}", e.getMessage(), e);
                    }
                } else {
                    log.debug("Skipping Ollama models - service not reachable");
                }

                // Add Chinese LLM provider models
                // Only add models for providers that have API keys configured
                try {
                    String[] chineseProviders = {"deepseek", "zhipu", "moonshot", "minimax"};

                    for (String provider : chineseProviders) {
                        // Check if API key is configured for this provider
                        String apiKey = org.qainsights.jmeter.ai.utils.AiConfig.getProperty(provider + ".api.key", "");
                        if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY_HERE")) {
                            List<String> models = ProviderRegistry.getModelsForProvider(provider);
                            for (String model : models) {
                                allModels.add(provider + ":" + model);
                                log.debug("Added {} model: {}", provider, model);
                            }
                            log.info("Added {} {} models", models.size(), provider);
                        } else {
                            log.debug("Skipping {} models - API key not configured", provider);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error adding Chinese LLM models: {}", e.getMessage(), e);
                }

                return allModels;
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    modelSelector.removeAllItems();

                    // Get the default model ID
                    String defaultModelId = claudeService.getCurrentModel();
                    log.info("Default model ID: {}", defaultModelId);

                    String defaultModel = null;

                    for (String model : models) {
                        modelSelector.addItem(model);
                        if (model.equals(defaultModelId)) {
                            defaultModel = model;
                        }
                    }

                    // Select the default model if found
                    if (defaultModel != null) {
                        modelSelector.setSelectedItem(defaultModel);
                        log.info("Selected default model: {}", defaultModel);
                        // Set model on the appropriate service
                        setModelForProvider(defaultModel);
                        // Switch to the appropriate service
                        switchAiService();
                    } else if (modelSelector.getItemCount() > 0) {
                        // If default model not found, select the first one
                        modelSelector.setSelectedIndex(0);
                        String selectedModel = (String) modelSelector.getSelectedItem();
                        setModelForProvider(selectedModel);
                        log.info("Default model not found, selected first available: {}", selectedModel);
                        // Switch to the appropriate service
                        switchAiService();
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
                // Use default service during construction
                aiService = claudeService;
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
     */
    private AiService getAiServiceForCurrentModel() {
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            return claudeService;
        }

        // Extract provider from model ID (format: "provider:model" or just "model")
        String[] parts = selectedModel.split(":", 2);
        String provider = parts.length == 2 ? parts[0] : "";

        // Select service based on provider (without setting currentAiService)
        return switch (provider) {
            case "openai", "deepseek", "zhipu", "moonshot", "minimax" -> openAiService;
            case "ollama" -> ollamaService;
            default -> claudeService; // Default to Claude for Anthropic models or no prefix
        };
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
    // TODO 调整欢迎语
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");

        String welcomeMessage = "# Welcome to Feather Wand - JMeter Agent\n\n" +
                "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
                "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
                "**Special commands:**\n" +
                "- Use `@this` to get information about the currently selected element\n" +
                "- Use `@optimize` to get optimization suggestions for your test plan\n" +
                "- Use `@lint` to rename elements in your test plan with meaningful names\n" +
                "- Use `@wrap` to group HTTP request samplers under Transaction Controllers\n" +
                "- Use `@usage` to view usage statistics for your AI interactions\n\n" +
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

        // Clear the chat area
        chatArea.setText("");

        // Clear the AgentLoop session
        if (agentLoop != null) {
            agentLoop.getSessionManager().clearSession(CHAT_SESSION_KEY);
        }

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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "You: " + message, getThemeColor("TextPane.foreground", Color.BLACK), false);
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
                messageField.setEnabled(true);
                sendButton.setEnabled(true);
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
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    return;
                } catch (BadLocationException e) {
                    log.error("Error displaying error message", e);
                }
            }
        }

        // Disable input while processing
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Use AgentSwingWorker to process the message through AgentLoop
        new AgentSwingWorker(
                agentLoop,
                message,
                CHAT_SESSION_KEY,
                this::handleAgentResponse,
                this::handleProgress
        ).execute();
    }

    /**
     * Handle AgentLoop response callback.
     */
    private void handleAgentResponse(AgentResponse response) {
        // Remove the loading indicator
        removeLoadingIndicator();

        if (!response.isSuccess()) {
            // Display error message
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Error: " + response.getErrorMessage(),
                        Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
        } else {
            // Process the AI response
            // Note: AgentLoop manages conversation history via SessionManager
            processAiResponse(response.getContent());
        }

        // Re-enable input
        messageField.setEnabled(true);
        sendButton.setEnabled(true);
        messageField.requestFocusInWindow();
    }

    /**
     * Handle AgentLoop progress callback.
     */
    private void handleProgress(String progress) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Remove the loading indicator if it exists
                removeLoadingIndicator();

                // Add progress message
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        progress, Color.GRAY, false);
            } catch (BadLocationException e) {
                log.error("Error displaying progress", e);
            }
        });
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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), response, getThemeColor("TextPane.foreground", Color.BLACK), true);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }

        // Create element buttons for context-aware suggestions after the AI response
        SwingUtilities.invokeLater(() -> {
            log.info("Creating element buttons for context-aware suggestions");

            // Make sure the navigation panel is visible
            navigationPanel.setVisible(true);

            // Process the response to create element buttons
            elementSuggestionManager.createElementButtons(response);

            // Ensure the navigation panel is visible and properly laid out
            navigationPanel.revalidate();
            navigationPanel.repaint();

            // Log the number of components in the navigation panel
            log.info("Navigation panel now has {} components", navigationPanel.getComponentCount());

            // Scroll to the bottom of the chat area to show the latest message
            SwingUtilities.invokeLater(() -> {
                JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatArea);
                if (scrollPane != null) {
                    JScrollBar vertical = scrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
            });
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
}