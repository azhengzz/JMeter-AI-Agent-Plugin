package org.gitee.jmeter.ai.agent;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 回合事件流对抗压力测试（unify-turn-event-display 三轮修复后的继续攻击面）。
 *
 * <p><b>单类三套件：</b>套件 A（快速取消风暴，种子 0xC0FFEE）、套件 B（模型切换风暴，
 * 种子 0xBEEF）、套件 C（混合源风暴，种子 0x5EED）。种子固定保证动作序列可复现；
 * 竞态分支结果本身随调度不定，但两条分支都被编排吸收、断言对结果封闭——极偏调度
 * 只弱化窗口覆盖面，绝不假红。失败消息均携带回合 id 与事件序列。
 *
 * <p><b>线程模型要点（决定全部编排约束）：</b>
 * <ul>
 *   <li>回合跑在 loop 单线程 executor 上；STARTED 在提交线程同步发射（先于 execute），
 *       故「每回合首事件是 STARTED」在本编排下可断言；</li>
 *   <li>signalCancel 五步在同一调用线程内完成：句柄捕获(先) → abort → interrupt →
 *       future.cancel+摘槽 → tryClaimTerminal 后发 TURN_CANCELLED。单次 signalCancel
 *       <b>不可能误中</b>「因摘槽才得以开跑」的新回合：probe 走 Phase 3 的前提是路由槽
 *       已被本次 signalCancel 摘除，即本次对句柄/future 表的读取必然先于 probe 的写入
 *       （Phase 2 注入路径不写句柄表）——套件 A 的交错据此编排，无需设防误中分支；</li>
 *   <li>GatedCall HANG 策略下 interrupt 把回合体挂到 hang 锁；每轮统一<b>双锁 drain</b>
 *       （release+hang 都 countDown）免疫 loop 线程 interrupt 标志残留
 *       （AgentRunner 入口/finally 亦会清标志，双保险）。</li>
 * </ul>
 *
 * <p><b>套件 B 为什么走生产工厂路径而非反射注入：</b>退役收容（retireLoop）、
 * 工厂级订阅重挂（createAgentLoop 全量重挂 globalTurnSubscribers）、signalCancelAny 的
 * 当前+退役双路由与剪枝，是三个环环相扣的工厂静态行为——只有经
 * {@code AgentLoopFactory.getAgentLoop / reset / signalCancelAny} 的真实调用链才能被
 * 整体轰击。JMeter home 钉到 @TempDir（面板测试同配方）防止工厂创建的
 * MemoryStore/SessionManager 污染仓库目录。
 *
 * <p><b>不变量②在跨 loop 场景不成立（刻意的断言边界）：</b>换血后新旧 loop 的同会话
 * 回合在不同执行器线程上并发跑，COMPLETED(N) 晚于 STARTED(N+1) 是物理可达且契约未禁止的
 * （契约的跨回合适序由单线程执行器+路由槽串行化背书，仅覆盖单 loop）。套件 B 按其
 * 断言清单只断 ①/可达性/④——绝不要在此补加 assertNoCompletedAfterLaterStart。
 *
 * <p>运行：{@code mvn test -Dtest=AgentLoopAdversarialStressTest}。
 */
class AgentLoopAdversarialStressTest {

    @TempDir
    Path tempDir;

    /** 收尾安全网：各 @Test 登记的驻留门控调用与 future，@AfterEach 统一双锁放行并等完成。 */
    private final List<GatedCall> trackedCalls = new ArrayList<>();
    private final List<CompletableFuture<AgentResponse>> trackedFutures = new ArrayList<>();
    private AgentLoop loop;

    @AfterEach
    void tearDown() throws Exception {
        for (GatedCall c : trackedCalls) {
            c.release.countDown();
            c.hang.countDown();
        }
        if (!trackedFutures.isEmpty()) {
            AwaitUtil.awaitUntil(() -> trackedFutures.stream().allMatch(CompletableFuture::isDone),
                    "teardown：全部登记 future 必须终结");
        }
        if (loop != null) {
            loop.shutdown();
        }
        AgentLoopFactory.reset();
        AgentLoopFactory.clearTurnSubscribersForTest();
    }

