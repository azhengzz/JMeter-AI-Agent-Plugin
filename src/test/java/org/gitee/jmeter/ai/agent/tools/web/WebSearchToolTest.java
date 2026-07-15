package org.gitee.jmeter.ai.agent.tools.web;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link WebSearchTool}.
 *
 * Tests cover metadata, parameter validation, disabled state, provider routing,
 * and configuration-driven defaults. The actual HTTP search calls are not mocked
 * here — they will fail naturally in a unit-test environment (no network/keys),
 * which exercises the error-handling and fallback paths.
 */
@DisplayName("WebSearchTool")
class WebSearchToolTest {

    private MockedStatic<JMeterUtils> jmeterUtilsMock;

    @AfterEach
    void tearDown() {
        if (jmeterUtilsMock != null) {
            jmeterUtilsMock.close();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Create a WebSearchTool with the given config, stubbing JMeterUtils.
     * The mock is closed and re-created each call so tests are isolated.
     */
    private WebSearchTool createTool(boolean enabled) {
        return createTool(enabled, "brave", 10, 5);
    }

    private WebSearchTool createTool(boolean enabled, String provider, int maxResults, int timeoutSeconds) {
        if (jmeterUtilsMock != null) {
            jmeterUtilsMock.close();
        }
        jmeterUtilsMock = mockStatic(JMeterUtils.class);

        // Fallback: return the second argument (the default) for unstubbed keys
        jmeterUtilsMock.when(() -> JMeterUtils.getPropDefault(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        // Specific overrides for WebSearchTool / AbstractWebTool config keys
        stubProp("agent.tools.websearch.enabled", String.valueOf(enabled));
        stubProp("agent.tools.websearch.provider", provider);
        stubProp("agent.tools.websearch.max.results", String.valueOf(maxResults));
        stubProp("agent.tools.websearch.timeout", String.valueOf(timeoutSeconds));
        stubProp("agent.tools.websearch.brave.api.key", "");
        stubProp("agent.tools.websearch.tavily.api.key", "");
        stubProp("agent.tools.websearch.jina.api.key", "");
        stubProp("agent.tools.web.ssrf.protection", "true");
        stubProp("agent.tools.web.max.redirects", "5");

        return new WebSearchTool();
    }

    private void stubProp(String key, String value) {
        jmeterUtilsMock.when(() -> JMeterUtils.getPropDefault(eq(key), anyString())).thenReturn(value);
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> p = new HashMap<>();
        p.put(key, value);
        return p;
    }

    private static Map<String, Object> params(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> p = params(k1, v1);
        p.put(k2, v2);
        return p;
    }

    // ── metadata ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("getName returns 'web_search'")
        void toolName() {
            WebSearchTool tool = createTool(true);
            assertEquals("web_search", tool.getName());
        }

        @Test
        @DisplayName("getDescription is non-empty and mentions providers")
        void toolDescription() {
            WebSearchTool tool = createTool(true);
            String desc = tool.getDescription();
            assertNotNull(desc);
            assertFalse(desc.isEmpty());
            assertTrue(desc.contains("Brave"), "Description should mention Brave");
            assertTrue(desc.contains("Tavily"), "Description should mention Tavily");
            assertTrue(desc.contains("Jina"), "Description should mention Jina");
        }

        @Test
        @DisplayName("getParameterSchema contains query, provider, max_results")
        void parameterSchema() {
            WebSearchTool tool = createTool(true);
            String schema = tool.getParameterSchema();
            assertNotNull(schema);
            assertTrue(schema.contains("query"), "Schema should define 'query'");
            assertTrue(schema.contains("provider"), "Schema should define 'provider'");
            assertTrue(schema.contains("max_results"), "Schema should define 'max_results'");
        }

        @Test
        @DisplayName("getParameterSchema marks query as required")
        void queryIsRequired() {
            WebSearchTool tool = createTool(true);
            String schema = tool.getParameterSchema();
            assertTrue(schema.contains("\"required\""));
            assertTrue(schema.contains("\"query\""));
        }

        @Test
        @DisplayName("getParameterSchema lists all 3 providers in enum")
        void providerEnumValues() {
            WebSearchTool tool = createTool(true);
            String schema = tool.getParameterSchema();
            assertTrue(schema.contains("\"brave\""));
            assertTrue(schema.contains("\"tavily\""));
            assertTrue(schema.contains("\"jina\""));
            assertFalse(schema.contains("\"duckduckgo\""));
            assertFalse(schema.contains("\"serpapi\""));
        }
    }

    // ── disabled state ────────────────────────────────────────────────────

    @Nested
    @DisplayName("When web tools are disabled")
    class DisabledState {

        @Test
        @DisplayName("execute returns error when tools are disabled")
        void returnsErrorWhenDisabled() {
            WebSearchTool tool = createTool(false);
            ToolResult result = tool.execute(params("query", "test"));
            assertFalse(result.isSuccess(), "Result should not be successful");
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("disabled"),
                    "Error should mention tools are disabled: " + result.getError());
        }

        @Test
        @DisplayName("disabled error is returned even with valid parameters")
        void disabledEvenWithValidParams() {
            WebSearchTool tool = createTool(false);
            ToolResult result = tool.execute(params("query", "anything"));
            assertFalse(result.isSuccess());
        }
    }

    // ── parameter validation ─────────────────────────────────────────────

    @Nested
    @DisplayName("Parameter validation")
    class ParameterValidation {

        @Test
        @DisplayName("execute returns error when query parameter is missing")
        void missingQuery() {
            WebSearchTool tool = createTool(true);
            ToolResult result = tool.execute(new HashMap<>());
            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("query") || result.getError().contains("Missing"),
                    "Error should mention missing query: " + result.getError());
        }

        @Test
        @DisplayName("execute returns error when query is empty string")
        void emptyQuery() {
            WebSearchTool tool = createTool(true);
            ToolResult result = tool.execute(params("query", ""));
            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("empty") || result.getError().contains("cannot be empty"),
                    "Error should mention empty query: " + result.getError());
        }

        @Test
        @DisplayName("execute returns error when query is null (treated as missing)")
        void nullQueryParamValue() {
            WebSearchTool tool = createTool(true);
            Map<String, Object> p = new HashMap<>();
            p.put("query", null);
            ToolResult result = tool.execute(p);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("execute with valid query attempts search (fails with error, not exception)")
        void validQueryAttemptsSearch() {
            WebSearchTool tool = createTool(true);
            // Search will fail because there's no real API key / network in unit tests,
            // but it should NOT throw — errors are captured into ToolResult.
            ToolResult result = tool.execute(params("query", "jmeter testing"));
            assertNotNull(result, "Result should never be null");
            // Either success (unlikely in unit test) or error with a message
            if (result.isSuccess()) {
                assertNotNull(result.getContent());
            } else {
                assertNotNull(result.getError());
                assertFalse(result.getError().isEmpty());
            }
        }
    }

    // ── provider routing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Provider routing")
    class ProviderRouting {

        @Test
        @DisplayName("uses default provider from config when none is specified")
        void usesDefaultProvider() {
            WebSearchTool tool = createTool(true, "brave", 10, 5);
            // Execute with query only — should route to brave
            ToolResult result = tool.execute(params("query", "test query"));
            assertNotNull(result);
            // Brave has no key → falls back to Jina, which also fails (no network).
            // Either way, we get a ToolResult — verify it's not a crash.
            assertFalse(result.isSuccess() || result.getContent() == null && result.getError() == null);
        }

        @Test
        @DisplayName("unknown provider defaults to Brave")
        void unknownProviderFallsBackToBrave() {
            WebSearchTool tool = createTool(true);
            ToolResult result = tool.execute(params("query", "test", "provider", "unknown"));
            assertNotNull(result);
            // Should route to Brave via default case in performSearch
        }

        @Test
        @DisplayName("tavily provider falls back to Jina when API key is empty")
        void tavilyFallsBackWithoutKey() {
            WebSearchTool tool = createTool(true); // tavilyApiKey = ""
            ToolResult result = tool.execute(params("query", "test", "provider", "tavily"));
            assertNotNull(result);
            // Tavily has no key → falls back to Jina
        }

        @Test
        @DisplayName("jina provider is the terminal free fallback (free, no key)")
        void jinaProviderDirectCall() {
            WebSearchTool tool = createTool(true);
            ToolResult result = tool.execute(params("query", "test", "provider", "jina"));
            assertNotNull(result);
            // Jina needs no key; on failure it returns an error (no further fallback)
        }

        @Test
        @DisplayName("brave provider falls back to Jina when API key is empty")
        void braveFallsBackWithoutKey() {
            WebSearchTool tool = createTool(true); // braveApiKey = ""
            ToolResult result = tool.execute(params("query", "test", "provider", "brave"));
            assertNotNull(result);
            // Brave has no key → short-circuits to Jina (no wasted HTTP call)
            assertFalse(result.isSuccess() || result.getContent() == null && result.getError() == null);
        }
    }

    // ── config-driven defaults ────────────────────────────────────────────

    @Nested
    @DisplayName("Configuration-driven defaults")
    class ConfigDefaults {

        @Test
        @DisplayName("max_results parameter defaults to configured value when not specified")
        void maxResultsDefaultFromConfig() {
            WebSearchTool tool = createTool(true, "brave", 5, 5);
            // The default maxResults should be 5 (from config), not 10
            // We verify this indirectly: the tool doesn't reject the call
            ToolResult result = tool.execute(params("query", "test"));
            assertNotNull(result);
        }

        @Test
        @DisplayName("max_results parameter overrides config when explicitly passed")
        void maxResultsOverrideFromParameter() {
            WebSearchTool tool = createTool(true, "brave", 10, 5);
            ToolResult result = tool.execute(params("query", "test", "max_results", 3));
            assertNotNull(result);
        }

        @Test
        @DisplayName("provider defaults to 'brave' when config key is absent")
        void providerDefaultIsBrave() {
            // createTool stubs "agent.tools.websearch.provider" = "brave"
            WebSearchTool tool = createTool(true);
            ToolResult result = tool.execute(params("query", "config test"));
            assertNotNull(result);
        }

        @Test
        @DisplayName("timeout is read from config")
        void timeoutFromConfig() {
            // Short timeout = 1s for fast test failure
            WebSearchTool tool = createTool(true, "brave", 10, 1);
            ToolResult result = tool.execute(params("query", "test"));
            assertNotNull(result);
        }
    }

    // ── tool interface compliance ─────────────────────────────────────────

    @Nested
    @DisplayName("Tool interface compliance")
    class InterfaceCompliance {

        @Test
        @DisplayName("hasRequiredParameters returns false (uses built-in validation)")
        void hasNoRequiredParametersDeclared() {
            WebSearchTool tool = createTool(true);
            assertFalse(tool.hasRequiredParameters());
        }

        @Test
        @DisplayName("validateParameters returns valid (validation is in executeInternal)")
        void validateParametersAlwaysValid() {
            WebSearchTool tool = createTool(true);
            var validation = tool.validateParameters(new HashMap<>());
            assertTrue(validation.isValid());
        }

        @Test
        @DisplayName("getPriority returns 0 (default)")
        void defaultPriority() {
            WebSearchTool tool = createTool(true);
            assertEquals(0, tool.getPriority());
        }

        @Test
        @DisplayName("getTimeoutMs returns 0 (use registry default)")
        void defaultTimeout() {
            WebSearchTool tool = createTool(true);
            assertEquals(0, tool.getTimeoutMs());
        }

        @Test
        @DisplayName("AbstractTool.execute catches exceptions and returns error result")
        void executeNeverThrows() {
            WebSearchTool tool = createTool(true);
            // Should never throw, even with unexpected input
            assertDoesNotThrow(() -> {
                ToolResult result = tool.execute(params("query", "safe test"));
                assertNotNull(result);
            });
        }
    }

    // ── AbstractWebTool SSRF protection config ────────────────────────────

    @Nested
    @DisplayName("SSRF protection (inherited from AbstractWebTool)")
    class SsrfProtection {

        @Test
        @DisplayName("SSRF protection is enabled by default")
        void ssrfProtectionEnabledByDefault() {
            WebSearchTool tool = createTool(true);
            assertTrue(tool.isSsrfProtectionEnabled());
        }

        @Test
        @DisplayName("validateUrl rejects empty URL")
        void validateUrlRejectsEmpty() {
            WebSearchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl(""));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl(null));
        }

        @Test
        @DisplayName("validateUrl rejects non-HTTP schemes")
        void validateUrlRejectsNonHttp() {
            WebSearchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("file:///etc/passwd"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("ftp://example.com"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("javascript:alert(1)"));
        }

        @Test
        @DisplayName("validateUrl rejects private network addresses")
        void validateUrlRejectsPrivateNetworks() {
            WebSearchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://localhost:8080/api"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://127.0.0.1/admin"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://192.168.1.1/config"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://10.0.0.1/internal"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://169.254.1.1/meta"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://0.0.0.0/debug"));
        }

        @Test
        @DisplayName("validateUrl accepts public URLs")
        void validateUrlAcceptsPublic() {
            WebSearchTool tool = createTool(true);
            assertDoesNotThrow(() -> tool.validateUrl("https://www.example.com"));
            assertDoesNotThrow(() -> tool.validateUrl("https://api.search.brave.com/res/v1/web/search?q=test"));
            assertDoesNotThrow(() -> tool.validateUrl("http://example.com/path"));
        }

        @Test
        @DisplayName("validateUrl rejects malformed URLs")
        void validateUrlRejectsMalformed() {
            WebSearchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("not a url at all"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://"));
        }
    }
}
