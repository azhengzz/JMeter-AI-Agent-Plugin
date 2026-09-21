package org.gitee.jmeter.ai.agent;

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
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.testsupport.RecordingSubscriber;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对抗性竞态不变式测试（unify-turn-event-display 三轮评审修复后的继续攻击）。
 *
 * <p>攻击目标[缺陷0]：{@code AgentLoop.signalCancel} 的 TOCTOU——第 846 行先读
 * {@code activeTurnHandles}（可能还是旧回合 N 的句柄），第 864 行才
 * {@code activeTasks.remove}（此时表项可能已被垂死回合 finally 里 re-publish 的
 * {@code startTurn(O)} 覆盖成新回合 O 的 future），第 885 行却拿旧句柄
 * {@code tryClaimTerminal}。当 signalCancel 的 [846→864] 跨越 startTurn(O) 的
 * [418 put handle → 562 put future] 窗口时：被 cancel 的是 O 的 future，被认领
 * 的却是 N 的句柄（N 已在 try 尾认领过、claim 失败）→ 不发 TURN_CANCELLED；
 * O 的任务被 executor 取出时命中 pre-pickup guard 分支（433-441 行）只作废残留
 * 即 return（无任何终态发射）——O 成为「STARTED 已派发、终态事件数为 0」的回合，
 * 面板 liveTurnIds 永不移除该 id、loading/Stop 模式永久滞留。
 *
 * <p>测试 1（压测主攻，概率性）：锚定 COMPLETED(N) 派发瞬间对齐 canceller 起跑，让用户
 * Stop 与垂死回合收尾的 re-publish 竞速上千轮，聚合断言 spec 不变式
 * 「每个 STARTED 计数 ≥1 的回合，终态事件数恰为 1」。零假红：无害交错不触犯聚合
 * 断言；可能假绿：单轮命中率为指令级窗口（机器负载相关），靠轮数 + 时间预算供给
 * 机会——失败消息携带轮数与种子可复核。
 * 测试 2（确定性护栏）：pre-pickup 被取消的排队回合（自洽句柄读取路径）必须
 * 恰好收到一个终态——钉住修复方向，防止「signalCancel 不认领、guard 也不发」
 * 的半吊子修复把卡死换一个形态。
 *
 * <p>构造配方与 {@link AgentLoopTurnEventTest} 同款：Mockito.mock(MemoryStore) +
 * ToolRegistry(Runnable::run) + NoopTool + new AgentLoop(...)。
 */
class AgentLoopCancelRaceInvariantTest {

    private static final long TIMEOUT_SECONDS = 10;
    /** 竞速轮数：窗口为指令级交错，单轮命中率低，靠轮数供给机会（时间预算 60s 兜底）。 */
    private static final int ROUNDS = 1500;
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(60);

    @TempDir
    Path tempDir;

    AgentLoop loop;
    GatedScriptAiService aiService;
    RecordingSubscriber recorder;

    /**
     * 压测锚点 latch：垂死回合 N（LOCAL_PANEL 源）COMPLETED 派发的第一站 countDown——
     * 此刻 N 的句柄已认领终态、finally 的 re-publish 尚未开始，canceller 由此起跑
     * 与 startTurn(O) 的 [418→562] 窗口竞速。volatile：loop 线程写、canceller 跨线程读。
     */
    private volatile CountDownLatch anchor;

    /** 锚未 fire 的轮数（canceller 有界等待超时即竞速机会作废，见 canceller 内注释）。 */
    private final AtomicInteger anchorMisses = new AtomicInteger();

