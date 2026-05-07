package org.qainsights.jmeter.ai.agent.tools.jmeter.execution;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.JMeterContextService;
import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
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
                        }
                    }
                }
                """;
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

        // Reset and inject collector
        AgentResultCollector.reset();
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

                    // Trigger start via ActionRouter
                    String actionName = ignoreTimers
                            ? ActionNames.ACTION_START_NO_TIMERS
                            : ActionNames.ACTION_START;
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
        for (int i = 0; i < 30; i++) {
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
                "Test started successfully.\n- Total threads: %d\n- Ignore timers: %b\n" +
                "Use get_test_status to monitor progress and get_test_results to view results.",
                totalThreads, ignoreTimers));
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
