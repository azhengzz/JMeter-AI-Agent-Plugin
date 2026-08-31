package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.agent.run.InjectionManager;
import org.gitee.jmeter.ai.agent.session.Session;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.agent.tools.Tool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列归回合所有 + 路由槽模型的对抗性测试：攻击首轮 20 个测试未覆盖的机制面。
 *
 * <p>攻击面清单：
 * <ul>
 *   <li>双击 Stop / 外来会话取消 / 连续两轮取消后新回合（取消路径的幂等与隔离）；</li>
 *   <li>队列满穿透 Phase 3（put 替换槽发生在原回合存活时——per-turn 所有权的关键路径）；</li>
 *   <li>委派消息：健康回合 busy 拒绝 vs 垂死窗口放行成独立委派回合；</li>
 *   <li>re-publish 级联（孤儿回合自身被注入再被 Stop，二级孤儿）；</li>
 *   <li>/new 命令落在垂死窗口；无订阅者时的 re-publish；双会话交错；</li>
 *   <li>offer 与 cancelRouting 的原子性压力（竞态的穷举攻击：每一条返回 true 的
 *       offer 必须能在队列里找到，无一丢失、无一幻影）；</li>
 *   <li>三会话混合操作 soak（无 ExecutionException、全部收敛）。</li>
 * </ul>
 */
class AgentLoopAdversarialTest {

    private static final long TIMEOUT_SECONDS = 15;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    ScriptedAiService aiService;
    SessionManager sessionManager;
    /** 事件派发守卫只放行当前实例键：会话键与其保持一致（「sess-A/B」等跨会话锚点照旧）。 */
    String sessionKey;

    /** 孤儿回合（REPUBLISH 源）事件记录器；仅需要孤儿断言的用例按需订阅。 */
    OrphanRecorder orphans;

    @BeforeEach
    void setUp() {
        aiService = new ScriptedAiService();
        sessionKey = InstanceContext.currentSessionKey();

        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        MemoryConsolidator consolidator = Mockito.mock(MemoryConsolidator.class);

        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());

        ContextBuilder contextBuilder = new ContextBuilder(memoryStore, tempDir);
        sessionManager = new SessionManager(tempDir, sessionKey);

        loop = new AgentLoop(registry, memoryStore, consolidator, contextBuilder,
                sessionManager, aiService);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    /** 订阅孤儿回合事件记录器；@param expected 本测试期望观察到的 STARTED 个数（latch 计数）。 */
    private void subscribeOrphans(int expected) {
        orphans = new OrphanRecorder(expected);
        loop.addTurnSubscriber(orphans);
    }

    // ------------------------------------------------------------------
    // 取消路径的幂等与隔离
    // ------------------------------------------------------------------

    /** 双击 Stop：第二次取消不得抛异常、不得留副作用，后续回合照常。 */
    @Test
    void doubleStop_rapidFire_secondIsNoOp() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall call2 = aiService.scriptGated(LLMResponse.text("R2"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        loop.cancelActiveTask(sessionKey, CancelCause.USER_STOP);   // 第一次 Stop（内部等待收尾 latch）
        loop.signalCancel(sessionKey);       // 立即第二次（回合已死/收尾中）——不得抛异常

        assertFalse(loop.hasActiveRun(sessionKey));
        complete(call1);
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "cancelled turn settles");

        CompletableFuture<AgentResponse> f2 = loop.processMessage("M2", sessionKey);
        await(call2.entered, "second turn LLM call started");
        assertTrue(loop.hasActiveRun(sessionKey), "双击 Stop 不得影响后续回合");
        complete(call2);
        assertEquals("R2", f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
    }

    /** 取消一个从未有回合的会话：纯 no-op，不抛异常。 */
    @Test
    void stopForeignSession_isNoOp() {
        assertFalse(loop.signalCancel("never-seen-session"));
        assertFalse(loop.hasActiveRun("never-seen-session"));
    }

    /** 连续两轮「起回合→取消」后，第三轮正常回合必须完好（abortFlag/latch 按值清理不串扰）。 */
    @Test
    void consecutiveCancelCycles_thenCleanTurn() throws Exception {
        for (int i = 1; i <= 2; i++) {
            ScriptedCall call = aiService.scriptGated(LLMResponse.text("R" + i));
            CompletableFuture<AgentResponse> f = loop.processMessage("M" + i, sessionKey);
            await(call.entered, "turn " + i + " LLM call started");
            loop.cancelActiveTask(sessionKey, CancelCause.USER_STOP);
            assertThrows(CancellationException.class, () -> f.get(1, TimeUnit.SECONDS));
            complete(call);
            awaitUntil(() -> !loop.hasActiveRun(sessionKey), "turn " + i + " settles");
        }

        aiService.scriptImmediate(LLMResponse.text("R3-CLEAN"));
        CompletableFuture<AgentResponse> f3 = loop.processMessage("M3", sessionKey);
        assertEquals("R3-CLEAN", f3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "两轮取消后的第三轮必须完好");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "settles");
    }

