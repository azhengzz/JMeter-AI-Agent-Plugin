package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code open_jmx_file} 的错误契约回归(实施评审 P3-1):EDT 等待异常的自含契约文案
 * 不得在 rootMessage 的「剥一层 cause」里丢失。复现形态:中断路径返回
 * {@code IllegalStateException(契约文案, InterruptedException)}——cause 的 message
 * 为 null,旧实现取到 null,拼出 "Failed to load JMX file: null",UNKNOWN/复查指引
 * 全部对模型不可见。
 */
class OpenJmxFileToolRootMessageTest {

    private static final String INTERRUPT_CONTRACT =
            "Aborted while waiting for the JMeter GUI event thread (caller thread interrupted, e.g. Stop). "
                    + "Outcome UNKNOWN: the operation may still run on the EDT afterwards — "
                    + "verify with get_test_plan_tree before any further tree change.";

    @Test
    void interruptPathContractSurvivesRootMessage() {
        String msg = OpenJmxFileTool.rootMessage(
                new IllegalStateException(INTERRUPT_CONTRACT, new InterruptedException()));
        assertEquals(INTERRUPT_CONTRACT, msg,
                "cause(message=null) must fall back to the outer contract message, not render as null");
    }

    @Test
    void timeoutContractHasNoCause_passesThrough() {
        String msg = OpenJmxFileTool.rootMessage(new EdtRunner.EdtWaitTimeoutException(5_000));
        assertTrue(msg.contains("UNKNOWN") && msg.contains("get_test_plan_tree"),
                "no-cause timeout contract must pass through untouched");
    }

    @Test
    void actionRootCauseStillPreferred() {
        // holder[1] 路径(动作内异常)仍优先取真实根因——null 回退不改变该形态
        String msg = OpenJmxFileTool.rootMessage(new RuntimeException(new IllegalStateException("real root")));
        assertEquals("real root", msg);
    }
}
