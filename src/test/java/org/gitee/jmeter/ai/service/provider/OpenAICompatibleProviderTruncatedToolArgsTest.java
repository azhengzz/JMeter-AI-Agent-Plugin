package org.gitee.jmeter.ai.service.provider;

import com.fasterxml.jackson.core.io.JsonEOFException;
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
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 复现 2026-08-23 16:53 生产日志 (jmeter-error.log) 的输出截断问题。
 *
 * <p>事件链：save_memory 要求在工具参数里逐字复述整份 MEMORY.md（~13k+ 字符），
 * 模型输出超过服务端有效输出上限被 {@code finish_reason=length} 截断，参数 JSON
 * 在字符串值中间被切断；{@code doGenerateWithTools} 用 Jackson 解析这段残缺 JSON
 * 抛 {@code JsonEOFException: Unexpected end-of-input: was expecting closing quote}，
 * 该 tool call 被静默丢弃——上游只看到「未调用工具, finishReason=length」。
 *
 * <p>与 {@link OpenAICompatibleProviderTest} 的分工：那一个只测纯逻辑（不构造 SDK
 * 字段）；本类构造 SDK 响应对象，验证截断响应在 provider 内的实际走向。
 */
class OpenAICompatibleProviderTruncatedToolArgsTest {

    /** 日志中第一次截断发生的列号（JsonEOFException at column 13260）。 */
    private static final int INCIDENT_COLUMN = 13_260;

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    private OpenAICompatibleProvider provider;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getProperty(anyString(), anyString())).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getDefaultModel()).thenReturn("deepseek:deepseek-v4-flash-0731");
        aiConfigMock.when(() -> AiConfig.getTemperature()).thenReturn(0.7);
        aiConfigMock.when(() -> AiConfig.getMaxTokens()).thenReturn(65536);
        aiConfigMock.when(() -> AiConfig.getReasoningEffort()).thenReturn("medium");

        systemPromptMock = mockStatic(SystemPrompt.class);
        systemPromptMock.when(SystemPrompt::get).thenReturn("test system prompt");
    }

    @AfterAll
    static void tearDownAll() {
        if (systemPromptMock != null) systemPromptMock.close();
        if (aiConfigMock != null) aiConfigMock.close();
    }

    @BeforeEach
    void setUp() {
        ProviderSpec spec = new ProviderSpec.Builder()
                .name("deepseek")
                .displayName("DeepSeek")
                .defaultApiBase("https://api.deepseek.com/v1")
                .envKey("deepseek.api.key")
                .build();
        provider = new OpenAICompatibleProvider(spec);
    }

    @Test
    void truncatedToolArguments_dropped_finishReasonLength() throws Exception {
        String full = fullSaveMemoryArguments();
        assertTrue(full.length() > INCIDENT_COLUMN, "fixture 必须超过事件规模 (~13k 字符)");
        // 事件签名：在列 13260 处截断，断点必须落在字符串值内部
        String truncated = full.substring(0, INCIDENT_COLUMN);
        JsonEOFException fixtureError = assertThrows(JsonEOFException.class, () ->
                new ObjectMapper().readValue(truncated, new TypeReference<Map<String, Object>>() {}));
        assertTrue(fixtureError.getMessage().contains("Unexpected end-of-input"),
                "fixture 自检：截断点必须复现日志同款 EOF 错误");

        mockSdkReturning(completionWithToolArgs(truncated, ChatCompletion.Choice.FinishReason.LENGTH));

        LLMResponse response = provider.generateResponseWithTools(
                List.of(Message.user("整合这批消息")), List.of(saveMemoryToolDef()));

        // HTTP 层是成功的（日志中 LangSmith status=success），不报错
        assertFalse(response.isError());
        // 截断的 save_memory 调用被静默丢弃——这正是 ERROR 日志的可观测后果
        assertFalse(response.hasToolCalls());
        // 上游 (MemoryConsolidator) 只能看到 finishReason=length，据此判「未调用工具」
        assertEquals("length", response.getFinishReason());
        assertEquals("。。。", response.getContent());
        assertEquals(4785, response.getUsage().get("completion_tokens"));
    }

    @Test
    void forcedToolPath_alsoDropsTruncatedCall() throws Exception {
        // 日志中第 2/3 次尝试走 generateResponseWithForcedTool，同样截断失败
        String truncated = fullSaveMemoryArguments().substring(0, INCIDENT_COLUMN);
        mockSdkReturning(completionWithToolArgs(truncated, ChatCompletion.Choice.FinishReason.LENGTH));

        LLMResponse response = provider.generateResponseWithForcedTool(
                List.of(Message.user("整合这批消息")), List.of(saveMemoryToolDef()), "save_memory");

        assertFalse(response.isError());
        assertFalse(response.hasToolCalls());
        assertEquals("length", response.getFinishReason());
    }

    @Test
    void control_untruncatedArguments_toolCallSurvives() throws Exception {
        // 对照组：同一套构造，仅参数 JSON 完整（未截断）→ tool call 正常存活。
        // 证明上面的丢弃行为确由截断引起，而非 fixture 其他部分。
        String full = fullSaveMemoryArguments();
        mockSdkReturning(completionWithToolArgs(full, ChatCompletion.Choice.FinishReason.TOOL_CALLS));

        LLMResponse response = provider.generateResponseWithTools(
                List.of(Message.user("整合这批消息")), List.of(saveMemoryToolDef()));

        assertTrue(response.hasToolCalls());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("save_memory", response.getToolCalls().get(0).getName());
        assertTrue(response.getToolCalls().get(0).getArguments().containsKey("history_entry"));
        assertTrue(response.getToolCalls().get(0).getArguments().containsKey("memory_update"));
    }

    // ==================== fixtures ====================

    /**
     * 事件级规模的完整 save_memory 参数：history_entry + memory_update（全量
     * MEMORY.md 复述）。序列化后 ~20k 字符，覆盖日志中 13260 列的截断点。
     */
    private static String fullSaveMemoryArguments() throws Exception {
        StringBuilder memory = new StringBuilder("# Long-term Memory\n\n## User & Environment\n");
        for (int i = 1; memory.length() < 20_000; i++) {
            memory.append("- fact line ").append(i).append(" kept from earlier sessions.\n");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("history_entry",
                "[2026-08-23 16:51] User asked disk usage check. C: 92.8% used; D: 84.3% used.");
        args.put("memory_update", memory.toString());
        return new ObjectMapper().writeValueAsString(args);
    }

    /** 构造与日志同形态的 ChatCompletion：finish_reason=length + 截断的 save_memory 调用。 */
    private static ChatCompletion completionWithToolArgs(String arguments,
            ChatCompletion.Choice.FinishReason finishReason) {
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .content("。。。")
                // SDK 将 refusal 标记为必填（可显式缺席，不可不设）
                .refusal(java.util.Optional.empty())
                .addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                        .id("call_0")
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name("save_memory")
                                .arguments(arguments)
                                .build())
                        .build())
                .build();
        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0L)
                .finishReason(finishReason)
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

    private static ToolDefinition saveMemoryToolDef() {
        return ToolDefinition.builder()
                .name("save_memory")
                .description("Save the memory consolidation result.")
                .parameters(Map.of("type", "object"))
                .build();
    }

    /** 把 provider 的 SDK client 替换为返回指定响应的 mock 链（client→chat→completions）。 */
    private void mockSdkReturning(ChatCompletion completion) throws Exception {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);
        ChatService chat = mock(ChatService.class);
        when(chat.completions()).thenReturn(completions);
        OpenAIClient client = mock(OpenAIClient.class);
        when(client.chat()).thenReturn(chat);

        Field f = OpenAICompatibleProvider.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(provider, client);
    }
}
