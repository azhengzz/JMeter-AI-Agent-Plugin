package org.qainsights.jmeter.ai.agent.tools;

import org.qainsights.jmeter.ai.agent.tools.exec.ExecTool;
import org.qainsights.jmeter.ai.agent.tools.filesystem.*;
import org.qainsights.jmeter.ai.agent.tools.jmeter.*;
import org.qainsights.jmeter.ai.agent.tools.jmeter.execution.*;
import org.qainsights.jmeter.ai.agent.tools.web.*;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central registry for JMeter-specific tools.
 * Handles registration of default tools for JMeter operations.
 */
public class JMeterToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(JMeterToolRegistry.class);

    // Configuration keys
    private static final String FS_TOOLS_ENABLED = "agent.tools.filesystem.enabled";
    private static final String WEB_TOOLS_ENABLED = "agent.tools.websearch.enabled";
    private static final String EXEC_TOOLS_ENABLED = "agent.tools.exec.enabled";

    /**
     * Register all default JMeter tools with the given registry.
     *
     * @param registry The tool registry to register tools with
     * @param aiService The AI service (required for some tools)
     */
    public static void registerDefaultTools(ToolRegistry registry, AiService aiService) {
        // Register core JMeter tools
        registry.register(new GetSelectedElementTool());
        registry.register(new GetTestPlanTreeTool());
        registry.register(new FindElementTool());
        registry.register(new CreateJMeterElementTool());
        registry.register(new UpdateJMeterElementTool());
        registry.register(new DeleteJMeterElementTool());
        registry.register(new MoveJMeterElementTool());
        registry.register(new CopyPasteJMeterElementTool());

        // Register tools that require AI service
        if (aiService != null) {
            registry.register(new OptimizeJMeterElementTool(aiService));
            registry.register(new LintElementsTool(aiService));
            registry.register(new UsageTool(aiService));
        }

        // Register tools without AI service dependency
        registry.register(new WrapSamplersTool());

        // Register test execution tools
        registry.register(new RunTestTool());
        registry.register(new GetTestStatusTool());
        registry.register(new GetTestResultsTool());

        // Register filesystem tools if enabled
        registerFilesystemTools(registry);

        // Register web tools if enabled
        registerWebTools(registry);

        // Register exec tool if enabled
        registerExecTools(registry);
    }

    /**
     * Register filesystem tools if enabled.
     *
     * @param registry The tool registry to register tools with
     */
    public static void registerFilesystemTools(ToolRegistry registry) {
        boolean enabled = Boolean.parseBoolean(AiConfig.getProperty(FS_TOOLS_ENABLED, "false"));

        if (enabled) {
            log.info("Registering filesystem tools");
            registry.register(new ReadFileTool());
            registry.register(new WriteFileTool());
            registry.register(new EditFileTool());
            registry.register(new ListDirTool());
        } else {
            log.info("Filesystem tools are disabled");
        }
    }

    /**
     * Register web tools if enabled.
     *
     * @param registry The tool registry to register tools with
     */
    public static void registerWebTools(ToolRegistry registry) {
        boolean enabled = Boolean.parseBoolean(AiConfig.getProperty(WEB_TOOLS_ENABLED, "false"));

        if (enabled) {
            log.info("Registering web tools");
            registry.register(new WebSearchTool());
            registry.register(new WebFetchTool());
        } else {
            log.info("Web tools are disabled");
        }
    }

    /**
     * Register exec tool if enabled.
     *
     * @param registry The tool registry to register tools with
     */
    public static void registerExecTools(ToolRegistry registry) {
        boolean enabled = Boolean.parseBoolean(AiConfig.getProperty(EXEC_TOOLS_ENABLED, "false"));

        if (enabled) {
            log.info("Registering exec tool");
            registry.register(new ExecTool());
        } else {
            log.info("Exec tool is disabled");
        }
    }

    /**
     * Register a minimal set of tools (no AI service required).
     *
     * @param registry The tool registry to register tools with
     */
    public static void registerBasicTools(ToolRegistry registry) {
        registry.register(new GetSelectedElementTool());
        registry.register(new GetTestPlanTreeTool());
        registry.register(new FindElementTool());
        registry.register(new CreateJMeterElementTool());
        registry.register(new UpdateJMeterElementTool());
        registry.register(new DeleteJMeterElementTool());
        registry.register(new MoveJMeterElementTool());
        registry.register(new CopyPasteJMeterElementTool());
        registry.register(new WrapSamplersTool());
        registry.register(new RunTestTool());
        registry.register(new GetTestStatusTool());
        registry.register(new GetTestResultsTool());
    }

    /**
     * Get a description of all registered JMeter tools.
     *
     * @param registry The tool registry
     * @return Markdown formatted description of tools
     */
    public static String getToolDescriptions(ToolRegistry registry) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available JMeter Tools\n\n");

        for (String toolName : registry.getToolNames()) {
            var tool = registry.get(toolName);
            if (tool != null) {
                sb.append("- **").append(toolName).append("**: ")
                        .append(tool.getDescription()).append("\n");
            }
        }

        return sb.toString();
    }
}
