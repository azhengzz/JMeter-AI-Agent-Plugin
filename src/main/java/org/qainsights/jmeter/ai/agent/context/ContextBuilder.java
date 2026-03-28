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
 * Based on Nanobot's context assembly logic.
 */
public class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]";

    private final MemoryStore memoryStore;
    private final String baseSystemPrompt;
    private final SkillsLoader skillsLoader;
    private final Path workspace;

    public ContextBuilder(MemoryStore memoryStore, String baseSystemPrompt, Path workspace) {
        this.memoryStore = memoryStore;
        this.baseSystemPrompt = baseSystemPrompt != null ? baseSystemPrompt : getDefaultSystemPrompt();
        this.workspace = workspace;
        this.skillsLoader = new SkillsLoader(workspace);

        // Log available skills
        log.info("Loaded {} skills (always: {})",
                skillsLoader.listSkills(true).size(),
                skillsLoader.getAlwaysSkills());
    }

    /**
     * Build system prompt from identity, memory, and skills.
     * Based on Nanobot's build_system_prompt logic.
     */
    public String buildSystemPrompt(List<String> activeSkills) {
        List<String> parts = new ArrayList<>();

        // 1. Identity
        parts.add(baseSystemPrompt);

        // 2. Memory
        String memoryContext = memoryStore.getMemoryContext();
        if (!memoryContext.isEmpty()) {
            parts.add("# Memory\n\n" + memoryContext);
        }

        // 3. Active Skills (always=true skills with full content)
        List<String> alwaysSkills = skillsLoader.getAlwaysSkills();
        if (!alwaysSkills.isEmpty()) {
            String alwaysContent = skillsLoader.loadSkillsForContext(alwaysSkills);
            if (!alwaysContent.isEmpty()) {
                parts.add("# Active Skills\n\n" + alwaysContent);
            }
        }

        // 4. Skills Summary (XML format listing all available skills)
        String skillsSummary = skillsLoader.buildSkillsSummary();
        if (!skillsSummary.isEmpty()) {
            parts.add("# Skills\n\n" +
                    "The following skills extend your capabilities. Skills marked with available=\"false\" " +
                    "need dependencies installed first.\n\n" +
                    skillsSummary);
        }

        return String.join("\n\n---\n\n", parts);
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
     * Build complete message list with channel context.
     * Based on Nanobot's build_messages logic.
     */
    public List<Message> buildMessages(
            List<Message> history,
            String currentMessage,
            List<Map<String, Object>> tools,
            String channel,
            String chatId) {

        List<Message> messages = new ArrayList<>();

        // Build runtime context and merge with user message
        String runtimeContext = buildRuntimeContext(channel, chatId);
        String mergedUserContent = runtimeContext + "\n\n" + currentMessage;

        // Build system prompt
        String systemPrompt = buildSystemPromptWithTools(tools);

        // Add system message
        messages.add(Message.system(systemPrompt));

        // Add history (filter out system messages from history)
        for (Message msg : history) {
            if (msg.getRole() != Message.Role.SYSTEM) {
                messages.add(msg);
            }
        }

        // Add current user message (merged with runtime context)
        messages.add(Message.user(mergedUserContent));

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
     * Build runtime metadata block for injection before the user message.
     * Based on Nanobot's _build_runtime_context.
     */
    private String buildRuntimeContext(String channel, String chatId) {
        StringBuilder lines = new StringBuilder();
        lines.append(RUNTIME_CONTEXT_TAG).append("\n");
        lines.append("Current Time: ").append(LocalDateTime.now().format(TIME_FORMAT));

        if (channel != null && !channel.isEmpty()) {
            lines.append("\n").append("Channel: ").append(channel);
        }
        if (chatId != null && !chatId.isEmpty()) {
            lines.append("\n").append("Chat ID: ").append(chatId);
        }

        return lines.toString();
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
     * Get default system prompt for JMeter AI Agent.
     * Based on Nanobot's identity section format.
     */
    private String getDefaultSystemPrompt() {
        String workspacePath = workspace.toAbsolutePath().toString();
        String os = System.getProperty("os.name");
        String javaVersion = System.getProperty("java.version");
        String runtime = os + ", Java " + javaVersion;

        // Platform-specific policy
        String platformPolicy;
        if (os.toLowerCase().contains("win")) {
            platformPolicy = """
                    ## Platform Policy (Windows)
                    - You are running on Windows. Do not assume GNU tools like `grep`, `sed`, or `awk` exist.
                    - Prefer Windows-native commands or file tools when they are more reliable.
                    """;
        } else {
            platformPolicy = """
                    ## Platform Policy (POSIX)
                    - You are running on a POSIX system. Prefer UTF-8 and standard shell tools.
                    - Use file tools when they are simpler or more reliable than shell commands.
                    """;
        }

        return String.format("""
                # JMeter AI Assistant

                You are an expert JMeter assistant embedded in the Feather Wand plugin.
                Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans.

                ## Runtime
                %s

                ## Workspace
                Your workspace is at: %s
                - Long-term memory: %s/memory/MEMORY.md (write important facts here)
                - History log: %s/memory/HISTORY.md (grep-searchable). Each entry starts with [YYYY-MM-DD HH:MM].
                - Custom skills: %s/skills/{skill-name}/SKILL.md

                %s
                ## JMeter AI Guidelines
                - State intent before tool calls, but NEVER predict or claim results before receiving them.
                - Before modifying a file, read it first. Do not assume files or directories exist.
                - After writing or editing a file, re-read it if accuracy matters.
                - If a tool call fails, analyze the error before retrying with a different approach.
                - Ask for clarification when the request is ambiguous.
                - Focus responses on JMeter concepts and best practices.
                - Provide concise, accurate information with practical, actionable advice.

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

                ## Programming Languages
                - Groovy (default for JSR223 elements)
                - Java
                - Regular expressions for extractors and assertions

                ## Response Format
                - Use code blocks for scripts and commands
                - Use bullet points for steps and options
                - Use bold for element names and important concepts

                Version: JMeter 5.6+
                """, runtime, workspacePath, workspacePath, workspacePath, workspacePath, platformPolicy);
    }
}
