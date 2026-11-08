package org.gitee.jmeter.ai.gui;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.model.StreamDelta;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnHandle;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.testsupport.AwaitUtil;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.swing.*;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板级流式端到端：STREAM_* 进度经面板分发进转录（合并 flush 后成卡）、
 * loading 保留至首条可见增量、已流式回合的 INTERMEDIATE 全文回放被渲染端丢弃、
 * TURN_COMPLETED(streamedFinal) 原位重渲不新增重复卡、无卡回退既有路径。
 * 面板内部 TranscriptView 用默认 Swing 调度器（~100ms），断言经轮询收敛。
 */
class AiChatPanelStreamingPresenterTest {

    @TempDir
    Path tempDir;

    AgentLoop loop;
    AiChatPanel panel;
    String sessionKey;
    String previousJMeterHome;

    @BeforeEach
    void setUp() throws Exception {
        sessionKey = InstanceContext.currentSessionKey();
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, sessionKey), new GatedScriptAiService());

        previousJMeterHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(tempDir.toString());

        panel = new AiChatPanel();
        JComboBox<?> selector = field(panel, "modelSelector");
        AwaitUtil.awaitUntil(() -> selector.getSelectedItem() != null,
                "loadModelsInBackground.done() landed");
        SwingUtilities.invokeAndWait(() -> { });
        setField(panel, "agentLoop", loop);
        loop.addTurnSubscriber(panel);
    }

    @AfterEach
    void tearDown() throws Exception {
        loop.removeTurnSubscriber(panel);
        AgentLoopFactory.removeTurnSubscriber(panel);
        AwaitUtil.awaitUntil(() -> loop.activeTurn(sessionKey).isEmpty(),
                "active turn drained before teardown");
        loop.shutdown();
        AgentLoopFactory.reset();
        if (previousJMeterHome != null) {
            JMeterUtils.setJMeterHome(previousJMeterHome);
        }
    }

    private static <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private String visibleText() {
        try {
            TranscriptView transcript = field(panel, "transcript");
            return transcript.visibleTextForTests();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void streamedTurnRendersIncrementallyAndCompletesInPlace() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "hi", false);
        panel.onTurnEvent(TurnEvent.started(turn));
        AwaitUtil.awaitUntil(() -> onEdtBool(t -> t.isThinkingShowing()),
                "loading indicator armed after turn start");

        // 流段增量（懒 STREAM_STARTED + 累计 delta）
        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.stream(
                StreamDelta.started(1L, 1))));
        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.stream(
                StreamDelta.contentDelta(1L, 1, "STREAM-ANSWER"))));
        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.stream(
                StreamDelta.reasoningDelta(1L, 1, "STREAM-REASON"))));

        AwaitUtil.awaitUntil(() -> onEdtBool(t -> t.visibleTextForTests().contains("STREAM-ANSWER")),
                "streamed content rendered after coalesced flush");
        AwaitUtil.awaitUntil(() -> !onEdtBool(t -> t.isThinkingShowing()),
                "loading removed at first visible delta (not at turn start)");

        TranscriptView transcript = field(panel, "transcript");
        // 流式卡 = 当前列表末卡（其前是欢迎卡与 You 行，索引不固定）
        int streamedCardIndex = onEdtGet(transcript::getCardCount) - 1;
        MessageCard streamedCard = onEdtGet(() -> transcript.getCard(streamedCardIndex));
        int cardsBefore = onEdtGet(transcript::getCardCount);

        // 已流式回合：INTERMEDIATE 全文回放被渲染端丢弃（发射端仍交付，IPC 口径不变）
        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.intermediateResponse("STREAM-ANSWER")));
        // THINKING 全文同口径：adapter 照常发布（程序化消费者可收），面板不重复渲染
        panel.onTurnEvent(TurnEvent.progress(turn,
                ProgressUpdate.thinking("<think>STREAM-REASON</think>\nSTREAM-ANSWER")));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(cardsBefore, onEdtGet(transcript::getCardCount),
                "intermediate full-text replay dropped for streamed turn");
        assertEquals(1, countOccurrences(visibleText(), "STREAM-ANSWER"),
                "THINKING full-text replay dropped at panel render (content appears once)");

        // 成功终态 + streamedFinal：原位重渲权威终答（含 goal 后缀形态差异）
        AgentResponse finalResponse = AgentResponse.success("STREAM-ANSWER[Goal still active]")
                .toBuilder().streamedFinal(true).finalStreamId(1L).build();
        panel.onTurnEvent(TurnEvent.completed(turn, finalResponse));
        AwaitUtil.awaitUntil(() -> onEdtBool(t ->
                        t.getCard(streamedCardIndex).getText().contains("[Goal still active]")),
                "final content rendered in place");

        assertEquals(cardsBefore, onEdtGet(transcript::getCardCount),
                "completion renders in place, no duplicate card");
        assertSame(streamedCard, onEdtGet(() -> transcript.getCard(streamedCardIndex)),
                "same card instance survives delta→replacement");
    }

    @Test
    void streamedFinalWithoutCardFallsBackToLegacyAppend() throws Exception {
        TurnHandle turn = new TurnHandle(sessionKey, TurnOrigin.LOCAL_PANEL, "hi", false);
        panel.onTurnEvent(TurnEvent.started(turn));

        // 纯思考流（无正文卡）+ streamedFinal：查无卡片 → 回退既有新增卡路径
        panel.onTurnEvent(TurnEvent.progress(turn, ProgressUpdate.stream(
                StreamDelta.reasoningDelta(7L, 1, "ONLY-REASONING"))));
        AwaitUtil.awaitUntil(() -> onEdtBool(t ->
                        t.getStreamThinkingCard(turn.id()) != null),
                "stream thinking card created");

        AgentResponse finalResponse = AgentResponse.success("FALLBACK-FINAL")
                .toBuilder().streamedFinal(true).finalStreamId(7L).build();
        panel.onTurnEvent(TurnEvent.completed(turn, finalResponse));
        AwaitUtil.awaitUntil(() -> onEdtBool(t ->
                        t.visibleTextForTests().contains("FALLBACK-FINAL")),
                "fallback final rendered via legacy path");
    }

    private interface TranscriptCheck {
        boolean check(TranscriptView t) throws Exception;
    }

    private boolean onEdtBool(TranscriptCheck check) {
        boolean[] result = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TranscriptView t = field(panel, "transcript");
                    result[0] = check.check(t);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result[0];
    }

    private <T> T onEdtGet(java.util.function.Supplier<T> supplier) throws Exception {
        Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> out[0] = supplier.get());
        return (T) out[0];
    }
}
