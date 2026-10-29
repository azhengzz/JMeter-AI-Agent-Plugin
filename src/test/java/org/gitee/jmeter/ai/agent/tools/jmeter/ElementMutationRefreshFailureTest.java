package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestPlan;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElementMutationRefreshFailureTest {

    @Test
    void updateAppliedBeforeRefreshFailureReturnsWarning() {
        GuiPackage gui = mock(GuiPackage.class);
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode target = firstController(model);
        TestPlan selected = (TestPlan) planNode(model).getTestElement();
        selected.setUserDefinedVariables(new Arguments());
        mockCurrentNode(gui, model, planNode(model));
        when(gui.getTreeModel()).thenReturn(model);
        doThrow(new NullPointerException("simulated GUI refresh failure"))
                .when(gui).refreshCurrentGui();

        ToolResult result;
        try (MockedStatic<GuiPackage> staticGui = mockStatic(GuiPackage.class)) {
            staticGui.when(GuiPackage::getInstance).thenReturn(gui);
            result = new UpdateJMeterElementTool().execute(Map.of(
                    "elementId", System.identityHashCode(target),
                    "properties", Map.of("name", "renamed")));
        }

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("GUI refresh failed"));
        assertEquals("renamed", target.getName());
    }

    @Test
    void deleteAppliedBeforeRefreshFailureReturnsWarning() {
        GuiPackage gui = mock(GuiPackage.class);
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode target = firstController(model);
        mockCurrentNode(gui, model, planNode(model));
        when(gui.getTreeModel()).thenReturn(model);
        doThrow(new NullPointerException("simulated GUI refresh failure"))
                .when(gui).refreshCurrentGui();

        ToolResult result;
        try (MockedStatic<GuiPackage> staticGui = mockStatic(GuiPackage.class)) {
            staticGui.when(GuiPackage::getInstance).thenReturn(gui);
            result = new DeleteJMeterElementTool().execute(Map.of(
                    "elementId", System.identityHashCode(target)));
        }

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().contains("GUI refresh failed"));
        assertNull(target.getParent());
        verify(gui).removeNode(target.getTestElement());
    }

    @Test
    void updateModelFailureStillReturnsError() {
        GuiPackage gui = mock(GuiPackage.class);
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode target = firstController(model);
        when(gui.getTreeModel()).thenReturn(model);

        ToolResult result;
        try (MockedStatic<GuiPackage> staticGui = mockStatic(GuiPackage.class)) {
            staticGui.when(GuiPackage::getInstance).thenReturn(gui);
            result = new UpdateJMeterElementTool().execute(Map.of(
                    "elementId", System.identityHashCode(target),
                    "properties", Map.of("ThreadGroup.main_controller", "invalid scalar")));
        }

        assertFalse(result.isSuccess(), result::getResult);
        assertTrue(result.getError().contains("No properties were modified"), result::getError);
        verify(gui, never()).refreshCurrentGui();
    }

    private static JMeterTreeNode planNode(JMeterTreeModel model) {
        return (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
    }

    private static JMeterTreeNode firstController(JMeterTreeModel model) {
        org.apache.jmeter.threads.ThreadGroup element = new org.apache.jmeter.threads.ThreadGroup();
        element.setName("target");
        element.setSamplerController(new org.apache.jmeter.control.LoopController());
        JMeterTreeNode threadGroup = new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), model);
        planNode(model).add(threadGroup);
        JMeterTreeNode target = new JMeterTreeNode(element, model);
        threadGroup.add(target);
        return target;
    }

    private static void mockCurrentNode(GuiPackage gui, JMeterTreeModel model, JMeterTreeNode selected) {
        JMeterTreeListener listener = new JMeterTreeListener(model);
        listener.setJTree(new javax.swing.JTree(model));
        listener.getJTree().setSelectionPath(new javax.swing.tree.TreePath(selected.getPath()));
        when(gui.getTreeListener()).thenReturn(listener);
    }
}
