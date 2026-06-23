package org.gitee.jmeter.ai.service.provider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import com.openai.models.ReasoningEffort;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the pure-logic methods of {@link OpenAICompatibleProvider}.
 * <p>
 * Same scope discipline as {@code OpenAiServiceTest}: no real SDK calls,
 * no SDK-field construction — those are covered by the manual end-to-end test
 * in the upgrade plan. Tests cover provider-prefix stripping, reasoning-effort
 * conversion, thinking-style normalization, raw JSON response parsing,
 * tool-choice support detection, and error-message mapping.
 */
@ExtendWith(MockitoExtension.class)
class OpenAICompatibleProviderTest {

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    private OpenAICompatibleProvider provider;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getProperty("MINIMAX_API_KEY", "")).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getProperty("minimax.api.base.url", "https://api.minimaxi.chat/v1"))
                .thenReturn("https://api.minimaxi.chat/v1");
        aiConfigMock.when(() -> AiConfig.getDefaultModel()).thenReturn("minimax:MiniMax-M2.7");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.temperature", "0.7")).thenReturn("0.7");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.max.tokens", "4096")).thenReturn("4096");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.reasoning.effort", "medium")).thenReturn("medium");

        systemPromptMock = mockStatic(SystemPrompt.class);
        systemPromptMock.when(SystemPrompt::get).thenReturn("Mocked system prompt");
    }

    @AfterAll
    static void tearDownAll() {
        if (systemPromptMock != null) systemPromptMock.close();
        if (aiConfigMock != null) aiConfigMock.close();
    }

    @BeforeEach
    void setUp() {
        ProviderSpec spec = new ProviderSpec.Builder()
                .name("minimax")
                .displayName("MiniMax")
                .defaultApiBase("https://api.minimaxi.chat/v1")
                .envKey("MINIMAX_API_KEY")
                .build();
        provider = new OpenAICompatibleProvider(spec);
    }

    // ==================== Reflection helpers ====================

    private Object invokeInstance(String methodName, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = OpenAICompatibleProvider.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(provider, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = OpenAICompatibleProvider.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ==================== stripProviderPrefix ====================

    @Test
    void testStripProviderPrefix_WithPrefix() throws Throwable {
        assertEquals("MiniMax-M2.7",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "minimax:MiniMax-M2.7"));
    }

    @Test
    void testStripProviderPrefix_NoPrefix() throws Throwable {
        assertEquals("MiniMax-M2.7",
                invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, "MiniMax-M2.7"));
    }

    @Test
    void testStripProviderPrefix_NullInput() throws Throwable {
        assertNull(invokeInstance("stripProviderPrefix", new Class<?>[]{String.class}, (Object) null));
    }

    // ==================== toReasoningEffort (static) ====================

    @ParameterizedTest
    @MethodSource("provideReasoningEffortMappings")
    void testToReasoningEffort(String input, ReasoningEffort expected) throws Throwable {
        assertEquals(expected,
                invokeStatic("toReasoningEffort", new Class<?>[]{String.class}, input));
    }

    private static Stream<Arguments> provideReasoningEffortMappings() {
        return Stream.of(
                Arguments.of("minimal", ReasoningEffort.MINIMAL),
                Arguments.of("minimum", ReasoningEffort.MINIMAL),
                Arguments.of("low", ReasoningEffort.LOW),
                Arguments.of("medium", ReasoningEffort.MEDIUM),
                Arguments.of("high", ReasoningEffort.HIGH),
                Arguments.of("xhigh", ReasoningEffort.XHIGH),
                Arguments.of("none", null),
                Arguments.of("null", null),
                Arguments.of(null, null),
                Arguments.of("garbage", ReasoningEffort.MEDIUM),
                Arguments.of("", ReasoningEffort.MEDIUM)
        );
    }

    // ==================== ProviderSpec thinkingAlwaysOn ====================

    @Test
    void testThinkingAlwaysOn_RegisteredModel_CaseInsensitive() {
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertTrue(moonshot.isThinkingAlwaysOn("kimi-k2.7-code"));
        assertTrue(moonshot.isThinkingAlwaysOn("KIMI-K2.7-CODE"));
    }

    @Test
    void testThinkingAlwaysOn_DisableableModel() {
        ProviderSpec moonshot = ProviderRegistry.findByName("moonshot");
        assertNotNull(moonshot);
        assertFalse(moonshot.isThinkingAlwaysOn("kimi-k2.6"));
        assertFalse(moonshot.isThinkingAlwaysOn("kimi-k2.5"));
    }

    @Test
    void testThinkingAlwaysOn_OtherProviders_AlwaysFalse() {
        ProviderSpec deepseek = ProviderRegistry.findByName("deepseek");
        assertNotNull(deepseek);
        assertFalse(deepseek.isThinkingAlwaysOn("deepseek-reasoner"));
        assertFalse(deepseek.isThinkingAlwaysOn(null));
    }

    // ==================== parseResponseIgnoringUnknownFields ====================

    @Test
    void testParseResponseIgnoringUnknownFields_NormalResponse() throws Throwable {
        String json = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"unknown_field\":\"ignore me\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"},"
                + "\"finish_reason\":\"stop\"}]}";
        assertEquals("Hello world",
                invokeInstance("parseResponseIgnoringUnknownFields", new Class<?>[]{String.class}, json));
    }

    @Test
    void testParseResponseIgnoringUnknownFields_MissingChoices() throws Throwable {
        String json = "{\"id\":\"chatcmpl-1\"}";
        String result = (String) invokeInstance(
                "parseResponseIgnoringUnknownFields", new Class<?>[]{String.class}, json);
        assertTrue(result.startsWith("Error"), "expected error string when choices missing, got: " + result);
    }

    @Test
    void testParseResponseIgnoringUnknownFields_EmptyChoices() throws Throwable {
        String json = "{\"choices\":[]}";
        String result = (String) invokeInstance(
                "parseResponseIgnoringUnknownFields", new Class<?>[]{String.class}, json);
        assertTrue(result.startsWith("Error"), "expected error string when choices empty, got: " + result);
    }

    @Test
    void testParseResponseIgnoringUnknownFields_MissingContent() throws Throwable {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";
        String result = (String) invokeInstance(
                "parseResponseIgnoringUnknownFields", new Class<?>[]{String.class}, json);
        assertTrue(result.startsWith("Error"), "expected error string when content missing, got: " + result);
    }

    @Test
    void testParseResponseIgnoringUnknownFields_InvalidJson() throws Throwable {
        String result = (String) invokeInstance(
                "parseResponseIgnoringUnknownFields", new Class<?>[]{String.class}, "not json");
        assertNotNull(result);
        assertTrue(result.startsWith("Error"), "expected error string for invalid JSON, got: " + result);
    }

    // ==================== isToolChoiceUnsupported(Throwable) ====================

    @ParameterizedTest
    @MethodSource("provideToolChoiceUnsupportedThrowables")
    void testIsToolChoiceUnsupported_Throwable(Throwable input, boolean expected) throws Throwable {
        boolean actual = (boolean) invokeInstance(
                "isToolChoiceUnsupported", new Class<?>[]{Throwable.class}, input);
        assertEquals(expected, actual);
    }

    private static Stream<Arguments> provideToolChoiceUnsupportedThrowables() {
        return Stream.of(
                Arguments.of(new RuntimeException("tool_choice is not supported"), true),
                Arguments.of(new RuntimeException("model does not support tool_choice"), true),
                Arguments.of(new RuntimeException("tool_choice should be [\"none\", \"auto\"]"), true),
                Arguments.of(new RuntimeException("some unrelated error"), false),
                Arguments.of(new RuntimeException(""), false),
                Arguments.of(null, false)
        );
    }

    // ==================== isToolChoiceUnsupported(String) ====================

    @ParameterizedTest
    @MethodSource("provideToolChoiceUnsupportedStrings")
    void testIsToolChoiceUnsupported_String(String input, boolean expected) throws Throwable {
        boolean actual = (boolean) invokeInstance(
                "isToolChoiceUnsupported", new Class<?>[]{String.class}, input);
        assertEquals(expected, actual);
    }

    private static Stream<Arguments> provideToolChoiceUnsupportedStrings() {
        return Stream.of(
                Arguments.of("tool_choice is not supported", true),
                Arguments.of("model does not support tool_choice", true),
                Arguments.of("tool_choice should be [\"none\", \"auto\"]", true),
                Arguments.of("TOOL_CHOICE uppercase should match", true),
                Arguments.of("some unrelated error", false),
                Arguments.of("", false),
                Arguments.of(null, false)
        );
    }

    // ==================== isCausedByInterrupt ====================

    @Test
    void testIsCausedByInterrupt_InterruptedIOException() throws Throwable {
        Throwable e = new java.io.InterruptedIOException("timeout");
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertTrue(actual);
    }

    @Test
    void testIsCausedByInterrupt_InterruptedException() throws Throwable {
        Throwable e = new InterruptedException("cancelled");
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertTrue(actual);
    }

    @Test
    void testIsCausedByInterrupt_NestedCause() throws Throwable {
        Throwable root = new InterruptedException("deep");
        Throwable e = new RuntimeException("wrapper", root);
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertTrue(actual);
    }

    @Test
    void testIsCausedByInterrupt_NotInterrupt() throws Throwable {
        Throwable e = new RuntimeException("regular error");
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, e);
        assertFalse(actual);
    }

    @Test
    void testIsCausedByInterrupt_Null() throws Throwable {
        boolean actual = (boolean) invokeInstance(
                "isCausedByInterrupt", new Class<?>[]{Throwable.class}, (Object) null);
        assertFalse(actual);
    }

    // ==================== extractErrorMessage ====================

    @Test
    void testExtractErrorMessage_InsufficientQuota() throws Throwable {
        Exception e = new RuntimeException("Error: insufficient_quota");
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("Credit balance"), "got: " + result);
    }

    @Test
    void testExtractErrorMessage_InvalidApiKey() throws Throwable {
        Exception e = new RuntimeException("Error: invalid_api_key or authentication failed");
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("Invalid API key"), "got: " + result);
    }

    @Test
    void testExtractErrorMessage_RateLimit() throws Throwable {
        Exception e = new RuntimeException("rate_limit too many requests");
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("Rate limit"), "got: " + result);
    }

    @Test
    void testExtractErrorMessage_ModelNotFound() throws Throwable {
        Exception e = new RuntimeException("Error: model_not_found");
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.toLowerCase().contains("not found"), "got: " + result);
    }

    @Test
    void testExtractErrorMessage_ContextLength() throws Throwable {
        Exception e = new RuntimeException("Error: context_length_exceeded");
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("too long"), "got: " + result);
    }

    @Test
    void testExtractErrorMessage_NullMessage() throws Throwable {
        Exception e = new RuntimeException();
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains(provider.getName()) || result.contains("Unknown error"),
                "expected provider name or unknown error, got: " + result);
    }

    @Test
    void testExtractErrorMessage_MultiLineTruncatedToFirstLine() throws Throwable {
        Exception e = new RuntimeException("first line of error\nsecond line\nthird line");
        String result = (String) invokeInstance(
                "extractErrorMessage", new Class<?>[]{Exception.class}, e);
        assertEquals("first line of error", result);
    }
}
