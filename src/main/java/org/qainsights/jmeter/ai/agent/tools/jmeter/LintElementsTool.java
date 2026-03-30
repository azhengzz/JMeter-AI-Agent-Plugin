package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.lint.LintCommandHandler;
import org.qainsights.jmeter.ai.service.AiService;

import java.util.Map;

/**
 * Tool to lint/analyze JMeter test plan elements and suggest improvements.
 * Corresponds to the @lint command.
 */
public class LintElementsTool extends AbstractTool {

    private final AiService aiService;
    private final LintCommandHandler lintHandler;

    public LintElementsTool(AiService aiService) {
        this.aiService = aiService;
        this.lintHandler = new LintCommandHandler(aiService);
    }

    @Override
    public String getName() {
        return "lint_jmeter_elements";
    }

    @Override
    public String getDescription() {
        return "Analyze JMeter test plan elements and rename them for better organization. " +
                "Uses AI to suggest descriptive names based on element configuration and purpose. " +
                "Supports undo/redo functionality.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "description": "Action to perform: 'rename' (default) to rename elements with meaningful names, or provide custom instructions"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String action = getStringParameter(parameters, "action", "rename");

        try {
            // Use the LintCommandHandler
            String command = "@lint " + action;
            String result = lintHandler.processLintCommand(command);
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error executing lint command", e);
            return ToolResult.error("Lint operation failed: " + e.getMessage());
        }
    }

    /**
     * Undo the last lint operation
     */
    public ToolResult undo() {
        try {
            String result = lintHandler.undoLastRename();
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error undoing lint operation", e);
            return ToolResult.error("Undo failed: " + e.getMessage());
        }
    }

    /**
     * Redo the last undone lint operation
     */
    public ToolResult redo() {
        try {
            String result = lintHandler.redoLastUndo();
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error redoing lint operation", e);
            return ToolResult.error("Redo failed: " + e.getMessage());
        }
    }
}
