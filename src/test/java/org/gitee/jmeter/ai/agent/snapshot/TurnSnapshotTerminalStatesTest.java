package org.gitee.jmeter.ai.agent.snapshot;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.run.AgentRunResult;
import org.gitee.jmeter.ai.agent.run.AgentRunner;
import org.gitee.jmeter.ai.agent.run.AgentRunSpec;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四终态验收（规格验收标准）：正常完成 / 用户 Stop（含中断位）/ 异常中止 / 会话
 * 重置取消，四条终态路径都必须触发回合快照的 begin→complete 配对——Stop 打断在
 * 回合中途时终态比对恰是最需要的时刻。经可替换的服务实例记录调用（无 GUI 环境，
 * 真实采集链路由 SnapshotTreeCloneTest 与手动冒烟覆盖）。
 */
class TurnSnapshotTerminalStatesTest {

    private static final String KEY = "snap-terminal-test";

    /** 记录 begin/complete 调用的假服务：begin 恒返回真作用域，complete 记录非空配对。 */
    private static final class RecordingSnapshotService extends TurnPlanSnapshotService {
        final List<String> events = new CopyOnWriteArrayList<>();

        RecordingSnapshotService(ExecutorService executor) {
            super(executor);
        }

        @Override
        public TurnSnapshotScope beginTurn(Long turnId, TurnOrigin origin) {
            events.add("begin:" + turnId + ":" + origin);
            if (turnId == null || origin == null) {
                return null; // 契约与生产一致：无回合身份 → 无作用域
            }
            return new TurnSnapshotScope(turnId, origin, "recording");
        }

        @Override
        public void completeTurn(TurnSnapshotScope scope) {
            if (scope != null) {
                events.add("complete:" + scope.turnId);
            }
        }
    }

    private ExecutorService pool;
    private RecordingSnapshotService service;
    private TurnPlanSnapshotService original;

