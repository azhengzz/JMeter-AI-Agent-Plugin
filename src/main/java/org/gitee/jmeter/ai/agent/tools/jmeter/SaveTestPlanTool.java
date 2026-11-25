package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool to save the live test plan to disk (Ctrl+S / Save As semantics).
 *
 * <p>Save semantics and protection rules live in {@link PlanSaver}, which the goal-completion
 * save-back shares; this class only renders the outcome for the model. Without {@code filePath}
 * the plan is written back to its associated file; with {@code filePath} it is written there and
 * re-associated with that path.
 */
public class SaveTestPlanTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PlanSaver planSaver;

    public SaveTestPlanTool() {
        this(new PlanSaver());
    }

    /** 包内可见：注入 PlanSaver 供单测。 */
    SaveTestPlanTool(PlanSaver planSaver) {
        this.planSaver = planSaver;
    }

    @Override
    public String getName() {
        return "save_test_plan";
    }

    @Override
    public String getDescription() {
        return "Save the live test plan to disk through JMeter's own Save action (equivalent to the user "
                + "pressing Ctrl+S). Without filePath: writes the plan back to its associated .jmx file. "
                + "With filePath (absolute path ending in .jmx, parent directory must exist): Save As - "
                + "writes to that path and re-associates the plan with it; pass overwrite=true when that "
                + "file already exists. Returns the saved path and size. Call this tool only when the user "
                + "explicitly asks to save - now, or after the changes they requested; never save on your "
                + "own initiative. Exception: in /goal turns, once validation passes (static PASS + smoke "
                + "zero errors), call this tool yourself BEFORE update_goal(complete) to write the plan "
                + "back to its associated file, then report the save outcome in your final reply. If the "
                + "plan has never been saved, this tool refuses - ask the user for a save path instead "
                + "of guessing one.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "filePath": {
                            "type": "string",
                            "description": "Optional. Absolute path ending in .jmx. Omit to save back to the plan's associated file; provide it to Save As (writes there and re-associates the plan with that path)"
                        },
                        "overwrite": {
                            "type": "boolean",
                            "description": "Required true when filePath points at an existing file (default: false). Not needed when saving back to the associated file"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String filePath = getStringParameter(parameters, "filePath", null);
        boolean overwrite = getBooleanParameter(parameters, "overwrite", false);

        PlanSaver.SaveOutcome outcome = planSaver.save(filePath, overwrite);
        if (!outcome.saved()) {
            return ToolResult.error(outcome.failure());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", outcome.filePath());
        result.put("fileName", new File(outcome.filePath()).getName());
        result.put("mode", outcome.saveAs() ? "save_as" : "save");
        result.put("bytes", outcome.bytes());

        try {
            return ToolResult.success(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("Error serializing save result to JSON", e);
            return ToolResult.error("The test plan was saved to '" + outcome.filePath()
                    + "', but the result could not be serialized: " + e.getMessage());
        }
    }
}
