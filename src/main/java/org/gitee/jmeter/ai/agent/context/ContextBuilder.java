package org.gitee.jmeter.ai.agent.context;

import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.skills.SkillsLoader;
import org.gitee.jmeter.ai.agent.config.AgentConfig;
import org.gitee.jmeter.ai.selection.ElementInfo;
import org.gitee.jmeter.ai.selection.SelectionSnapshot;
import org.gitee.jmeter.ai.selection.SelectionTracker;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
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

    // Bootstrap files to load from workspace (similar to Nanobot's BOOTSTRAP_FILES)
    private static final String[] BOOTSTRAP_FILES = {
        "AGENTS.md",
        "SOUL.md",
        "USER.md",
        "TOOLS.md"
    };

    private final MemoryStore memoryStore;
    private final SkillsLoader skillsLoader;
    private final Path workspace;

    public ContextBuilder(MemoryStore memoryStore, Path workspace) {
        this.memoryStore = memoryStore;
        this.workspace = workspace;
        this.skillsLoader = new SkillsLoader(workspace);

        // Log available skills
        log.info("Loaded {} skills (always: {})",
                skillsLoader.listSkills(true).size(),
                skillsLoader.getAlwaysSkills());
    }

    /**
     * Build system prompt from identity, bootstrap files, memory, and skills.
     * Based on Nanobot's build_system_prompt logic.
     */
    public String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();

        // 1. Identity (default system prompt)
        parts.add(getDefaultSystemPrompt());

        // 2. Bootstrap files (AGENTS.md, SOUL.md, USER.md, TOOLS.md)
        String bootstrap = loadBootstrapFiles();
        if (!bootstrap.isEmpty()) {
            parts.add(bootstrap);
        }

        // 3. Memory
        String memoryContext = memoryStore.getMemoryContext();
        if (!memoryContext.isEmpty()) {
            parts.add("# Memory\n\n" + memoryContext);
        }

        // 4. Active Skills (always=true skills with full content)
        List<String> alwaysSkills = skillsLoader.getAlwaysSkills();
        if (!alwaysSkills.isEmpty()) {
            String alwaysContent = skillsLoader.loadSkillsForContext(alwaysSkills);
            if (!alwaysContent.isEmpty()) {
                parts.add("# Active Skills\n\n" + alwaysContent);
            }
        }

        // 5. Skills Summary (XML format listing all available skills)
        String skillsSummary = skillsLoader.buildSkillsSummary();
        if (!skillsSummary.isEmpty()) {
            parts.add("# Skills\n\n" +
                    "The following skills extend your capabilities. To use a skill, read its SKILL.md file using the read_file tool.\n" +
                    "Skills with available=\"false\" need dependencies installed first.\n\n" +
                    skillsSummary);
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * Load bootstrap files from workspace.
     * Based on Nanobot's _load_bootstrap_files logic.
     */
    private String loadBootstrapFiles() {
        List<String> parts = new ArrayList<>();

        for (String filename : BOOTSTRAP_FILES) {
            Path filePath = workspace.resolve(filename);
            if (Files.exists(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    parts.add("## " + filename + "\n\n" + content);
                    log.debug("Loaded bootstrap file: {}", filename);
                } catch (Exception e) {
                    log.warn("Failed to read bootstrap file {}: {}", filename, e.getMessage());
                }
            } else {
                log.debug("Bootstrap file not found: {} (optional)", filename);
            }
        }

        return String.join("\n\n", parts);
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
        // String systemPrompt = buildSystemPromptWithTools(tools);
        String systemPrompt = buildSystemPrompt();

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
     * Add an assistant message with reasoning content to the message list.
     */
    public List<Message> addAssistantMessage(
            List<Message> messages,
            String content,
            List<ToolCall> toolCalls,
            String reasoningContent) {

        messages.add(Message.assistant(content, toolCalls, reasoningContent));
        return messages;
    }

    // /**
    //  * Build system prompt with tool descriptions
    //  */
    // @SuppressWarnings("unchecked")
    // private String buildSystemPromptWithTools(List<Map<String, Object>> tools) {
    //     StringBuilder prompt = new StringBuilder(buildSystemPrompt(null));

    //     if (tools != null && !tools.isEmpty()) {
    //         prompt.append("\n\n## Available Tools\n\n");
    //         prompt.append("You have access to the following tools. Call them when needed:\n\n");

    //         for (Map<String, Object> tool : tools) {
    //             Map<String, Object> function = tool.containsKey("function") ? (Map<String, Object>) tool.get("function") : null;
    //             if (function != null) {
    //                 String name = function.getOrDefault("name", "").toString();
    //                 String description = function.getOrDefault("description", "").toString();
    //                 prompt.append("- **").append(name).append("**: ")
    //                     .append(description).append("\n");
    //             }
    //         }

    //         prompt.append("\nWhen you need to use a tool, respond with a tool call in the appropriate format.");
    //     }

    //     return prompt.toString();
    // }

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

        appendSelectionContext(lines);

        return lines.toString();
    }

    /**
     * 追加当前 JMeter 选中元素的信息（L1 + L2）。
     * 仅当 {@link SelectionTracker#isInjectToContextEnabled()} 为 true 且 snapshot 非空时输出。
     *
     * <p>日志面板上下文（L2 焦点在底部 LoggerPanel）下，L1 的"当前选中元素"与日志选区
     * 互不相关，改为输出 "Selected: Log Panel"，不附带 element id。
     */
    private void appendSelectionContext(StringBuilder lines) {
        if (!SelectionTracker.isInjectToContextEnabled()) {
            return;
        }
        SelectionSnapshot snapshot = SelectionTracker.getCurrentSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }

        boolean isLogContext = snapshot.focusControl != null
                && "LoggerPanel".equals(snapshot.focusControl.controlType);

        if (isLogContext) {
            lines.append("\n").append("Selected: Log Panel");
        } else {
            String type = snapshot.elementType != null ? snapshot.elementType : "";
            String name = snapshot.element.getName();
            if (name == null) {
                name = "";
            }
            lines.append("\n").append("Selected Element: ")
                    .append("type=").append(type)
                    .append(", name=\"").append(name).append("\"")
                    .append(", id=").append(snapshot.elementId);
        }

        if (snapshot.focusControl != null
                && snapshot.focusControl.controlType != null
                && !snapshot.focusControl.isEmpty()) {
            ElementInfo fc = snapshot.focusControl;
            String field = (fc.fieldName == null || fc.fieldName.isEmpty())
                    ? "(unlabeled field)" : fc.fieldName;
            lines.append("\n").append("Focused Field: ").append(field);
            if (fc.value != null && !fc.value.isEmpty()) {
                lines.append(" = ").append(fc.value);
            }
        }
    }

    /**
     * Truncate content if needed for token limits
     */
    private String truncateIfNeeded(String content) {
        if (content == null) {
            return "";
        }

        // Tool results should be limited to prevent token overflow
        int maxChars = AgentConfig.getInstance().getToolResultMaxChars();

        if (content.length() > maxChars) {
            return content.substring(0, maxChars) + "\n...(truncated)";
        }

        return content;
    }

    /**
     * Get default system prompt for JMeter AI Agent.
     * Uses the unified SystemPrompt utility for consistency.
     */
    private String getDefaultSystemPrompt() {
        // Use the unified SystemPrompt with workspace information
        return SystemPrompt.getDefaultWithWorkspace(workspace);
    }
}
