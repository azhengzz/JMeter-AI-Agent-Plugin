package org.qainsights.jmeter.ai.agent.tools.web;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Tool to fetch and extract readable content from URLs.
 * Based on Nanobot's WebFetchTool implementation.
 *
 * Uses Jina Reader API for content extraction, with fallback to direct fetching.
 */
public class WebFetchTool extends AbstractWebTool {
    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private final int timeoutSeconds;
    private final int maxContentLength;
    private final String jinaApiKey;

    public WebFetchTool() {
        super();
        this.timeoutSeconds = Integer.parseInt(AiConfig.getProperty("agent.tools.webfetch.timeout", "30"));
        this.maxContentLength = Integer.parseInt(AiConfig.getProperty("agent.tools.webfetch.max.length", "50000"));
        this.jinaApiKey = AiConfig.getProperty("agent.tools.websearch.jina.api.key", "");
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "Fetch and extract readable content from a URL. " +
                "Removes navigation, ads, and other clutter to get the main content. " +
                "Supports text extraction from web pages, articles, and documentation. " +
                "Uses Jina Reader API for optimal content extraction.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "url": {
                            "type": "string",
                            "description": "The URL to fetch content from"
                        },
                        "use_reader": {
                            "type": "boolean",
                            "description": "Whether to use Jina Reader API for content extraction (default: true)"
                        }
                    },
                    "required": ["url"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (!isWebToolsEnabled()) {
            return ToolResult.error("Web tools are disabled. Enable them in configuration.");
        }

        try {
            String url = getRequiredParameter(parameters, "url");
            boolean useReader = getBooleanParameter(parameters, "use_reader", true);

            // Validate URL
            validateUrl(url);

            // Fetch content
            if (useReader) {
                return fetchWithJinaReader(url);
            } else {
                return fetchDirect(url);
            }

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for web_fetch: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error fetching URL", e);
            return ToolResult.error("Fetch failed: " + e.getMessage());
        }
    }

    /**
     * Fetch content using Jina Reader API.
     */
    private ToolResult fetchWithJinaReader(String url) {
        try {
            // Encode the target URL
            String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
            String readerUrl = "https://r.jina.ai/" + encodedUrl;

            HttpURLConnection conn = createConnection(readerUrl, "GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            // Add API key if available
            if (!jinaApiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + jinaApiKey);
            }

            int responseCode = conn.getResponseCode();

            // Handle redirects manually for security
            if (isRedirect(responseCode)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();

                if (location != null) {
                    // Validate redirect target
                    validateUrl(location);
                    return fetchWithJinaReader(location);
                } else {
                    return ToolResult.error("Server returned redirect without Location header");
                }
            }

            if (responseCode != 200) {
                String error = readErrorResponse(conn);
                conn.disconnect();
                log.warn("Jina Reader returned status {}: {}", responseCode, error);
                // Fall back to direct fetch
                return fetchDirect(url);
            }

            String content = readResponse(conn);
            conn.disconnect();

            // Truncate if too long
            if (content.length() > maxContentLength) {
                content = content.substring(0, maxContentLength) +
                        "\n\n... (content truncated, " +
                        (content.length() - maxContentLength) + " more characters)";
            }

            StringBuilder result = new StringBuilder();
            result.append("Source: ").append(url).append("\n\n");
            result.append(content);

            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.warn("Jina Reader failed, falling back to direct fetch", e);
            return fetchDirect(url);
        }
    }

    /**
     * Fetch content directly from URL.
     */
    private ToolResult fetchDirect(String url) {
        try {
            HttpURLConnection conn = createConnection(url, "GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml");

            int responseCode = conn.getResponseCode();

            // Handle redirects
            if (isRedirect(responseCode)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();

                if (location != null) {
                    validateUrl(location);
                    return fetchDirect(location);
                } else {
                    return ToolResult.error("Server returned redirect without Location header");
                }
            }

            if (responseCode != 200) {
                String error = readErrorResponse(conn);
                conn.disconnect();
                return ToolResult.error("HTTP " + responseCode + ": " + error);
            }

            // Check content type
            String contentType = conn.getContentType();
            if (contentType != null && !contentType.contains("text/html") &&
                !contentType.contains("text/plain")) {
                conn.disconnect();
                return ToolResult.error("Unsupported content type: " + contentType);
            }

            String html = readResponse(conn);
            conn.disconnect();

            // Extract text from HTML (simple extraction)
            String text = extractTextFromHtml(html);

            // Truncate if too long
            if (text.length() > maxContentLength) {
                text = text.substring(0, maxContentLength) +
                        "\n\n... (content truncated)";
            }

            StringBuilder result = new StringBuilder();
            result.append("Source: ").append(url).append("\n");
            result.append("(Note: Content extracted from HTML, may include formatting)\n\n");
            result.append(text);

            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.error("Direct fetch failed", e);
            return ToolResult.error("Failed to fetch URL: " + e.getMessage());
        }
    }

    /**
     * Extract readable text from HTML (simple implementation).
     */
    private String extractTextFromHtml(String html) {
        StringBuilder text = new StringBuilder();

        // Remove script tags
        html = html.replaceAll("(?s)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?s)<style[^>]*>.*?</style>", "");

        // Replace common block elements with newlines
        html = html.replaceAll("(?s)</?p[^>]*>", "\n\n");
        html = html.replaceAll("(?s)</?div[^>]*>", "\n");
        html = html.replaceAll("(?s)</?h[1-6][^>]*>", "\n\n");
        html = html.replaceAll("(?s)<br[^>]*>", "\n");
        html = html.replaceAll("(?s)</li>", "\n");

        // Remove all remaining HTML tags
        html = html.replaceAll("<[^>]+>", "");

        // Decode HTML entities
        html = html.replace("&nbsp;", " ");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");
        html = html.replace("&amp;", "&");
        html = html.replace("&quot;", "\"");
        html = html.replace("&#39;", "'");
        html = html.replace("&mdash;", "---");
        html = html.replace("&ndash;", "--");
        html = html.replace("&rsquo;", "'");
        html = html.replace("&lsquo;", "'");
        html = html.replace("&rdquo;", "\"");
        html = html.replace("&ldquo;", "\"");

        // Clean up excessive whitespace
        html = html.replaceAll("\\n{3,}", "\n\n");
        html = html.replaceAll("[ \t]+", " ");

        return html.trim();
    }

    /**
     * Check if response code is a redirect.
     */
    private boolean isRedirect(int responseCode) {
        return responseCode == 301 || responseCode == 302 ||
               responseCode == 303 || responseCode == 307 ||
               responseCode == 308;
    }

    /**
     * Create HTTP connection with timeout settings.
     */
    private HttpURLConnection createConnection(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        conn.setInstanceFollowRedirects(false); // Handle redirects manually
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
     * Get required parameter or throw exception.
     */
    private String getRequiredParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value.toString();
    }
}
