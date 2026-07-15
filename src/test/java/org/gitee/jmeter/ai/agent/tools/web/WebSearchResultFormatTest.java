package org.gitee.jmeter.ai.agent.tools.web;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Verifies the unified formatter {@code formatResults} (mirrors Nanobot's
 * {@code _format_results}): every provider feeds it the same {@code SearchResult}
 * model and gets an identical "Results for: {query}" layout.
 *
 * Provider-specific parsing is inlined in each search method and reached only via
 * live HTTP, so it is not unit-tested here; this test pins the formatting contract.
 */
@DisplayName("WebSearchTool — unified result formatting")
class WebSearchResultFormatTest {

    private MockedStatic<JMeterUtils> jmeterUtilsMock;
    private WebSearchTool tool;

    private static final String QUERY = "test query";

    @BeforeEach
    void setUp() {
        jmeterUtilsMock = mockStatic(JMeterUtils.class);
        // Default: return the supplied default for any unstubbed property key.
        jmeterUtilsMock.when(() -> JMeterUtils.getPropDefault(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        tool = new WebSearchTool();
    }

    @AfterEach
    void tearDown() {
        if (jmeterUtilsMock != null) {
            jmeterUtilsMock.close();
        }
    }

    private ToolResult format(List<WebSearchTool.SearchResult> items, int max) {
        return tool.formatResults(QUERY, items, max);
    }

    private static WebSearchTool.SearchResult result(String title, String url, String snippet) {
        return new WebSearchTool.SearchResult(title, url, snippet);
    }

    @Test
    @DisplayName("results render in the unified 'Results for:' layout")
    void rendersUnifiedLayout() {
        List<WebSearchTool.SearchResult> items = List.of(
                result("First Title", "https://first.example.com", "First snippet"),
                result("Second Title", "https://second.example.com", "Second snippet"));

        String out = format(items, 10).getContent();

        assertTrue(out.startsWith("Results for: " + QUERY + "\n"), out);
        assertTrue(out.contains("1. First Title\n"), out);
        assertTrue(out.contains("   https://first.example.com\n"), out);
        assertTrue(out.contains("   First snippet"), out);
        assertTrue(out.contains("2. Second Title\n"), out);
        assertTrue(out.contains("   https://second.example.com\n"), out);
    }

    @Test
    @DisplayName("snippet line is omitted when snippet is empty/blank")
    void omitsBlankSnippet() {
        String out = format(List.of(result("Title", "https://x.example.com", "")), 10).getContent();
        assertTrue(out.contains("1. Title\n"), out);
        // No snippet → the URL line is the last line (output is trimmed, no trailing newline).
        assertTrue(out.endsWith("   https://x.example.com"), out);
        assertFalse(out.contains("   \n"), out);
    }

    @Test
    @DisplayName("empty result list yields 'No results for:'")
    void emptyResultsReturnNoResults() {
        ToolResult res = format(List.of(), 10);
        assertTrue(res.isSuccess());
        assertEquals("No results for: " + QUERY, res.getContent());
    }

    @Test
    @DisplayName("null result list yields 'No results for:'")
    void nullResultsReturnNoResults() {
        assertEquals("No results for: " + QUERY, format(null, 10).getContent());
    }

    @Test
    @DisplayName("maxResults caps the number of rendered items")
    void maxResultsCapsOutput() {
        List<WebSearchTool.SearchResult> items = List.of(
                result("First", "https://a.example.com", ""),
                result("Second", "https://b.example.com", ""));

        String out = format(items, 1).getContent();
        assertTrue(out.contains("1. First"), out);
        assertFalse(out.contains("2. Second"), out);
    }

    @Test
    @DisplayName("HTML tags are stripped from title and snippet")
    void htmlTagsStripped() {
        String out = format(
                List.of(result("<b>Bold</b> Title", "https://x.example.com", "<i>Italic</i> snippet")),
                10).getContent();
        assertTrue(out.contains("1. Bold Title\n"), out);
        assertTrue(out.contains("   Italic snippet"), out);
        assertFalse(out.contains("<b>"), out);
        assertFalse(out.contains("<i>"), out);
    }

    @Test
    @DisplayName("excessive whitespace in title/snippet is normalized")
    void whitespaceNormalized() {
        String out = format(
                List.of(result("Spaced   Title", "https://x.example.com", "Multi\n\n\n\nblank")),
                10).getContent();
        assertTrue(out.contains("1. Spaced Title\n"), out);
        assertTrue(out.contains("   Multi\n\nblank"), out);
        assertFalse(out.contains("Multi\n\n\n"), out);
    }
}
