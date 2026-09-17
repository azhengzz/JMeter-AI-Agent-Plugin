package org.gitee.jmeter.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归钉（对抗测试轮 2026-09-17 收编）：中止落盘、懒收尾经真实 AgentLoop 接线（startTurn 的 epoch 供应商）的端到端钉——删掉接线行本测试即红。
 *
 * <p>openspec 变更 persist-user-message-early 的 spec 承诺两条正向持久化行为：
 * ① 场景「LLM 首调中被停止的回合以合成收尾」——Stop 取消后「jsonl 中触发 user 消息
 * 之后紧跟一条内容为 "Error: Task interrupted before a response was generated."
 * 的 assistant 消息，携带 _recovery_interrupted: true，且无其他回合消息」；
 * ② 需求「悬空 user 尾的懒收尾」——LLM 错误回合遗留的 user 尾「在下一回合开始时被
 * 合成收尾闭合（带 _recovery_interrupted 标记）并落盘；本回合 LLM 上下文以该收尾
 * 闭合转录，不存在连续两条 user 消息」。
 *
 * <p>这两条承诺在生产里唯一的生效通道是 AgentLoop.startTurn 构造 AgentRunSpec 时的
 * 一行接线 {@code .resetEpochSupplier(() -> currentEpoch(sessionKey))}
 * （AgentLoop.java:450）：AgentRunner.resetEpochUnchanged（AgentRunner.java:848-858）
 * 对 null 供应商恒返 false，中止落盘分支（AgentRunner.java:213-221）与懒收尾入口守卫
 * （AgentRunner.java:942）双双静默 no-op。而既有测试无一钉住该接线：
 * AgentRunnerEarlyPersistTest 的 Harness 自行接供应商（{@code epoch::get}），只测
 * runner 不测接线；loop 级测试只断言 RESET-负向（「文件保持 1 行」类，供应商为 null
 * 时同样平凡成立）。删除该行接线可使两条特性在生产全灭而测试全绿（对抗验证已实证：
 * 删线跑 mvn clean test，643 tests, 0 failures）。
 *
 * <p>本 PoC 从 spec 承诺出发、经<b>真实 AgentLoop 全链路</b>（processMessage →
 * startTurn → executor 线程 → agentRunner.run，供应商只由被测接线提供，测试不做任何
 * 自接线）钉住两条正向行为：删掉 AgentLoop.java:450 的接线，本测试两用例即红；
 * 接线在场则绿。时序全部确定性（门控 LLM 调用 + entered latch + signalCancel +
 * waitForCancellation），无 sleep 同步。
 */
class AgentLoopPersistEarlyWiringTest {

    private static final long TIMEOUT_SECONDS = 15;
    private static final String NO_RESPONSE =
            "Error: Task interrupted before a response was generated.";

    /** 记录每次 LLM 请求消息列表的 GatedScriptAiService 装饰器（懒收尾的「上下文无连续 user」钉）。 */
    private static final class RecordingGatedAiService implements AiService {
        final GatedScriptAiService delegate = new GatedScriptAiService();
        final List<List<Message>> requests = new CopyOnWriteArrayList<>();

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            requests.add(messages);
            return delegate.generateResponseWithTools(messages, tools, options);
        }
        @Override public String getName() { return "recording-gated"; }
        @Override public GenerationSettings getGenerationSettings() {
            return delegate.getGenerationSettings();
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
    }

    @TempDir
    Path tempDir;

