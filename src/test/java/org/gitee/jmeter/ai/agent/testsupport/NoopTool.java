package org.gitee.jmeter.ai.agent.testsupport;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.Tool;

import java.util.Map;

/** 立即成功的空操作工具（工具迭代产生 THINKING/TOOL_CALL 进度事件）。 */
public final class NoopTool implements Tool {
    @Override public String getName() {
        return "noop_tool";
    }

    @Override public String getDescription() {
        return "test noop tool";
    }

    @Override public String getParameterSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override public ToolResult execute(Map<String, Object> parameters) {
        return ToolResult.success("ok");
    }
}
