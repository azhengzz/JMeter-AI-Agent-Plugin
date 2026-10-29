package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.recorder.RecorderEngine;
import org.gitee.jmeter.ai.recorder.RecorderRuntime;

import java.util.function.Supplier;

/**
 * 录制工具基类：默认与 GUI 按钮共用 {@link RecorderRuntime} 的同一引擎（单会话互斥、
 * 工具可接管既有会话）；单测注入 fake 供应商。
 */
abstract class AbstractRecorderTool extends AbstractTool {
    private final Supplier<RecorderEngine> engineSupplier;

    AbstractRecorderTool() {
        this(() -> RecorderRuntime.get().engine());
    }

    AbstractRecorderTool(Supplier<RecorderEngine> engineSupplier) {
        this.engineSupplier = engineSupplier;
    }

    protected RecorderEngine engine() {
        return engineSupplier.get();
    }
}
