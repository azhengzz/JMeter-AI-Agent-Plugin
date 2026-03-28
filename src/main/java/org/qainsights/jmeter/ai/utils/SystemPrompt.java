package org.qainsights.jmeter.ai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized System Prompt management for all AI providers.
 * <p>
 * Configuration priority (highest to lowest):
 * 1. Unified: jmeter.ai.system.prompt (applies to all providers)
 * 2. Built-in default + workspace bootstrap files
 * <p>
 * Usage:
 * <pre>
 * String prompt = SystemPrompt.get();                          // Use unified config + bootstrap files
 * String prompt = SystemPrompt.get("custom prompt");           // Direct override
 * String prompt = SystemPrompt.getWithWorkspace(workspace);    // With workspace info
 * </pre>
 */
public class SystemPrompt {
    private static final Logger log = LoggerFactory.getLogger(SystemPrompt.class);

    // Bootstrap files to load from workspace
    private static final String[] BOOTSTRAP_FILES = {
        "AGENTS.md",
        "SOUL.md",
        "USER.md",
        "TOOLS.md"
    };

    // Default workspace path
    private static final Path DEFAULT_WORKSPACE =
        Paths.get(System.getProperty("user.home")).resolve(".jmeter-ai").resolve("agent");

    /**
     * The default JMeter system prompt used when no custom prompt is configured.
     * Based on Nanobot's identity section format.
     */
    public static final String DEFAULT_JMETER_SYSTEM_PROMPT = buildDefaultPrompt();

    private static String buildDefaultPrompt() {
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
                - Thread Groups (Standard, Setup, Teardown)
                - Samplers (HTTP, JDBC, JSR223)
                - Controllers (Loop, If, While, Transaction, Random, ForEach)
                - Config Elements (CSV Data Set, HTTP Request Defaults, Header Manager, Cookie Manager, User Defined Variables)
                - Pre-Processors (JSR223, User Parameters, Regex User Parameters)
                - Post-Processors (Regex Extractor, JSON Extractor, XPath Extractor, Boundary Extractor, JSON JMESPath Extractor)
                - Assertions (Response, JSON Path, Duration, Size, XPath, JSR223, MD5Hex, Compare)
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

                Version: JMeter 5.6+ (Also support questions about older versions from 3.0+)
                """, runtime, platformPolicy);
    }

    /**
     * Get the default prompt with workspace information.
     *
     * @param workspace The workspace path
     * @return The system prompt with workspace info
     */
    public static String getDefaultWithWorkspace(Path workspace) {
        String defaultPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
        String workspacePath = workspace.toAbsolutePath().toString();

        // Insert workspace section after Runtime
        String workspaceSection = String.format("""
                ## Workspace
                Your workspace is at: %s
                - Long-term memory: %s/memory/MEMORY.md (write important facts here)
                - History log: %s/memory/HISTORY.md (grep-searchable). Each entry starts with [YYYY-MM-DD HH:MM].
                - Custom skills: %s/skills/{skill-name}/SKILL.md
                """, workspacePath, workspacePath, workspacePath, workspacePath);

        // Insert after "## Runtime" section
        int runtimeIndex = defaultPrompt.indexOf("## Runtime");
        if (runtimeIndex >= 0) {
            int endOfRuntime = defaultPrompt.indexOf("\n\n", runtimeIndex);
            if (endOfRuntime > runtimeIndex) {
                return defaultPrompt.substring(0, endOfRuntime) + workspaceSection +
                       defaultPrompt.substring(endOfRuntime);
            }
        }

        return defaultPrompt;
    }


    // Keys for configuration
    private static final String UNIFIED_PROMPT_KEY = "jmeter.ai.system.prompt";

    /**
     * Get the system prompt using unified configuration.
     * <p>
     * Priority:
     * 1. jmeter.ai.system.prompt (unified for all providers)
     * 2. Built-in default + workspace bootstrap files
     *
     * @return The system prompt to use
     */
    public static String get() {
        // Check unified configuration
        String unifiedPrompt = AiConfig.getProperty(UNIFIED_PROMPT_KEY, "");
        if (!unifiedPrompt.isEmpty()) {
            log.debug("Using unified system prompt");
            return unifiedPrompt;
        }

        // Initialize workspace and get full system prompt with bootstrap files
        Path workspace = getWorkspacePath();
        initializeWorkspace(workspace);

        String fullPrompt = buildFullSystemPrompt(workspace);
        log.debug("Using built-in default system prompt with bootstrap files (length: {})", fullPrompt.length());
        return fullPrompt;
    }

    /**
     * Get the system prompt with direct override.
     *
     * @param override  Direct override prompt (takes highest priority)
     * @return The system prompt to use
     */
    public static String get(String override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return get();
    }

    /**
     * Get the built-in default prompt without checking any configuration.
     *
     * @return The default JMeter system prompt
     */
    public static String getDefault() {
        return DEFAULT_JMETER_SYSTEM_PROMPT;
    }

    /**
     * Check if unified system prompt is configured.
     *
     * @return true if unified prompt is configured
     */
    public static boolean isUnifiedConfigured() {
        return !AiConfig.getProperty(UNIFIED_PROMPT_KEY, "").isEmpty();
    }

    /**
     * Get the workspace path from configuration.
     *
     * @return The workspace path
     */
    private static Path getWorkspacePath() {
        String configuredPath = AiConfig.getProperty("agent.workspace.path", null);
        if (configuredPath != null && !configuredPath.isEmpty()) {
            return Path.of(configuredPath);
        }
        return DEFAULT_WORKSPACE;
    }

    /**
     * Initialize workspace with template files if not already initialized.
     *
     * @param workspace The workspace path
     */
    private static void initializeWorkspace(Path workspace) {
        if (!WorkspaceInitializer.isInitialized(workspace)) {
            log.info("Initializing workspace: {}", workspace);
            WorkspaceInitializer.initialize(workspace, true);
        }
    }

    /**
     * Build full system prompt with bootstrap files from workspace.
     *
     * @param workspace The workspace path
     * @return The complete system prompt
     */
    private static String buildFullSystemPrompt(Path workspace) {
        List<String> parts = new ArrayList<>();

        // 1. Base identity with workspace info
        parts.add(getDefaultWithWorkspace(workspace));

        // 2. Load bootstrap files
        String bootstrap = loadBootstrapFiles(workspace);
        if (!bootstrap.isEmpty()) {
            parts.add(bootstrap);
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * Load bootstrap files from workspace.
     *
     * @param workspace The workspace path
     * @return Combined content of all bootstrap files
     */
    private static String loadBootstrapFiles(Path workspace) {
        List<String> parts = new ArrayList<>();

        for (String filename : BOOTSTRAP_FILES) {
            Path filePath = workspace.resolve(filename);
            if (Files.exists(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    if (!content.trim().isEmpty()) {
                        parts.add("## " + filename + "\n\n" + content);
                        log.debug("Loaded bootstrap file: {}", filename);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read bootstrap file {}: {}", filename, e.getMessage());
                }
            }
        }

        return String.join("\n\n", parts);
    }
}
