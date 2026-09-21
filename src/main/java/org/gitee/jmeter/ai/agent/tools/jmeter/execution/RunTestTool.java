package org.gitee.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool to start, stop, or shutdown JMeter test execution.
 * Injects AgentResultCollector into the GUI tree before starting
 * so results are captured for the AI agent.
 */
public class RunTestTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RunTestTool.class);

    @Override
    public String getName() {
        return "run_test";
    }

    @Override
    public String getDescription() {
        return "Start, stop, or shutdown the current JMeter test plan. " +
                "Custom JMeter properties can be injected before starting. " +
                "When starting, a result collector is injected to capture sample data. " +
                "Use get_test_status to check progress and get_test_results to view results after execution.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": ["start", "stop", "shutdown"],
                            "description": "Action to perform: 'start' begins execution, 'stop' forces immediate stop, 'shutdown' waits for current samples to finish",
                            "default": "start"
                        },
                        "ignore_timers": {
                            "type": "boolean",
                            "description": "If true, skip timer delays during execution (useful for quick validation)",
                            "default": false
                        },
                        "properties": {
                            "type": "object",
                            "description": "Optional JMeter properties to inject before starting, equivalent to command-line -J properties. Values can be strings, numbers, or booleans and are converted to strings.",
                            "additionalProperties": {
                                "type": ["string", "number", "boolean"]
                            }
                        }
                    }
                }
                """;
    }

    @Override
    public ValidationResult validateParameters(Map<String, Object> parameters) {
        String action = getStringParameter(parameters, "action", "start");
        if (!"start".equalsIgnoreCase(action)) {
            return ValidationResult.valid();
        }

        Object propertiesValue = parameters.get("properties");
        if (propertiesValue == null) {
            return ValidationResult.valid();
        }
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return ValidationResult.invalid("Parameter 'properties' must be an object");
        }

        ValidationResult.Builder builder = ValidationResult.builder();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank()) {
                builder.addError("Property names in 'properties' must be non-blank strings");
                continue;
            }

            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                builder.addError("Property '" + name + "' must be a string, number, or boolean");
            }
        }
        return builder.build();
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        String action = getStringParameter(parameters, "action", "start");

        return switch (action.toLowerCase()) {
            case "start" -> doStart(parameters);
            case "stop" -> doStop();
            case "shutdown" -> doShutdown();
            default -> ToolResult.error("Unknown action: " + action + ". Use 'start', 'stop', or 'shutdown'.");
        };
    }

    private ToolResult doStart(Map<String, Object> parameters) {
        Map<String, String> runProperties = normalizeRunProperties(
                (Map<?, ?>) parameters.get("properties"));

        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return ToolResult.error("JMeter GUI is not available");
        }

        // Check if test is already running
        if (JMeterContextService.getTestStartTime() > 0) {
            return ToolResult.error("A test is already running. Use action 'stop' or 'shutdown' to stop it first.");
        }

        // Check test plan has thread groups
        JMeterTreeNode root = (JMeterTreeNode) gui.getTreeModel().getRoot();
        if (!hasThreadGroups(root)) {
            return ToolResult.error("Test plan has no thread groups. Add at least one thread group before running.");
        }

        boolean ignoreTimers = getBooleanParameter(parameters, "ignore_timers", false);

        // Reset and inject collector (tag this run as Agent-initiated)
        AgentResultCollector.reset(AgentResultCollector.RunProvenance.AGENT);
        AgentResultCollector collector = new AgentResultCollector();

        // Execute tree injection and start on EDT
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            try {
                ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(gui.getClass().getClassLoader());

                    // Find TestPlan node (first child of root)
                    JMeterTreeNode testPlanNode = findTestPlanNode(root);
                    if (testPlanNode == null) {
                        errorRef.set(new RuntimeException("Could not find TestPlan node in tree"));
                        return;
                    }

                    // Remove any leftover collector from a previous run
                    AgentResultCollector.removeFromGuiTree();

                    // Inject collector into the tree
                    gui.getTreeModel().addComponent(collector, testPlanNode);
                    log.info("Injected AgentResultCollector into test plan tree");

                    runProperties.forEach(JMeterUtils::setProperty);
                    if (!runProperties.isEmpty()) {
                        log.info("Injected {} JMeter properties before test start", runProperties.size());
                    }

                    // Trigger start via ActionRouter
                    String actionName = ignoreTimers
                            ? ActionNames.ACTION_START_NO_TIMERS
                            : ActionNames.ACTION_START;
                    // Arm re-injection: Start.doAction may synchronously fire SAVE
                    // (popupShouldSave) which strips our collector for a clean .jmx; the Save
                    // POST-listener restores it before startEngine clones the tree.
                    AgentResultCollector.armForStartReinject();
                    ActionRouter.getInstance().actionPerformed(
                            new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionName));
                    log.info("Triggered test start action: {}", actionName);

                } finally {
                    Thread.currentThread().setContextClassLoader(originalCl);
                }
            } catch (Exception e) {
                errorRef.set(e);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                return ToolResult.error("Timed out waiting for test start on EDT");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while starting test");
        }

        if (errorRef.get() != null) {
            return ToolResult.error("Failed to start test: " + errorRef.get().getMessage());
        }

        // Wait for engine to confirm start
        boolean started = false;
        for (int i = 0; i < 100; i++) {
            if (JMeterContextService.getTestStartTime() > 0) {
                started = true;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!started) {
            return ToolResult.error("Test did not start within expected time. Check JMeter logs for errors.");
        }

        int totalThreads = JMeterContextService.getTotalThreads();
        return ToolResult.success(String.format(
                "Test started successfully.\n- Total threads: %d\n- Ignore timers: %b\n- Injected properties: %d\n" +
                "Use get_test_status to monitor progress and get_test_results to view results.",
                totalThreads, ignoreTimers, runProperties.size()));
    }

    static Map<String, String> normalizeRunProperties(Map<?, ?> properties) {
        if (properties == null) {
            return Map.of();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        properties.forEach((name, value) -> normalized.put((String) name, value.toString()));
        return normalized;
    }

    private ToolResult doStop() {
        if (JMeterContextService.getTestStartTime() == 0) {
            return ToolResult.error("No test is currently running.");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_STOP));
                log.info("Triggered test stop action");
            } catch (Exception e) {
                log.error("Failed to stop test", e);
            }
        });

        return ToolResult.success("Stop command sent. The test will be stopped immediately.");
    }

    private ToolResult doShutdown() {
        if (JMeterContextService.getTestStartTime() == 0) {
            return ToolResult.error("No test is currently running.");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.ACTION_SHUTDOWN));
                log.info("Triggered test shutdown action");
            } catch (Exception e) {
                log.error("Failed to shutdown test", e);
            }
        });

        return ToolResult.success("Shutdown command sent. The test will stop after current samples complete.");
    }

    private JMeterTreeNode findTestPlanNode(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof org.apache.jmeter.testelement.TestPlan) {
                return child;
            }
        }
        return null;
    }

    private boolean hasThreadGroups(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (hasThreadGroupsRecursive(child)) return true;
        }
        return false;
    }

    private boolean hasThreadGroupsRecursive(JMeterTreeNode node) {
        if (node.getTestElement() instanceof org.apache.jmeter.threads.AbstractThreadGroup) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasThreadGroupsRecursive((JMeterTreeNode) node.getChildAt(i))) return true;
        }
        return false;
    }

    @Override
    public long getTimeoutMs() {
        return 15000; // 15s — needs time for EDT dispatch and engine confirmation
    }
}
