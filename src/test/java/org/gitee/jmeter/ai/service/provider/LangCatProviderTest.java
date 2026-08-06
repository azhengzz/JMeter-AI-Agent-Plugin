package org.gitee.jmeter.ai.service.provider;

import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.gitee.jmeter.ai.utils.SystemPrompt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Verification tests for the LangCat provider registration
 * (OpenSpec change: add-langcat-provider).
 * <p>
 * Same scope discipline as {@link OpenAICompatibleProviderTest}: no real SDK
 * calls — tests cover registry registration, provider detection, factory
 * routing to the openai_compat backend, base-URL override read path, and the
 * thinking_type extra_body mapping that produces LangCat's
 * {@code thinking:{"type":"enabled"|"disabled"}} body.
 */
@ExtendWith(MockitoExtension.class)
class LangCatProviderTest {

    private static final String DEFAULT_BASE = "https://api.longcat.chat/openai/v1";

    private static MockedStatic<AiConfig> aiConfigMock;
    private static MockedStatic<SystemPrompt> systemPromptMock;

    @BeforeAll
    static void setUpAll() {
        aiConfigMock = mockStatic(AiConfig.class);
        aiConfigMock.when(() -> AiConfig.getProperty("langcat.api.key", "")).thenReturn("");
        aiConfigMock.when(() -> AiConfig.getProperty("langcat.api.base.url", DEFAULT_BASE)).thenReturn(DEFAULT_BASE);
        aiConfigMock.when(AiConfig::getDefaultModel).thenReturn("langcat:LongCat-2.0");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.temperature", "0.7")).thenReturn("0.7");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.max.tokens", "4096")).thenReturn("4096");
        aiConfigMock.when(() -> AiConfig.getProperty("jmeter.ai.reasoning.effort", "medium")).thenReturn("medium");
        // Keep LangSmith tracing off so AiServiceFactory does not wrap the service.
        // (sample.rate is parsed unconditionally in the LangSmithClient constructor.)
        aiConfigMock.when(() -> AiConfig.getProperty("langsmith.enabled", "true")).thenReturn("false");
        aiConfigMock.when(() -> AiConfig.getProperty("langsmith.sample.rate", "1.0")).thenReturn("1.0");

        systemPromptMock = mockStatic(SystemPrompt.class);
        systemPromptMock.when(SystemPrompt::get).thenReturn("Mocked system prompt");
    }

    @AfterAll
    static void tearDownAll() {
        if (systemPromptMock != null) systemPromptMock.close();
        if (aiConfigMock != null) aiConfigMock.close();
    }

    // ==================== Registration (1.2) ====================

    @Test
    void testLangcatRegistered_InRegistry() {
        ProviderSpec spec = ProviderRegistry.findByName("langcat");
        assertNotNull(spec, "langcat must be registered in ProviderRegistry");
        assertEquals("langcat", spec.getName());
        assertEquals("LangCat", spec.getDisplayName());
        assertEquals(DEFAULT_BASE, spec.getDefaultApiBase());
        assertEquals("langcat.api.key", spec.getEnvKey());
        assertEquals("openai_compat", spec.getBackend(), "langcat reuses the openai_compat backend");
        assertEquals("thinking_type", spec.getThinkingStyle());
        assertTrue(spec.supportsThinking("LongCat-2.0"),
                "empty thinkingModels => all models support thinking");
        assertFalse(spec.isThinkingAlwaysOn("LongCat-2.0"));
    }

    @Test
    void testLangcat_DetectedByModelAndPrefixedId() {
        ProviderSpec byModel = ProviderRegistry.findByModel("LongCat-2.0");
        assertNotNull(byModel, "keyword 'longcat' must match the model id");
        assertEquals("langcat", byModel.getName());

        ProviderSpec byPrefixedId = ProviderRegistry.detectProvider("langcat:LongCat-2.0");
        assertNotNull(byPrefixedId);
        assertEquals("langcat", byPrefixedId.getName());
    }

    @Test
    void testLangcat_ServiceViaFactory_IsOpenAICompatible() throws Exception {
        AiService service = AiServiceFactory.createService("langcat:LongCat-2.0");
        assertTrue(service instanceof OpenAICompatibleProvider,
                "langcat should be served by OpenAICompatibleProvider, got: " + service.getClass().getName());
        assertEquals(DEFAULT_BASE, readBaseUrl((OpenAICompatibleProvider) service),
                "base URL defaults to the LangCat endpoint");
    }

    // ==================== Base URL override (3.2) ====================

    @Test
    void testLangcat_BaseUrlOverride_WinsOverDefault() throws Exception {
        String override = "https://langcat.example.com/v1";
        aiConfigMock.when(() -> AiConfig.getProperty("langcat.api.base.url", DEFAULT_BASE)).thenReturn(override);
        try {
            ProviderSpec spec = ProviderRegistry.findByName("langcat");
            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(spec);
            assertEquals(override, readBaseUrl(provider),
                    "langcat.api.base.url override must win over the default");
        } finally {
            aiConfigMock.when(() -> AiConfig.getProperty("langcat.api.base.url", DEFAULT_BASE)).thenReturn(DEFAULT_BASE);
        }
    }

    // ==================== thinking_type body mapping (4.2) ====================

    @Test
    void testLangcat_ThinkingTypeStyle_ProducesLangCatBody() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Function<Boolean, Map<String, Object>>> styleMap =
                (Map<String, Function<Boolean, Map<String, Object>>>) readField(OpenAICompatibleProvider.class, "THINKING_STYLE_MAP");
        assertNotNull(styleMap);
        Function<Boolean, Map<String, Object>> thinkingType = styleMap.get("thinking_type");
        assertNotNull(thinkingType, "thinking_type style must exist for langcat");
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), thinkingType.apply(true));
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), thinkingType.apply(false));
    }

    // ==================== Helpers ====================

    private static String readBaseUrl(OpenAICompatibleProvider provider) throws Exception {
        return (String) readField(provider, "baseUrl");
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field;
        if (target instanceof Class<?> clazz) {
            field = clazz.getDeclaredField(fieldName);
        } else {
            field = target.getClass().getDeclaredField(fieldName);
        }
        field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }
}
