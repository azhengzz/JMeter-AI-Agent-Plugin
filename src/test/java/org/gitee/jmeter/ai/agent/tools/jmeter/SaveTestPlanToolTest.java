package org.gitee.jmeter.ai.agent.tools.jmeter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * save_test_plan 的保护规则、返回形态与失败回传。JMeter 的 Save 派发经
 * {@link PlanSaver} 的注入点换成假实现——真写盘路径需要完整 GUI 启动
 * （ActionRouter 的命令表只由 JMeter.startGui 填充），由真 GUI 手工用例覆盖。
 */
class SaveTestPlanToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private GuiPackage gui;
    private Path plans;
    private Path autosave;
    private FakeDispatch dispatch;
    private PlanSaver saver;

    /** 假派发：记录目标路径；可编程「写文件并正常返回」「派发失败」「什么都没做」。 */
    private static final class FakeDispatch implements PlanSaver.SaveDispatch {
        final List<Path> targets = new CopyOnWriteArrayList<>();
        boolean fail;
        boolean silent;

        @Override
        public Exception dispatch(GuiPackage gui, Path target) {
            targets.add(target);
            if (fail) {
                return new IllegalStateException("boom");
            }
            if (silent) {
                return null;
            }
            try {
                Files.writeString(target, "<testplan/>");
            } catch (IOException e) {
                return e;
            }
            return null;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        gui = mock(GuiPackage.class);
        plans = Files.createDirectories(tmp.resolve("plans"));
        autosave = Files.createDirectories(tmp.resolve("autosave"));
        dispatch = new FakeDispatch();
        saver = new PlanSaver(dispatch, () -> autosave);
    }

    // ---- 保护规则：拒绝路径不派发、不写盘 ----

    @Test
    void guiUnavailableIsReported() {
        try (MockedStatic<GuiPackage> staticGui = mockStatic(GuiPackage.class)) {
            ToolResult result = new SaveTestPlanTool().execute(Map.of());
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("GUI is not available"), result.getError());
        }
    }

    @Test
    void neverSavedPlanWithoutFilePathIsRejectedWithoutDispatch() {
        when(gui.getTestPlanFile()).thenReturn(null);
        ToolResult result = save(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("never been saved"), result.getError());
        assertTrue(result.getError().contains("filePath"), result.getError());
        assertTrue(dispatch.targets.isEmpty(), "no save may be dispatched");
    }

    @Test
    void relativeFilePathIsRejected() {
        ToolResult result = save(Map.of("filePath", "plans/login.jmx"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("absolute path"), result.getError());
        assertTrue(dispatch.targets.isEmpty());
    }

    @Test
    void nonJmxFilePathIsRejected() {
        Path target = plans.resolve("login.txt");
        ToolResult result = save(Map.of("filePath", target.toString()));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("must end with .jmx"), result.getError());
        assertFalse(Files.exists(target), "no file may be created");
        assertTrue(dispatch.targets.isEmpty());
    }

    @Test
    void missingParentDirectoryIsRejected() {
        Path missing = plans.resolve("nope");
        ToolResult result = save(Map.of("filePath", missing.resolve("login.jmx").toString()));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("does not exist"), result.getError());
        assertFalse(Files.exists(missing), "no directory may be created");
        assertTrue(dispatch.targets.isEmpty());
    }

    @Test
    void existingTargetWithoutOverwriteIsRejectedAndUntouched() throws IOException {
        Path existing = Files.writeString(plans.resolve("login.jmx"), "OLD");
        // 目标恰为当前关联文件：传了 filePath 就按另存处理，同样要 overwrite
        when(gui.getTestPlanFile()).thenReturn(existing.toString());
        ToolResult result = save(Map.of("filePath", existing.toString()));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("overwrite: true"), result.getError());
        assertEquals("OLD", Files.readString(existing));
        assertTrue(dispatch.targets.isEmpty());
    }

    @Test
    void existingTargetWithOverwriteIsSavedAs() throws IOException {
        Path existing = Files.writeString(plans.resolve("login.jmx"), "OLD");
        when(gui.getTestPlanFile()).thenReturn(plans.resolve("other.jmx").toString());
        ToolResult result = save(Map.of("filePath", existing.toString(), "overwrite", true));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals("save_as", field(result, "mode"));
        assertEquals("<testplan/>", Files.readString(existing));
        assertEquals(List.of(existing), dispatch.targets);
    }

    @Test
    void associationUnderAutosaveWithoutFilePathIsRejected() throws IOException {
        Path snapshot = Files.writeString(
                autosave.resolve("20261012-153012-345-12345-678912345-17-local.jmx"), "SNAPSHOT");
        when(gui.getTestPlanFile()).thenReturn(snapshot.toString());
        ToolResult result = save(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("autosave snapshot"), result.getError());
        assertTrue(result.getError().contains("filePath"), result.getError());
        assertEquals("SNAPSHOT", Files.readString(snapshot), "the snapshot must stay untouched");
        assertTrue(dispatch.targets.isEmpty());
    }

    @Test
    void associationUnderAutosaveWithExplicitFilePathIsAllowed() throws IOException {
        Path snapshot = Files.writeString(
                autosave.resolve("20261012-153012-345-12345-678912345-17-local.jmx"), "SNAPSHOT");
        Path real = plans.resolve("real.jmx");
        when(gui.getTestPlanFile()).thenReturn(snapshot.toString());
        ToolResult result = save(Map.of("filePath", real.toString()));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals("save_as", field(result, "mode"));
        assertEquals("SNAPSHOT", Files.readString(snapshot));
    }

    @Test
    void directoryTargetIsRejectedBeforeDispatch() throws IOException {
        Path dir = Files.createDirectory(plans.resolve("dir.jmx"));
        ToolResult result = save(Map.of("filePath", dir.toString(), "overwrite", true));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("directory"), result.getError());
        assertTrue(dispatch.targets.isEmpty());
    }

    @Test
    void readOnlyAssociatedFileIsRejectedBeforeDispatch() throws IOException {
        Path login = Files.writeString(plans.resolve("login.jmx"), "OLD");
        assertTrue(login.toFile().setWritable(false));
        assumeTrue(!Files.isWritable(login), "platform does not honor the read-only flag");
        when(gui.getTestPlanFile()).thenReturn(login.toString());
        try {
            ToolResult result = save(Map.of());
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("read-only"), result.getError());
            assertEquals("OLD", Files.readString(login));
            assertTrue(dispatch.targets.isEmpty());
        } finally {
            login.toFile().setWritable(true);
        }
    }

    // ---- 成功路径的返回形态 ----

    @Test
    void associatedPlanIsSavedBackWithModeSave() throws IOException {
        Path login = Files.writeString(plans.resolve("login.jmx"), "OLD");
        when(gui.getTestPlanFile()).thenReturn(login.toString());
        ToolResult result = save(Map.of());
        assertTrue(result.isSuccess(), result.getError());
        JsonNode json = JSON.readTree(result.getContent());
        assertEquals(login.toString(), json.get("filePath").asText());
        assertEquals("login.jmx", json.get("fileName").asText());
        assertEquals("save", json.get("mode").asText());
        assertEquals(Files.size(login), json.get("bytes").asLong());
        assertEquals("<testplan/>", Files.readString(login));
        assertEquals(List.of(login), dispatch.targets);
    }

    @Test
    void saveAsWritesOnlyTheNewPath() throws IOException {
        Path login = Files.writeString(plans.resolve("login.jmx"), "OLD");
        Path v2 = plans.resolve("login-v2.jmx");
        when(gui.getTestPlanFile()).thenReturn(login.toString());
        ToolResult result = save(Map.of("filePath", v2.toString()));
        assertTrue(result.isSuccess(), result.getError());
        JsonNode json = JSON.readTree(result.getContent());
        assertEquals(v2.toString(), json.get("filePath").asText());
        assertEquals("save_as", json.get("mode").asText());
        assertEquals("OLD", Files.readString(login), "the previous file stays untouched");
    }

    // ---- 失败如实回传：不以「已派发」当成功 ----

    @Test
    void unfinishedSaveActionIsReportedAsFailure() throws IOException {
        Path login = Files.writeString(plans.resolve("login.jmx"), "OLD");
        when(gui.getTestPlanFile()).thenReturn(login.toString());
        dispatch.fail = true;
        ToolResult result = save(Map.of());
        assertFalse(result.isSuccess());
        assertNull(result.getContent(), "a failed save must not carry a success payload");
        assertTrue(result.getError().contains(login.toString()), result.getError());
        assertEquals("OLD", Files.readString(login));
    }

    @Test
    void missingTargetAfterDispatchIsReportedAsFailure() {
        // 派发返回「正常」却没写出文件：存在性复核兜底，不得报成功
        when(gui.getTestPlanFile()).thenReturn(plans.resolve("login.jmx").toString());
        dispatch.silent = true;
        ToolResult result = save(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not a regular file"), result.getError());
    }

    // ---- 调用纪律由工具描述承载 ----

    @Test
    void descriptionCarriesCallingDiscipline() {
        SaveTestPlanTool tool = new SaveTestPlanTool();
        assertEquals("save_test_plan", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("only when the user explicitly asks"), description);
        assertTrue(description.contains("call this tool yourself BEFORE update_goal(complete)"),
                "description must tell the model to save itself before completing a goal");
        String schema = tool.getParameterSchema();
        assertTrue(schema.contains("\"filePath\""));
        assertTrue(schema.contains("\"overwrite\""));
    }

    // ---- helpers ----

    private ToolResult save(Map<String, Object> params) {
        try (MockedStatic<GuiPackage> staticGui = mockStatic(GuiPackage.class)) {
            staticGui.when(GuiPackage::getInstance).thenReturn(gui);
            return new SaveTestPlanTool(saver).execute(params);
        }
    }

    private static String field(ToolResult result, String name) throws IOException {
        return JSON.readTree(result.getContent()).get(name).asText();
    }
}
