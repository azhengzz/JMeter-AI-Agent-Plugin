package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.wrap.WrapCommandHandler;
import org.gitee.jmeter.ai.wrap.WrapUndoRedoHandler;

import java.util.Map;

/**
 * Tool to wrap HTTP Samplers under a Transaction Controller.
 * Corresponds to the @wrap command.
 */
public class WrapSamplersTool extends AbstractTool {

    private final WrapCommandHandler wrapHandler;

    public WrapSamplersTool() {
        this.wrapHandler = new WrapCommandHandler();
    }

    @Override
    public String getName() {
        return "wrap_http_samplers";
    }

    @Override
    public String getDescription() {
        return "Wrap consecutive HTTP Request samplers under a Transaction Controller. " +
                "This is useful for grouping related requests together and measuring aggregate response times. " +
                "Supports undo/redo functionality.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "transactionName": {
                            "type": "string",
                            "description": "Optional name for the Transaction Controller (if not specified, uses auto-generated name based on first request name)"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        try {
            String result = wrapHandler.processWrapCommand();
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error executing wrap command", e);
            return ToolResult.error("Wrap operation failed: " + e.getMessage());
        }
    }

    /**
     * Undo the last wrap operation
     */
    public ToolResult undo() {
        try {
            String result = WrapUndoRedoHandler.getInstance().undoLastWrap();
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error undoing wrap operation", e);
            return ToolResult.error("Undo failed: " + e.getMessage());
        }
    }

    /**
     * Redo the last undone wrap operation
     */
    public ToolResult redo() {
        try {
            String result = WrapUndoRedoHandler.getInstance().redoLastUndo();
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("Error redoing wrap operation", e);
            return ToolResult.error("Redo failed: " + e.getMessage());
        }
    }
}
