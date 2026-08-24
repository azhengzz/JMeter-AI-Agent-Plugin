package org.gitee.jmeter.ai.service.provider;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * 真实 LLM 版的 save_memory 输出截断复现（手动冒烟，默认跳过）。
 *
 * <p>离线复现（{@code OpenAICompatibleProviderTruncatedToolArgsTest} 注入合成的截断响应）
 * 之外，本类发起<b>真实 HTTP 请求</b>：构造真实 {@link OpenAICompatibleProvider}（注册的
 * deepseek spec，thinking_type 样式与事故现场一致），配置面经 MockedStatic 桥接自
 * {@code -D} 系统属性（{@code JMeterUtils.appProperties} 在测试 JVM 为 null，-D 无法直达），
 * client / HTTP / SDK / Jackson 解析全程无 mock。
 *
 * <p>未设 {@code -Ddeepseek.smoke.key} 时自动跳过（计入 skipped），不影响常规
 * {@code mvn test}。手动运行（CMD 下含 URL 的 -D 必须整体加引号）：
 * <pre>
 *   mvn test -Dtest=SaveMemoryTruncationSmokeTest \
 *     "-Ddeepseek.smoke.key=sk-..." \
 *     "-Ddeepseek.smoke.base=https://api.moark.com/v1" \
 *     "-Ddeepseek.smoke.model=deepseek-v4-flash-0731"
 * </pre>
 *
 * <p><b>2026-08-24 17:01 双网关对照实测（deepseek-v4-flash-0731，20k 中英混排记忆，prompt ~7717 tok）：</b>
 * 两个网关行为截然相反，事故只发生在 moark（用户的内部网关）：
 * <ul>
 *   <li><b>{@code max_completion_tokens} 被两个网关都完全忽略</b>：请求 600，moark 实际
 *       completion=4555、deepseek 官方=7976——插件发的 65536 全部无效（事故第一根因）。</li>
 *   <li><b>moark 有 ~4.5k 服务端输出上限</b>：auto/forced 两路径 completion 全被钳在
 *       4339~4555 并 {@code finish_reason=length} 截断；deepseek 官方能写到 7983 完整复述
 *       （memory_update_len=20332）。事故发生在 moark 环境的原因。</li>
 *   <li><b>原生 {@code max_tokens} 官方严格尊重（600→600），moark 不严格（600→1214）</b>：
 *       改发 max_tokens 是必要不充分修复；moark 环境下仍被 ~4.5k 上限截断。</li>
 *   <li>forced+thinking：moark 一次 400（14:12）、一次 200+截断（17:01），拒绝与路由/后端
 *       相关、时有时无；deepseek 官方稳定 400 "Thinking mode does not support this
 *       tool_choice"。插件已有的 forced→auto 降级分支覆盖此形态。</li>
 * </ul>
 * <b>结论：光改参数救不了 moark 环境。</b> ~4.5k 上限是服务端写死的，只要 save_memory 要求
 * 「整份 MEMORY.md 全量复述」且记忆超 ~4.5k token 就必然截断；真解在记忆侧限幅/增量。详见
 * 项目记忆 {@code deepseek-moark-output-cap}。
 *
 * <p>四个用例各探一个问题：
 * <ul>
 *   <li>{@code forcedToolChoice_thinkingMode_gatewayResponse} — forced+thinking 现在是否仍被网关接受（事故 attempts 2/3 形态）。</li>
 *   <li>{@code autoToolChoice_echoFullMemory_liveIncidentReplay} — 事故 attempt 1 形态（auto 路径，
 *       网关当前唯一可用），20k 密集记忆复述重放，观察是否撞服务端默认上限被 length 截断。</li>
 *   <li>{@code maxCompletionTokens_600requested_probeGatewayHonors} — 请求预算 600，实测
 *       completion 是否远超（验证网关忽略该参数）。</li>
 *   <li>{@code nativeMaxTokens_600_probe} — 原生 {@code max_tokens} 探针（raw SDK 直发）：
 *       验证修复方向「对 DeepSeek 系端点显式发 max_tokens」是否真能控制预算。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "deepseek.smoke.key", matches = ".+")
class SaveMemoryTruncationSmokeTest {

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    private static String key() {
        return System.getProperty("deepseek.smoke.key");
    }

    private static String base() {
        return System.getProperty("deepseek.smoke.base", "https://api.deepseek.com");
    }

