package org.gitee.jmeter.ai.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.service.provider.OpenAICompatibleProvider;
import org.gitee.jmeter.ai.service.provider.ProviderSpec;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端到端复现 2026-08-23 16:53 记忆整合降级事件：真实 {@link OpenAICompatibleProvider}
 * （SDK client 替换为返回截断响应的 mock）接入 {@link MemoryConsolidator}，
 * 验证完整链路的可观测后果。
 *
 * <p>链路：模型输出被 {@code finish_reason=length} 截断 → save_memory 参数 JSON
 * 解析失败被 provider 丢弃（该层单测见
 * {@code OpenAICompatibleProviderTruncatedToolArgsTest}）→ 三次 AI 整合尝试全部
 * 「未调用工具」→ 降级 raw archive（对应日志 attempt 1/3、2/3、3/3 与
 * "All AI consolidation attempts failed, falling back to raw archive"）。
 *
 * <p>同时固化当前行为的两个已知缺陷表现：
 * <ul>
 *   <li>MEMORY.md 未得到任何 AI 更新（writeLongTermMemory 从未被调用）</li>
 *   <li>同一批消息被 raw archive <b>两次</b>：handleConsolidationFailure 在第 3 次
 *       失败时归档一次，archiveMessagesAsync 兜底又归档一次——日志 16:54:51,221 与
 *       16:54:51,229 两行 "raw-archived 6 messages" 的根因。若未来修复为只归档一次，
 *       把 times(2) 改成 times(1)，此断言即回归哨兵。</li>
 * </ul>
 */
class MemoryConsolidatorTruncatedOutputTest {

