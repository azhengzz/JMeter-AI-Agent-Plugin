package org.qainsights.jmeter.ai.agent.tools.jmeter;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.qainsights.jmeter.ai.service.AiService;

import java.util.Map;

/**
 * Tool to optimize the currently selected JMeter element.
 * Corresponds to the @optimize command.
 */
public class OptimizeJMeterElementTool extends AbstractTool {

    private final AiService aiService;

    public OptimizeJMeterElementTool(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public String getName() {
        return "optimize_jmeter_element";
    }

    @Override
    public String getDescription() {
        return "Analyze and optimize the currently selected JMeter element. " +
                "Provides specific recommendations for improving performance, configuration, and best practices. " +
                "Supports Thread Groups, Samplers, Controllers, Timers, Assertions, Config Elements, and more.";
    }

    @Override
    public String getParameterSchema() {
        return "{\"type\": \"object\", \"properties\": {}}";
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (aiService == null) {
            return ToolResult.error("AI service is not available");
        }

        String result = OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(aiService);
        return ToolResult.success(result);
    }
}
