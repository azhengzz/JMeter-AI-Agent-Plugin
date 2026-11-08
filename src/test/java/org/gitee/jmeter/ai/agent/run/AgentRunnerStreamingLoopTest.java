package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.hooks.AgentHookContext;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * runner 级流式集成：流段事件与既有 hook 时序的关系（STREAM_ENDED 严格先于工具
 * hook）、重试环（可重试分类重试 + attemptSeq、不可重试即止、退避中 abort）、
 * 流中 Stop 的中止分类、流式闸门关闭零事件。
 */
class AgentRunnerStreamingLoopTest {

    /** 记录流段事件 + 既有 hook 事件的单一时序表（锁发射顺序）。 */
    private static final class RecordingStreamHook implements AgentHook {
        final List<String> events = new ArrayList<>();
        final boolean streaming;

        RecordingStreamHook(boolean streaming) {
            this.streaming = streaming;
        }

        @Override public boolean wantsStreaming() { return streaming; }

        @Override public void onStreamDelta(StreamDelta d, AgentHookContext ctx) {
            events.add(switch (d.getKind()) {
                case STREAM_STARTED -> "S(" + d.getStreamId() + "," + d.getAttemptSeq() + ")";
                case CONTENT_DELTA -> "C(" + d.getStreamId() + "," + d.getAttemptSeq() + ")=" + d.getContentText();
                case REASONING_DELTA -> "R(" + d.getStreamId() + "," + d.getAttemptSeq() + ")";
                case STREAM_ENDED -> "E(" + d.getStatus() + "," + d.getStreamId() + "," + d.getAttemptSeq() + ")";
            });
        }

        @Override public void beforeIteration(AgentHookContext ctx) { events.add("beforeIteration:" + ctx.getCurrentIteration()); }
        @Override public void beforeExecuteTools(List<ToolCall> t, AgentHookContext ctx) { events.add("beforeExecuteTools"); }
        @Override public void afterExecuteTools(List<ToolCall> t, AgentHookContext ctx) { events.add("afterExecuteTools"); }
        @Override public void afterIteration(AgentHookContext ctx) { events.add("afterIteration:" + ctx.getCurrentIteration()); }
        @Override public String finalizeContent(String content, AgentHookContext ctx) {
            events.add("finalize:" + content);
            return content;
        }
    }