    // =====================================================================
    // 套件 A：快速取消风暴 —— 40 轮，固定种子随机交错四类动作：
    //   顺序自然完成 / 顺序取消(USER_STOP|RESET|SILENT) /
    //   正面竞态①（放行 vs signalCancel 同时触发，轰击「终态恰好一次」的双发射点）/
    //   正面竞态②（signalCancel vs 下一回合提交同时触发，轰击「取消路径倒序例外」窗口）。
    //
    // 断言清单：
    //  A1 ① 每个回合 id 的终态（COMPLETED+CANCELLED）恰好一次（tryClaimTerminal 执法）；
    //  A2 ② 自然路径无倒序：COMPLETED(N) 不得晚于 STARTED(N+1)
    //     （CANCELLED 的取消倒序例外是契约允许，但不豁免 COMPLETED）；
    //  A3 每个回合的首事件是 STARTED（本编排的取消永远瞄准已 STARTED 的驻留回合）；
    //  A4 STARTED 总数 == 终态总数（可见回合一终态：无悬挂回合、无账号外终态）；
    //  A5 ③ 毒订阅者（每事件必抛 IllegalStateException）不杀回合、不饿死记录者，
    //     且确实被触发过（防空转覆盖）；
    //  A6 全部 CANCELLED 的 cause 落在风暴使用的 {USER_STOP, RESET, SILENT} 之内。
    // 预计时长：典型 3-6s；上限 60s（任何锚点 10s 超时即快败）。
    // =====================================================================
    @Test
    void cancelStorm_exactlyOneTerminal_andNaturalOrderHolds() throws Exception {
        String session = InstanceContext.currentSessionKey();
        GatedScriptAiService aiService = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        loop = newLoop(aiService);
        RecordingSubscriber recorder = new RecordingSubscriber();
        AtomicInteger poisonFired = new AtomicInteger();
        // ③ 毒订阅者挡在记录者之前：dispatch 逐订阅者异常隔离必须在风暴节奏下持续成立
        loop.addTurnSubscriber(new TurnSubscriber() {
            @Override
            public void onTurnEvent(TurnEvent event) {
                poisonFired.incrementAndGet();
                throw new IllegalStateException("poison: " + event.kind());
            }
        });
        loop.addTurnSubscriber(recorder);

        Random rng = new Random(0xC0FFEE);                     // 固定种子：动作序列可复现
        CancelCause[] causes = {CancelCause.USER_STOP, CancelCause.RESET, CancelCause.SILENT};
        ArrayDeque<GatedCall> pending = new ArrayDeque<>();    // 与 fake 脚本队列同序（FIFO）
        CompletableFuture<AgentResponse> currentFuture = null;
        GatedCall currentCall = null;

        for (int round = 0; round < 40; round++) {
            if (currentFuture == null) {                       // 开新回合并驻留在门控 LLM 调用上
                if (pending.isEmpty()) {
                    pending.add(scriptOne(aiService));
                }
                currentCall = pending.peekFirst();
                currentFuture = loop.processMessage("M-" + round, session);
                trackedFutures.add(currentFuture);
                trackedCalls.add(currentCall);
                assertTrue(currentCall.entered.await(10, TimeUnit.SECONDS),
                        "round " + round + " 的回合必须驻留在门控 LLM 调用上"); // 确定性锚点
                pending.pollFirst();
            }

            int dice = rng.nextInt(100);
            CancelCause cause = causes[rng.nextInt(causes.length)];
            if (dice < 35) {
                // —— 顺序自然完成：终态在 try 尾、先于 future.complete ——
                drainParked(currentCall);
                assertNotNull(currentFuture.get(10, TimeUnit.SECONDS));
                currentFuture = null;
                currentCall = null;
            } else if (dice < 55) {
                // —— 正面竞态①：放行（自然完成）与 signalCancel 同时触发 ——
                //    spec「Stop 与自然完成竞态只出一条终态」的加压版（A1 的真攻击点）
                CyclicBarrier barrier = new CyclicBarrier(2);
                GatedCall call = currentCall;
                CompletableFuture<AgentResponse> future = currentFuture;
                Thread releaser = new Thread(() -> {
                    awaitBarrier(barrier);
                    call.release.countDown();
                }, "storm-releaser-" + round);
                Thread canceller = new Thread(() -> {
                    awaitBarrier(barrier);
                    loop.signalCancel(session, cause);
                }, "storm-race-canceller-" + round);
                releaser.start();
                canceller.start();
                joinAssertDone(releaser, "releaser");
                joinAssertDone(canceller, "race-canceller");
                drainParked(call);
                AwaitUtil.awaitUntil(future::isDone, "竞态回合必须终结（无论哪个发射点认领）");
                currentFuture = null;
                currentCall = null;
            } else if (dice < 80) {
                // —— 顺序取消：signalCancel 同步认领并发 TURN_CANCELLED，回合体挂 hang ——
                assertTrue(loop.signalCancel(session, cause), "驻留回合必须可取消");
                drainParked(currentCall);
                assertTrue(currentFuture.isCompletedExceptionally(), "取消后的 future 不得正常完成");
                currentFuture = null;
                currentCall = null;
            } else {
                // —— 正面竞态②：signalCancel vs 下一回合提交 —— 契约「取消路径倒序例外」
                //    窗口的持续轰击。probe 两种合法结局都封闭断言：
                //    (a) 撞上垂死路由槽 → INJECTED ack、残留随取消作废、无新回合；
                //    (b) 落进已摘槽窗口 → 成为新回合，其 STARTED 可能早于垂死回合的
                //        CANCELLED 到达（合法倒序，A2 只禁 COMPLETED 倒序）。
                String probeMsg = "P-" + round;
                GatedCall probeCall = scriptOne(aiService);
                pending.add(probeCall);
                trackedCalls.add(probeCall);
                CyclicBarrier barrier = new CyclicBarrier(2);
                AtomicReference<CompletableFuture<AgentResponse>> probeRef = new AtomicReference<>();
                AtomicBoolean cancelled = new AtomicBoolean();
                Thread canceller = new Thread(() -> {
                    awaitBarrier(barrier);
                    cancelled.set(loop.signalCancel(session, cause));
                }, "storm-canceller-" + round);
                Thread submitter = new Thread(() -> {
                    awaitBarrier(barrier);
                    probeRef.set(loop.processMessage(probeMsg, session));
                }, "storm-submitter-" + round);
                canceller.start();
                submitter.start();
                joinAssertDone(canceller, "canceller");
                joinAssertDone(submitter, "submitter");
                assertTrue(cancelled.get(), "驻留回合必须被本次取消命中");
                drainParked(currentCall);          // 先放行垂死体，probe 才能接管单线程执行器
                assertTrue(currentFuture.isCompletedExceptionally(), "垂死回合本体必须已被取消");
                CompletableFuture<AgentResponse> probe = probeRef.get();
                assertNotNull(probe, "probe 提交必须返回 future");
                // 分支判定基于 join 后同步已落地的事件（INJECTED 在 processMessage 返回前同步派发），
                // 非超时判别——确定性
                boolean injectedAck = recorder.events.stream().anyMatch(
                        e -> e.kind() == TurnEvent.Kind.INJECTED && probeMsg.equals(e.message()));
                if (injectedAck) {
                    assertTrue(probe.get(10, TimeUnit.SECONDS).getContent()
                            .startsWith("Message injected"), "撞垂死槽的 probe 必须拿到注入 ack");
                    currentFuture = null;          // 残留随取消作废：无新回合
                    currentCall = null;
                } else {
                    assertTrue(probeCall.entered.await(10, TimeUnit.SECONDS),
                            "落进摘槽窗口的 probe 必须接管探测调用并驻留（成为新回合）");
                    pending.pollFirst();
                    currentFuture = probe;
                    currentCall = probeCall;
                }
            }
        }

        // —— 终局排空（全部锚点有界）——
        for (GatedCall c : trackedCalls) {
            drainParked(c);
        }
        AwaitUtil.awaitUntil(() -> trackedFutures.stream().allMatch(CompletableFuture::isDone),
                "全部回合 future 必须终结");
        AwaitUtil.awaitUntil(() -> loop.activeTurn(session).isEmpty(), "不得残留在跑回合");

        assertExactlyOneTerminalPerTurnId(recorder);                                  // A1 ①
        assertNoCompletedAfterLaterStart(recorder);                                    // A2 ②
        assertStartedIsFirstEventPerTurn(recorder);                                    // A3
        assertEquals(recorder.count(TurnEvent.Kind.TURN_STARTED),
                recorder.count(TurnEvent.Kind.TURN_COMPLETED)
                        + recorder.count(TurnEvent.Kind.TURN_CANCELLED),
                "A4：可见回合数与终态数一一对应（无悬挂、无账号外终态）");
        assertTrue(recorder.count(TurnEvent.Kind.TURN_STARTED) >= 40, "40 轮提交必须各有一回合");
        assertTrue(poisonFired.get() > 0, "A5：毒订阅者必须实际被触发（否则③的覆盖是空转）"); // A5 ③
        for (TurnEvent e : recorder.events) {                                          // A6
            if (e.kind() == TurnEvent.Kind.TURN_CANCELLED) {
                assertTrue(EnumSet.of(CancelCause.USER_STOP, CancelCause.RESET, CancelCause.SILENT)
                                .contains(e.cause()), "A6：CANCELLED cause 不得漂移：" + e.cause());
            }
        }
    }

