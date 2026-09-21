package org.gitee.jmeter.ai.agent;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * waitForCancellation 收尾等待契约的对抗测试（新建类，与 AgentLoopTurnEventTest 同包同配方）。
 *
 * <p>缺陷[15]：signalCancel 第 3 步 future.cancel(true)（AgentLoop.java:868）在取消线程内
 * 【同步】触发 startTurn 注册的 whenComplete（:563-572），当场摘除 completionLatches
 * 表项并 countDown——随后 waitForCancellation（:787-791）读到 null latch 立即
 * return true，javadoc :778-786「有界等待垂死回合完成收尾（finally 抽干注入队列、
 * 清理 map）」在取消路径确定性落空。
 *
 * <p>spec.md 锚（openspec/changes/unify-turn-event-display/specs/agent-turn-events/spec.md）：
 * <ul>
 *   <li>「Requirement: 结果通道语义不变」——"IPC 阻塞等待与 HTTP 信封（成功/超时/
 *       终止的状态可区分性）MUST NOT 改变"：IpcServer /agent 超时分支经
 *       cancelActiveTask(TIMEOUT) = signalCancel + waitForCancellation(5s) 等收尾后再
 *       回 504；等待被架空 = 回包时回合仍在写会话，阻塞等待语义实质改变；</li>
 *   <li>「Requirement: 事件顺序保证」取消路径例外段——"取消终态在任务槽摘除之后才
 *       发射，槽已空窗口内新回合可开跑"：waitForCancellation 是该交叠窗口内调用方
 *       观察「垂死回合已收尾」的唯一通道，被架空则窗口不可观察。</li>
 * </ul>
 *
 * <p>被架空的调用方：cancelActiveTask（:772-776，CloseConsolidationDialog.java:113 依赖
 * 「先取消在跑回合再快照/提炼，否则提炼与并发回合写会话竞态」）、AiChatPanel
 * .stopActiveTask:1285-1286 的后台 5s 等待。
 *
 * <p>脚手架复用 agent.testsupport：全类共用 HANG_UNTIL_RELEASED 门控策略——interrupt
 * 命中后回合体改挂 call.hang，「垂死回合任务体尚未收尾」由此成为【确定性事实】而非
 * 竞速假设（AgentLoopTurnEventTest:196/:221/:244 的 waitForCancellation 断言均在
 * hang 放行后才调，null-latch 快路径下恒真无齿——本类在 hang 放行【前】断言）。
 */
class AgentLoopWaitForCancellationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

    AgentLoop loop;
    GatedScriptAiService aiService;

    @BeforeEach
    void setUp() {
        // HANG_UNTIL_RELEASED：被 interrupt 的门控 LLM 调用改挂 hang，测试不 countDown
        // hang，垂死回合任务体的 finally（抽干队列/清理 map/补完成 future）必然未执行
        aiService = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), aiService);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    // ---- 1. 核心证伪[15]：垂死回合仍挂 hang（finally 必未执行）时不得谎报「已收尾」----

    /**
     * 契约：AgentLoop.waitForCancellation javadoc（:778-786）——"有界等待某会话被
     * signalCancel 的垂死回合完成收尾（finally 抽干注入队列、清理 map）"、
     * "@return true 若回合已收尾"；支撑 spec.md「Requirement: 结果通道语义不变」
     * （IPC 阻塞等待语义 MUST NOT 改变）与「Requirement: 事件顺序保证」取消路径
     * 例外段（垂死/新回合交叠窗口的可观察性）。
     *
     * <p>缺陷机理：signalCancel 第 3 步 future.cancel(true)（:868）在取消线程内同步
     * 触发 whenComplete（:563-572）——CompletableFuture.cancel 对已注册的非异步
     * dependent 在完成线程内联执行——当场摘 completionLatches 表项；随后
     * waitForCancellation 在 :788 读到 null latch 立即 return true。
     *
     * <p>确定性锚：call.interrupted 计数成功 ⇒ 回合体已捕获 interrupt 并改挂
     * call.hang.await()（GatedScriptAiService 单次门控语义：release 永不放行，
     * hang 未 countDown 前任务体不可能走出 LLM 调用）⇒ finally 必未执行。
     * 此刻 waitForCancellation 短超时必须 false（真等到超时），现实现返回 true 即证伪。
     */
    @Test
    void waitForCancellationMustNotClaimTeardownWhileDyingTurnStillHanging() throws Exception {
        String current = InstanceContext.currentSessionKey();
        GatedCall call = aiService.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> future = loop.processMessage("cancel me", current);
        assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "回合体必须已进入门控 LLM 调用");

        assertTrue(loop.signalCancel(current), "在跑回合必须可取消");
        assertTrue(call.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "interrupt 已命中且回合体改挂 hang——此刻 finally 必未执行（确定性，非竞速）");

        // —— 缺陷红线：completionLatches 表项已被 future.cancel 同步触发的 whenComplete
        // 摘除，waitForCancellation 读到 null 立即返回 true；契约要求阻塞至收尾
        //（短超时后返回 false）。
        assertFalse(loop.waitForCancellation(current, 300, TimeUnit.MILLISECONDS),
                "垂死回合任务体仍挂在 hang 上（finally 未执行、future 未补完成、注入队列"
                        + "未抽干），waitForCancellation 不得返回 true——收尾等待契约被"
                        + " future.cancel 同步触发的 whenComplete 架空");

        // —— 正向半边（修复后语义必须同时成立）：放行 hang，回合体真正走完 finally
        // 收尾后，waitForCancellation 必须能观察到完成。
        call.hang.countDown();
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "垂死回合收尾真正完成后 waitForCancellation 必须返回 true");
        assertTrue(future.isCompletedExceptionally(), "被取消的 future 不得正常完成");
    }

    // ---- 2. 生产入口[15]：cancelActiveTask 的「最多等 5s 收尾」不得退化为 μs 级空转 ----

    /**
     * 契约：cancelActiveTask（AgentLoop.java:772-776）= signalCancel +
     * waitForCancellation(5s)，javadoc「Cancel the active task for a session and wait
     * (bounded) for its teardown」——spec.md「Requirement: 结果通道语义不变」
     *（"IPC 阻塞等待与 HTTP 信封（成功/超时/终止的状态可区分性）MUST NOT 改变"：
     * /agent 超时分支回 504 前须等收尾）；CloseConsolidationDialog.java:113 依赖该等待
     * 保证「先取消在跑回合再快照/提炼，否则提炼与并发回合写会话竞态」。
     *
     * <p>确定性锚：worker 线程跑 cancelActiveTask；call.interrupted 计数成功 ⇒
     * signalCancel 的 interrupt 已命中且回合体改挂 hang。此后 worker 只剩第 3-5 步
     *（数个 CHM 操作）+ waitForCancellation。缺陷下 waitForCancellation 读 null
     * latch 即返回 true，worker μs 级死亡；修复下 worker 必须阻塞在 5s 收尾等待上。
     * join(600) 作有界观察窗（join-with-timeout 即完整的负向窗口，无需手写轮询）：
     * 缺陷下 worker 已死 → isAlive()==false → 断言失败；修复下 600ms < 5s 且 latch
     * 未计数 → worker 必然存活。
     */
    @Test
    void cancelActiveTaskMustBlockUntilDyingTurnTeardownCompletes() throws Exception {
        String current = InstanceContext.currentSessionKey();
        GatedCall call = aiService.scriptGated(LLMResponse.text("interrupted-final"));
        CompletableFuture<AgentResponse> future = loop.processMessage("consolidation race", current);
        assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "回合体必须已进入门控 LLM 调用");

        AtomicBoolean cancelledFlag = new AtomicBoolean(false);
        Thread worker = new Thread(
                () -> cancelledFlag.set(loop.cancelActiveTask(current, CancelCause.USER_STOP)),
                "cancel-active-task-worker");
        worker.start();

        // 等 signalCancel 的 interrupt 真正命中回合体——此后垂死回合挂 hang 不放行
        assertTrue(call.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "cancelActiveTask 内的 signalCancel 必须已 interrupt 回合体");

        // —— 缺陷红线：垂死回合仍挂 hang（收尾未完成、会话仍在被并发写入），
        // cancelActiveTask（含 5s 收尾等待）却已返回——「先取消再快照/提炼」的等待落空。
        // join(600) 为有界观察窗：缺陷下 worker 早已退出 → isAlive()==false → 红。
        worker.join(600);
        assertTrue(worker.isAlive(),
                "垂死回合任务体仍挂在 hang 上未收尾，cancelActiveTask（signalCancel + "
                        + "waitForCancellation(5s)）不得返回——CloseConsolidationDialog/"
                        + "IpcServer 超时分支依赖此等待保证快照/回包前回合已停止写会话");

        // 放行收尾：worker 必须在 5s 等待上限内随真实收尾返回
        call.hang.countDown();
        worker.join(TIMEOUT_SECONDS * 1000);
        assertFalse(worker.isAlive(), "放行 hang 后 cancelActiveTask 必须及时返回");
        assertTrue(cancelledFlag.get(), "确有在跑回合被取消");
        assertTrue(future.isCompletedExceptionally(), "被取消的 future 不得正常完成");
    }

    // ---- 3. 契约表守卫（缺陷下亦绿，防修复过冲破坏自然路径与快路径）----

    /**
     * 契约表的三段合法语义（AgentLoop.waitForCancellation javadoc :778-786）：
     * ① 无在跑回合（无 latch）：立即返回 true——"无在跑回合（无 latch）立即返回 true"；
     * ② 自然在跑（未取消）：latch 在册未计数 → 有界等待必须真等（短超时 false）；
     * ③ 自然收尾完成后：任务尾 future.complete（:540）同线程触发 whenComplete 摘
     *    latch → true（现实现此路径时序正确，缺陷声明亦确认）。
     *
     * <p>本测试不证伪当前缺陷（其只影响取消路径），而是给修复立约束：不得为修取消
     * 路径把 ② 改成恒真（= 现状缺陷）、不得把 ① 改成阻塞、不得在 ③ 收尾后遗留
     * 在册 latch 使后续等待悬挂。spec.md 锚：「Requirement: 事件顺序保证」自然完成
     * 路径的跨回合终态序依赖收尾可观察；「Requirement: 结果通道语义不变」——
     * processMessage future 语义与等待通道行为不得回归。
     *
     * <p>确定性：② 的 200ms await 在册未计数 latch 上确定性超时；③ 的 future.get 是
     * happens-before 同步点——whenComplete 由任务尾 future.complete 在回合体线程内
     * 同步触发后才返回，latch 必已摘除。无竞速。
     */
    @Test
    void waitForCancellationContractTableNaturalPaths() throws Exception {
        String current = InstanceContext.currentSessionKey();

        // ① 无在跑回合：null-latch 快路径合法，立即 true
        assertTrue(loop.waitForCancellation(current, 50, TimeUnit.MILLISECONDS),
                "无在跑回合（无 latch）时 waitForCancellation 必须立即返回 true");

        // ② 自然在跑（不取消）：GatedCall 的 release 不放行，回合体挂在 release.await()
        // 上——注意本测试全程不 interrupt，HANG_UNTIL_RELEASED 策略不触发，行为与
        // 普通门控一致
        GatedCall call = aiService.scriptGated(LLMResponse.text("natural-final"));
        CompletableFuture<AgentResponse> future = loop.processMessage("natural run", current);
        assertTrue(call.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(loop.waitForCancellation(current, 200, TimeUnit.MILLISECONDS),
                "自然在跑回合未收尾，有界等待必须真等到超时返回 false——不得恒真");

        // ③ 自然收尾后：whenComplete 由任务尾 future.complete 同线程触发（时序正确），
        // latch 已摘 → true
        call.release.countDown();
        assertEquals("natural-final",
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getContent());
        assertTrue(loop.waitForCancellation(current, TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "自然收尾完成后 latch 已摘除，waitForCancellation 返回 true");
    }
}