    @Test
    void streamEventsOrderedBeforeToolHooksAndNextCallIndependent() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "t1"))
                .content("我要调工具");
        ai.script(LLMResponse.text("FINAL"));
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertEquals("FINAL", r.getContent());
        // 调用 1（流式工具轮）：S → C → E 严格先于 beforeExecuteTools；调用 2（无分片脚本）零流段事件
        int beforeTools = hook.events.indexOf("beforeExecuteTools");
        assertTrue(beforeTools > 0, "tool hook fired");
        int sIdx = hook.events.indexOf("S(1,1)");
        int eIdx = hook.events.indexOf("E(SUCCESS,1,1)");
        int cIdx = -1;
        for (int i = 0; i < hook.events.size(); i++) {
            if (hook.events.get(i).startsWith("C(1,1)=")) cIdx = i;
        }
        assertTrue(sIdx >= 0 && cIdx > sIdx && eIdx > cIdx && eIdx < beforeTools,
                "S→C→E 全部严格先于 beforeExecuteTools: " + hook.events);
        assertEquals("C(1,1)=我要调工具", hook.events.get(cIdx), "累计载荷（节流窗口合并后终值）");
        // 调用 2 无流段事件（非流式脚本回退）
        long secondCallStreamEvents = hook.events.stream()
                .filter(e -> e.matches("[SCE]\\(.*,2\\)|[SCE]\\(2,.*\\)")).count();
        assertEquals(0, secondCallStreamEvents);
    }

    @Test
    void retryableFailureRetriesWithSameStreamIdAndIncrementedAttempt() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.error("gateway 503", true, 503, "server"))
                .content("部分回答");
        ai.scriptStreamed(LLMResponse.text("RECOVERED")).content("RECOVERED");
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertEquals("RECOVERED", r.getContent());
        assertTrue(hook.events.contains("E(FAILED,1,1)"), "首次尝试失败终态先于重试: " + hook.events);
        assertTrue(hook.events.contains("E(SUCCESS,1,2)"), "重试成功：同 streamId=1、attemptSeq=2: " + hook.events);
        // attempt 2 屏外缓冲：无实时 delta，但流段协议完整（S6：STARTED(attemptSeq=2) → ENDED）
        assertTrue(hook.events.contains("S(1,2)"));
        assertFalse(hook.events.stream().anyMatch(e -> e.startsWith("C(1,2)")),
                "off-screen attempt delivers no live delta");
        // 顺序：失败终态 → 成功终态 → finalize
        int failedIdx = hook.events.indexOf("E(FAILED,1,1)");
        int successIdx = hook.events.indexOf("E(SUCCESS,1,2)");
        int finalizeIdx = -1;
        for (int i = 0; i < hook.events.size(); i++) {
            if (hook.events.get(i).startsWith("finalize:")) {
                finalizeIdx = i;
                break;
            }
        }
        assertTrue(failedIdx >= 0 && successIdx > failedIdx && finalizeIdx > successIdx);
    }

    @Test
    void nonRetryableErrorFailsFastWithoutRetry() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.script(LLMResponse.error("HTTP 401: bad key", false, 401, "auth"));
        // 若错误地重试，第二次 poll 会拿到 DEFAULT-FINAL——断言内容证明未重试
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertTrue(r.getContent().contains("401"), "error surfaced: " + r.getContent());
        assertFalse(r.getContent().contains("DEFAULT-FINAL"), "no second attempt");
        // 武装但零增量（无分片脚本=非流式回退）：不构成流段，零事件——
        // 面板不会误标"已流式"，THINKING 全文回放照常
        assertTrue(hook.events.stream().noneMatch(e -> e.matches("[SCE]\\(.*")),
                "zero-delta armed call emits no stream events: " + hook.events);
    }

    @Test
    void abortMidStreamClassifiesInterruptedAndEndsAborted() {
        GatedScriptAiService ai = new GatedScriptAiService();
        AtomicBoolean abortFlag = new AtomicBoolean(false);
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "never"))
                .content("第一片")
                .midFragment(() -> abortFlag.set(true))
                .content("第二片");
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec.abortFlag(abortFlag));

        assertFalse(r.isSuccess() && "never".equals(r.getContent()));
        assertTrue(hook.events.contains("E(ABORTED,1,1)"),
                "mid-stream abort → ABORTED 终态: " + hook.events);
        assertTrue(hook.events.stream().anyMatch(e -> e.startsWith("C(1,1)=第一片")),
                "中止前已发射的累计保留在事件流中");
        assertFalse(hook.events.contains("beforeExecuteTools"), "aborted before tool execution");
    }

    @Test
    void streamingGateOffYieldsZeroStreamEvents() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.text("PLAIN")).content("x");
        RecordingStreamHook hook = new RecordingStreamHook(false);   // 不请求流式

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertEquals("PLAIN", r.getContent());
        assertTrue(hook.events.stream().noneMatch(e -> e.matches("[SCE]\\(.*")),
                "闸门关闭（wantsStreaming=false）→ options 不挂消费者 → 零流段事件: " + hook.events);
    }

    @Test
    void abortDuringBackoffAbortsRetryImmediately() throws Exception {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.error("gateway 503", true, 503, "server"))
                .content("部分");
        ai.scriptStreamed(LLMResponse.text("RECOVERED")).content("RECOVERED");
        RecordingStreamHook hook = new RecordingStreamHook(true);
        AtomicBoolean abortFlag = new AtomicBoolean(false);
        // 退避（1s）期间置中止位：100ms « 1000ms，确定性余量充足
        Thread stopper = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            abortFlag.set(true);
        });
        stopper.start();

        AgentRunResult r = run(ai, hook, spec -> spec.abortFlag(abortFlag));

        // 退避内中止 → 回合按中止收尾（content null，错误分支的 503 文案被 abort 短路）
        assertNull(r.getContent(), "aborted turn closes without error text");
        assertFalse(r.getContent() != null && r.getContent().contains("RECOVERED"), "backoff aborted before attempt 2");
        assertTrue(hook.events.contains("E(FAILED,1,1)"));
        assertFalse(hook.events.contains("E(SUCCESS,1,2)"));
        stopper.join(2000);
    }

    @Test
    void allAttemptsFailWithFailedTerminalOnLastAttempt() {
        GatedScriptAiService ai = new GatedScriptAiService();
        // 尝试 1 流式（有部分内容）；尝试 2/3 走非流式回退（零增量，不构成流段）
        ai.scriptStreamed(LLMResponse.error("gateway 503", true, 503, "server")).content("部分");
        ai.script(LLMResponse.error("gateway 503", true, 503, "server"));
        ai.script(LLMResponse.error("gateway 503", true, 503, "server"));
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertTrue(r.getContent().contains("503"));
        assertTrue(hook.events.contains("E(FAILED,1,1)"), "streamed attempt ends FAILED: " + hook.events);
        assertFalse(hook.events.stream().anyMatch(e -> e.matches("E\\(.*,1,[23]\\)")),
                "fallback attempts (zero delta) produce no segment events");
        // 无第四次调用（DEFAULT-FINAL 未消费）
        assertFalse(r.getContent().contains("DEFAULT-FINAL"));
    }

    @Test
    void streamedFinalMarkerCarriesFinalStreamIdToTerminal() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "t1"))
                .content("工具轮");
        ai.scriptStreamed(LLMResponse.text("FINAL")).content("FINAL");
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        var terminal = r.toAgentResponse();
        assertTrue(terminal.isStreamedFinal(), "final answer streamed → marker set");
        assertEquals(2L, terminal.getFinalStreamId(), "final segment = last logical call");
    }

    @Test
    void nonStreamedFinalCarriesNoMarker() {
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "t1"))
                .content("工具轮");
        ai.script(LLMResponse.text("FALLBACK-FINAL"));
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        var terminal = r.toAgentResponse();
        assertEquals("FALLBACK-FINAL", terminal.getContent());
        assertFalse(terminal.isStreamedFinal(),
                "final call fell back non-streaming → no marker → panel renders via legacy path");
    }

    @Test
    void stickyMarkerSurvivesNonStreamingRetrySuccess() {
        GatedScriptAiService ai = new GatedScriptAiService();
        // 尝试 1 流式失败（已实时交付增量）；重试经非流式回退成功（provider streamed=false）
        ai.scriptStreamed(LLMResponse.error("gateway 503", true, 503, "server")).content("部分");
        ai.script(LLMResponse.text("RECOVERED-VIA-FALLBACK"));
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertEquals("RECOVERED-VIA-FALLBACK", r.getContent());
        var terminal = r.toAgentResponse();
        assertTrue(terminal.isStreamedFinal(),
                "粘滞标记跨尝试/回退保留：已交付增量的调用即使终答来自回退路径也带标记（无双渲染窗口）");
    }

    @Test
    void errorFinalAfterEarlierStreamedToolRoundDoesNotCarryStreamedFinal() {
        // S8 复现：首轮流式工具轮后，第二轮 401 错误——错误终答不得继承上一轮的
        // streamedFinal（面板会把错误文案当终答原位覆盖先前卡片）
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "FIRST"))
                .content("FIRST");
        ai.script(LLMResponse.error("HTTP 401: bad key", false, 401, "auth"));
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec);

        assertTrue(r.getContent().contains("401"));
        var terminal = r.toAgentResponse();
        assertFalse(terminal.isStreamedFinal(),
                "error terminal must not inherit previous streamed round's marker");
        assertFalse(terminal.getFinalStreamId() != null && terminal.getFinalStreamId() == 1L,
                "stale finalStreamId must not ride along");
    }

    @Test
    void toolErrorFinalDoesNotInheritStreamedFinal() throws Exception {
        // R9 复现：流式工具轮（已发射增量）后工具致命错误（failOnToolError）——
        // 错误终答不得继承 streamedFinal（面板会把错误文案当终答原位覆盖流式卡）
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("failing_tool", Map.of())), "工具轮"))
                .content("工具轮");
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec.failOnToolError(true));

        assertTrue(r.getContent().contains("Tool execution failed"), "tool error surfaced");
        var terminal = r.toAgentResponse();
        assertFalse(terminal.isStreamedFinal(),
                "tool-error terminal must not inherit the streamed round's marker");
    }

    @Test
    void maxIterationsPlaceholderDoesNotInheritStreamedFinal() throws Exception {
        // R10 复现：迭代耗尽的占位终答不是流式终答——不继承 streamedFinal
        GatedScriptAiService ai = new GatedScriptAiService();
        ai.scriptStreamed(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "工具轮"))
                .content("工具轮");
        RecordingStreamHook hook = new RecordingStreamHook(true);

        AgentRunResult r = run(ai, hook, spec -> spec.maxIterations(1));

        assertTrue(r.getContent().contains("maximum number of tool call iterations"),
                "placeholder text: " + r.getContent());
        var terminal = r.toAgentResponse();
        assertFalse(terminal.isStreamedFinal(),
                "max-iterations placeholder must not inherit the streamed round's marker");
    }

    /** 立即失败的固定工具（failOnToolError 路径脚手架）。 */
    private static final class FailingTool implements org.gitee.jmeter.ai.agent.tools.Tool {
        @Override public String getName() { return "failing_tool"; }
        @Override public String getDescription() { return "always fails"; }
        @Override public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        @Override public org.gitee.jmeter.ai.agent.model.ToolResult execute(Map<String, Object> parameters) {
            return org.gitee.jmeter.ai.agent.model.ToolResult.error("boom");
        }
    }

    private AgentRunResult run(GatedScriptAiService ai, AgentHook hook,
            Function<AgentRunSpec.Builder, AgentRunSpec.Builder> tweak) {
        try {
            Path workspace = Files.createTempDirectory("streamloop-test-ws");
            workspace.toFile().deleteOnExit();
            ToolRegistry tools = new ToolRegistry();
            tools.register(new NoopTool());
            tools.register(new FailingTool());
            AgentRunner runner = new AgentRunner(
                tools, null,
                new ContextBuilder(new MemoryStore(workspace), workspace),
                null, ai, 40, 16000, 30000);

            return runner.run(tweak.apply(AgentRunSpec.builder()
                .sessionKey("streamloop-test")
                .initialMessages(List.of(Message.user("hi")))
                .persistSession(false)
                .hook(hook)).build());
        } catch (Exception e) {
            throw new IllegalStateException("test setup failed", e);
        }
    }
}