    // =====================================================================
    // 套件 B：模型切换风暴 —— 12 轮（每轮 1-2 次生产路径的 loop 全量重建，
    // 累计 ≈18 次换血，远超 retiredLoops 上限 4，充分轰击路由与剪枝；
    // 轮数保守是因每次重建都要注册全套 JMeter 工具，成本高于 A/C 的轻量轮）。
    // 每轮流程：建新一代 loop → 提交回合并驻留（门控确定性锚点）→ 三分支随机收尾：
    //   dice==0 换血(reset) → signalCancelAny 必须打断退役 loop 的驻留调用（可达性）；
    //   dice==1 换血(reset) → 退役 loop 自然完成，迟到终态必须仍达工厂订阅者（④）；
    //   dice==2 双代同时在跑（getAgentLoop 直接换血，旧代带活回合退役）→
    //          signalCancelAny 必须双双打断两代驻留调用（多退役路由）。
    //
    // 断言清单：
    //  B1 ① 每个回合 id 终态恰好一次（跨全部 12+ 代 loop、含退役代的迟到终态）；
    //  B2 可达性A：换血后 signalCancelAny 必须真打断退役 loop 的驻留 LLM 调用
    //     （call.interrupted 锚点）且其 future 完成异常；
    //  B3 可达性B：双退役代同时在跑时一次 signalCancelAny 必须同时够得着两代；
    //  B4 ④ 工厂订阅表跨模型切换不丢：整场只在开头注册一次 recorder，
    //     其 STARTED 数/回显集合必须与全部提交一一对应（每一代 loop 都收到）；
    //  B5 ④ 晚挂接：风暴中途（round 6）经工厂注册第二个订阅者，其后所有代照常可达
    //     （晚订阅者 STARTED 数 == recorder 增量）；
    //  B6 每回合首事件 STARTED；排空终局 signalCancelAny 必须返回 false
    //     （无可取消对象残留——含退役表剪枝正常）。
    // 预计时长：典型 8-15s（loop 重建占大头）；上限 120s。
    // =====================================================================
    @Test
    void modelSwitchStorm_subscriptionSurvives_andCancelRoutingReachesRetiredLoops() throws Exception {
        String previousHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());   // 工厂 workspace 钉进 tempDir（防仓库污染）
        try {
            String session = InstanceContext.currentSessionKey();
            AgentLoopFactory.reset();                    // 卫生：清掉其他测试类可能残留的单例
            AgentLoopFactory.clearTurnSubscribersForTest();
            Random rng = new Random(0xBEEF);             // 固定种子：换血/收尾序列可复现
            RecordingSubscriber recorder = new RecordingSubscriber();
            AgentLoopFactory.addTurnSubscriber(recorder); // ④ 整场只注册这一次
            List<String> submitted = new ArrayList<>();
            List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();
            List<GatedCall> calls = new ArrayList<>();
            RecordingSubscriber late = null;
            int lateBaseline = -1;

            for (int round = 0; round < 12; round++) {
                // —— 建新一代 loop 并让回合驻留在门控 LLM 调用上 ——
                GatedScriptAiService svc = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
                GatedCall call = svc.scriptGated(LLMResponse.text("R-" + round));
                AgentLoop current = AgentLoopFactory.getAgentLoop(svc);   // 生产换血路径
                assertNotNull(current, "agent.enabled 默认 true——工厂必须建出 loop（非静默跳过）");
                CompletableFuture<AgentResponse> f = current.processMessage("M-" + round, session);
                submitted.add("M-" + round);
                futures.add(f);
                calls.add(call);
                trackedFutures.add(f);
                trackedCalls.add(call);
                assertTrue(call.entered.await(10, TimeUnit.SECONDS),
                        "round " + round + " 的回合必须驻留在门控 LLM 调用上"); // 确定性锚点

                if (round == 6 && late == null) {        // ④ 加码：风暴中途晚挂接第二个订阅者
                    late = new RecordingSubscriber();
                    lateBaseline = recorder.count(TurnEvent.Kind.TURN_STARTED);
                    AgentLoopFactory.addTurnSubscriber(late);
                }

                int dice = rng.nextInt(3);
                if (dice == 0) {
                    // —— 换血 → 工厂路由取消退役 loop 的驻留回合（B2 可达性A）——
                    AgentLoopFactory.reset();             // 退役收容 + shutdown（不打断在跑回合）
                    assertTrue(AgentLoopFactory.signalCancelAny(session),
                            "退役 loop 的驻留回合必须经工厂路由可达");
                    assertTrue(call.interrupted.await(10, TimeUnit.SECONDS),
                            "路由取消必须真打断退役 loop 的 LLM 调用（视觉 Stop 生效、回合实际继续烧即此处红）");
                    call.hang.countDown();
                    assertTrue(f.isCompletedExceptionally(), "被路由取消的 future 不得正常完成");
                    AwaitUtil.awaitUntil(() -> current.activeTurn(session).isEmpty(), "退役 loop 回合体排空");
                } else if (dice == 1) {
                    // —— 换血 → 退役 loop 上自然完成：迟到终态仍须到达工厂级订阅者（B4 ④）——
                    AgentLoopFactory.reset();
                    call.release.countDown();
                    assertEquals("R-" + round, f.get(10, TimeUnit.SECONDS).getContent(),
                            "退役 loop 的在跑回合必须照常自然完成（shutdown 不打断）");
                    AwaitUtil.awaitUntil(() -> current.activeTurn(session).isEmpty(), "退役 loop 回合体排空");
                } else {
                    // —— 双代同时在跑：直接换血（旧代带活回合退役）再驻留一代，
                    //    统一 signalCancelAny 必须双双够得着（B3 可达性B）——
                    GatedScriptAiService svc2 = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
                    GatedCall call2 = svc2.scriptGated(LLMResponse.text("N-" + round));
                    AgentLoop second = AgentLoopFactory.getAgentLoop(svc2); // current 就地退役（在生产跑）
                    assertNotNull(second);
                    CompletableFuture<AgentResponse> f2 = second.processMessage("N-" + round, session);
                    submitted.add("N-" + round);
                    futures.add(f2);
                    calls.add(call2);
                    trackedFutures.add(f2);
                    trackedCalls.add(call2);
                    assertTrue(call2.entered.await(10, TimeUnit.SECONDS), "二代回合必须驻留");
                    assertTrue(AgentLoopFactory.signalCancelAny(session, CancelCause.USER_STOP),
                            "两代在跑回合都必须可路由");
                    assertTrue(call.interrupted.await(10, TimeUnit.SECONDS), "退役代的驻留回合必须被路由打断");
                    assertTrue(call2.interrupted.await(10, TimeUnit.SECONDS), "当代的驻留回合必须被路由打断");
                    call.hang.countDown();
                    call2.hang.countDown();
                    assertTrue(f.isCompletedExceptionally() && f2.isCompletedExceptionally(),
                            "两代 future 都必须异常完成");
                    AwaitUtil.awaitUntil(() -> current.activeTurn(session).isEmpty()
                                    && second.activeTurn(session).isEmpty(), "两代回合体排空");
                }
            }

            // —— 终局排空 + 断言 ——
            for (GatedCall c : calls) {
                c.release.countDown();
                c.hang.countDown();
            }
            AwaitUtil.awaitUntil(() -> futures.stream().allMatch(CompletableFuture::isDone),
                    "全部 future 必须终结");
            assertFalse(AgentLoopFactory.signalCancelAny(session),
                    "B6：排空后不得再有可取消对象（当前 + 全部退役 loop，含剪枝后的残留）");

            assertExactlyOneTerminalPerTurnId(recorder);                               // B1 ①
            assertStartedIsFirstEventPerTurn(recorder);                                 // B6
            assertEquals(submitted.size(), recorder.count(TurnEvent.Kind.TURN_STARTED),
                    "B4 ④：一次性注册的工厂订阅必须覆盖每一代 loop 的每个回合");
            assertEquals(new HashSet<>(submitted), new HashSet<>(recorder.startedMessages),
                    "B4 ④：回合回显与提交一一对应（换血不丢事件、不凭空多事件）");
            assertEquals(recorder.count(TurnEvent.Kind.TURN_STARTED),
                    recorder.count(TurnEvent.Kind.TURN_COMPLETED)
                            + recorder.count(TurnEvent.Kind.TURN_CANCELLED),
                    "B1：可见回合数与终态数一一对应");
            assertNotNull(late, "种子下 round 6 必已挂接晚订阅者");
            assertEquals(recorder.count(TurnEvent.Kind.TURN_STARTED) - lateBaseline,
                    late.count(TurnEvent.Kind.TURN_STARTED),
                    "B5 ④：晚挂接订阅者必须收到挂接之后每一代 loop 的全部回合");
        } finally {
            AgentLoopFactory.reset();
            AgentLoopFactory.clearTurnSubscribersForTest();
            if (previousHome != null) {
                JMeterUtils.setJMeterHome(previousHome);
            }
        }
    }

    // =====================================================================
    // 套件 C：混合源风暴 —— 30 轮、固定种子。主回合来源在
    // {LOCAL_PANEL, IPC_CLI, IPC_DELEGATED} 间随机轮换；五种 flavor：
    //   PLAIN(0-2)     驻留 → 完成/取消/new 混合收尾；
    //   INJECT(3-5)    驻留忙窗内随机轰击 1-2 发次级操作（本地注入 / CLI 注入 /
    //                  委派撞忙快拒 / /status 优先命令(IPC 源) / /help 忙期命令(本地源)）；
    //   OVERFLOW(6)    注入周期超限（6 步门控工具迭代各注入 1 条）→ 残留 re-publish
    //                  成 REPUBLISH 孤儿（AgentLoopTurnEventTest#3 同款确定性配方）；
    //   NEWFLIP(7)     /new（resetConversation：RESET 取消 + 代数翻转），偶尔带一条
    //                  忙窗注入证明「残留作废不复活」；
    //   CANCELMIX(8-9) 混合 cause（USER_STOP/TIMEOUT/SILENT）取消。
    //
    // 断言清单（显示域不串 = 每个 turnId 的 origin 恒定 + 会话级事件 origin 保真）：
    //  C1 ① 每回合终态恰好一次；
    //  C2 ② 自然路径无倒序（COMPLETED(N) 不晚于 STARTED(N+1)）；
    //     溢出轮加验：垂死 COMPLETED 索引 < 孤儿 STARTED 索引；
    //  C3 每回合首事件 STARTED；回合系事件 origin() 访问器解析自 turn；
    //  C4 origin 恒定：每 turnId 全部事件 origin 集合大小为 1；非 REPUBLISH 的
    //     STARTED echoText → 提交来源映射逐一匹配；REPUBLISH echoText 为 null；
    //  C5 INJECTED 总数与 origin 逐条匹配注入方记录（本地/CLI 不串）；
    //  C6 REJECTED_BUSY 恰等于委派撞忙次数（委派绝不并入注入队列——深度守卫前提）；
    //  C7 COMMAND_RESULT 总数与 origin 按命令方 FIFO 匹配（命令同步串行）；
    //  C8 REPUBLISH 孤儿总数精确对账：自然完成的忙窗注入全量 re-publish、取消 / /new
    //     作废清零、溢出轮 +1（/new 后旧残留复活即此处红）。
    // 预计时长：典型 4-8s；上限 90s。
    // =====================================================================
    @Test
    void mixedSourceStorm_originPinnedPerTurn_andNewFlipVoidsLeftovers() throws Exception {
        String session = InstanceContext.currentSessionKey();
        GatedScriptAiService svc = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        loop = newLoop(svc);
        RecordingSubscriber recorder = new RecordingSubscriber();
        loop.addTurnSubscriber(recorder);

        Random rng = new Random(0x5EED);                   // 固定种子：flavor/来源/收尾序列可复现
        TurnOrigin[] origins = {TurnOrigin.LOCAL_PANEL, TurnOrigin.IPC_CLI, TurnOrigin.IPC_DELEGATED};
        CancelCause[] causes = {CancelCause.USER_STOP, CancelCause.TIMEOUT, CancelCause.SILENT};
        Map<String, TurnOrigin> turnOriginByEcho = new HashMap<>();
        Map<String, TurnOrigin> injectorOriginByMsg = new HashMap<>();
        Map<String, ArrayDeque<TurnOrigin>> cmdOriginByRaw = new HashMap<>();
        int expectedInjected = 0;
        int expectedRejected = 0;
        int expectedCmd = 0;
        int expectedRepublish = 0;

        for (int round = 0; round < 30; round++) {
            int flavor = rng.nextInt(10);
            TurnOrigin busyOrigin = origins[rng.nextInt(origins.length)];

            if (flavor == 6) {
                // ===== 溢出轮：门控脚本链（不走 override）=====
                svc.override = null;
                String msg = "O-" + round;
                turnOriginByEcho.put(msg, busyOrigin);
                List<GatedCall> steps = new ArrayList<>();
                for (int i = 1; i <= 6; i++) {
                    steps.add(svc.scriptGated(LLMResponse.withToolCalls(
                            List.of(new ToolCall("c" + round + "-" + i, "noop_tool", Map.of())),
                            "step " + i)));
                }
                GatedCall finalCall = svc.scriptGated(LLMResponse.text("OF-" + round));
                svc.script(LLMResponse.text("ORPHAN-" + round));  // 非门控：孤儿秒完成
                CompletableFuture<AgentResponse> f = loop.processMessage(msg, session, null, busyOrigin);
                trackedFutures.add(f);
                trackedCalls.addAll(steps);
                trackedCalls.add(finalCall);
                long victimId = loop.activeTurn(session).orElseThrow().id();
                for (int i = 0; i < 6; i++) {               // 每步驻留时注入一条（来源交错）
                    GatedCall step = steps.get(i);
                    assertTrue(step.entered.await(10, TimeUnit.SECONDS), "溢出轮 step " + i);
                    String followup = "F-" + round + "-" + i;
                    TurnOrigin injOrigin = rng.nextBoolean()
                            ? TurnOrigin.LOCAL_PANEL : TurnOrigin.IPC_CLI;
                    assertTrue(loop.processMessage(followup, session, null, injOrigin)
                            .get(10, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
                    injectorOriginByMsg.put(followup, injOrigin);
                    expectedInjected++;
                    step.release.countDown();
                }
                assertTrue(finalCall.entered.await(10, TimeUnit.SECONDS), "溢出轮终 call 驻留");
                finalCall.release.countDown();
                assertEquals("OF-" + round, f.get(10, TimeUnit.SECONDS).getContent());
                expectedRepublish++;                         // 第 6 条溢出 → 唯一孤儿
                int target = expectedRepublish;
                AwaitUtil.awaitUntil(() -> republishStarts(recorder) == target,
                        "溢出残留必须 re-publish 成 REPUBLISH 孤儿");
                TurnEvent orphanStart = lastRepublishStart(recorder);
                assertNull(orphanStart.turn().echoText(), "REPUBLISH 孤儿不重画 You 回显");
                assertTrue(recorder.indexOf(TurnEvent.Kind.TURN_COMPLETED, victimId)
                                < recorder.indexOf(TurnEvent.Kind.TURN_STARTED, orphanStart.turn().id()),
                        "C2：垂死终态必须先于孤儿 STARTED（②）");
                AwaitUtil.awaitUntil(() -> recorder.terminalCountFor(orphanStart.turn().id()) == 1,
                        "孤儿必须恰好一个终态");
                AwaitUtil.awaitUntil(() -> loop.activeTurn(session).isEmpty(), "孤儿链排空");
                continue;
            }

            // ===== 驻留骨架（PLAIN / INJECT / NEWFLIP / CANCELMIX 共用）=====
            String msg = "M-" + round;
            final int r = round; // lambda 捕获需 effectively final（round 被 for 循环更新）
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch hang = new CountDownLatch(1);
            svc.override = () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {          // HANG 语义：interrupt 后改挂 hang
                    try {
                        hang.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                return LLMResponse.text("R-" + r);
            };
            CompletableFuture<AgentResponse> f = loop.processMessage(msg, session, null, busyOrigin);
            trackedFutures.add(f);
            turnOriginByEcho.put(msg, busyOrigin);
            assertTrue(entered.await(10, TimeUnit.SECONDS),
                    "round " + round + " 的回合必须驻留在门控 LLM 调用上"); // 忙窗确定性锚点
            long victimId = loop.activeTurn(session).orElseThrow().id();

            int injectedHere = 0;
            if (flavor >= 3 && flavor <= 5) {               // INJECT：忙窗内的混合源次级轰击
                int burst = 1 + rng.nextInt(2);
                for (int k = 0; k < burst; k++) {
                    int op = rng.nextInt(5);
                    if (op == 0) {                          // 本地注入
                        String m = "L-" + round + "-" + k;
                        assertTrue(loop.processMessage(m, session).get(10, TimeUnit.SECONDS)
                                .getContent().startsWith("Message injected"));
                        injectorOriginByMsg.put(m, TurnOrigin.LOCAL_PANEL);
                        expectedInjected++;
                        injectedHere++;
                    } else if (op == 1) {                   // CLI 注入（带来源前缀）
                        String m = "[from cli] C-" + round + "-" + k;
                        assertTrue(loop.processMessage(m, session, null, TurnOrigin.IPC_CLI)
                                .get(10, TimeUnit.SECONDS).getContent().startsWith("Message injected"));
                        injectorOriginByMsg.put(m, TurnOrigin.IPC_CLI);
                        expectedInjected++;
                        injectedHere++;
                    } else if (op == 2) {                   // 委派撞忙：必须快拒
                        assertFalse(loop.processMessage("[delegated-from X] D-" + round + "-" + k,
                                        session, null, TurnOrigin.IPC_DELEGATED)
                                        .get(10, TimeUnit.SECONDS).isSuccess(),
                                "委派撞忙必须被快拒（并入队列即深度守卫被绕过）");
                        expectedRejected++;
                    } else if (op == 3) {                   // 优先命令（Phase 1，IPC 源）
                        assertTrue(loop.processMessage("/status", session, null, TurnOrigin.IPC_CLI)
                                .get(10, TimeUnit.SECONDS).isSuccess());
                        cmdOriginByRaw.computeIfAbsent("/status", x -> new ArrayDeque<>())
                                .add(TurnOrigin.IPC_CLI);
                        expectedCmd++;
                    } else {                                // 忙期 dispatchable 命令（Phase 2，本地源）
                        assertTrue(loop.processMessage("/help", session).get(10, TimeUnit.SECONDS)
                                .isSuccess(), "忙期 dispatchable 命令必须同步分发");
                        cmdOriginByRaw.computeIfAbsent("/help", x -> new ArrayDeque<>())
                                .add(TurnOrigin.LOCAL_PANEL);
                        expectedCmd++;
                    }
                }
            } else if (flavor == 7 && rng.nextBoolean()) {  // NEWFLIP：偶尔带一条注入证明「作废」
                String m = "W-" + round;
                assertTrue(loop.processMessage(m, session).get(10, TimeUnit.SECONDS)
                        .getContent().startsWith("Message injected"));
                injectorOriginByMsg.put(m, TurnOrigin.LOCAL_PANEL);
                expectedInjected++;
                injectedHere++;
            }

            int resolve;
            if (flavor == 7) {
                resolve = 2;                                // /new
            } else if (flavor >= 8) {
                resolve = 1;                                // 混合 cause 取消
            } else {
                resolve = rng.nextInt(3);                   // PLAIN/INJECT：完成/取消/new 混合
            }
            if (resolve == 0) {
                // 自然完成：忙窗注入在回合内的后续检查点被消费（≤2 条 << MAX_INJECTION_CYCLES=5，
                // 每条驱动一次续跑迭代——override 保持在场使续跑调用同样快速返回），
                // 队列不残留 → 无 REPUBLISH 孤儿；孤儿链只由溢出轮（flavor 6）制造
                release.countDown();
                hang.countDown();
                assertEquals("R-" + round, f.get(10, TimeUnit.SECONDS).getContent());
                int target = expectedRepublish;
                AwaitUtil.awaitUntil(() -> republishStarts(recorder) == target,
                        "被消费的注入不得再 re-publish 成孤儿（自然完成轮零孤儿）");
                AwaitUtil.awaitUntil(() -> loop.activeTurn(session).isEmpty(), "回合体排空");
            } else if (resolve == 1) {
                CancelCause cause = causes[rng.nextInt(causes.length)];
                loop.signalCancel(session, cause);          // 取消：残留一律作废（2026-08-23 契约）
                release.countDown();
                hang.countDown();
                AwaitUtil.awaitUntil(f::isDone, "取消回合必须终结");
                AwaitUtil.awaitUntil(() -> loop.activeTurn(session).isEmpty(), "取消回合体排空");
                assertEquals(cause, recorder.eventOf(TurnEvent.Kind.TURN_CANCELLED, victimId).cause(),
                        "取消 cause 必须保真（载荷不漂移）");
            } else {
                loop.resetConversation(session);             // /new：RESET 取消 + 代数翻转 + 清空
                release.countDown();
                hang.countDown();
                AwaitUtil.awaitUntil(f::isDone, "重置回合必须终结");
                AwaitUtil.awaitUntil(() -> loop.activeTurn(session).isEmpty(), "重置回合体排空");
                assertEquals(CancelCause.RESET,
                        recorder.eventOf(TurnEvent.Kind.TURN_CANCELLED, victimId).cause(),
                        "/new 的取消必须携带 RESET");
            }
        }

        // —— 终局排空 + 断言（排空后无新事件源，「缺席」类断言此时确定性成立）——
        for (GatedCall c : trackedCalls) {
            c.release.countDown();
            c.hang.countDown();
        }
        AwaitUtil.awaitUntil(() -> trackedFutures.stream().allMatch(CompletableFuture::isDone),
                "全部回合 future 必须终结");
        AwaitUtil.awaitUntil(() -> loop.activeTurn(session).isEmpty(), "不得残留在跑回合");
        svc.override = null;

        assertExactlyOneTerminalPerTurnId(recorder);                                // C1 ①
        assertNoCompletedAfterLaterStart(recorder);                                  // C2 ②
        assertStartedIsFirstEventPerTurn(recorder);                                  // C3

        Map<Long, TurnOrigin> originById = new HashMap<>();                          // C4 origin 恒定
        for (TurnEvent e : recorder.events) {
            if (e.turn() == null) {
                continue;
            }
            TurnOrigin prev = originById.putIfAbsent(e.turn().id(), e.turn().origin());
            assertTrue(prev == null || prev == e.turn().origin(),
                    "C4：回合 " + e.turn().id() + " 的 origin 必须恒定（显示域不串）");
            assertEquals(e.turn().origin(), e.origin(), "C3：回合系事件 origin() 必须解析自 turn");
        }
        for (TurnEvent e : recorder.events) {                                        // STARTED 回显 ↔ 提交来源
            if (e.kind() != TurnEvent.Kind.TURN_STARTED) {
                continue;
            }
            if (e.turn().origin() == TurnOrigin.REPUBLISH) {
                assertNull(e.turn().echoText(), "C4：REPUBLISH 孤儿 echoText 必须为 null");
            } else {
                assertEquals(turnOriginByEcho.get(e.turn().echoText()), e.turn().origin(),
                        "C4：回合 " + e.turn().id() + " 的来源必须与提交来源一致（不串显示域）");
            }
        }
        assertEquals(expectedInjected, recorder.count(TurnEvent.Kind.INJECTED), "C5：注入 ack 总数");
        for (TurnEvent e : recorder.events) {
            if (e.kind() != TurnEvent.Kind.INJECTED) {
                continue;
            }
            assertEquals(injectorOriginByMsg.get(e.message()), e.origin(),
                    "C5：注入确认 origin 必须是注入方来源（本地/CLI 不串）");
        }
        assertEquals(expectedRejected, recorder.count(TurnEvent.Kind.REJECTED_BUSY),
                "C6：委派撞忙每次恰发一条 REJECTED_BUSY");
        assertEquals(expectedCmd, recorder.count(TurnEvent.Kind.COMMAND_RESULT), "C7：命令结果总数");
        for (TurnEvent e : recorder.events) {                                        // 命令同步串行 → FIFO 匹配
            if (e.kind() != TurnEvent.Kind.COMMAND_RESULT) {
                continue;
            }
            TurnOrigin expect = cmdOriginByRaw.get(e.message()).poll();
            assertEquals(expect, e.origin(), "C7：命令结果 origin 与命令方一致");
        }
        assertEquals(expectedRepublish, republishStarts(recorder),
                "C8：REPUBLISH 孤儿数精确对账（取消//new 作废、自然完成溢出才 re-publish）");
    }

    // ---------------------------------------------------------------------
    // 脚手架（与 AgentLoopTurnEventTest 同配方）
    // ---------------------------------------------------------------------

    /** 与既有测试同配方的 loop 构造（Mockito 记忆组件 + 直跑 ToolRegistry + NoopTool）。 */
    private AgentLoop newLoop(GatedScriptAiService service) {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        return new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "adversarial-stress"), service);
    }

    private static GatedCall scriptOne(GatedScriptAiService svc) {
        return svc.scriptGated(LLMResponse.text("R"));
    }

    /** 双锁放行：release 正常开门；hang 兜底 HANG 策略挂起与 interrupt 标志残留。 */
    private static void drainParked(GatedCall call) {
        call.release.countDown();
        call.hang.countDown();
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException("barrier broken", e);
        }
    }

    private static void joinAssertDone(Thread t, String what) throws InterruptedException {
        t.join(10_000);
        assertFalse(t.isAlive(), what + " 线程必须在 10s 内退出");
    }

    private static long republishStarts(RecordingSubscriber r) {
        return r.events.stream().filter(e -> e.kind() == TurnEvent.Kind.TURN_STARTED
                && e.turn().origin() == TurnOrigin.REPUBLISH).count();
    }

    private static TurnEvent lastRepublishStart(RecordingSubscriber r) {
        return r.events.stream().filter(e -> e.kind() == TurnEvent.Kind.TURN_STARTED
                && e.turn().origin() == TurnOrigin.REPUBLISH).reduce((a, b) -> b).orElseThrow();
    }

    // ---------------------------------------------------------------------
    // 事件流不变量断言（三套件共用）
    // ---------------------------------------------------------------------

    /** 不变量①：每个回合 id 的终态（COMPLETED+CANCELLED）计数恰好为 1。 */
    private static void assertExactlyOneTerminalPerTurnId(RecordingSubscriber r) {
        Map<Long, Integer> terminals = new TreeMap<>();
        for (TurnEvent e : r.events) {
            if (e.turn() == null) {
                continue;
            }
            if (e.kind() == TurnEvent.Kind.TURN_COMPLETED || e.kind() == TurnEvent.Kind.TURN_CANCELLED) {
                terminals.merge(e.turn().id(), 1, Integer::sum);
            }
        }
        assertFalse(terminals.isEmpty(), "风暴后必须存在终态事件");
        terminals.forEach((id, n) -> assertEquals(1, n, () -> "①回合 " + id + " 终态计数必须恰好一次"));
    }

    /**
     * 不变量②（自然路径）：COMPLETED(N) 不得晚于 STARTED(N+1)。
     * 取消路径倒序例外只豁免 CANCELLED——COMPLETED 出现在更晚回合 STARTED 之后即违规。
     * 注意：仅在单 loop 套件（A/C）调用——跨 loop（套件 B）同会话并发回合不受
     * 单线程执行器串行化保护，契约不涵盖该序（B 按其断言清单只断 ①/可达性/④）。
     */
    private static void assertNoCompletedAfterLaterStart(RecordingSubscriber r) {
        List<TurnEvent> ev = r.events;
        for (int i = 0; i < ev.size(); i++) {
            TurnEvent e = ev.get(i);
            if (e.kind() != TurnEvent.Kind.TURN_COMPLETED) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                TurnEvent s = ev.get(j);
                if (s.kind() == TurnEvent.Kind.TURN_STARTED && s.turn().id() > e.turn().id()) {
                    fail("②COMPLETED(" + e.turn().id() + ") 晚于 STARTED(" + s.turn().id()
                            + ")：自然完成路径无倒序豁免");
                }
            }
        }
    }

    /** 本编排下取消永远瞄准已 STARTED 的驻留回合、STARTED 在提交线程同步先发射——每回合首事件必须是 STARTED。 */
    private static void assertStartedIsFirstEventPerTurn(RecordingSubscriber r) {
        Map<Long, Integer> firstIdx = new HashMap<>();
        for (int i = 0; i < r.events.size(); i++) {
            TurnEvent e = r.events.get(i);
            if (e.turn() != null) {
                firstIdx.merge(e.turn().id(), i, Math::min);
            }
        }
        Set<Long> checked = new HashSet<>();
        for (TurnEvent e : r.events) {
            if (e.turn() == null || e.kind() != TurnEvent.Kind.TURN_STARTED || !checked.add(e.turn().id())) {
                continue;
            }
            long id = e.turn().id();
            assertEquals(r.indexOf(TurnEvent.Kind.TURN_STARTED, id), firstIdx.get(id),
                    "回合 " + id + " 的首事件必须是 STARTED（订阅者按回合身份过滤的前提）");
        }
    }
}