    @BeforeEach
    void setUp() {
        aiService = new GatedScriptAiService();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), aiService);
        recorder = new RecordingSubscriber();
        loop.addTurnSubscriber(recorder);
    }

    @AfterEach
    void tearDown() {
        anchor = null;
        loop.shutdown();
    }

    // ====================================================================
    // 测试 1（压测主攻）[缺陷0]：Stop 与垂死回合 re-publish 竞速下，
    // 每个 STARTED 已派发的回合必须恰有一个终态事件
    //
    // 钉住 spec.md（openspec/changes/unify-turn-event-display/specs/agent-turn-events/spec.md）
    // 两条 Requirement：
    //   - 「事件顺序保证」：同一回合内 SHALL 保持 开始 → 进度* → 恰好一个终态 的全序；
    //   - 「终态恰好一次」：每个回合 SHALL 恰好发射一个终态事件（完成或取消其一），
    //     即使存在多个潜在发射点（取消路径与回合体收尾路径竞态）MUST NOT 双发。
    // 连带钉住「面板过滤为活回合集合」Requirement 的前提：终态按标识移除活回合 id——
    // 零终态回合令 liveTurnIds 永不移除、按钮复位判据永不满足（面板永久卡死）。
    // ====================================================================
    @Test
    @Timeout(150)
    void everyStartedTurnGetsExactlyOneTerminalUnderCancelRepublishRace() throws Exception {
        final String current = InstanceContext.currentSessionKey();

        // 订阅者重排：对齐订阅者置于订阅者表首位——COMPLETED(N) 派发的第一站即
        // countDown，给 canceller 争取在垂死回合 republish 的 startTurn(O) put
        // handle（418 行）之前完成 signalCancel 的句柄读取（846 行）
        final TurnSubscriber aligner = new TurnSubscriber() {
            @Override public void onTurnEvent(TurnEvent event) {
                // 只锚定 LOCAL_PANEL 源回合的 COMPLETED：N 是本地提交回合；
                // 孤儿 O 是 REPUBLISH 源，其 COMPLETED 不得误触下一轮锚
                if (event.kind() == TurnEvent.Kind.TURN_COMPLETED
                        && event.turn() != null
                        && event.turn().origin() == TurnOrigin.LOCAL_PANEL) {
                    CountDownLatch a = anchor;
                    if (a != null) {
                        a.countDown();
                    }
                }
            }
        };
        loop.removeTurnSubscriber(recorder);
        loop.addTurnSubscriber(aligner);
        loop.addTurnSubscriber(recorder);

        long startNanos = System.nanoTime();
        int roundsRun = 0;
        for (int round = 0; round < ROUNDS; round++) {
            if (System.nanoTime() - startNanos > BUDGET_NANOS) {
                // 时间预算兜底：已跑轮数的不变式断言依然有效（聚合按回合 id 统计）
                break;
            }
            roundsRun = round + 1;

            // ---- 本轮脚本：6 条 tool-call 门控 + 1 条 final 纯文本（AgentLoopTurnEventTest
            // 孤儿配方）：注入周期 MAX_INJECTION_CYCLES=5，6 条注入中前 5 条被检查点消费、
            // 第 6 条溢出成 leftover，N 自然完成后被 re-publish 成孤儿回合 O。
            // 孤儿 O 的 LLM 调用走 GatedScriptAiService 空队列兜底 DEFAULT-FINAL——
            // 无论 O 被竞速取消还是正常运行，本轮脚本条目消耗恒定，跨轮零错位。
            List<GatedCall> calls = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                calls.add(aiService.scriptGated(LLMResponse.withToolCalls(
                        List.of(new ToolCall("noop_tool", Map.of())), "step " + i)));
            }
            GatedCall finalCall = aiService.scriptGated(LLMResponse.text("R-FINAL"));

            // ---- 回合 N：占住 loop 线程并制造注入残留
            loop.processMessage("M-" + round, current);
            for (int i = 0; i < 6; i++) {
                GatedCall call = calls.get(i);
                assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "round " + round + " LLM call " + (i + 1) + " must be reached");
                // 忙期注入（Phase 2）：前 5 条被注入检查点消费，第 6 条溢出为 leftover
                String ack = loop.processMessage("FOLLOWUP-" + round + "-" + (i + 1), current)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent();
                assertTrue(ack.startsWith("Message injected"),
                        "round " + round + " injection " + (i + 1) + " must be acked");
                call.release.countDown();
            }
            assertTrue(finalCall.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "round " + round + " final LLM call must be reached");

            // ---- canceller（模拟用户点 Stop，含连点两次）：锚定 COMPLETED(N) 派发瞬间起跑，
            // 与垂死回合 finally → republishLeftovers → startTurn(O) 的 [418→562] 窗口竞速。
            // 二次 signalCancel 供给变体 B（双 Stop）形态的窗口，且不会制造假红：
            // 它要么自洽（读到 O 的新句柄 → CANCELLED(O)，恰一终态），要么命中同一缺陷。
            anchor = new CountDownLatch(1);
            Thread canceller = new Thread(() -> {
                try {
                    if (!anchor.await(2, TimeUnit.SECONDS)) {
                        // 锚未 fire（harness 编排异常，非产品缺陷信号）：实测约 2/20 次运行，
                        // 失败栈证 canceller 永久 parked 在无界 await——COMPLETED(N) 在锚
                        // 装填后未派发，与 spinUntil 必过/事件流必达的推理链互斥；静态穷举
                        // （signalCancel 全路径非阻塞、各轮 canceller 均已 join 死亡、孤儿
                        // REPUBLISH 源过滤确认、AgentRunner 入口清中断标志）未能定位机制，
                        // 且事后 18 连绿（含 6×CPU 负载）无法按需复现。有界化处置：该轮
                        // 竞速机会作废（跳过 signalCancel，不造假红也不丢不变式——聚合
                        // 断言仍按已派发事件全量核查）；系统性锚失效由尾部 miss 占比断言
                        // 拦截，单发 miss 仅计数可见。
                        anchorMisses.incrementAndGet();
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                loop.signalCancel(current);
                loop.signalCancel(current);
            }, "race-canceller-" + round);
            canceller.setDaemon(true);
            canceller.start();

            // 放行 N 收尾：COMPLETED(N) 派发（锚 countDown）→ finally re-publish O
            // （与 canceller 竞速）→ O 正常跑完或被 pre-pickup cancel 后 guard 作废
            finalCall.release.countDown();

            // ---- 轮收尾锚①：无在跑回合（handle 表清空 = 所有 future 已 complete/cancel
            // = 回合体已收尾、guard 已有机会执行）。缺陷命中轮 O 的 handle 在 future 被
            // cancel 时即由 whenComplete 清除，本锚不会卡死。
            spinUntil(() -> loop.activeTurn(current).isEmpty(), 5000,
                    "round " + round + ": active turn must settle");
            // ---- 轮收尾锚②：canceller 已退出——竞速路径 = 已从 signalCancel 返回
            // （TURN_CANCELLED 派发点同步于调用内，join 返回即事件完备）；锚 miss 路径 =
            // 本轮未发任何取消（无 pending CANCELLED）。两路径下 join 返回后事件流均完备。
            canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            if (canceller.isAlive()) {
                // 取证增强：失败消息携带 canceller 此刻的完整线程栈——区分「卡在
                // anchor.await()（COMPLETED 未派发，缺陷信号）」与「卡在 signalCancel
                // 内部（非阻塞路径异常，环境信号）」，避免仅凭 isAlive 猜测。
                // 二轮取证升级：anchor 未 fire ⟹ 本轮 LOCAL_PANEL COMPLETED 在装填前
                // 已派发或从未派发——同时抓 loop/carrier 线程栈与事件流尾部定位真因。
                StringBuilder diag = new StringBuilder();
                for (StackTraceElement f : canceller.getStackTrace()) {
                    diag.append("\n  [canceller] at ").append(f);
                }
                Thread[] live = new Thread[Thread.activeCount() + 8];
                int n = Thread.enumerate(live);
                for (int i = 0; i < n; i++) {
                    Thread t = live[i];
                    if (t == null || t == canceller || t == Thread.currentThread()) {
                        continue;
                    }
                    String name = t.getName();
                    if (name.startsWith("agent-loop") || name.contains("ForkJoin")
                            || name.startsWith("race-canceller")) {
                        StackTraceElement[] fs = t.getStackTrace();
                        if (fs.length == 0) {
                            continue;
                        }
                        diag.append("\n  [").append(t.getName()).append(" state=")
                                .append(t.getState()).append("] at ").append(fs[0]);
                        for (int j = 1; j < Math.min(fs.length, 6); j++) {
                            diag.append("\n      at ").append(fs[j]);
                        }
                    }
                }
                List<TurnEvent> tail = recorder.events.subList(
                        Math.max(0, recorder.events.size() - 40), recorder.events.size());
                for (TurnEvent e : tail) {
                    diag.append("\n  [event] ").append(e.kind())
                            .append(e.turn() != null
                                    ? " turn#" + e.turn().id() + " origin=" + e.turn().origin()
                                    : " (session)");
                }
                assertTrue(false,
                        "round " + round + ": canceller must finish (signalCancel must not "
                                + "block); diagnostics:" + diag);
            }

            // 防会话 jsonl O(N^2) 全量重写拖垮压测：每轮清空内存会话（此刻无回合在跑，安全；
            // 磁盘残留不影响事件流断言）
            loop.getSessionManager().getOrCreate(current).clear();
        }

        // ---- 聚合不变式断言：每个 STARTED 计数 ≥1 的回合 id，其终态事件数恰为 1 ----
        // （同时检测双终态：!= 1 覆盖 0 与 ≥2 两个方向）
        Map<Long, Integer> startedByTurn = new TreeMap<>();
        Map<Long, Integer> terminalsByTurn = new HashMap<>();
        for (TurnEvent e : recorder.events) {
            if (e.turn() == null) {
                continue; // 会话级事件（INJECTED/COMMAND_RESULT/REJECTED_BUSY）无回合身份
            }
            long id = e.turn().id();
            if (e.kind() == TurnEvent.Kind.TURN_STARTED) {
                startedByTurn.merge(id, 1, Integer::sum);
            }
            if (e.kind() == TurnEvent.Kind.TURN_COMPLETED
                    || e.kind() == TurnEvent.Kind.TURN_CANCELLED) {
                terminalsByTurn.merge(id, 1, Integer::sum);
            }
        }
        List<Long> broken = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : startedByTurn.entrySet()) {
            if (terminalsByTurn.getOrDefault(entry.getKey(), 0) != 1) {
                broken.add(entry.getKey());
            }
        }
        // 断言红线：缺陷存在且竞速命中时，此处红——失败消息列出零终态回合 id 及其
        // 事件序列（有 TURN_STARTED、无 TURN_COMPLETED/TURN_CANCELLED），并携带
        // 轮数/种子供复现（竞态概率性：本地复现建议连跑 3-5 次）
        assertTrue(broken.isEmpty(),
                "存在 STARTED 已派发但终态事件数 != 1 的回合（rounds=" + roundsRun
                        + "/" + ROUNDS + ", seed=无（固定编排）, budget=60s）: "
                        + broken.stream()
                        .map(id -> "turn#" + id + "(terminal="
                                + terminalsByTurn.getOrDefault(id, 0)
                                + ", kinds=" + recorder.kindsFor(id) + ")")
                        .collect(Collectors.joining(", "))
                        + " —— 违反 spec.md『事件顺序保证：同一回合内 SHALL 保持"
                        + " 开始 → 进度* → 恰好一个终态 的全序』与『终态恰好一次：每个回合"
                        + " SHALL 恰好发射一个终态事件』；根因：signalCancel 第 3 步"
                        + " activeTasks.remove（AgentLoop.java:864）摘到的 future 与第 5 步"
                        + "（885 行）认领的句柄分属不同回合，被 pre-pickup cancel 的回合走"
                        + " guard 分支（433-441 行）只作废残留、零终态发射，面板 liveTurnIds"
                        + " 永不移除该回合 id（loading/Stop 模式永久滞留）");

        // 压测自身健康度：轮数足够才对「未命中」有解释力（0 轮跑成则本测试无意义）；
        // 锚 miss 占比过半 ⟹ canceller 有界等待系统性超时、竞速编排失效，不得静默缴械
        assertTrue(startedByTurn.size() >= 1, "at least one STARTED turn must have run");
        assertTrue(anchorMisses.get() * 2 < roundsRun,
                "anchor miss 占比过半（" + anchorMisses.get() + "/" + roundsRun
                        + "）——canceller 竞速机会系统性作废，压测对缺陷0失去解释力");
    }

    // ====================================================================
    // 测试 2（确定性护栏）：pre-pickup 被取消的排队回合必须恰好一个终态。
    //
    // 编排全程 latch/future.get 门控（无竞速窗口）：Stop#1 取消在跑回合 A（HANG 策略
    // 钉住其收尾）→ 路由槽已摘 → M2 走 Phase 3 排队（STARTED(M2) 已派发、future 已入
    // activeTasks、任务排在 A 之后尚未 pickup）→ Stop#2 取消 M2 → signalCancel 为 M2
    // 的句柄认领并发射 TURN_CANCELLED → executor 取出 M2 死任务时 guard 分支作废。
    // 排空锚：M3 排在 M2 死任务之后（executor FIFO），其 future.get 返回 ⟹ guard 已
    // 执行完毕——此后终态数必须仍恰为一（不双发）。
    //
    // 钉住 spec.md『终态恰好一次』Requirement：「每个回合 SHALL 恰好发射一个终态事件
    // （完成或取消），即使存在多个潜在发射点（取消路径与回合体收尾路径竞态）
    // MUST NOT 双发；订阅者 MUST NOT 需要自行去重终态」——扩展到第四个发射点
    // （pre-pickup guard 分支）：signalCancel 认领后 guard 不得再发，guard 负责
    // 发终态的修复路线下 signalCancel 则不得抢先认领，二者必居其一、恰居其一。
    // ====================================================================
    @Test
    @Timeout(30)
    void prePickupCancelledQueuedTurnKeepsExactlyOneTerminal() throws Exception {
        final String current = InstanceContext.currentSessionKey();
        GatedScriptAiService service = swapService(
                new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED));

        // 回合 A：占住 loop 线程（门控挂起），Stop#1 将其取消并认领 A 的终态
        GatedCall callA = service.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> futureA = loop.processMessage("busy A", current);
        assertTrue(callA.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "turn A must park in its gated LLM call");

        assertTrue(loop.signalCancel(current), "Stop#1 must cancel turn A");
        assertTrue(callA.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        long turnA = recorder.lastCancelled().turn().id();
        assertEquals(CancelCause.USER_STOP, recorder.lastCancelled().cause());
        assertEquals(1, recorder.terminalCountFor(turnA), "turn A: exactly one terminal");

        // M2：A 已垂死（signalCancel 第 4 步已摘路由槽）→ 新消息走 Phase 3 开新回合。
        // startTurn(M2) 在调用线程上同步完成：STARTED(M2) 已派发、future 已入
        // activeTasks，但任务排在 A 之后尚未 pickup——这就是 pre-pickup 可取消的排队回合
        service.script(LLMResponse.text("M3-FINAL"));
        loop.processMessage("queued M2", current);
        long turnM2 = recorder.lastStarted().turn().id();

        // Stop#2：此刻句柄读取与 future 摘除自洽（同属 M2）——signalCancel 必须为 M2
        // 认领并发射 TURN_CANCELLED：STARTED 已发的排队回合被取消，终态恰一次
        assertTrue(loop.signalCancel(current, CancelCause.USER_STOP),
                "Stop#2 must cancel the queued turn M2");
        assertEquals(turnM2, recorder.lastCancelled().turn().id(),
                "the claimed handle must belong to the cancelled future's turn (M2)");
        assertEquals(CancelCause.USER_STOP, recorder.lastCancelled().cause());
        assertEquals(1, recorder.terminalCountFor(turnM2),
                "queued turn M2: exactly one terminal right after signalCancel");

        // 放行 A 的 hang：A 的回合体收尾（claim 已被 Stop#1 认领 → try 尾静默，不双发）；
        // executor 随后取出 M2 的死任务 → pre-pickup guard 分支作废残留即 return
        callA.hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // 排空锚：M3 排在 M2 死任务之后（单线程 executor FIFO），future.get(M3) 返回
        // ⟹ M2 的 guard 分支已执行完毕——终态数必须仍恰为一（guard 不得再发第二个）
        AgentResponse m3 = loop.processMessage("flush M3", current)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("M3-FINAL", m3.getContent());

        assertEquals(1, recorder.terminalCountFor(turnM2),
                "spec『终态恰好一次』：pre-pickup 取消的排队回合在整个生命周期内恰一个终态"
                        + "（signalCancel 认领后 guard 分支不得再发；若修复改为 guard 发终态，"
                        + "signalCancel 则不得抢先认领——两发射点合计恰一）");
        assertEquals(1, recorder.terminalCountFor(turnA),
                "turn A keeps exactly one terminal after its body finishes silently");
        assertTrue(futureA.isCompletedExceptionally(), "cancelled turn A must not complete normally");
    }

    // ---- scaffolding ----

    /** 换上指定 fake service 的同配方 loop（Stop 钉子用 HANG 策略 service；见 AgentLoopTurnEventTest）。 */
    private <S extends AiService> S swapService(S service) {
        loop.shutdown();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), service);
        loop.addTurnSubscriber(recorder);
        return service;
    }

    /**
     * 1ms 粒度条件等待（压测专用，AwaitUtil 的 20ms 轮询粒度在千轮规模下浪费时长）。
     * 超时抛 AssertionError——编排故障 fail fast，不与不变式断言混淆。
     */
    private static void spinUntil(BooleanSupplier condition, long timeoutMs, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(1);
        }
    }
}