    // ------------------------------------------------------------------
    // 委派语义：健康 busy 拒绝 vs 垂死放行
    // ------------------------------------------------------------------

    /** 健康回合在跑：delegated 请求必须立即 busy 拒绝，不得入队、不得穿透。 */
    @Test
    void delegatedWhileHealthyTurn_busyRejectedFast() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        CompletableFuture<AgentResponse> f = loop.processMessage("delegated-task", sessionKey, null, TurnOrigin.IPC_DELEGATED);
        assertTrue(f.isDone(), "健康回合下 delegated 必须立即回执（busy 拒绝），不得排队");
        AgentResponse resp = f.get(1, TimeUnit.SECONDS);
        assertFalse(resp.isSuccess(), "busy 拒绝应为错误回执");
        assertTrue(resp.getErrorMessage() != null && resp.getErrorMessage().contains("busy"),
                "拒绝信息应可读地说明 busy：" + resp.getErrorMessage());

        complete(call1);
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "settles");
    }

    /** 垂死窗口内到达的 delegated 请求：路由槽已摘，不得 busy 拒绝，应放行为独立委派回合。 */
    @Test
    void delegatedDuringDyingWindow_runsAsIndependentTurn() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        aiService.scriptImmediate(LLMResponse.text("DEL-DONE"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");
        loop.signalCancel(sessionKey);

        CompletableFuture<AgentResponse> f = loop.processMessage("delegated-task", sessionKey, null, TurnOrigin.IPC_DELEGATED);
        assertFalse(f.isDone(), "垂死窗口内 delegated 不应被立即拒绝（槽已摘）");

        complete(call1);  // 垂死回合收尾 → 委派回合 pickup
        AgentResponse resp = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(resp.isSuccess(), "委派回合应正常完成");
        assertEquals("DEL-DONE", resp.getContent());
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "settles");
    }

    // ------------------------------------------------------------------
    // 队列满穿透：put 替换槽发生在原回合存活时（per-turn 所有权的关键路径）
    // ------------------------------------------------------------------

    /**
     * 队列容量 20：第 21 条消息 offer 失败穿透 Phase 3 起独立回合并 put 替换路由槽，
     * 此时原回合仍存活且按句柄继续抽自己的队列——两回合互不干扰，20 条注入 15 条
     * 被消化、5 条超限残留走 re-publish，全部消息有归宿。
     */
    @Test
    void queueFull_21stMessage_fallsThroughToNewTurn_putReplaceSafe() throws Exception {
        subscribeOrphans(5);  // 20 - 15（5 周期 × 3 条）= 5 条超限残留
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.withToolCalls(
                List.of(new ToolCall("c1", "noop_tool", Map.of())), "step 1"));
        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");

        for (int i = 1; i <= 20; i++) {
            AgentResponse ack = loop.processMessage("INJ-" + i, sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(ack.getContent().startsWith("Message injected"),
                    "队列未满时第 " + i + " 条应注入成功");
        }

        // 第 21 条：offer 失败（队满）→ 穿透 Phase 3 → 独立回合 future + put 替换槽
        CompletableFuture<AgentResponse> f21 = loop.processMessage("OVERFLOW-MSG", sessionKey);
        assertFalse(f21.isDone(), "第 21 条应穿透成独立回合 future，而非立即 ack");
        assertTrue(loop.hasActiveRun(sessionKey), "穿透回合占槽后路由仍应报 active");

        // T1 余下脚本：4 次工具调用（各触发一个注入周期）+ 最终回复
        for (int i = 2; i <= 5; i++) {
            aiService.scriptImmediate(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i));
        }
        aiService.scriptImmediate(LLMResponse.text("T1-FINAL"));

        complete(call1);
        assertEquals("T1-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "原回合按句柄抽自己的队列，不受穿透回合 put 替换槽影响");

        assertTrue(orphans.latch.get().await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "5 条超限残留应各产生一个 REPUBLISH 源回合 STARTED 事件");
        assertEquals(5, orphans.started.size());

        AgentResponse r21 = f21.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(r21.isSuccess(), "穿透的独立回合应正常完成（脚本耗尽 → DEFAULT-FINAL）");

        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "all turns settle");
    }

    // ------------------------------------------------------------------
    // 契约修订（2026-08-23，Stop=硬边界）：运行中注入 + Stop → 残留作废
    // ------------------------------------------------------------------

    /**
     * Stop 时未消费的注入一律作废：不产生孤儿回合、不落盘，也不得有二级级联。
     * 后续新回合的 pickup 时点（executor 串行）作为垂死收尾已执行的确定性锚点。
     */
    @Test
    void stopDuringActiveTurn_queuedInjection_discarded_noOrphanNoCascade() throws Exception {
        subscribeOrphans(4);  // 宽松上限：若实现错误重发布（含级联）会在此暴露
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));
        ScriptedCall callNext = aiService.scriptGated(LLMResponse.text("R-NEXT"));
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-MUST-NOT-RUN"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");
        assertTrue(loop.processMessage("M-left", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getContent().startsWith("Message injected"));

        loop.signalCancel(sessionKey);
        complete(call1);

        CompletableFuture<AgentResponse> fNext = loop.processMessage("M-next", sessionKey);
        await(callNext.entered, "follow-up turn started（隐含垂死收尾已执行）");
        complete(callNext);
        assertEquals("R-NEXT", fNext.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());

        assertTrue(orphans.started.isEmpty(), "Stop 后的队列残留必须作废，不得重发布成孤儿（含级联）");
        assertFalse(assistantContents().contains("ORPHAN-MUST-NOT-RUN"), "作废消息不得被处理落盘");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "all turns settle");
    }

    // ------------------------------------------------------------------
    // 命令与无监听器路径
    // ------------------------------------------------------------------

    /**
     * /new 落在垂死窗口：语义上一切消息（含命令）都是新回合，排在垂死回合之后
     * pickup（延迟=垂死回合 abort 时长，生产中 interrupt 秒级）。命令路由本身不受
     * 取消状态影响，最终正常回执。
     */
    @Test
    void newCommandDuringDyingWindow_settlesClean() throws Exception {
        ScriptedCall call1 = aiService.scriptGated(LLMResponse.text("R1"));

        loop.processMessage("M1", sessionKey);
        await(call1.entered, "first LLM call started");
        loop.signalCancel(sessionKey);

        CompletableFuture<AgentResponse> f = loop.processMessage("/new", sessionKey);
        complete(call1);   // 垂死回合收尾 → /new 回合才 pickup（测试的 fake LLM 吞中断，须显式放行）

        AgentResponse resp = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(resp.isSuccess(), "/new 在垂死窗口应正常回执：" + resp.getErrorMessage());
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "settles");
    }

    /**
     * 未订阅任何 TurnSubscriber 时 re-publish 仍必须执行（回合照跑落盘），不得静默丢弃消息。
     * 契约修订（2026-08-23）后 Stop 不再触发重发布，改用自然完成（注入周期 5/5 超限）构造：
     * 第 6 条注入在封顶窗口入队、回合自然结束时成为残留。
     */
    @Test
    void noSubscriber_republishStillProcesses() throws Exception {
        List<ScriptedCall> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                    List.of(new ToolCall("c" + i, "noop_tool", Map.of())), "step " + i)));
        }
        ScriptedCall finalCall = aiService.scriptGated(LLMResponse.text("T1-FINAL"));
        aiService.scriptImmediate(LLMResponse.text("ORPHAN-NO-LISTENER"));

        CompletableFuture<AgentResponse> f1 = loop.processMessage("M1", sessionKey);
        for (int i = 0; i < 5; i++) {
            await(calls.get(i).entered, "LLM call " + (i + 1));
            assertTrue(loop.processMessage("FOLLOWUP-" + (i + 1), sessionKey)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
            complete(calls.get(i));
        }
        await(calls.get(5).entered, "LLM call 6"); // 周期已 5/5 封顶：LEFT 不再被消化
        assertTrue(loop.processMessage("LEFT", sessionKey).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getContent().startsWith("Message injected"));
        complete(calls.get(5));
        await(finalCall.entered, "final LLM call");
        complete(finalCall);
        assertEquals("T1-FINAL", f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "原回合自然完成，残留走 finally re-publish");

        awaitUntil(() -> assistantContents().contains("ORPHAN-NO-LISTENER"),
                "无订阅者时孤儿回合仍须执行并落盘");
        awaitUntil(() -> !loop.hasActiveRun(sessionKey), "settles");
    }

    // ------------------------------------------------------------------
    // 双会话交错
    // ------------------------------------------------------------------

    /** 会话 A 的长回合占住 executor 时：B 起回合→取消→垂死窗口发新消息，全部互不串扰。 */
    @Test
    void twoSessionsInterleaved_blockerStopAndSend() throws Exception {
        ScriptedCall blocker = aiService.scriptGated(LLMResponse.text("BLOCK-DONE"));
        ScriptedCall callB = aiService.scriptGated(LLMResponse.text("B1"));
        aiService.scriptImmediate(LLMResponse.text("B2-FINAL"));

        loop.processMessage("a-msg", "sess-A");
        await(blocker.entered, "session A blocker started");

        CompletableFuture<AgentResponse> fB1 = loop.processMessage("b1", "sess-B");
        complete(blocker);                       // A 收尾 → B pickup
        await(callB.entered, "session B LLM call started");

        loop.signalCancel("sess-B");             // B 垂死
        CompletableFuture<AgentResponse> fB2 = loop.processMessage("b2", "sess-B");  // 垂死窗口新回合
        complete(callB);

        assertEquals("B2-FINAL", fB2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent(),
                "垂死窗口新回合必须正常完成");
        awaitUntil(() -> !loop.hasActiveRun("sess-A") && !loop.hasActiveRun("sess-B"),
                "both sessions settle, slots not crossed");
    }

    // ------------------------------------------------------------------
    // offer 与 cancelRouting 的原子性压力（竞态穷举攻击）
    // ------------------------------------------------------------------

    /**
     * 每一条 offer 返回 true 的消息，最终必须恰好出现在该队列里（drain 可见）——
     * 不丢失（写入悬挂队列却回 true）、不幻影（返回 true 但队列里没有）。
     * 多轮 register→并发 offer→cancelRouting 交错，穷举攻击 computeIfPresent 与
     * remove 的 bin 锁互斥。
     */
    @Test
    void offerVsCancelRouting_atomicityStress() throws Exception {
        InjectionManager manager = new InjectionManager();
        for (int iter = 0; iter < 300; iter++) {
            final String key = "s-" + iter;
            final LinkedBlockingQueueHolder holder = new LinkedBlockingQueueHolder(manager.register(key));
            final List<String> acked = new CopyOnWriteArrayList<>();
            final AtomicInteger offeredCount = new AtomicInteger();
            final CountDownLatch offersDone = new CountDownLatch(1);

            Thread offerer = new Thread(() -> {
                for (int i = 0; i < 60; i++) {
                    if (manager.offer(key, "m-" + i)) {
                        acked.add("m-" + i);
                    }
                    offeredCount.incrementAndGet();
                }
                offersDone.countDown();
            });
            offerer.start();

            // 在 offer 进行到一半时摘槽（与生产 signalCancel 的时序交错对齐）
            while (offeredCount.get() < 25) {
                Thread.onSpinWait();
            }
            manager.cancelRouting(key);

            assertTrue(offersDone.await(5, TimeUnit.SECONDS), "offerer must finish");
            offerer.join();

            List<String> drained = new ArrayList<>();
            for (InjectionManager.InjectionItem item : manager.cleanup(key, holder.queue)) {
                drained.add(item.getText());
            }
            Collections.sort(drained);
            List<String> sortedAcked = new ArrayList<>(acked);
            Collections.sort(sortedAcked);
            assertEquals(sortedAcked, drained,
                    "iter " + iter + ": acked 与队列内容必须一一对应（不丢失、不幻影）");
            assertFalse(manager.hasActiveRun(key));
        }
    }

    /** holder 只为在 lambda 里 effectively-final 持有队列引用。 */
    private static final class LinkedBlockingQueueHolder {
        final java.util.concurrent.LinkedBlockingQueue<InjectionManager.InjectionItem> queue;
        LinkedBlockingQueueHolder(java.util.concurrent.LinkedBlockingQueue<InjectionManager.InjectionItem> queue) {
            this.queue = queue;
        }
    }

    // ------------------------------------------------------------------
    // 三会话混合操作 soak
    // ------------------------------------------------------------------

    /**
     * 三会话轮转混合「新回合 / 注入 / 取消」，末尾全部收口：任何 future 不得以
     * ExecutionException 结束（CancellationException 属预期的取消），所有会话收敛。
     */
    @Test
    void chaosSoak_threeSessions_mixedOps_allSettle() throws Exception {
        String[] sessions = {"s1", "s2", "s3"};
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        for (int round = 0; round < 24; round++) {
            String s = sessions[round % 3];
            int op = round % 4;
            switch (op) {
                case 0, 1 -> futures.add(loop.processMessage("m-" + round, s));
                case 2 -> futures.add(loop.processMessage("inj-" + round, s));  // 可能 ack 或新回合
                default -> loop.signalCancel(s);
            }
        }
        // 收口：每会话最后一个确定的新回合
        for (String s : sessions) {
            futures.add(loop.processMessage("final-" + s, s));
        }

        for (CompletableFuture<AgentResponse> f : futures) {
            try {
                f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (CancellationException expected) {
                // 被 signalCancel 取消的回合：预期路径
            }
        }
        for (String s : sessions) {
            String key = s;
            awaitUntil(() -> !loop.hasActiveRun(key), "session " + s + " settles");
        }
    }

    // ------------------------------------------------------------------
    // 测试基础设施
    // ------------------------------------------------------------------

    /** 孤儿回合（REPUBLISH 源）事件记录器：STARTED 计数喂 latch（终态不在此断言）。 */
    private static final class OrphanRecorder implements TurnSubscriber {
        final List<TurnEvent> started = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<CountDownLatch> latch;

        OrphanRecorder(int expected) {
            latch = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(expected));
        }

        @Override public void onTurnEvent(TurnEvent event) {
            if (event.turn() != null && event.turn().origin() == TurnOrigin.REPUBLISH
                    && event.kind() == TurnEvent.Kind.TURN_STARTED) {
                started.add(event);
                latch.get().countDown();
            }
        }
    }

    private void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for: " + what);
    }

    private void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }

    private void complete(ScriptedCall call) {
        call.release.complete(null);
    }

    private List<String> assistantContents() {
        Session session = sessionManager.get(sessionKey);
        if (session == null) {
            return List.of();
        }
        List<String> contents = new ArrayList<>();
        for (Message msg : session.getMessages()) {
            if (msg.getRole() == Message.Role.ASSISTANT && msg.getContent() != null) {
                contents.add(msg.getContent());
            }
        }
        return contents;
    }

    /** 一次脚本化 LLM 调用：entered 信号 + release 门 + 应答。 */
    private static final class ScriptedCall {
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final LLMResponse response;

        ScriptedCall(LLMResponse response) {
            this.response = response;
        }
    }

    /**
     * 按脚本逐调用应答的 fake AiService（中断容忍等待，与生产 HTTP 行为一致；
     * 脚本耗尽返回 DEFAULT-FINAL）。
     */
    private static final class ScriptedAiService implements AiService {
        final ConcurrentLinkedQueue<ScriptedCall> script = new ConcurrentLinkedQueue<>();

        ScriptedCall scriptGated(LLMResponse response) {
            ScriptedCall call = new ScriptedCall(response);
            script.add(call);
            return call;
        }

        void scriptImmediate(LLMResponse response) {
            ScriptedCall call = new ScriptedCall(response);
            call.release.complete(null);
            script.add(call);
        }

        @Override
        public LLMResponse generateResponseWithTools(
                List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
            ScriptedCall call = script.poll();
            if (call == null) {
                return LLMResponse.text("DEFAULT-FINAL");
            }
            call.entered.countDown();
            while (!call.release.isDone()) {
                try {
                    call.release.get();
                } catch (InterruptedException e) {
                    // 吞中断继续等待测试放行
                } catch (ExecutionException | CancellationException | CompletionException e) {
                    throw new IllegalStateException("release future completed unexpectedly", e);
                }
            }
            return call.response;
        }

        @Override
        public String getName() {
            return "scripted-fake";
        }

        @Override
        public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 4096, "medium");
        }

        @Override
        public void setGenerationSettings(GenerationSettings settings) {
            // no-op
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }

    /** 立即成功的空操作工具。 */
    private static final class NoopTool implements Tool {
        @Override
        public String getName() {
            return "noop_tool";
        }

        @Override
        public String getDescription() {
            return "test noop tool";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }
}
