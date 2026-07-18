package org.gitee.jmeter.ai.agent.tools.web;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool to search the web using multiple search providers.
 * Based on Nanobot's WebSearchTool implementation.
 *
 * Supported providers:
 * - Brave Search (requires API key)
 * - Tavily (requires API key)
 * - Jina Search (no API key required; terminal free fallback)
 */
public class WebSearchTool extends AbstractWebTool {
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final String defaultProvider;
    private final int maxResults;
    private final int timeoutSeconds;

    // API keys from configuration
    private final String braveApiKey;
    private final String tavilyApiKey;
    private final String jinaApiKey;

    /** Shared JSON mapper (project convention). */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Default User-Agent for all providers (mirrors Nanobot _DEFAULT_USER_AGENT). */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_2) AppleWebKit/537.36";

    public WebSearchTool() {
        super();
        this.defaultProvider = AiConfig.getProperty("agent.tools.websearch.provider", "jina");
        this.maxResults = Integer.parseInt(AiConfig.getProperty("agent.tools.websearch.max.results", "10"));
        this.timeoutSeconds = Integer.parseInt(AiConfig.getProperty("agent.tools.websearch.timeout", "30"));

        this.braveApiKey = AiConfig.getProperty("agent.tools.websearch.brave.api.key", "");
        this.tavilyApiKey = AiConfig.getProperty("agent.tools.websearch.tavily.api.key", "");
        this.jinaApiKey = AiConfig.getProperty("agent.tools.websearch.jina.api.key", "");
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the web for information. " +
                "Supports multiple search providers including Brave, Tavily, and Jina. " +
                "Returns relevant search results with titles, snippets, and URLs. " +
                "Useful for finding current information, documentation, and online resources.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The search query"
                        },
                        "provider": {
                            "type": "string",
                            "enum": ["brave", "tavily", "jina"],
                            "description": "The search provider to use (default: jina)"
                        },
                        "max_results": {
                            "type": "number",
                            "description": "Maximum number of results to return (default: 10)"
                        }
                    },
                    "required": ["query"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (!isWebToolsEnabled()) {
            return ToolResult.error("Web search tools are disabled. Enable them in configuration.");
        }

        try {
            String query = getRequiredParameter(parameters, "query");
            String provider = getStringParameter(parameters, "provider", defaultProvider);
            int maxResults = getIntParameter(parameters, "max_results", this.maxResults);

            if (query.isEmpty()) {
                return ToolResult.error("Search query cannot be empty");
            }

            // Perform search based on provider
            return performSearch(query, provider, maxResults);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for web_search: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error performing web search", e);
            return ToolResult.error("Search failed: " + e.getMessage());
        }
    }

    /**
     * Perform web search using the specified provider.
     */
    private ToolResult performSearch(String query, String provider, int maxResults) {
        switch (provider.toLowerCase()) {
            case "brave":
                return searchBrave(query, maxResults);
            case "tavily":
                return searchTavily(query, maxResults);
            case "jina":
                return searchJina(query, maxResults);
            default:
                return searchBrave(query, maxResults); // Default fallback
        }
    }

    /**
     * Search using Brave Search API.
     */
    private ToolResult searchBrave(String query, int maxResults) {
        // No API key → short-circuit to the free fallback (no wasted HTTP call), mirroring Nanobot.
        if (braveApiKey.isEmpty()) {
            log.warn("Brave API key not set, falling back to Jina");
            return searchJina(query, maxResults);
        }
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery +
                        "&count=" + Math.min(maxResults, 20);

            // Retry once on HTTP 429 (mirrors Nanobot: sleep 1s, single retry).
            int responseCode = 429;
            HttpURLConnection conn = null;
            for (int attempt = 0; attempt < 2 && responseCode == 429; attempt++) {
                if (attempt > 0) {
                    log.warn("Brave search rate limited; retrying once in 1.0s");
                    Thread.sleep(1000L);
                }
                conn = createConnection(url, "GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("X-Subscription-Token", braveApiKey);
                responseCode = conn.getResponseCode();
            }

            if (responseCode == 429) {
                conn.disconnect();
                return ToolResult.error("Brave search rate limited after retry. "
                        + "Retry later or reduce consecutive web_search calls.");
            }
            if (responseCode != 200) {
                conn.disconnect();
                log.warn("Brave Search returned status: {}", responseCode);
                return ToolResult.error("Brave search failed (HTTP " + responseCode + ")");
            }

            String response = readResponse(conn);
            conn.disconnect();

            // Normalize Brave response (web.results[]: title/url/description) → unified model
            List<SearchResult> items = new ArrayList<>();
            try {
                for (JsonNode x : OBJECT_MAPPER.readTree(response).path("web").path("results")) {
                    items.add(new SearchResult(
                            x.path("title").asText(""),
                            x.path("url").asText(""),
                            x.path("description").asText("")));
                }
            } catch (Exception e) {
                log.warn("Failed to parse Brave results: {}", e.getMessage());
            }
            return formatResults(query, items, maxResults);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Brave search interrupted");
        } catch (Exception e) {
            log.warn("Brave Search failed", e);
            return ToolResult.error("Brave search failed: " + e.getMessage());
        }
    }

    /**
     * Search using Tavily API.
     */
    private ToolResult searchTavily(String query, int maxResults) {
        if (tavilyApiKey.isEmpty()) {
            log.warn("Tavily API key not configured, falling back to Jina");
            return searchJina(query, maxResults);
        }

        try {
            String url = "https://api.tavily.com/search";

            HttpURLConnection conn = createConnection(url, "POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + tavilyApiKey);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setDoOutput(true);

            // Build request body via ObjectMapper so the query is escaped correctly
            // (mirrors Nanobot's json={"query","max_results"}; search_depth omitted — "basic" is the API default).
            ObjectNode body = OBJECT_MAPPER.createObjectNode();
            body.put("query", query);
            body.put("max_results", maxResults);
            conn.getOutputStream().write(OBJECT_MAPPER.writeValueAsBytes(body));

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("Tavily API returned status: {}", responseCode);
                return ToolResult.error("Tavily search failed (HTTP " + responseCode + ")");
            }

            String response = readResponse(conn);
            conn.disconnect();

            // Normalize Tavily response (results[]: title/url/content) → unified model
            List<SearchResult> items = new ArrayList<>();
            try {
                for (JsonNode x : OBJECT_MAPPER.readTree(response).path("results")) {
                    items.add(new SearchResult(
                            x.path("title").asText(""),
                            x.path("url").asText(""),
                            x.path("content").asText("")));
                }
            } catch (Exception e) {
                log.warn("Failed to parse Tavily results: {}", e.getMessage());
            }
            return formatResults(query, items, maxResults);

        } catch (Exception e) {
            log.warn("Tavily search failed", e);
            return ToolResult.error("Tavily search failed: " + e.getMessage());
        }
    }

    /**
     * Search using Jina Search API (free, no key required). Terminal free fallback:
     * Brave/Tavily fall back here when unconfigured, and Jina returns an error on failure
     * rather than cascading. The .cn mirror's free tier works without a key.
     */
    private ToolResult searchJina(String query, int maxResults) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://s.jinaai.cn/?q=" + encodedQuery;

            HttpURLConnection conn = createConnection(url, "GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (!jinaApiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + jinaApiKey);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("Jina Search returned status: {}", responseCode);
                return ToolResult.error("Jina search failed (HTTP " + responseCode + ")");
            }

            String response = readResponse(conn);
            conn.disconnect();

            // Normalize Jina response (data[]: title/url/content) → unified model.
            // Non-JSON / unparseable → return an error (Jina is the terminal fallback).
            List<SearchResult> items = new ArrayList<>();
            try {
                for (JsonNode d : OBJECT_MAPPER.readTree(response).path("data")) {
                    String content = d.path("content").asText("");
                    if (content.length() > 500) {
                        content = content.substring(0, 500);
                    }
                    items.add(new SearchResult(
                            d.path("title").asText(""),
                            d.path("url").asText(""),
                            content));
                }
            } catch (Exception e) {
                log.warn("Jina returned non-JSON response: {}", e.getMessage());
                return ToolResult.error("Jina search failed: " + e.getMessage());
            }
            return formatResults(query, items, maxResults);

        } catch (Exception e) {
            log.warn("Jina Search failed", e);
            return ToolResult.error("Jina search failed: " + e.getMessage());
        }
    }

    /**
     * Create HTTP connection with timeout settings.
     */
    private HttpURLConnection createConnection(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        return conn;
    }

    /**
     * Read response from HTTP connection.
     */
    private String readResponse(HttpURLConnection conn) throws Exception {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        }
        return response.toString();
    }

    /**
     * Remove HTML tags and decode common entities (mirrors Nanobot _strip_tags).
     */
    private static String stripTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text;
        t = t.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        t = t.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        t = t.replaceAll("(?s)<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return t;
    }

    /**
     * Collapse runs of spaces/tabs and excessive blank lines (mirrors Nanobot _normalize).
     */
    private static String normalizeWs(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text.replaceAll("[ \t]+", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    /**
     * Format parsed results into the unified plaintext layout (mirrors Nanobot
     * _format_results). Every provider renders identically regardless of source.
     */
    ToolResult formatResults(String query, List<SearchResult> results, int maxResults) {
        if (results == null || results.isEmpty()) {
            return ToolResult.success("No results for: " + query);
        }
        StringBuilder out = new StringBuilder();
        out.append("Results for: ").append(query).append("\n\n");
        int limit = Math.min(results.size(), Math.max(1, maxResults));
        for (int i = 0; i < limit; i++) {
            SearchResult r = results.get(i);
            String title = normalizeWs(stripTags(r.title));
            String snippet = normalizeWs(stripTags(r.snippet));
            String url = r.url == null ? "" : r.url;
            out.append(i + 1).append(". ").append(title).append("\n");
            out.append("   ").append(url).append("\n");
            if (!snippet.isEmpty()) {
                out.append("   ").append(snippet).append("\n");
            }
        }
        return ToolResult.success(out.toString().trim());
    }

    /**
     * Get required parameter or throw exception.
     */
    private String getRequiredParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value.toString();
    }

    /**
     * Search result data class (package-visible for unit testing the formatter).
     */
    static class SearchResult {
        final String title;
        final String url;
        final String snippet;

        SearchResult(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }
}
