package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.run.AgentRunContext;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.DelegationGuard;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * concurrency-safe 分批执行回归(concurrency-safe-tool-batching):
 * <ul>
 *   <li>连续安全调用合并为一个并行批(闭锁证明真并发);</li>
 *   <li>非安全调用切单例批内联串行,绝不与安全工具重叠(进入时 activeSafe==0);</li>
 *   <li>结果/事件按原始调用顺序返回(完成顺序被人为倒置后仍保持调用序);</li>
 *   <li>DelegationGuard 随 AgentRunContext 搬运:并行批内守卫可见,池化线程无残留。</li>
 * </ul>
 */
class ToolConcurrencyBatchingTest {

    /** 跨探针共享状态:并发计数、进入/退出时序、闭锁结果、守卫可见性。 */
    private static final class SharedState {
        final AtomicInteger activeSafe = new AtomicInteger();
        final List<String> timeline = Collections.synchronizedList(new ArrayList<>());
        volatile boolean barrierPassed = false;
        volatile boolean guardSeenDuringExecution = false;
        volatile int unsafeSawActiveSafe = -1;
    }

    /** 安全探针:可选结对闭锁(证明同批并行)+ 可选进入延时(倒置完成顺序)。 */
    private static final class SafeProbeTool implements Tool {
        private final String name;
        private final SharedState state;
        private final long postBarrierSleepMs;
        private final CyclicBarrier pairBarrier;

        SafeProbeTool(String name, SharedState state, long postBarrierSleepMs, CyclicBarrier pairBarrier) {
            this.name = name;
            this.state = state;
            this.postBarrierSleepMs = postBarrierSleepMs;
            this.pairBarrier = pairBarrier;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "safe probe " + name; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public boolean isConcurrencySafe() { return true; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            state.activeSafe.incrementAndGet();
            state.guardSeenDuringExecution = DelegationGuard.isActive();
            state.timeline.add("enter:" + name);
            try {
                if (pairBarrier != null) {
                    try {
                        pairBarrier.await(10, TimeUnit.SECONDS);
                        state.barrierPassed = true;
                    } catch (Exception e) {
                        return ToolResult.error("barrier broken: " + e);
                    }
                }
                if (postBarrierSleepMs > 0) {
                    Thread.sleep(postBarrierSleepMs);
                }
                return ToolResult.success(name + "-ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            } finally {
                state.activeSafe.decrementAndGet();
                state.timeline.add("exit:" + name);
            }
        }
    }

    /** 非安全探针:记录进入瞬间的安全工具并发数(必须为 0),并撑宽执行窗口。 */
    private static final class UnsafeProbeTool implements Tool {
        private final String name;
        private final SharedState state;

        UnsafeProbeTool(String name, SharedState state) {
            this.name = name;
            this.state = state;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "unsafe probe " + name; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            state.unsafeSawActiveSafe = state.activeSafe.get();
            state.timeline.add("enter:" + name);
            try {
                Thread.sleep(100);
                return ToolResult.success(name + "-ok");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted");
            } finally {
                state.timeline.add("exit:" + name);
            }
        }
    }

    /** 记录守卫/上下文可见性的安全探针(注册表层搬运测试)。 */
    private static final class ContextProbeTool implements Tool {
        final AtomicBoolean guardSeen = new AtomicBoolean(false);
        final AtomicReference<Object> runContextSeen = new AtomicReference<>(new Object()); // sentinel

        @Override public String getName() { return "context_probe"; }
        @Override public String getDescription() { return "records guard/context visibility"; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public boolean isConcurrencySafe() { return true; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            guardSeen.set(DelegationGuard.isActive());
            runContextSeen.set(AgentRunContext.current());
            return ToolResult.success("probed");
        }
    }

    /** 首次 LLM 调用发起给定工具调用,再次调用收尾;记录驱动回合的第二次调用消息。 */
    private static final class ScriptedAiService implements AiService {
        private final List<ToolCall> firstCalls;
        private final List<List<Message>> calls = Collections.synchronizedList(new ArrayList<>());
        private volatile List<Message> secondCallMessages;
        private final AtomicInteger invocations = new AtomicInteger();

        ScriptedAiService(List<ToolCall> firstCalls) {
            this.firstCalls = firstCalls;
        }

        @Override public String getName() { return "scripted"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            calls.add(messages);
            if (invocations.getAndIncrement() == 0) {
                return LLMResponse.withToolCalls(firstCalls, "");
            }
            if (secondCallMessages == null) {
                secondCallMessages = messages;
            }
            return LLMResponse.text("done");
        }
    }

    private static AgentLoop newLoop(AiService ai, ToolRegistry registry) throws Exception {
        Path workspace = Files.createTempDirectory("tool-batching-test");
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace);
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory,
            new MemoryConsolidator(memory, ai, sessions, context, registry),
            context, sessions, ai);
    }

