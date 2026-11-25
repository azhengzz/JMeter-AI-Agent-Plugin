package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Save;
import org.gitee.jmeter.ai.agent.snapshot.SnapshotNames;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 测试计划落盘内核：{@code save_test_plan} 工具的唯一执行路径。goal 完成时由模型按指引
 * 调用同一工具保存，保护规则与派发路径对所有调用方一致。
 *
 * <p>写盘复用 JMeter 自带的 Save 动作（经 ActionRouter 派发），与用户按 Ctrl+S 等价：
 * JMeter 的保存备份、未保存状态清除、结果收集器节点剥离（Save 预监听）、实例端口文件
 * 的 jmxPath 同步（Save 后监听）全部一并生效。直写 {@code SaveService.saveTree} 需要
 * 手工复刻这些簿记，漏一项就是「存了但关窗仍问要不要保存」。
 *
 * <p>所有拒绝路径不弹窗：JMeter 在关联文件为空时弹文件选择框、在写盘失败时只弹错误框
 * 且不向调用方回传异常——自主回合无人应答，弹窗即卡死 EDT。
 */
public final class PlanSaver {

    private static final Logger log = LoggerFactory.getLogger(PlanSaver.class);

    static final String NEVER_SAVED = "This plan has never been saved, so there is no associated file "
            + "to write back to. Ask the user where to save it, then call save_test_plan again with that filePath.";

    static final String AUTOSAVE_NEXT_STEP = "A plain save would overwrite rollback material. "
            + "Save it with an explicit filePath at a proper location instead "
            + "(add overwrite: true if that file already exists).";

    private static final String SAVE_ACTION_FAILED = "JMeter's Save action did not complete. "
            + "It reports write failures only in a dialog (check jmeter.log). Free the file and retry.";

    /** 在 EDT 上执行 JMeter Save 派发；返回 null 表示 Save 动作正常返回，否则为失败原因。 */
    @FunctionalInterface
    interface SaveDispatch {
        Exception dispatch(GuiPackage gui, Path target);
    }

    private final SaveDispatch dispatch;
    private final Supplier<Path> autosaveDir;

    public PlanSaver() {
        this(PlanSaver::dispatchJMeterSave, PlanSaver::defaultAutosaveDir);
    }

    /** 包内可见：注入派发与 autosave 目录供单测。 */
    PlanSaver(SaveDispatch dispatch, Supplier<Path> autosaveDir) {
        this.dispatch = dispatch;
        this.autosaveDir = autosaveDir;
    }

    /** 存回关联文件（goal 完成存回与不传 filePath 的工具调用共用）。 */
    public SaveOutcome saveAssociated() {
        return save(null, false);
    }

