package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool to get current JMeter script information and runtime environment.
 * Returns file path, save status, JMeter/JDK version, and directory info.
 */
public class GetScriptInfoTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_script_info";
    }

    @Override
    public String getDescription() {
        return "Get current JMeter script file information and runtime environment details, " +
                "including script path, save status, JMeter version, JDK version, and JMETER_HOME.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        Map<String, Object> info = new LinkedHashMap<>();

        // Basic script info
        String testPlanFile = guiPackage.getTestPlanFile();
        info.put("scriptFilePath", testPlanFile != null ? testPlanFile : null);
        info.put("scriptFileName", testPlanFile != null ? new File(testPlanFile).getName() : null);

        // Runtime environment
        info.put("jmeterVersion", JMeterUtils.getJMeterVersion());
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("jmeterHome", JMeterUtils.getJMeterHome());

        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(info);
            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            log.error("Error serializing script info", e);
            return ToolResult.error("Failed to serialize script info: " + e.getMessage());
        }
    }
}
