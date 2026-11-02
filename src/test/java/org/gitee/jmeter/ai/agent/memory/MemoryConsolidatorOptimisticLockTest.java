package org.gitee.jmeter.ai.agent.memory;

import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 乐观并发锁范围回归:LLM 在锁外、写盘在锁内「重读-验证-写」。
 *
 * <p>冲突路径(快照在 LLM 期间被他实例改写)是本重构的核心不变式——现有
 * {@code MemoryConsolidatorTruncatedOutputTest} 的 mock 恒返同值,永远探测不到:
 * <ol>
 *   <li>冲突触发带最新快照的重试(最终写盘基于 M1,不丢并发写);</li>
 *   <li>冲突不是失败:不重复 appendHistory、不产生 [RAW] 归档;</li>
 *   <li>读失败(IO)放弃整合,不以空串为快照基线写盘。</li>
 * </ol>
 */
class MemoryConsolidatorOptimisticLockTest {

    private static final String M0 = "# Memory M0\n- baseline fact";
    private static final String M1 = "# Memory M1\n- baseline fact\n- peer instance wrote this";
    private static final String M2 = M1 + "\n- second peer write";

    private MemoryStore memoryStore;
    private AiService aiService;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() throws Exception {
        memoryStore = mock(MemoryStore.class);
        when(memoryStore.isEnabled()).thenReturn(true);
        when(memoryStore.lockLongTermMemory(any()))
                .thenReturn(mock(MemoryStore.MemoryWriteLock.class));
        when(memoryStore.appendHistory(anyString())).thenReturn(true);
        when(memoryStore.writeLongTermMemory(anyString())).thenReturn(true);

        aiService = mock(AiService.class);
        consolidator = new MemoryConsolidator(memoryStore, aiService, null, null, null);
    }

    /** fake LLM:从 prompt 里解析当前记忆,产出「当前记忆 + 新事实」的全文重写。 */
    private static LLMResponse echoMemoryUpdate(List<Message> chatMessages) {
        String currentMemory = "";
        for (Message m : chatMessages) {
            String c = m.getContent();
            if (c != null && c.contains("## Current Long-term Memory")) {
                int from = c.indexOf("## Current Long-term Memory") + "## Current Long-term Memory".length();
                int to = c.indexOf("## Conversation to Process");
                currentMemory = c.substring(from, to).trim();
                if (currentMemory.equals("(empty)")) {
                    currentMemory = "";
                }
            }
        }
        ToolCall call = new ToolCall("save_memory", Map.of(
                "history_entry", "[2026-10-31 12:00] Conflict-path regression consolidated a session.",
                "memory_update", currentMemory + "\n- new fact from this session"));
        return LLMResponse.withToolCalls(List.of(call), null);
    }

    @Test
    void snapshotConflict_retriesWithFreshSnapshot_writesPeerInclusiveMemory() throws Exception {
        // 严格读链:首次 M0(LLM 期间被改写) → 其后恒 M1
        when(memoryStore.readLongTermMemoryStrictly()).thenReturn(M0, M1, M1, M1);
        Mockito.when(aiService.generateResponseWithForcedTool(anyList(), anyList(), anyString()))
                .thenAnswer(inv -> echoMemoryUpdate(inv.getArgument(0)));

        Boolean ok = consolidator.archiveMessagesAsync(List.of(
                Message.user("conflict-path"), Message.assistant("ok"))).get(30, TimeUnit.SECONDS);

        assertTrue(ok, "conflict retry must converge to a successful write");
        // 冲突触发第二轮 LLM(带最新快照 M1)
        verify(aiService, times(2)).generateResponseWithForcedTool(anyList(), anyList(), anyString());
        // 最终写盘基于 M1:含对端写入的事实与新事实(不丢并发写)
        verify(memoryStore).writeLongTermMemory(argThat((String s) ->
                s != null && s.contains("peer instance wrote this") && s.contains("new fact from this session")));
        // 两门写恰一次 HISTORY 追加,且不是 [RAW] 归档(冲突不计失败)
        verify(memoryStore, times(1)).appendHistory(argThat((String e) -> e != null && !e.contains("[RAW]")));
    }

    @Test
    void noConflict_writesOnFirstAttempt_singleLlmCall() throws Exception {
        when(memoryStore.readLongTermMemoryStrictly()).thenReturn(M0, M0, M0, M0);
        Mockito.when(aiService.generateResponseWithForcedTool(anyList(), anyList(), anyString()))
                .thenAnswer(inv -> echoMemoryUpdate(inv.getArgument(0)));

        Boolean ok = consolidator.archiveMessagesAsync(List.of(
                Message.user("happy path"), Message.assistant("ok"))).get(30, TimeUnit.SECONDS);

        assertTrue(ok);
        verify(aiService, times(1)).generateResponseWithForcedTool(anyList(), anyList(), anyString());
        verify(memoryStore, times(1)).writeLongTermMemory(anyString());
        verify(memoryStore, times(1)).appendHistory(anyString());
    }

    @Test
    void persistentConflict_fallsBackToLockedFullPass_replacingThirdAttempt() throws Exception {
        // 读链:尝试1 m0=M0 → 锁内 m1=M1(冲突);尝试2 m0=M1 → 锁内 m1=M2(再冲突);
        // 兜底持锁完整流程读到 M2。「替换非追加」上限语义:2 乐观 + 1 兜底 = 恰 3 次 LLM
        when(memoryStore.readLongTermMemoryStrictly()).thenReturn(M0, M1, M1, M2, M2);
        Mockito.when(aiService.generateResponseWithForcedTool(anyList(), anyList(), anyString()))
                .thenAnswer(inv -> echoMemoryUpdate(inv.getArgument(0)));

        Boolean ok = consolidator.archiveMessagesAsync(List.of(
                Message.user("persistent conflict"), Message.assistant("ok"))).get(30, TimeUnit.SECONDS);

        assertTrue(ok, "locked fallback must converge to a successful write");
        verify(aiService, times(3)).generateResponseWithForcedTool(anyList(), anyList(), anyString());
        // 最终写盘基于兜底锁内读到的 M2:含对端两次写入与新事实(不丢并发写)
        verify(memoryStore).writeLongTermMemory(argThat((String s) ->
                s != null && s.contains("peer instance wrote this") && s.contains("second peer write")
                        && s.contains("new fact from this session")));
        // 两次 conflict 均未混入失败计数:恰一次 HISTORY 追加且非 [RAW] 归档
        verify(memoryStore, times(1)).appendHistory(argThat((String e) -> e != null && !e.contains("[RAW]")));
    }

    @Test
    void readFailure_abortsWithoutWriting_neverTreatsErrorAsEmptyBaseline() throws Exception {
        // 读 IO 故障 → 放弃本次 AI 整合:MEMORY.md 绝不被写(不把空串当快照基线覆盖既有
        // 记忆)、LLM 从不被调。外层 archiveMessagesAsync 的 raw 兜底(消息不丢)仍会
        // 归档 HISTORY 并返回 true——HISTORY 追加不依赖 MEMORY 读,属既有正确设计
        when(memoryStore.readLongTermMemoryStrictly())
                .thenThrow(new java.io.IOException("disk error"));

        consolidator.archiveMessagesAsync(List.of(
                Message.user("read fails"), Message.assistant("ok"))).get(30, TimeUnit.SECONDS);

        verify(memoryStore, never()).writeLongTermMemory(anyString());
        verify(aiService, never()).generateResponseWithForcedTool(anyList(), anyList(), anyString());
    }
}
