package org.qainsights.jmeter.ai.agent.context;

import org.qainsights.jmeter.ai.agent.memory.MemoryStore;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolCall;
import org.qainsights.jmeter.ai.agent.skills.SkillsLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds context for Agent Loop communication.
 * Constructs system prompts and message lists for LLM calls.
 */
public class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context]";

    private final MemoryStore memoryStore;
    private final String baseSystemPrompt;
    private final SkillsLoader skillsLoader;

    public ContextBuilder(MemoryStore memoryStore, String baseSystemPrompt, Path workspace) {
        this.memoryStore = memoryStore;
        this.baseSystemPrompt = baseSystemPrompt != null ? baseSystemPrompt : getDefaultSystemPrompt();
        this.skillsLoader = new SkillsLoader(workspace);

        // Log available skills
        log.info("Loaded {} skills (always: {})",
                skillsLoader.listSkills(true).size(),
                skillsLoader.getAlwaysSkills());
    }

    /**
     * Build system prompt with memory and skills
     */
    public String buildSystemPrompt(List<String> activeSkills) {
        StringBuilder prompt = new StringBuilder();

        // Add base identity
        prompt.append(baseSystemPrompt);

        // Add memory context if available
        String memoryContext = memoryStore.getMemoryContext();
        if (!memoryContext.isEmpty()) {
            prompt.append("\n\n").append(memoryContext);
        }

        // Add always skills first
        List<String> alwaysSkills = skillsLoader.getAlwaysSkills();
        if (!alwaysSkills.isEmpty()) {
            prompt.append("\n\n").append(skillsLoader.loadSkillsForContext(alwaysSkills));
        }

        // Add explicitly requested skills
        if (activeSkills != null && !activeSkills.isEmpty()) {
            // Filter out always skills (already loaded)
            List<String> requestedSkills = new ArrayList<>();
            for (String skill : activeSkills) {
                if (!alwaysSkills.contains(skill)) {
                    requestedSkills.add(skill);
                }
            }

            if (!requestedSkills.isEmpty()) {
                String skillsContent = skillsLoader.loadSkillsForContext(requestedSkills);
                if (!skillsContent.isEmpty()) {
                    prompt.append("\n\n").append(skillsContent);
                }
            }
        }

        return prompt.toString();
    }

    /**
     * Get the skills loader
     */
    public SkillsLoader getSkillsLoader() {
        return skillsLoader;
    }

    /**
     * Build complete message list for LLM call
     */
    public List<Message> buildMessages(
            List<Message> history,
            String currentMessage,
            List<Map<String, Object>> tools) {
        return buildMessages(history, currentMessage, tools, null, null);
    }

    /**
     * Build complete message list with channel context
     */
    public List<Message> buildMessages(
            List<Message> history,
            String currentMessage,
            List<Map<String, Object>> tools,
            String channel,
            String chatId) {

        List<Message> messages = new ArrayList<>();

        // Add system prompt
        messages.add(Message.system(buildSystemPromptWithTools(tools)));

        // Add history (filter out system messages from history)
        for (Message msg : history) {
            if (msg.getRole() != Message.Role.SYSTEM) {
                messages.add(msg);
            }
        }

        // Add current user message with runtime context
        String userContent = buildUserContent(currentMessage, channel, chatId);
        messages.add(Message.user(userContent));

        return messages;
    }

    /**
     * Add a tool result to the message list
     */
    public List<Message> addToolResult(
            List<Message> messages,
            String toolCallId,
            String toolName,
            String result) {

        String truncatedResult = truncateIfNeeded(result);
        messages.add(Message.tool(toolCallId, toolName, truncatedResult));
        return messages;
    }

    /**
     * Add an assistant message to the message list
     */
    public List<Message> addAssistantMessage(
            List<Message> messages,
            String content,
            List<ToolCall> toolCalls) {

        messages.add(Message.assistant(content, toolCalls));
        return messages;
    }

    /**
     * Build system prompt with tool descriptions
     */
    private String buildSystemPromptWithTools(List<Map<String, Object>> tools) {
        StringBuilder prompt = new StringBuilder(buildSystemPrompt(null));

        if (tools != null && !tools.isEmpty()) {
            prompt.append("\n\n## Available Tools\n\n");
            prompt.append("You have access to the following tools. Call them when needed:\n\n");

            for (Map<String, Object> tool : tools) {
                String name = tool.containsKey("name") ? tool.get("name").toString() : "";
                String description = tool.containsKey("description") ? tool.get("description").toString() : "";
                prompt.append("- **").append(name).append("**: ")
                        .append(description).append("\n");
            }

            prompt.append("\nWhen you need to use a tool, respond with a tool call in the appropriate format.");
        }

        return prompt.toString();
    }

    /**
     * Build user content with optional runtime context
     */
    private String buildUserContent(String message, String channel, String chatId) {
        StringBuilder content = new StringBuilder();

        // Add runtime context if channel/chatId provided
        if (channel != null || chatId != null) {
            content.append(RUNTIME_CONTEXT_TAG).append("\n");
            content.append("Current Time: ").append(LocalDateTime.now().format(TIME_FORMAT)).append("\n");
            if (channel != null) {
                content.append("Channel: ").append(channel).append("\n");
            }
            if (chatId != null) {
                content.append("Chat ID: ").append(chatId).append("\n");
            }
            content.append("\n");
        }

        content.append(message != null ? message : "");

        return content.toString();
    }

    /**
     * Truncate content if needed for token limits
     */
    private String truncateIfNeeded(String content) {
        if (content == null) {
            return "";
        }

        // Tool results should be limited to prevent token overflow
        int maxChars = Integer.parseInt(System.getProperty(
                "agent.tool.result.max.chars", "16000"));

        if (content.length() > maxChars) {
            return content.substring(0, maxChars) + "\n... (truncated)";
        }

        return content;
    }

    /**
     * Get default system prompt for JMeter AI Agent
     */
    private String getDefaultSystemPrompt() {
        return """
                # JMeter AI Assistant

                You are an expert JMeter assistant embedded in the Feather Wand plugin.
                Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans.

                ## Capabilities

                - Provide detailed information about JMeter elements and their properties
                - Suggest appropriate elements based on testing needs
                - Explain best practices for performance testing
                - Help troubleshoot and optimize test plans
                - Generate script snippets in Groovy or Java
                - Analyze test results and provide insights

                ## Supported Elements

                - Thread Groups (Standard)
                - Samplers (HTTP, JDBC)
                - Controllers (Logic, Transaction, Loop, If, While, Random)
                - Config Elements (CSV Data Set, HTTP Request Defaults, Header Manager, Cookie Manager)
                - Pre-Processors (JSR223, Regex User Parameters, User Parameters)
                - Post-Processors (Regex Extractor, JSON Extractor, XPath Extractor, Boundary Extractor)
                - Assertions (Response, JSON Path, Duration, Size, XPath, JSR223, MD5Hex)
                - Timers (Constant, Uniform Random, Gaussian Random, Poisson Random, Constant Throughput)
                - Listeners (View Results Tree, Aggregate Report, Summary Report, Backend Listener)

                ## Guidelines

                1. Focus responses on JMeter concepts and best practices
                2. Provide concise, accurate information
                3. Prioritize JMeter's built-in capabilities
                4. Be specific about where elements can be added in the test plan hierarchy
                5. Consider test plan maintainability and performance overhead
                6. Highlight potential pitfalls or memory issues
                7. Use proper JMeter terminology and element names

                ## Programming Languages

                1. Groovy (default for JSR223 elements)
                2. Java
                3. Regular expressions for extractors and assertions

                ## Response Format

                - Use code blocks for scripts and commands
                - Use bullet points for steps and options
                - Use bold for element names and important concepts
                - Provide practical, actionable advice

                Version: JMeter 5.6+
                """;
    }
}