    /** 真实 AgentLoop（AgentLoopTurnEventTest 同配方：mock 记忆件 + 真 SessionManager）。 */
    private AgentLoop newLoop(AiService ai) {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        return new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "poc-loop-wiring"), ai);
    }

    /** 会话 jsonl 路径（对齐 SessionManager.safeFileName 的文件名规范化）。 */
    private Path jsonlFor(String sessionKey) {
        return tempDir.resolve("sessions")
                .resolve(sessionKey.replaceAll("[^a-zA-Z0-9-_]", "_") + ".jsonl");
    }

    /** 解析 jsonl 的消息行（跳过首行 metadata），供角色/内容/标记断言。 */
    private static List<JsonNode> messageRows(Path file) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node = mapper.readTree(line);
            if (node.has("_type") && "metadata".equals(node.get("_type").asText())) {
                continue;
            }
            rows.add(node);
        }
        return rows;
    }

    /**
     * spec 场景「LLM 首调中被停止的回合以合成收尾」经真实 loop 接线：
     * 首次 LLM 调用挂起期间 Stop（signalCancel），回合收尾后 jsonl 必须为
     * [触发 user 行, 合成 assistant 收尾行（NO_RESPONSE + _recovery_interrupted:true）]，
     * 且无其他回合消息。
     */
    @Test
    void stopDuringFirstLlmCall_persistsUserRowAndSyntheticCloser_throughLoopWiring() throws Exception {
        GatedScriptAiService ai = new GatedScriptAiService();
        AgentLoop loop = newLoop(ai);
        try {
            String key = InstanceContext.currentSessionKey();
            GatedScriptAiService.GatedCall call = ai.scriptGated(LLMResponse.text("NEVER-MATERIALIZED"));

            CompletableFuture<AgentResponse> future =
                    loop.processMessage("poc stop during first llm call", key);
            assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "门控 LLM 调用未进入——回合未起跑（触发消息未早落盘）");

            // Stop（USER_STOP，非重置类取消）：置 abort + interrupt + cancel future
            assertTrue(loop.signalCancel(key), "活跃回合必须可被 Stop 取消");
            assertTrue(loop.waitForCancellation(key, TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "回合收尾超时（中止落盘发生在 run() 内，latch 释放即已完成）");
            assertTrue(future.isDone(), "被取消回合的 future 必须已落定");

            List<JsonNode> rows = messageRows(jsonlFor(key));
            String file = Files.readString(jsonlFor(key));
            assertEquals(2, rows.size(),
                    "spec：触发 user 之后紧跟一条合成收尾，且无其他回合消息。实际文件：\n" + file);
            assertEquals("user", rows.get(0).path("role").asText());
            assertTrue(rows.get(0).path("content").asText("").contains("poc stop during first llm call"),
                    "触发 user 行必须在场（早落盘）。文件：\n" + file);
            assertEquals("assistant", rows.get(1).path("role").asText());
            assertEquals(NO_RESPONSE, rows.get(1).path("content").asText(),
                    "合成收尾内容必须为 spec 钉定的中断文案。文件：\n" + file);
            assertTrue(rows.get(1).path("_recovery_interrupted").asBoolean(false),
                    "合成收尾必须携带 _recovery_interrupted: true。文件：\n" + file);
        } finally {
            loop.shutdown();
        }
    }

    /**
     * spec 需求「悬空 user 尾的懒收尾」经真实 loop 接线：回合 1 因 LLM 错误只落盘触发
     * user 行（既有连续 user 砖化源），回合 2 开始构建历史前必须以合成收尾闭合该尾并
     * 落盘；收尾纳入回合 2 的 LLM 上下文——请求中不存在相邻两条 user。
     */
    @Test
    void danglingUserTailFromErrorTurn_closedByLazyCloserNextTurn_throughLoopWiring() throws Exception {
        RecordingGatedAiService ai = new RecordingGatedAiService();
        AgentLoop loop = newLoop(ai);
        try {
            String key = InstanceContext.currentSessionKey();

            // 回合 1：LLM 错误（spec 场景 WHEN：错误响应不产生 assistant 行，只落盘触发 user）
            ai.delegate.script(LLMResponse.error("boom"));
            loop.processMessage("poc turn one llm error", key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<JsonNode> afterFirst = messageRows(jsonlFor(key));
            assertEquals(1, afterFirst.size(),
                    "前提钉：LLM 错误回合遗留悬空 user 尾（1 行 user、无 assistant）。实际：\n"
                            + Files.readString(jsonlFor(key)));
            assertEquals("user", afterFirst.get(0).path("role").asText());

            // 回合 2：正常完成——悬空 user 尾必须在历史构建前被合成收尾闭合
            ai.delegate.script(LLMResponse.text("SECOND-FINAL"));
            loop.processMessage("poc turn two after dangling tail", key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<JsonNode> rows = messageRows(jsonlFor(key));
            String file = Files.readString(jsonlFor(key));
            assertEquals(4, rows.size(),
                    "spec：悬空尾被合成收尾闭合后转录为 user/收尾/user/终答。实际文件：\n" + file);
            assertEquals("user", rows.get(0).path("role").asText());
            assertEquals("assistant", rows.get(1).path("role").asText());
            assertEquals(NO_RESPONSE, rows.get(1).path("content").asText(),
                    "闭合悬空尾的必须是 spec 钉定的合成收尾文案。文件：\n" + file);
            assertTrue(rows.get(1).path("_recovery_interrupted").asBoolean(false),
                    "懒收尾合成行必须携带 _recovery_interrupted: true。文件：\n" + file);
            assertEquals("user", rows.get(2).path("role").asText());
            assertTrue(rows.get(2).path("content").asText("").contains("poc turn two after dangling tail"),
                    "回合 2 触发 user 行必须在场。文件：\n" + file);
            assertEquals("assistant", rows.get(3).path("role").asText());
            assertEquals("SECOND-FINAL", rows.get(3).path("content").asText(),
                    "回合 2 正常终答必须落盘。文件：\n" + file);

            // spec：收尾纳入回合 2 的 LLM 上下文——最后一个请求不存在相邻两条 user，
            // 且收尾文案在场（标记被历史清洗剥离、内容保留）
            List<Message> request = ai.requests.get(ai.requests.size() - 1);
            for (int i = 1; i < request.size(); i++) {
                assertFalse(request.get(i - 1).getRole() == Message.Role.USER
                                && request.get(i).getRole() == Message.Role.USER,
                        "回合 2 的 LLM 请求出现相邻两条 user（Anthropic 400 砖化源未闭合）："
                                + request.stream().map(m -> m.getRole().name()).toList());
            }
            assertTrue(request.stream().anyMatch(m -> m.getRole() == Message.Role.ASSISTANT
                            && NO_RESPONSE.equals(m.getContent())),
                    "合成收尾必须进入回合 2 的 LLM 上下文。请求角色序："
                            + request.stream().map(m -> m.getRole().name()).toList());
        } finally {
            loop.shutdown();
        }
    }
}
