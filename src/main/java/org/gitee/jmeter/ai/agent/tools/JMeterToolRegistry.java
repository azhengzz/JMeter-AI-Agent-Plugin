package org.gitee.jmeter.ai.agent.tools;

import org.gitee.jmeter.ai.agent.tools.exec.ExecTool;
import org.gitee.jmeter.ai.agent.tools.filesystem.*;
import org.gitee.jmeter.ai.agent.tools.ipc.DelegateToInstanceTool;
import org.gitee.jmeter.ai.agent.tools.ipc.ListInstancesTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.*;
import org.gitee.jmeter.ai.agent.tools.jmeter.execution.*;
import org.gitee.jmeter.ai.agent.tools.web.*;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central registry for JMeter-specific tools.
 * Handles registration of default tools for JMeter operations.
 */
public class JMeterToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(JMeterToolRegistry.class);

    /**
     * Register all default JMeter tools with the given registry.
     *
     * @param registry The tool registry to register tools with
     */
    public static void registerDefaultTools(ToolRegistry registry) {
        // Register core JMeter tools
        registry.register(new GetScriptInfoTool());
        registry.register(new GetSelectedElementTool());
        registry.register(new GetTestPlanTreeTool());
        registry.register(new ParseJmxFileTool());
        registry.register(new OpenJmxFileTool());
        registry.register(new FindElementTool());
        registry.register(new CreateJMeterElementTool());
        registry.register(new UpdateJMeterElementTool());
        registry.register(new BatchUpdateJMeterElementTool());
        registry.register(new DeleteJMeterElementTool());
        registry.register(new BatchDeleteJMeterElementTool());
        registry.register(new MoveJMeterElementTool());
        registry.register(new BatchMoveJMeterElementTool());
        registry.register(new CopyPasteJMeterElementTool());
        registry.register(new ToggleJMeterElementTool());
        registry.register(new BatchToggleJMeterElementTool());
        registry.register(new QueryElementPropertiesTool());
        registry.register(new GetLogPanelContentTool());

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

        // Register cross-instance coordination tools if IPC + coordination enabled
        registerInstanceCoordinationTools(registry);
    }

    /**
     * Register filesystem tools if enabled.
     *
     * @param registry The tool registry to register tools with
     */
    private static void registerFilesystemTools(ToolRegistry registry) {
        boolean enabled = AiConfig.isFilesystemToolsEnabled();

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
    private static void registerWebTools(ToolRegistry registry) {
        boolean enabled = AiConfig.isWebsearchToolsEnabled();

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
    private static void registerExecTools(ToolRegistry registry) {
        boolean enabled = AiConfig.isExecToolsEnabled();

        if (enabled) {
            log.info("Registering exec tool");
            registry.register(new ExecTool());
        } else {
            log.info("Exec tool is disabled");
        }
    }

    /**
     * Register cross-instance coordination tools (list_instances / delegate_to_instance) when
     * IPC is enabled. IPC provides the transport (port files + /agent endpoint); without it the
     * tools would only ever fail, so they are not registered.
     */
    public static void registerInstanceCoordinationTools(ToolRegistry registry) {
        if (AiConfig.isIpcEnabled()) {
            log.info("Registering instance-coordination tools");
            registry.register(new ListInstancesTool());
            registry.register(new DelegateToInstanceTool());
        } else {
            log.info("Instance-coordination tools disabled (IPC disabled, no transport)");
        }
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