    @BeforeEach
    void installRecordingService() {
        pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "snap-terminal-test");
            t.setDaemon(true);
            return t;
        });
        service = new RecordingSnapshotService(pool);
        original = TurnPlanSnapshotService.get();
        TurnPlanSnapshotService.setForTest(service);
    }

    @AfterEach
    void restoreService() {
        TurnPlanSnapshotService.setForTest(original);
        pool.shutdownNow();
    }

    private static final class Harness {
        final GatedScriptAiService ai = new GatedScriptAiService();
        final AgentRunner runner;
        final AtomicLong epoch = new AtomicLong();

        Harness(Path root) throws Exception {
            MemoryStore memory = new MemoryStore(root);
            SessionManager sessions = new SessionManager(root, KEY);
            ContextBuilder context = new ContextBuilder(memory, root);
            ToolRegistry tools = new ToolRegistry();
            this.runner = new AgentRunner(
                    tools, new MemoryConsolidator(memory, ai, sessions, context, tools),
                    context, sessions, ai, 40, 16000, 30000);
        }

        AgentRunSpec.Builder spec(String userMessage, AtomicBoolean abort) {
            return AgentRunSpec.builder()
                    .sessionKey(KEY)
                    .userMessage(userMessage)
                    .abortFlag(abort)
                    .resetEpochSupplier(epoch::get)
                    .turnIdentity(7L, TurnOrigin.LOCAL_PANEL);
        }
    }

    @TempDir
    Path tempDir;

    private Harness newHarness() throws Exception {
        return new Harness(Files.createTempDirectory(tempDir, "ws"));
    }

    /**
     * 独立线程跑 run()，等 gated LLM 调用进入后执行 {@code during(abort, runnerThread)}
     * （可置 abort、翻 epoch、中断 runner——模拟各终态触发动作），放行并收拢结果。
     */
    private AgentRunResult runGated(Harness h, AgentRunSpec spec,
            GatedScriptAiService.GatedCall call,
            BiConsumer<AtomicBoolean, Thread> during) throws Exception {
        AtomicReference<AgentRunResult> result = new AtomicReference<>();
        AtomicReference<Thread> runnerThread = new AtomicReference<>();
        AtomicBoolean abort = spec.getAbortFlag();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            runnerThread.set(Thread.currentThread());
            result.set(h.runner.run(spec));
            done.countDown();
        }, "snap-runner");
        t.start();
        assertTrue(call.entered.await(10, TimeUnit.SECONDS), "LLM 调用未进入");
        during.accept(abort, runnerThread.get());
        call.release.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "回合未收尾");
        t.join(10_000);
        return result.get();
    }

    private void assertBeginCompletePair() {
        int begin = service.events.indexOf("begin:7:LOCAL_PANEL");
        int complete = service.events.indexOf("complete:7");
        assertTrue(begin >= 0, "起点捕获已调用，实际: " + service.events);
        assertTrue(complete >= 0, "终态收尾已调用（本测试的核心断言），实际: " + service.events);
        assertTrue(begin < complete, "begin 先于 complete（两端配对次序），实际: " + service.events);
    }

    // ---- 终态 1/4：正常完成 ----

    @Test
    void completedTurn_triggersSnapshotBeginAndComplete() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.script(LLMResponse.text("FINAL"));

        AgentRunResult r = h.runner.run(h.spec("Q1", abort).build());

        assertTrue(r.isSuccess());
        assertBeginCompletePair();
    }

    // ---- 终态 2/4：用户 Stop（abort + runner 线程中断位仍在时收尾，终态比对照常提交）----

    @Test
    void stopCancelledTurn_withInterruptBit_stillTriggersComplete() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        GatedScriptAiService.GatedCall call = h.ai.scriptGated(LLMResponse.text("FINAL"));

        AgentRunResult r = runGated(h, h.spec("Q1", abort).build(), call,
                (flag, runner) -> {
                    flag.set(true);          // signalCancel 双动作之一：先置中止位
                    runner.interrupt();      // 之二：中断 runner——finally 提交快照时
                                             // 线程仍带中断位，提交不得被误伤
                });

        // 快照契约不关心 result 成败语义（中止回合仍可带终答）；核心断言是配对
        assertBeginCompletePair();
    }

    // ---- 终态 3/4：异常中止（LLM 调用抛异常，run 的 catch→finally 路径）----

    @Test
    void exceptionAbortedTurn_stillTriggersComplete() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.override = () -> {
            throw new RuntimeException("boom-from-llm");
        };

        AgentRunResult r = h.runner.run(h.spec("Q1", abort).build());

        // 注：LLM 错误被 runAgentLoop 收口为错误响应（非冒泡到 run 的 catch），
        // 本用例钉的是「错误收尾路径上 finally 的 completeTurn 必达」——比对/写盘
        // 行为由 TurnPlanSnapshotWriteTest 在真实服务路径上钉住
        assertBeginCompletePair();
    }

    // ---- 终态 4/4：会话重置取消（epoch 翻转 + abort，/new 语义）----

    @Test
    void resetCancelledTurn_stillTriggersComplete() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        GatedScriptAiService.GatedCall call = h.ai.scriptGated(LLMResponse.text("FINAL"));

        AgentRunResult r = runGated(h, h.spec("Q1", abort).build(), call,
                (flag, runner) -> {
                    flag.set(true);
                    h.epoch.incrementAndGet(); // /new：代数翻转 + 中止位
                });

        assertBeginCompletePair();
    }

    // ---- 无回合身份（子代理/直构测试运行）：不参与 ----

    @Test
    void runWithoutTurnIdentity_neverPairs() throws Exception {
        Harness h = newHarness();
        AtomicBoolean abort = new AtomicBoolean();
        h.ai.script(LLMResponse.text("FINAL"));

        AgentRunSpec noIdentity = AgentRunSpec.builder()
                .sessionKey(KEY)
                .userMessage("Q1")
                .abortFlag(abort)
                .build();

        h.runner.run(noIdentity);

        assertTrue(service.events.contains("begin:null:null"),
                "无身份仍调 begin（真实服务侧 no-op 返回 null），实际: " + service.events);
        assertFalse(service.events.stream().anyMatch(e -> e.startsWith("complete:")),
                "无身份回合不产生终态配对，实际: " + service.events);
    }
}