    /** 日志中第一次截断发生的列号（JsonEOFException at column 13260）。 */
    private static final int INCIDENT_COLUMN = 13_260;

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    private MemoryStore memoryStore;
    private OpenAICompatibleProvider provider;
    private ChatCompletionService completions;
    private MemoryConsolidator consolidator;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getProperty(anyString(), anyString())).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getDefaultModel()).thenReturn("deepseek:deepseek-v4-flash-0731");
        aiConfigMock.when(() -> AiConfig.getTemperature()).thenReturn(0.7);
        aiConfigMock.when(() -> AiConfig.getMaxTokens()).thenReturn(65536);
        aiConfigMock.when(() -> AiConfig.getReasoningEffort()).thenReturn("medium");
        aiConfigMock.when(() -> AiConfig.getContextWindowTokens()).thenReturn(512000);

        systemPromptMock = mockStatic(SystemPrompt.class);
        systemPromptMock.when(SystemPrompt::get).thenReturn("test system prompt");
    }

    @AfterAll
    static void tearDownAll() {
        if (systemPromptMock != null) systemPromptMock.close();
        if (aiConfigMock != null) aiConfigMock.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        // 真实 provider：SDK client 替换为永远返回「截断响应」的 mock 链
        ProviderSpec spec = new ProviderSpec.Builder()
                .name("deepseek")
                .displayName("DeepSeek")
                .defaultApiBase("https://api.deepseek.com/v1")
                .envKey("deepseek.api.key")
                .build();
        provider = new OpenAICompatibleProvider(spec);

        completions = mock(ChatCompletionService.class);
        // 先构造响应再进入 when(...)：构造若在中途抛异常会留下未完成的 stubbing，
        // 污染同 JVM 内后续测试类的 Mockito 框架状态
        ChatCompletion truncated = truncatedSaveMemoryCompletion();
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(truncated);
        ChatService chat = mock(ChatService.class);
        when(chat.completions()).thenReturn(completions);
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.chat()).thenReturn(chat);
        Field f = OpenAICompatibleProvider.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(provider, client);

        // MemoryStore mock：与事件现场同规模（MEMORY.md ~13k、6 条待整合消息）
        memoryStore = mock(MemoryStore.class);
        when(memoryStore.isEnabled()).thenReturn(true);
        when(memoryStore.readLongTermMemory()).thenReturn(incidentScaleMemory());
        when(memoryStore.lockLongTermMemory(any()))
                .thenReturn(mock(MemoryStore.MemoryWriteLock.class));
        when(memoryStore.appendHistory(anyString())).thenReturn(true);

        consolidator = new MemoryConsolidator(memoryStore, provider, null, null, null);
    }

    @Test
    void truncatedOutput_threeAiAttemptsFail_degradesToDoubleRawArchive() throws Exception {
        // 事件现场的 6 条未整合消息（16:45/16:51 两轮磁盘检查 + 闲聊）
        List<Message> sixMessages = List.of(
                Message.user("你好你检查下当前磁盘使用率"),
                Message.assistant("C: 92.8% used; D: 84.3% used."),
                Message.user("你好你检查下当前磁盘使用率"),
                Message.assistant("同上，无变化。"),
                Message.user("我喜欢吃苹果"),
                Message.assistant("哈哈，苹果不错"));

        Boolean ok = consolidator.archiveMessagesAsync(sixMessages).get(30, TimeUnit.SECONDS);

        // 兜底路径「成功」返回 true（消息没丢，但没经过 AI 精炼）
        assertTrue(ok);

        // 三次 AI 尝试（日志：attempt 1/3、2/3、3/3），每次都拿到截断响应；
        // forced 调用未报错，故无 Step-2 auto 重试 —— 恰好 3 次 SDK 调用
        verify(completions, times(3)).create(any(ChatCompletionCreateParams.class));

        // MEMORY.md 从未被 AI 更新（截断 → 无 tool call → 不落盘）
        verify(memoryStore, never()).writeLongTermMemory(anyString());

        // 双重 raw archive（当前缺陷行为，javadoc 详述）：
        // 两次 appendHistory 的内容都含 "[RAW] 6 messages" 块
        verify(memoryStore, times(2))
                .appendHistory(argThat((String entry) -> entry.contains("[RAW] 6 messages")));
    }

    // ==================== fixtures ====================

    /** 与事件同规模的 MEMORY.md（~13k 字符，日志 rawArguments 中可见的全文复述规模）。 */
    private static String incidentScaleMemory() {
        StringBuilder sb = new StringBuilder("# Long-term Memory\n\n## User & Environment\n");
        for (int i = 1; sb.length() < 13_000; i++) {
            sb.append("- memory fact ").append(i).append(" kept from earlier sessions.\n");
        }
        return sb.toString();
    }

    /** 截断的 save_memory 参数：完整 JSON 在列 13260 处被切断（字符串值内部）。 */
    private static String truncatedArguments() throws Exception {
        StringBuilder memory = new StringBuilder("# Long-term Memory\n\n## User & Environment\n");
        for (int i = 1; memory.length() < 20_000; i++) {
            memory.append("- fact line ").append(i).append(" kept from earlier sessions.\n");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("history_entry",
                "[2026-08-23 16:51] User asked disk usage check. C: 92.8% used; D: 84.3% used.");
        args.put("memory_update", memory.toString());
        String full = new ObjectMapper().writeValueAsString(args);
        if (full.length() <= INCIDENT_COLUMN) {
            throw new IllegalStateException("fixture 必须超过事件规模");
        }
        return full.substring(0, INCIDENT_COLUMN);
    }

    /** 与日志同形态的 ChatCompletion：finish_reason=length + 截断的 save_memory 调用。 */
    private static ChatCompletion truncatedSaveMemoryCompletion() throws Exception {
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .content("。。。")
                // SDK 将 refusal 标记为必填（可显式缺席，不可不设）
                .refusal(java.util.Optional.empty())
                .addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                        .id("call_0")
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name("save_memory")
                                .arguments(truncatedArguments())
                                .build())
                        .build())
                .build();
        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0L)
                .finishReason(ChatCompletion.Choice.FinishReason.LENGTH)
                .message(message)
                // SDK 将 logprobs 标记为必填（可显式缺席，不可不设）
                .logprobs(java.util.Optional.empty())
                .build();
        return ChatCompletion.builder()
                .id("chatcmpl-test")
                .created(1787480000L)
                .model("deepseek-v4-flash-0731")
                .object_(JsonValue.from("chat.completion"))
                .addChoice(choice)
                .usage(CompletionUsage.builder()
                        .promptTokens(7262L)
                        .completionTokens(4785L)
                        .totalTokens(12047L)
                        .build())
                .build();
    }
}