    /**
     * 不传 filePath = 存回关联文件；传 filePath = 另存到该路径并改关联。
     * 拒绝与失败都以 {@link SaveOutcome#failure()} 返回，不抛异常。
     */
    public SaveOutcome save(String filePath, boolean overwrite) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return SaveOutcome.failed(null, "The JMeter GUI is not available.");
        }

        // 显式 filePath 一律按另存为处理；形态校验与覆盖保护是纯文件系统操作，在 worker 线程完成
        Path requested = null;
        if (filePath != null && !filePath.isBlank()) {
            String raw = filePath.trim();
            try {
                requested = Paths.get(raw);
            } catch (InvalidPathException e) {
                return SaveOutcome.failed(raw, "filePath is not a valid path: '" + raw + "'.");
            }
            if (!requested.isAbsolute()) {
                return SaveOutcome.failed(raw, "filePath must be an absolute path: '" + raw + "'.");
            }
            if (!raw.toLowerCase(Locale.ROOT).endsWith(".jmx")) {
                return SaveOutcome.failed(raw, "filePath must end with .jmx: '" + raw + "'.");
            }
            Path parent = requested.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                return SaveOutcome.failed(raw, "The directory of filePath does not exist: '" + parent + "'. "
                        + "save_test_plan does not create directories.");
            }
            // 目标已存在就要显式 overwrite——目标恰为当前关联文件时也不豁免（一条规则，零特例）
            if (Files.exists(requested) && !overwrite) {
                return SaveOutcome.failed(raw, "File already exists: '" + requested + "'. "
                        + "Pass overwrite: true to replace it, or choose another filePath.");
            }
            String notWritable = writabilityProblem(requested);
            if (notWritable != null) {
                return SaveOutcome.failed(raw, "Cannot write to '" + requested + "': " + notWritable + ".");
            }
        }

        final Path saveAsTarget = requested;
        final boolean saveAs = saveAsTarget != null;
        final SaveOutcome[] rejection = new SaveOutcome[1];
        final Path[] effectiveTarget = new Path[]{saveAsTarget};
        final Exception[] failure = new Exception[1];

        Exception edtError = EdtRunner.run(gui, () -> {
            Path target = saveAsTarget;
            if (!saveAs) {
                // 无 filePath：关联文件与 autosave 保护必须在 EDT 上读权威值后再判定
                String associated = gui.getTestPlanFile();
                if (associated == null || associated.isBlank()) {
                    rejection[0] = SaveOutcome.failed(null, NEVER_SAVED);
                    return;
                }
                target = Paths.get(associated);
                effectiveTarget[0] = target;
                if (isUnderAutosave(target)) {
                    // 打开回合快照或运行自动存档后关联即指向 autosave：无参保存会覆写回滚
                    // 基线，也会让用户以为存好了而真实文件没更新
                    rejection[0] = SaveOutcome.failed(associated,
                            "The plan is associated with an autosave snapshot: '" + associated + "'. "
                                    + AUTOSAVE_NEXT_STEP);
                    return;
                }
                String notWritable = writabilityProblem(target);
                if (notWritable != null) {
                    rejection[0] = SaveOutcome.failed(associated,
                            "Cannot write to '" + associated + "': " + notWritable + ".");
                    return;
                }
            }
            failure[0] = dispatch.dispatch(gui, target);
        });

        if (rejection[0] != null) {
            return rejection[0];
        }
        String targetPath = String.valueOf(effectiveTarget[0]);
        if (edtError != null) {
            // EdtRunner 等待超时/中断的自含契约文案直接透传
            return SaveOutcome.failed(targetPath, "Failed to save the test plan: " + edtError.getMessage());
        }
        if (failure[0] != null) {
            return SaveOutcome.failed(targetPath,
                    "Failed to save the test plan to '" + targetPath + "': " + failure[0].getMessage());
        }
        try {
            if (!Files.isRegularFile(effectiveTarget[0])) {
                return SaveOutcome.failed(targetPath,
                        "JMeter reported a completed save, but '" + targetPath + "' is not a regular file.");
            }
            long bytes = Files.size(effectiveTarget[0]);
            log.info("Test plan saved ({}): {} ({} bytes)", saveAs ? "save as" : "save", targetPath, bytes);
            return SaveOutcome.saved(targetPath, bytes, saveAs);
        } catch (IOException e) {
            return SaveOutcome.failed(targetPath, "The test plan was saved to '" + targetPath
                    + "', but its size could not be read: " + e.getMessage());
        }
    }

    /**
     * 经 ActionRouter 派发 JMeter 的 Save 动作（须在 EDT 上调用）。
     *
     * @return null 表示 Save 动作正常返回；否则为失败原因
     */
    private static Exception dispatchJMeterSave(GuiPackage gui, Path target) {
        String targetPath = target.toString();
        if (!targetPath.equals(gui.getTestPlanFile())) {
            // 另存为免弹窗的唯一做法：先把关联指向目标再派发。Save.doAction 只在关联为空时
            // 才弹文件选择框——自主回合无人应答，弹窗即卡死 EDT
            gui.setTestPlanFile(targetPath);
        }
        AtomicBoolean completed = new AtomicBoolean();
        ActionListener probe = e -> completed.set(true);
        ActionRouter router = ActionRouter.getInstance();
        router.addPostActionListener(Save.class, probe);
        try {
            // doActionNow 而非 actionPerformed：后者只往 EDT 排队，派发返回时保存尚未发生
            router.doActionNow(new ActionEvent(PlanSaver.class, ActionEvent.ACTION_PERFORMED, ActionNames.SAVE));
        } finally {
            router.removePostActionListener(Save.class, probe);
        }
        if (!completed.get()) {
            // ActionRouter 吞掉 Command 抛出的异常（IllegalUserActionException 只弹错误框），
            // 调用方拿不到失败信号；而后置监听只在 doAction 正常返回后执行——未置位即写盘失败
            return new IllegalStateException(SAVE_ACTION_FAILED);
        }
        return null;
    }

    /** 目标不可写的原因；可写返回 null。目标是目录直接拒。 */
    private static String writabilityProblem(Path file) {
        if (Files.isDirectory(file)) {
            return "it is a directory";
        }
        if (Files.exists(file)) {
            return Files.isWritable(file) ? null : "the file is read-only";
        }
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return "its directory does not exist";
        }
        return Files.isWritable(parent) ? null : "the directory is not writable";
    }

    private boolean isUnderAutosave(Path file) {
        Path dir = autosaveDir.get().toAbsolutePath().normalize();
        return file.toAbsolutePath().normalize().startsWith(dir);
    }

    private static Path defaultAutosaveDir() {
        return WorkspacePaths.resolveWorkspace().resolve(SnapshotNames.AUTOSAVE_DIR);
    }

    /** 一次保存的结果：成功带路径与字节数，失败带面向模型的原因（可写、含下一步指引）。 */
    public record SaveOutcome(boolean saved, String filePath, long bytes, boolean saveAs, String failure) {

        public static SaveOutcome saved(String filePath, long bytes, boolean saveAs) {
            return new SaveOutcome(true, filePath, bytes, saveAs, null);
        }

        public static SaveOutcome failed(String filePath, String failure) {
            return new SaveOutcome(false, filePath, 0L, false, failure);
        }

        /** 供模型阅读的一行回执（goal 完成存回拼入 update_goal 返回时使用）。 */
        public String receipt() {
            return saved
                    ? "Plan saved to " + filePath + " (" + bytes + " bytes). Relay this in your final reply."
                    : "Plan NOT saved: " + failure + " Relay this in your final reply.";
        }
    }
}
