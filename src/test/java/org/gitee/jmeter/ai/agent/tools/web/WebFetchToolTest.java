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
 * Unit tests for {@link WebFetchTool}.
 *
 * Mirrors {@link WebSearchToolTest}: metadata, parameter validation, disabled state,
 * inherited SSRF protection, and Tool-interface compliance. The actual HTTP fetch is not
 * mocked — it will fail naturally without network, exercising the error/fallback paths.
 */
@DisplayName("WebFetchTool")
class WebFetchToolTest {

    private MockedStatic<JMeterUtils> jmeterUtilsMock;

    @AfterEach
    void tearDown() {
        if (jmeterUtilsMock != null) {
            jmeterUtilsMock.close();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private WebFetchTool createTool(boolean enabled) {
        return createTool(enabled, 1);
    }

    /** Short timeout so the real network attempt in the few tests that trigger it fails fast. */
    private WebFetchTool createTool(boolean enabled, int timeoutSeconds) {
        if (jmeterUtilsMock != null) {
            jmeterUtilsMock.close();
        }
        jmeterUtilsMock = mockStatic(JMeterUtils.class);

        jmeterUtilsMock.when(() -> JMeterUtils.getPropDefault(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        stubProp("agent.tools.websearch.enabled", String.valueOf(enabled));
        stubProp("agent.tools.web.ssrf.protection", "true");
        stubProp("agent.tools.web.max.redirects", "5");
        stubProp("agent.tools.webfetch.timeout", String.valueOf(timeoutSeconds));
        stubProp("agent.tools.websearch.jina.api.key", "");

        return new WebFetchTool();
    }

    private void stubProp(String key, String value) {
        jmeterUtilsMock.when(() -> JMeterUtils.getPropDefault(eq(key), anyString())).thenReturn(value);
    }

    private static Map<String, Object> params(String key, Object value) {
        Map<String, Object> p = new HashMap<>();
        p.put(key, value);
        return p;
    }

    // ── metadata ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("getName returns 'web_fetch'")
        void toolName() {
            WebFetchTool tool = createTool(true);
            assertEquals("web_fetch", tool.getName());
        }

        @Test
        @DisplayName("getDescription is non-empty and mentions extraction")
        void toolDescription() {
            WebFetchTool tool = createTool(true);
            String desc = tool.getDescription();
            assertNotNull(desc);
            assertFalse(desc.isEmpty());
            assertTrue(desc.toLowerCase().contains("fetch"), "Description should mention fetch");
        }

        @Test
        @DisplayName("getParameterSchema contains url, extract_mode, max_chars")
        void parameterSchema() {
            WebFetchTool tool = createTool(true);
            String schema = tool.getParameterSchema();
            assertNotNull(schema);
            assertTrue(schema.contains("\"url\""));
            assertTrue(schema.contains("\"extract_mode\""));
            assertTrue(schema.contains("\"max_chars\""));
        }

        @Test
        @DisplayName("getParameterSchema marks url as required")
        void urlIsRequired() {
            WebFetchTool tool = createTool(true);
            String schema = tool.getParameterSchema();
            assertTrue(schema.contains("\"required\""));
            assertTrue(schema.contains("\"url\""));
        }

        @Test
        @DisplayName("getParameterSchema lists extract_mode enum and no use_reader")
        void extractModeEnum() {
            WebFetchTool tool = createTool(true);
            String schema = tool.getParameterSchema();
            assertTrue(schema.contains("\"markdown\""));
            assertTrue(schema.contains("\"text\""));
            assertFalse(schema.contains("use_reader"), "use_reader parameter should be removed");
        }
    }

    // ── disabled state ────────────────────────────────────────────────────

    @Nested
    @DisplayName("When web tools are disabled")
    class DisabledState {

        @Test
        @DisplayName("execute returns error when tools are disabled")
        void returnsErrorWhenDisabled() {
            WebFetchTool tool = createTool(false);
            ToolResult result = tool.execute(params("url", "https://www.example.com"));
            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("disabled"),
                    "Error should mention tools are disabled: " + result.getError());
        }

        @Test
        @DisplayName("disabled error is returned even with a valid URL")
        void disabledEvenWithValidUrl() {
            WebFetchTool tool = createTool(false);
            ToolResult result = tool.execute(params("url", "https://www.example.com"));
            assertFalse(result.isSuccess());
        }
    }

    // ── parameter validation ─────────────────────────────────────────────

    @Nested
    @DisplayName("Parameter validation")
    class ParameterValidation {

        @Test
        @DisplayName("execute returns error when url parameter is missing")
        void missingUrl() {
            WebFetchTool tool = createTool(true);
            ToolResult result = tool.execute(new HashMap<>());
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("url") || result.getError().contains("Missing"),
                    "Error should mention missing url: " + result.getError());
        }

        @Test
        @DisplayName("execute returns error when url is empty string")
        void emptyUrl() {
            WebFetchTool tool = createTool(true);
            ToolResult result = tool.execute(params("url", ""));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("empty") || result.getError().contains("cannot be empty"),
                    "Error should mention empty url: " + result.getError());
        }

        @Test
        @DisplayName("execute returns error when url is null (treated as missing)")
        void nullUrlValue() {
            WebFetchTool tool = createTool(true);
            Map<String, Object> p = new HashMap<>();
            p.put("url", null);
            ToolResult result = tool.execute(p);
            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("execute with valid url attempts fetch (fails with error, never throws)")
        void validUrlAttemptsFetch() {
            WebFetchTool tool = createTool(true, 1); // 1s timeout for fast failure
            ToolResult result = tool.execute(params("url", "https://www.example.com"));
            assertNotNull(result, "Result should never be null");
            if (result.isSuccess()) {
                assertNotNull(result.getContent());
            } else {
                assertNotNull(result.getError());
                assertFalse(result.getError().isEmpty());
            }
        }

        @Test
        @DisplayName("execute strips backtick wrappers around the url before validating")
        void stripsBacktickWrappers() {
            WebFetchTool tool = createTool(true, 1);
            ToolResult result = tool.execute(params("url", "`https://www.example.com`"));
            assertNotNull(result);
            // Backticks are stripped → URL is valid → proceeds to fetch (fails on no network).
            // If stripping failed, the error would mention "Invalid URL" instead of a fetch error.
            if (!result.isSuccess()) {
                assertFalse(result.getError().contains("Invalid URL"),
                        "Should not fail URL validation after stripping: " + result.getError());
            }
        }
    }

    // ── tool interface compliance ─────────────────────────────────────────

    @Nested
    @DisplayName("Tool interface compliance")
    class InterfaceCompliance {

        @Test
        @DisplayName("hasRequiredParameters returns false (uses built-in validation)")
        void hasNoRequiredParametersDeclared() {
            WebFetchTool tool = createTool(true);
            assertFalse(tool.hasRequiredParameters());
        }

        @Test
        @DisplayName("validateParameters returns valid (validation is in executeInternal)")
        void validateParametersAlwaysValid() {
            WebFetchTool tool = createTool(true);
            var validation = tool.validateParameters(new HashMap<>());
            assertTrue(validation.isValid());
        }

        @Test
        @DisplayName("getPriority returns 0 (default)")
        void defaultPriority() {
            WebFetchTool tool = createTool(true);
            assertEquals(0, tool.getPriority());
        }

        @Test
        @DisplayName("getTimeoutMs returns 0 (use registry default)")
        void defaultTimeout() {
            WebFetchTool tool = createTool(true);
            assertEquals(0, tool.getTimeoutMs());
        }

        @Test
        @DisplayName("AbstractTool.execute catches exceptions and returns error result")
        void executeNeverThrows() {
            WebFetchTool tool = createTool(true, 1);
            assertDoesNotThrow(() -> {
                ToolResult result = tool.execute(params("url", "https://www.example.com"));
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
            WebFetchTool tool = createTool(true);
            assertTrue(tool.isSsrfProtectionEnabled());
        }

        @Test
        @DisplayName("validateUrl rejects empty URL")
        void validateUrlRejectsEmpty() {
            WebFetchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl(""));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl(null));
        }

        @Test
        @DisplayName("validateUrl rejects non-HTTP schemes")
        void validateUrlRejectsNonHttp() {
            WebFetchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("file:///etc/passwd"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("ftp://example.com"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("javascript:alert(1)"));
        }

        @Test
        @DisplayName("validateUrl rejects private network addresses")
        void validateUrlRejectsPrivateNetworks() {
            WebFetchTool tool = createTool(true);
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
            WebFetchTool tool = createTool(true);
            assertDoesNotThrow(() -> tool.validateUrl("https://www.example.com"));
            assertDoesNotThrow(() -> tool.validateUrl("https://r.jinaai.cn/https%3A%2F%2Fexample.com"));
            assertDoesNotThrow(() -> tool.validateUrl("http://example.com/path"));
        }

        @Test
        @DisplayName("validateUrl rejects malformed URLs")
        void validateUrlRejectsMalformed() {
            WebFetchTool tool = createTool(true);
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("not a url at all"));
            assertThrows(IllegalArgumentException.class, () -> tool.validateUrl("http://"));
        }
    }
}