    private static String model() {
        return System.getProperty("deepseek.smoke.model", "deepseek-v4-flash");
    }

    @BeforeAll
    static void setUpAll() {
        // 配置桥：只替换 AiConfig 的读取（等价于生产里 user.properties 的角色），
        // LLM 请求链零 mock
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getProperty("deepseek.api.key", "")).thenReturn(key());
        aiConfigMock.when(() -> AiConfig.getProperty(eq("deepseek.api.base.url"), anyString()))
                .thenReturn(base());
        aiConfigMock.when(() -> AiConfig.getDefaultModel()).thenReturn(model());
        aiConfigMock.when(() -> AiConfig.getTemperature()).thenReturn(0.7);
        aiConfigMock.when(() -> AiConfig.getReasoningEffort()).thenReturn("medium");

        systemPromptMock = mockStatic(SystemPrompt.class);
        systemPromptMock.when(SystemPrompt::get).thenReturn("test system prompt");
    }

    @AfterAll
    static void tearDownAll() {
        if (systemPromptMock != null) systemPromptMock.close();
        if (aiConfigMock != null) aiConfigMock.close();
    }

    /** 用指定输出预算构造真实 provider（每次重新构造以读取不同的 maxTokens）。 */
    private OpenAICompatibleProvider newProvider(int maxTokens) {
        aiConfigMock.when(() -> AiConfig.getMaxTokens()).thenReturn(maxTokens);
        return new OpenAICompatibleProvider(ProviderRegistry.findByName("deepseek"));
    }

    /** 事故 attempts 2/3 形态：forced tool_choice + thinking + 65536 预算。2026-08-24 实测网关 400。 */
    @Test
    void forcedToolChoice_thinkingMode_gatewayResponse() {
        OpenAICompatibleProvider provider = newProvider(65536);

        LLMResponse response = provider.generateResponseWithForcedTool(
                consolidationPrompt(), List.of(saveMemoryToolDef()), "save_memory");

        report("forced+thinking maxTokens=65536（事故 attempts 2/3 形态）", response);
        assertNotNull(response.getFinishReason(), "必须收到响应（含 400 错误响应）");
    }

    /** 事故 attempt 1 形态：auto tool_choice + thinking + 65536 预算 + 20k 密集记忆复述。 */
    @Test
    void autoToolChoice_echoFullMemory_liveIncidentReplay() {
        OpenAICompatibleProvider provider = newProvider(65536);

        LLMResponse response = provider.generateResponseWithTools(
                consolidationPrompt(), List.of(saveMemoryToolDef()));

        report("auto+thinking maxTokens=65536（事故 attempt 1 形态，活体重放）", response);
        assertNotNull(response.getFinishReason(), "必须收到响应");
        if (!response.isError() && "length".equals(response.getFinishReason())) {
            // 事故签名：length 截断的 save_memory 参数解析失败被 provider 静默丢弃
            assertFalse(response.hasToolCalls(),
                    "finish_reason=length 时截断的 tool call 应已被 provider 丢弃（事故签名）");
        }
    }

    /** 预算探针：请求 max_completion_tokens=600，观察实际 completion_tokens（2026-08-24 实测 3220 = 被忽略）。 */
    @Test
    void maxCompletionTokens_600requested_probeGatewayHonors() {
        OpenAICompatibleProvider provider = newProvider(600);

        LLMResponse response = provider.generateResponseWithTools(
                consolidationPrompt(), List.of(saveMemoryToolDef()));

        report("预算探针 requested max_completion_tokens=600", response);
        assertNotNull(response.getFinishReason(), "必须收到响应");
        Integer actual = response.getUsage() == null ? null : response.getUsage().get("completion_tokens");
        if (actual != null) {
            System.out.println("[smoke] verdict: requested=600 actual_completion_tokens=" + actual
                    + " → " + (actual > 600 ? "网关忽略 max_completion_tokens（事故根因实证）"
                                            : "网关尊重该参数"));
        }
    }

    /** 原生参数探针：raw SDK 直发 max_tokens=600（DeepSeek 系端点认的参数），验证修复方向。 */
    @Test
    void nativeMaxTokens_600_probe() {
        var client = OpenAIOkHttpClient.builder().apiKey(key()).baseUrl(base()).build();
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model())
                .maxTokens(600L)
                .addSystemMessage("Echo the user's text verbatim. Output nothing else.")
                .addUserMessage(syntheticMemory())
                .putAdditionalBodyProperty("thinking",
                        com.openai.core.JsonValue.from(Map.of("type", "enabled")))
                .build();

        ChatCompletion resp = client.chat().completions().create(params);
        String finishReason = resp.choices().get(0).finishReason().toString();
        long completion = resp.usage().map(u -> u.completionTokens()).orElse(-1L);

        System.out.println("[smoke] ===== native max_tokens=600 probe（raw SDK） =====");
        System.out.println("[smoke] finishReason=" + finishReason + " completion_tokens=" + completion);
        boolean honored = "length".equalsIgnoreCase(finishReason) && completion <= 700;
        System.out.println("[smoke] verdict: " + (honored
                ? "端点尊重原生 max_tokens → 修复方向成立（显式发 max_tokens 可控预算）"
                : "原生 max_tokens 同样未被尊重（completion=" + completion + "）→ 预算只能靠服务端默认值"));
        assertNotNull(finishReason, "必须收到响应");
    }

    // ==================== fixtures ====================

    /** ~20k 字符中英混排记忆（CJK 提高 token 密度，对齐事故 prompt ~7k token 的规模）。 */
    private static String syntheticMemory() {
        StringBuilder memory = new StringBuilder("# Long-term Memory\n\n## User & Environment\n");
        for (int i = 1; memory.length() < 20_000; i++) {
            memory.append("- fact line ").append(i).append(" kept from earlier sessions.\n");
            memory.append("- 第").append(i).append("条早期会话保留的事实记录，包含当时的上下文与结论。\n");
        }
        return memory.toString();
    }

    /**
     * 与 MemoryConsolidator.consolidateWithAiUnderLock 同款的整合 prompt：
     * 整份 MEMORY.md 复述 + 待整合对话。tool 参数 schema 要求
     * memory_update 返回全量记忆（"Include all existing facts plus new ones"）。
     */
    private static List<Message> consolidationPrompt() {
        String conversation = """
                user: 你好你检查下当前磁盘使用率
                assistant: C: 92.8% used; D: 84.3% used.
                user: 我喜欢吃苹果
                assistant: 哈哈，苹果不错
                """;
        return List.of(
                Message.system("You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."),
                Message.user("""
                        Process this conversation and call the save_memory tool with your consolidation.

                        ## Current Long-term Memory
                        %s

                        ## Conversation to Process
                        %s
                        """.formatted(syntheticMemory(), conversation)));
    }

    /** 与 MemoryConsolidator.SAVE_MEMORY_TOOL_DEF 同款 schema（含「全量复述」要求）。 */
    private static ToolDefinition saveMemoryToolDef() {
        return ToolDefinition.builder()
                .name("save_memory")
                .description("Save the memory consolidation result to persistent storage.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "history_entry", Map.of(
                                        "type", "string",
                                        "description", "A paragraph summarizing key events/decisions/topics. Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search."
                                ),
                                "memory_update", Map.of(
                                        "type", "string",
                                        "description", "Full updated long-term memory as markdown. Include all existing facts plus new ones. Return unchanged if nothing new."
                                )
                        ),
                        "required", List.of("history_entry", "memory_update")
                ))
                .build();
    }

    /** 打印诊断结论：是否复现截断（vs 复述完成 / 出错）。 */
    private static void report(String scenario, LLMResponse r) {
        System.out.println("[smoke] ===== " + scenario + " =====");
        System.out.println("[smoke] isError=" + r.isError()
                + (r.isError() ? " errorMessage=" + r.getErrorMessage() : ""));
        System.out.println("[smoke] finishReason=" + r.getFinishReason()
                + " hasToolCalls=" + r.hasToolCalls()
                + " content_len=" + (r.getContent() == null ? 0 : r.getContent().length()));
        System.out.println("[smoke] usage=" + r.getUsage());
        if (!r.isError() && r.hasToolCalls()) {
            Object memoryUpdate = r.getToolCalls().get(0).getArguments().get("memory_update");
            System.out.println("[smoke] 复述完成（未复现截断）: memory_update_len="
                    + (memoryUpdate == null ? 0 : String.valueOf(memoryUpdate).length()));
        } else if (!r.isError()) {
            System.out.println("[smoke] 复现截断: save_memory tool call 已被 provider 丢弃"
                    + "（JsonEOFException 详情见上方 ERROR 日志）");
        }
    }
}
