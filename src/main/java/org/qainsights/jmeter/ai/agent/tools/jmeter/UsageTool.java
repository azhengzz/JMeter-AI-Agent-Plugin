package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.usage.UsageCommandHandler;
import org.qainsights.jmeter.ai.service.AiService;

import java.util.Map;

/**
 * Tool to display token usage statistics.
 * Corresponds to the @usage command.
 */
public class UsageTool extends AbstractTool {

    private final AiService aiService;

    public UsageTool(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public String getName() {
        return "get_usage";
    }

    @Override
    public String getDescription() {
        return "Display token usage statistics for AI interactions. " +
                "Shows total tokens used, input tokens, output tokens, and cost estimates.";
    }

    @Override
    public String getParameterSchema() {
        return "{" +
                "\"type\": \"object\", " +
                "\"properties\": {}, " +
                "\"required\": []" +
                "}";
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (aiService == null) {
            return ToolResult.error("AI service is not available");
        }

        try {
            UsageCommandHandler handler = new UsageCommandHandler();
            String usage = handler.processUsageCommand(aiService);
            return ToolResult.success(usage);
        } catch (Exception e) {
            log.error("Error getting usage information", e);
            return ToolResult.error("Failed to get usage information: " + e.getMessage());
        }
    }
}
