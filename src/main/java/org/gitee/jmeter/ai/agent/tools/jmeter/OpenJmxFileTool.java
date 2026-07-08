package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.Load;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.collections.HashTree;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool to open (load) an external JMX script into the current JMeter GUI.
 *
 * <p>Unlike {@link ParseJmxFileTool} (read-only DOM parse that never touches GUI state),
 * this tool reuses JMeter's own load path — {@link SaveService#loadTree(File)} +
 * {@link Load#insertLoadedTree(int, HashTree, boolean)} — so the script becomes the live
 * test plan in the GUI (clearing the existing plan, or merging into it).
 *
 * <p>The whole load runs on the EDT via {@link EdtRunner}, matching JMeter's own
 * {@code Load} action (which is dispatched on EDT by {@code ActionRouter}). Running
 * {@code SaveService.loadTree} off-EDT pollutes GUI state (e.g.
 * {@code ParameterIncludeController.loadIncludedElements} recursion); on-EDT it is correct.
 *
 * <p>Returns the full GUI tree after load (same shape as {@code get_test_plan_tree}),
 * so callers can immediately read back the loaded component content.
 */
public class OpenJmxFileTool extends AbstractTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "open_jmx_file";
    }

    @Override
    public String getDescription() {
        return "Open (load) an external JMX script file into the current JMeter GUI test plan. " +
                "By default replaces the current plan (open mode); set merge=true to merge into it. " +
                "Returns the full GUI tree after load (same format as get_test_plan_tree) so the " +
                "loaded component content can be read back immediately. Requires the JMeter GUI to be running.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "filePath": {
                            "type": "string",
                            "description": "Absolute or relative path to the .jmx file to open and load into the GUI"
                        },
                        "merge": {
                            "type": "boolean",
                            "description": "If true, merge the file into the current plan; if false (default), replace the current plan (open mode)"
                        },
                        "includeProperties": {
                            "type": "boolean",
                            "description": "Whether to include element properties in the returned tree (default: false; the tree structure is returned without property details by default)"
                        },
                        "maxDepth": {
                            "type": "integer",
                            "description": "Maximum depth to traverse in the returned tree (-1 for unlimited, 0 for root only, default: -1)"
                        }
                    },
                    "required": ["filePath"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String filePath = getStringParameter(parameters, "filePath", "");
        if (filePath == null || filePath.trim().isEmpty()) {
            return ToolResult.error("Parameter 'filePath' is required");
        }
        if (!filePath.toLowerCase().endsWith(".jmx")) {
            return ToolResult.error("File must have .jmx extension: " + filePath);
        }

        File file = new File(filePath);
        String absPath = file.getAbsolutePath();
        if (!file.exists()) {
            return ToolResult.error("File not found: " + absPath);
        }
        if (!file.isFile()) {
            return ToolResult.error("Path is not a regular file: " + absPath);
        }
        if (!file.canRead()) {
            return ToolResult.error("File is not readable: " + absPath);
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        boolean merge = getBooleanParameter(parameters, "merge", false);
        boolean includeProperties = getBooleanParameter(parameters, "includeProperties", false);
        int maxDepth = getIntParameter(parameters, "maxDepth", -1);

        // holder[0] = summary Map on success; holder[1] = error message on failure.
        Object[] holder = new Object[2];
        final boolean fMerge = merge;
        Exception edtError = EdtRunner.run(guiPackage, () -> {
            try {
                if (!fMerge) {
                    // Match JMeter's loadProjectFile: set FileServer base first so relative
                    // paths (includes, CSV files) in the loaded plan resolve correctly.
                    try {
                        FileServer.getFileServer().setBaseForScript(file);
                    } catch (Exception sbs) {
                        log.warn("setBaseForScript failed for {} (non-fatal): {}", absPath, sbs.getMessage());
                    }
                }

                HashTree tree = SaveService.loadTree(file);
                if (tree == null || tree.getArray().length == 0) {
                    holder[1] = "JMX file contains no test elements: " + absPath;
                    return;
                }

                // Reuse JMeter's own insert path: clearTestPlan (open mode) + addSubTree +
                // updateCurrentGui + tree expand + SUB_TREE_LOADED event.
                boolean isTestPlan = Load.insertLoadedTree(ActionEvent.ACTION_PERFORMED, tree, fMerge);
                if (!fMerge && isTestPlan) {
                    guiPackage.setTestPlanFile(absPath);
                }

                // Read back the now-loaded GUI tree (same shape as get_test_plan_tree).
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("filePath", absPath);
                summary.put("fileName", file.getName());
                summary.put("testPlan", isTestPlan);
                summary.put("merge", fMerge);
                summary.put("loadedTree", buildCurrentTreeData(guiPackage, includeProperties, maxDepth));
                holder[0] = summary;
            } catch (Throwable t) {
                holder[1] = rootMessage(t);
            }
        });

        if (holder[1] != null) {
            return ToolResult.error("Failed to load JMX file: " + holder[1]);
        }
        if (edtError != null) {
            return ToolResult.error("Failed to load JMX file: " + rootMessage(edtError));
        }

        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(holder[0]);
            return ToolResult.success(json);
        } catch (JsonProcessingException e) {
            log.error("Error serializing loaded tree to JSON", e);
            return ToolResult.error("Failed to serialize loaded tree to JSON: " + e.getMessage());
        }
    }

    /**
     * Build tree data from the GUI tree model, skipping the virtual root and starting from
     * the actual test plan node (mirrors {@code GetTestPlanTreeTool}).
     */
    private static Map<String, Object> buildCurrentTreeData(GuiPackage guiPackage, boolean includeProperties, int maxDepth) {
        JMeterTreeNode rootNode = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        if (rootNode == null) {
            return null;
        }
        JMeterTreeNode actualRoot = rootNode;
        if (rootNode.getChildCount() == 1) {
            JMeterTreeNode firstChild = (JMeterTreeNode) rootNode.getChildAt(0);
            if (firstChild.getTestElement() != null) {
                actualRoot = firstChild;
            }
        }
        return JMeterTreeUtils.buildTreeData(actualRoot, includeProperties, maxDepth, 0);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause();
        return (c != null ? c : t).getMessage();
    }
}
