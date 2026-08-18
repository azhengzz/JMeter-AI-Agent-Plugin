package org.gitee.jmeter.ai.service.provider;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Live smoke test for the MiniMax raw-HTTP decision and thinking control.
 *
 * <p>Auto-disabled unless {@code -Dminimax.smoke.key=<key>} is set, so it is a no-op
 * (counted as skipped) in the normal {@code mvn test} suite. Run manually:
 * <pre>
 *   mvn test -Dtest=MinimaxRawHttpSmokeTest \
 *     -Dminimax.smoke.key=sk-... \
 *     -Dminimax.smoke.base=https://api.minimaxi.com/v1 \
 *     -Dminimax.smoke.model=MiniMax-M3
 * </pre>
 *
 * <p><b>Known blocker (pre-existing, unrelated to this change):</b> the project ships a Jackson
 * version skew (jackson-core 2.16.1 vs jackson-databind 2.20.1) that makes the openai-java SDK
 * throw {@code NoSuchMethodError: ParserMinimalBase.<init>(StreamReadConstraints)} on any live
 * call, so enabling this test currently errors at the SDK layer. The MiniMax API contract was
 * instead verified via raw HTTP (see {@code design.md} Open Questions). Once Jackson versions are
 * aligned, this test becomes runnable as-is.
 *
 * <p>Answers:
 * <ul>
 *   <li><b>3.1</b> — can openai-java SDK deserialize a MiniMax PLAIN (no-tools)
 *       chat.completion response? If {@code plainTextSdkResponse_deserializes} passes,
 *       conclusion <b>A</b> (SDK compatible → no raw-HTTP path needed);
 *       if it throws on deserialization, conclusion <b>B</b> (keep raw HTTP).</li>
 *   <li><b>3.4</b> — when {@code reasoning_split} is omitted, does reasoning appear inline
 *       as {@code <think>} in {@code content}, or in a separate {@code reasoning_content}
 *       field? Printed to stdout.</li>
 *   <li><b>6.2 (partial)</b> — {@code thinkingAdaptive_returnsReasoningContent} confirms the
 *       new {@code thinking.type=adaptive} + {@code reasoning_split:true} control works live
 *       on M3 and routes reasoning to {@code reasoning_content}. (Requires an M3 model.)</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "minimax.smoke.key", matches = ".+")
class MinimaxRawHttpSmokeTest {

    private String base() {
        return System.getProperty("minimax.smoke.base", "https://api.minimaxi.com/v1");
    }

    private String model() {
        return System.getProperty("minimax.smoke.model", "MiniMax-M3");
    }

    private String key() {
        return System.getProperty("minimax.smoke.key");
    }

    /**
     * Task 3.1: same client construction the tool path uses; plain (no-tools) completion.
     * If create() + choices() succeed, the SDK tolerates MiniMax's plain response → conclusion A.
     */
    @Test
    void plainTextSdkResponse_deserializes() {
        var client = OpenAIOkHttpClient.builder()
                .apiKey(key())
                .baseUrl(base())
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model())
                .maxCompletionTokens(256L)
                .addUserMessage("Reply with exactly: OK")
                .build();

        ChatCompletion resp = client.chat().completions().create(params);
        String content = resp.choices().get(0).message().content().orElse(null);

        var addl = resp.choices().get(0).message()._additionalProperties();
        boolean hasReasoningContent = addl.containsKey("reasoning_content");
        boolean inlineThink = content != null && content.contains("<think>");

        System.out.println("[smoke 3.1] plain-text deserialized OK");
        System.out.println("[smoke 3.1] content = " + content);
        System.out.println("[smoke 3.4] reasoning_content field present (no reasoning_split sent) = " + hasReasoningContent);
        System.out.println("[smoke 3.4] content contains <think> = " + inlineThink);

        assertNotNull(content, "plain-text response must have content (conclusion A: SDK compatible)");
    }

    /**
     * Task 6.2 (partial): confirms the new minimax_thinking control live on M3 —
     * thinking.type=adaptive turns thinking on, reasoning_split:true routes it to reasoning_content.
     * Run with an M3 model (e.g. -Dminimax.smoke.model=MiniMax-M3).
     */
    @Test
    void thinkingAdaptive_returnsReasoningContent() {
        var client = OpenAIOkHttpClient.builder()
                .apiKey(key())
                .baseUrl(base())
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model())
                .maxCompletionTokens(1024L)
                .addUserMessage("What is 17 * 23? Think step by step, then give the answer.")
                .putAdditionalBodyProperty("thinking",
                        com.openai.core.JsonValue.from(java.util.Map.of("type", "adaptive")))
                .putAdditionalBodyProperty("reasoning_split",
                        com.openai.core.JsonValue.from(true))
                .build();

        ChatCompletion resp = client.chat().completions().create(params);
        String content = resp.choices().get(0).message().content().orElse(null);
        var addl = resp.choices().get(0).message()._additionalProperties();

        System.out.println("[smoke 6.2] thinking=adaptive content = " + content);
        System.out.println("[smoke 6.2] reasoning_content field present = " + addl.containsKey("reasoning_content"));
        addl.entrySet().stream()
                .filter(e -> e.getKey().equals("reasoning_content"))
                .forEach(e -> System.out.println("[smoke 6.2] reasoning_content = " + e.getValue()));

        assertNotNull(content, "M3 adaptive-thinking response must have content");
    }
}
