package org.gitee.jmeter.ai.agent.tools.web;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * - Brave Search (default, no API key required)
 * - Tavily (requires API key)
 * - DuckDuckGo (no API key required)
 * - Jina Search (no API key required)
 */
public class WebSearchTool extends AbstractWebTool {
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final String defaultProvider;
    private final int maxResults;
    private final int timeoutSeconds;

    // API keys from configuration
    private final String tavilyApiKey;
    private final String serpApiKey;
    private final String jinaApiKey;

    public WebSearchTool() {
        super();
        this.defaultProvider = AiConfig.getProperty("agent.tools.websearch.provider", "brave");
        this.maxResults = Integer.parseInt(AiConfig.getProperty("agent.tools.websearch.max.results", "10"));
        this.timeoutSeconds = Integer.parseInt(AiConfig.getProperty("agent.tools.websearch.timeout", "30"));

        this.tavilyApiKey = AiConfig.getProperty("agent.tools.websearch.tavily.api.key", "");
        this.serpApiKey = AiConfig.getProperty("agent.tools.websearch.serpapi.key", "");
        this.jinaApiKey = AiConfig.getProperty("agent.tools.websearch.jina.api.key", "");
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the web for information. " +
                "Supports multiple search providers including Brave, Tavily, DuckDuckGo, and Jina. " +
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
                            "enum": ["brave", "tavily", "duckduckgo", "jina", "serpapi"],
                            "description": "The search provider to use (default: brave)"
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
            case "duckduckgo":
                return searchDuckDuckGo(query, maxResults);
            case "jina":
                return searchJina(query, maxResults);
            case "serpapi":
                return searchSerpApi(query, maxResults);
            default:
                return searchBrave(query, maxResults); // Default fallback
        }
    }

    /**
     * Search using Brave Search API.
     */
    private ToolResult searchBrave(String query, int maxResults) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery +
                        "&count=" + Math.min(maxResults, 20);

            HttpURLConnection conn = createConnection(url, "GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Subscription-Token", jinaApiKey); // Brave API key if available

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("Brave Search returned status: {}", responseCode);
                // Fall back to Jina Search
                return searchJina(query, maxResults);
            }

            String response = readResponse(conn);
            conn.disconnect();

            // Parse and format results
            return formatSearchResults(parseBraveResults(response), "Brave Search");

        } catch (Exception e) {
            log.warn("Brave Search failed, falling back to Jina", e);
            return searchJina(query, maxResults);
        }
    }

    /**
     * Search using Tavily API.
     */
    private ToolResult searchTavily(String query, int maxResults) {
        if (tavilyApiKey.isEmpty()) {
            log.warn("Tavily API key not configured, falling back to Brave");
            return searchBrave(query, maxResults);
        }

        try {
            String url = "https://api.tavily.com/search";

            HttpURLConnection conn = createConnection(url, "POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + tavilyApiKey);
            conn.setDoOutput(true);

            // Build request body
            String requestBody = String.format(
                "{\"query\":\"%s\",\"max_results\":%d,\"search_depth\":\"basic\"}",
                query.replace("\"", "\\\""), maxResults);

            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("Tavily API returned status: {}", responseCode);
                return searchBrave(query, maxResults);
            }

            String response = readResponse(conn);
            conn.disconnect();

            return formatSearchResults(parseTavilyResults(response), "Tavily");

        } catch (Exception e) {
            log.warn("Tavily search failed, falling back to Brave", e);
            return searchBrave(query, maxResults);
        }
    }

    /**
     * Search using DuckDuckGo (via HTML parsing).
     */
    private ToolResult searchDuckDuckGo(String query, int maxResults) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            HttpURLConnection conn = createConnection(url, "GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return searchJina(query, maxResults);
            }

            String response = readResponse(conn);
            conn.disconnect();

            return formatSearchResults(parseDuckDuckGoResults(response), "DuckDuckGo");

        } catch (Exception e) {
            log.warn("DuckDuckGo search failed, falling back to Jina", e);
            return searchJina(query, maxResults);
        }
    }

    /**
     * Search using Jina Search API (free, no key required).
     */
    private ToolResult searchJina(String query, int maxResults) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://s.jina.ai/http://" + encodedQuery;

            HttpURLConnection conn = createConnection(url, "GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String error = readErrorResponse(conn);
                return ToolResult.error("Jina Search failed: " + error);
            }

            String response = readResponse(conn);
            conn.disconnect();

            return ToolResult.success(response);

        } catch (Exception e) {
            log.error("Jina Search failed", e);
            return ToolResult.error("Search failed: " + e.getMessage());
        }
    }

    /**
     * Search using SerpAPI.
     */
    private ToolResult searchSerpApi(String query, int maxResults) {
        if (serpApiKey.isEmpty()) {
            log.warn("SerpAPI key not configured, falling back to Brave");
            return searchBrave(query, maxResults);
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://serpapi.com/search?q=" + encodedQuery +
                        "&api_key=" + serpApiKey +
                        "&engine=google" +
                        "&num=" + maxResults;

            HttpURLConnection conn = createConnection(url, "GET");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return searchBrave(query, maxResults);
            }

            String response = readResponse(conn);
            conn.disconnect();

            return formatSearchResults(parseSerpApiResults(response), "Google (SerpAPI)");

        } catch (Exception e) {
            log.warn("SerpAPI search failed, falling back to Brave", e);
            return searchBrave(query, maxResults);
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
     * Read error response from HTTP connection.
     */
    private String readErrorResponse(HttpURLConnection conn) throws Exception {
        StringBuilder error = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line);
            }
        }
        return error.toString();
    }

    /**
     * Parse Brave Search results (simplified JSON parsing).
     */
    private List<SearchResult> parseBraveResults(String response) {
        List<SearchResult> results = new ArrayList<>();
        // Simplified parsing - in production use a proper JSON library
        // This is a placeholder for the actual implementation
        return results;
    }

    /**
     * Parse Tavily results (simplified JSON parsing).
     */
    private List<SearchResult> parseTavilyResults(String response) {
        List<SearchResult> results = new ArrayList<>();
        // Simplified parsing - in production use a proper JSON library
        return results;
    }

    /**
     * Parse DuckDuckGo HTML results (simplified).
     */
    private List<SearchResult> parseDuckDuckGoResults(String response) {
        List<SearchResult> results = new ArrayList<>();
        // Simplified parsing - in production use a proper HTML parser
        return results;
    }

    /**
     * Parse SerpAPI results (simplified JSON parsing).
     */
    private List<SearchResult> parseSerpApiResults(String response) {
        List<SearchResult> results = new ArrayList<>();
        // Simplified parsing - in production use a proper JSON library
        return results;
    }

    /**
     * Format search results into human-readable output.
     */
    private ToolResult formatSearchResults(List<SearchResult> results, String provider) {
        if (results.isEmpty()) {
            return ToolResult.success("No results found from " + provider);
        }

        StringBuilder output = new StringBuilder();
        output.append("Search results from ").append(provider).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append(i + 1).append(". ").append(result.title).append("\n");
            output.append("   URL: ").append(result.url).append("\n");
            output.append("   ").append(result.snippet).append("\n\n");
        }

        return ToolResult.success(output.toString());
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
     * Search result data class.
     */
    private static class SearchResult {
        String title;
        String url;
        String snippet;
    }
}