    private static List<String> toolResultContents(List<Message> messages) {
        List<String> contents = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getRole() == Message.Role.TOOL && msg.getContent() != null) {
                contents.add(msg.getContent());
            }
        }
        return contents;
    }

    @Test
    void consecutiveSafeCallsRunInParallel() throws Exception {
        SharedState state = new SharedState();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new SafeProbeTool("safe_a", state, 0, barrier));
        registry.register(new SafeProbeTool("safe_b", state, 0, barrier));
        ScriptedAiService ai = new ScriptedAiService(List.of(
            new ToolCall("safe_a", Map.of()), new ToolCall("safe_b", Map.of())));
        AgentLoop loop = newLoop(ai, registry);
        try {
            AgentResponse response = loop
                .processMessage("run both", "chat:batch-par", null, false)
                .get(30, TimeUnit.SECONDS);
            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertTrue(state.barrierPassed,
                "two adjacent concurrency-safe calls must run concurrently "
                    + "(serial execution breaks the pair barrier)");
            assertEquals(List.of("safe_a-ok", "safe_b-ok"), toolResultContents(ai.secondCallMessages));
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void unsafeCallSplitsBatchesAndNeverOverlaps() throws Exception {
        SharedState state = new SharedState();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ToolRegistry registry = new ToolRegistry();
        // a 慢于 b(闭锁后延时 300ms) → 完成顺序倒置,但结果序必须保持调用序
        registry.register(new SafeProbeTool("safe_a", state, 300, barrier));
        registry.register(new SafeProbeTool("safe_b", state, 0, barrier));
        registry.register(new UnsafeProbeTool("unsafe_u", state));
        registry.register(new SafeProbeTool("safe_c", state, 0, null));
        ScriptedAiService ai = new ScriptedAiService(List.of(
            new ToolCall("safe_a", Map.of()), new ToolCall("safe_b", Map.of()),
            new ToolCall("unsafe_u", Map.of()), new ToolCall("safe_c", Map.of())));
        AgentLoop loop = newLoop(ai, registry);
        try {
            AgentResponse response = loop
                .processMessage("mixed batch", "chat:batch-mixed", null, false)
                .get(30, TimeUnit.SECONDS);
            assertTrue(response.isSuccess(), response.getErrorMessage());

            // 批 1(safe_a+safe_b)真并发
            assertTrue(state.barrierPassed, "first batch [a,b] must be parallel");

            // 非安全工具进入时无任何安全工具在跑
            assertEquals(0, state.unsafeSawActiveSafe,
                "unsafe singleton batch must never overlap a safe tool");

            // 批序:批 1 全部退出 < u 进入 < u 退出 < safe_c 进入
            List<String> snapshot;
            synchronized (state.timeline) {
                snapshot = new ArrayList<>(state.timeline);
            }
            int exitA = snapshot.indexOf("exit:safe_a");
            int exitB = snapshot.indexOf("exit:safe_b");
            int enterU = snapshot.indexOf("enter:unsafe_u");
            int exitU = snapshot.indexOf("exit:unsafe_u");
            int enterC = snapshot.indexOf("enter:safe_c");
            assertTrue(exitA >= 0 && exitB >= 0 && enterU >= 0 && exitU >= 0 && enterC >= 0,
                "timeline incomplete: " + snapshot);
            assertTrue(Math.max(exitA, exitB) < enterU, "batch 1 must complete before unsafe starts: " + snapshot);
            assertTrue(exitU < enterC, "unsafe must complete before next batch starts: " + snapshot);

            // 结果按原始调用序(完成序已被倒置)
            assertEquals(List.of("safe_a-ok", "safe_b-ok", "unsafe_u-ok", "safe_c-ok"),
                toolResultContents(ai.secondCallMessages));
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void guardCarriedIntoParallelBatchAndLeavesNoResidue() throws Exception {
        ContextProbeTool probe = new ContextProbeTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);

        // 调用线程守卫置位 → 派发到池线程的探针必须看到
        DelegationGuard.begin();
        try {
            registry.executeAsyncWithEvent("context_probe", Map.of(), 10_000).join();
            assertTrue(probe.guardSeen.get(),
                "the delegation guard must ride the dispatch into the pooled tool thread");
        } finally {
            DelegationGuard.end();
        }

        // 清理后:池化线程上不得有守卫/上下文残留(多轮提高线程复用命中概率)
        for (int i = 0; i < 8; i++) {
            CompletableFuture<ToolRegistry.ToolExecutionResult> f =
                registry.executeAsyncWithEvent("context_probe", Map.of(), 10_000);
            f.join();
            assertFalse(probe.guardSeen.get(), "iteration " + i + ": guard leaked on a pooled thread");
            assertNull(probe.runContextSeen.get(), "iteration " + i + ": run context leaked on a pooled thread");
        }
    }

    @Test
    void delegatedTurnGuardVisibleToParallelSafePair() throws Exception {
        SharedState state = new SharedState();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new SafeProbeTool("safe_a", state, 0, barrier));
        registry.register(new SafeProbeTool("safe_b", state, 0, barrier));
        ScriptedAiService ai = new ScriptedAiService(List.of(
            new ToolCall("safe_a", Map.of()), new ToolCall("safe_b", Map.of())));
        AgentLoop loop = newLoop(ai, registry);
        try {
            AgentResponse response = loop
                .processMessage("[delegated-from instanceId=peer-1] analyze both", "chat:batch-guard", null, true)
                .get(30, TimeUnit.SECONDS);
            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertTrue(state.barrierPassed, "safe pair must still run in parallel inside a delegated turn");
            assertTrue(state.guardSeenDuringExecution,
                "the armed guard must be visible to tools dispatched off the run thread "
                    + "(carry in ToolRegistry.executeAsyncWithEvent)");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void unknownAndUnsafeToolsDefaultToSingletonInline() {
        SharedState state = new SharedState();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new UnsafeProbeTool("unsafe_u", state));
        registry.register(new SafeProbeTool("safe_a", state, 0, null));

        // 未知工具名按不安全处理(默认 false 路径),注册表 get 返回 null → 单例批
        Tool unknown = registry.get("no_such_tool");
        assertNull(unknown);
        assertFalse(registry.get("unsafe_u").isConcurrencySafe(), "default must be unsafe");
        assertTrue(registry.get("safe_a").isConcurrencySafe(), "opt-in must be visible");
    }
}
