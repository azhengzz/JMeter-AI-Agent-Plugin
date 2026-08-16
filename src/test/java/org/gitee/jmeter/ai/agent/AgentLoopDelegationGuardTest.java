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
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.DelegationGuard;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 委派回合深度守卫的两条生产链路回归:
 * <ul>
 *   <li>guard 必须在<b>执行回合的线程</b>上置位——{@code AgentRunner.run} 以无执行器的
 *       {@code supplyAsync} 提交,串行工具调用跑在池化载体线程(而非 park 在 {@code join()}
 *       的 agent-loop 线程)上。在 loop 线程置位工具看不见,守卫形同虚设。</li>
 *   <li>委派请求命中<b>忙碌会话</b>必须立即失败,不得并入注入队列——队列只存 String,
 *       并入会丢失 delegated 标记且把"已注入"回执当任务结果还给委派方。</li>
 * </ul>
 */
class AgentLoopDelegationGuardTest {

    /** 探针工具:记录执行瞬间守卫是否在本线程置位。 */
    private static class ProbeTool implements Tool {
        final AtomicBoolean guardSeenDuringExecution = new AtomicBoolean(false);

        @Override public String getName() { return "probe_guard"; }
        @Override public String getDescription() { return "records whether the delegation guard is active"; }
        @Override public String getParameterSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            guardSeenDuringExecution.set(DelegationGuard.isActive());
            return ToolResult.success("probed");
        }
    }

    /** 首次 LLM 调用发起一次工具调用,再次调用收尾为文本。 */
    private static class TwoStepAiService implements AiService {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String getName() { return "two-step"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            if (calls.getAndIncrement() == 0) {
                return LLMResponse.withToolCalls(List.of(new ToolCall("probe_guard", Map.of())), "");
            }
            return LLMResponse.text("done");
        }
    }

    /** 每个 LLM 调用都阻塞在闭锁上,把回合钉在 active 状态。 */
    private static class BlockingAiService implements AiService {
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String getName() { return "blocking"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LLMResponse.text("first done");
        }
    }

    private static AgentLoop newLoop(AiService ai, ToolRegistry registry) throws Exception {
        Path workspace = Files.createTempDirectory("delegation-guard-test");
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace);
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory,
            new MemoryConsolidator(memory, ai, sessions, context, registry),
            context, sessions, ai);
    }

    @Test
    void delegatedTurnArmsGuardOnTheThreadThatRunsTools() throws Exception {
        ProbeTool probe = new ProbeTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);
        AgentLoop loop = newLoop(new TwoStepAiService(), registry);
        try {
            AgentResponse response = loop
                .processMessage("[delegated-from instanceId=peer-1] analyze", "chat:guard", null, true)
                .get(30, TimeUnit.SECONDS);

            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertTrue(probe.guardSeenDuringExecution.get(),
                "the tool executes on the pooled carrier thread of the run task — "
                    + "the guard must be armed there (not on the agent-loop thread parked in join())");
        } finally {
            loop.shutdown();
        }
    }

    @Test
    void delegatedRequestIntoBusySessionFailsFastInsteadOfInjecting() throws Exception {
        BlockingAiService ai = new BlockingAiService();
        AgentLoop loop = newLoop(ai, new ToolRegistry());
        try {
            // 回合 1 阻塞在首个 LLM 调用上,会话保持 active
            var first = loop.processMessage("user message", "chat:busy", null, false);

            // 委派请求:必须立即失败,不能并入注入队列(丢失标记)并回"已注入"回执
            AgentResponse delegated = loop
                .processMessage("[delegated-from instanceId=peer-1] task", "chat:busy", null, true)
                .get(5, TimeUnit.SECONDS);
            assertFalse(delegated.isSuccess(), "busy session must reject a delegated request");
            String err = delegated.getErrorMessage() != null ? delegated.getErrorMessage()
                    : String.valueOf(delegated.getContent());
            assertTrue(err.contains("busy"), "error should say the session is busy: " + err);

            // 对照:普通用户消息仍走注入队列(原行为不变)
            AgentResponse userFollowUp = loop
                .processMessage("user follow-up", "chat:busy", null, false)
                .get(5, TimeUnit.SECONDS);
            assertTrue(userFollowUp.isSuccess());
            assertTrue(String.valueOf(userFollowUp.getContent()).contains("injected"),
                "non-delegated busy-session routing must stay unchanged: " + userFollowUp.getContent());

            ai.release.countDown();
            assertTrue(first.get(30, TimeUnit.SECONDS).isSuccess(), "blocked turn must finish after release");
        } finally {
            ai.release.countDown();
            loop.shutdown();
        }
    }
}
