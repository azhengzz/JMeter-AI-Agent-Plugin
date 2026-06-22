package org.gitee.jmeter.ai.service;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the pure-logic methods of {@link OpenAiService}.
 * <p>
 * Scope intentionally excludes any real SDK calls or SDK-field construction:
 * those are validated by the manual end-to-end test in the upgrade plan.
 * These tests cover provider/model ID parsing, reasoning-effort conversion,
 * tool-choice support detection, and user-friendly error message mapping.
 */
@ExtendWith(MockitoExtension.class)
class OpenAiServiceTest {

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    private OpenAiService service;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getDefaultModel()).thenReturn("openai:gpt-4o");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.temperature", "0.7")).thenReturn("0.7");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.max.tokens", "4096")).thenReturn("4096");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.reasoning.effort", "medium")).thenReturn("medium");
        // initializeClient reads these via getConfigValue; unmocked calls return null which would NPE
        aiConfigMock.when(() -> AiConfig.getProperty("openai.api.key", null)).thenReturn(null);
        aiConfigMock.when(() -> AiConfig.getProperty("openai.api.key", "")).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getProperty("openai.api.base.url", null)).thenReturn(null);
        aiConfigMock.when(() -> AiConfig.getProperty("openai.api.base.url", "")).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getProperty("openai.log.level", null)).thenReturn(null);
        aiConfigMock.when(() -> AiConfig.getProperty("openai.log.level", "")).thenReturn("");

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
        service = new OpenAiService();
    }

    // ==================== Reflection helpers ====================

    private Object invokeInstance(String methodName, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = OpenAiService.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(service, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) throws Throwable {
        Method m = OpenAiService.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ==================== extractProvider ====================

    @ParameterizedTest
    @MethodSource("provideKnownProviders")
    void testExtractProvider_KnownProviders(String modelId, String expectedProvider) throws Throwable {
        assertEquals(expectedProvider,
                invokeInstance("extractProvider", new Class<?>[]{String.class}, modelId));
    }

    private static Stream<Arguments> provideKnownProviders() {
        return Stream.of(
                Arguments.of("openai:gpt-4o", "openai"),
                Arguments.of("deepseek:deepseek-chat", "deepseek"),
                Arguments.of("zhipu:glm-5", "zhipu"),
                Arguments.of("moonshot:kimi-k2.5", "moonshot"),
                Arguments.of("minimax:MiniMax-M2.7", "minimax")
        );
    }

    @Test
    void testExtractProvider_UnknownProvider_DefaultsToOpenai() throws Throwable {
        assertEquals("openai",
                invokeInstance("extractProvider", new Class<?>[]{String.class}, "unknown:model"));
    }

    @Test
    void testExtractProvider_NoPrefix_DefaultsToOpenai() throws Throwable {
        assertEquals("openai",
                invokeInstance("extractProvider", new Class<?>[]{String.class}, "gpt-4o"));
    }

    // ==================== extractModelName ====================

    @Test
    void testExtractModelName_WithPrefix() throws Throwable {
        assertEquals("gpt-4o",
                invokeInstance("extractModelName", new Class<?>[]{String.class}, "openai:gpt-4o"));
    }

    @Test
    void testExtractModelName_NoPrefix() throws Throwable {
        assertEquals("gpt-4o",
                invokeInstance("extractModelName", new Class<?>[]{String.class}, "gpt-4o"));
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
                Arguments.of("low", ReasoningEffort.LOW),
                Arguments.of("LOW", ReasoningEffort.LOW),
                Arguments.of("Low", ReasoningEffort.LOW),
                Arguments.of("medium", ReasoningEffort.MEDIUM),
                Arguments.of("high", ReasoningEffort.HIGH),
                Arguments.of("none", null),
                Arguments.of("null", null),
                Arguments.of(null, null),
                Arguments.of("invalid-value", ReasoningEffort.MEDIUM),
                Arguments.of("", ReasoningEffort.MEDIUM)
        );
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

    // ==================== extractUserFriendlyErrorMessage ====================

    @Test
    void testExtractUserFriendlyErrorMessage_InsufficientQuota() throws Throwable {
        Exception e = new RuntimeException("Error: insufficient_quota");
        String result = (String) invokeInstance(
                "extractUserFriendlyErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("credit balance"), "expected credit balance hint, got: " + result);
    }

    @Test
    void testExtractUserFriendlyErrorMessage_InvalidApiKey() throws Throwable {
        Exception e = new RuntimeException("Error: invalid_api_key");
        String result = (String) invokeInstance(
                "extractUserFriendlyErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("Invalid API key"), "expected invalid api key hint, got: " + result);
    }

    @Test
    void testExtractUserFriendlyErrorMessage_RateLimit() throws Throwable {
        Exception e = new RuntimeException("Error: rate_limit_exceeded");
        String result = (String) invokeInstance(
                "extractUserFriendlyErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("Rate limit"), "expected rate limit hint, got: " + result);
    }

    @Test
    void testExtractUserFriendlyErrorMessage_ModelNotFound() throws Throwable {
        Exception e = new RuntimeException("Error: model_not_found");
        String result = (String) invokeInstance(
                "extractUserFriendlyErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.toLowerCase().contains("not found"), "expected not found hint, got: " + result);
    }

    @Test
    void testExtractUserFriendlyErrorMessage_ContextLengthExceeded() throws Throwable {
        Exception e = new RuntimeException("Error: context_length_exceeded");
        String result = (String) invokeInstance(
                "extractUserFriendlyErrorMessage", new Class<?>[]{Exception.class}, e);
        assertTrue(result.contains("too long"), "expected too-long hint, got: " + result);
    }

    @Test
    void testExtractUserFriendlyErrorMessage_GenericFallback() throws Throwable {
        Exception e = new RuntimeException("some unknown error");
        String result = (String) invokeInstance(
                "extractUserFriendlyErrorMessage", new Class<?>[]{Exception.class}, e);
        assertFalse(result.startsWith("Error: insufficient_quota"),
                "should not leak raw error code for unknown errors");
    }
}
