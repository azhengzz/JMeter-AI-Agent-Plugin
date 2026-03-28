package org.qainsights.jmeter.ai.agent.tools.web;

import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Base class for web tools with common security and URL validation logic.
 */
public abstract class AbstractWebTool extends AbstractTool {
    private static final Logger log = LoggerFactory.getLogger(AbstractWebTool.class);

    // Patterns for detecting private/local network addresses
    private static final List<Pattern> PRIVATE_NETWORK_PATTERNS = List.of(
        Pattern.compile("^https?://localhost\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^https?://127\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^https?://10\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^https?://172\\.(1[6-9]|2[0-9]|3[01])\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^https?://192\\.168\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^https?://169\\.254\\.", Pattern.CASE_INSENSITIVE), // Link-local
        Pattern.compile("^https?://::1", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^https?://\\[?fc00:", Pattern.CASE_INSENSITIVE), // IPv6 private
        Pattern.compile("^https?://\\[?fe80:", Pattern.CASE_INSENSITIVE), // IPv6 link-local
        Pattern.compile("^https?://0\\.0\\.0\\.0", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^file://", Pattern.CASE_INSENSITIVE)
    );

    private final boolean enabled;
    private final boolean ssrfProtectionEnabled;
    private final int maxRedirects;

    public AbstractWebTool() {
        this.enabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.websearch.enabled", "false"));
        this.ssrfProtectionEnabled = Boolean.parseBoolean(
            AiConfig.getProperty("agent.tools.web.ssrf.protection", "true"));
        this.maxRedirects = Integer.parseInt(
            AiConfig.getProperty("agent.tools.web.max.redirects", "5"));
    }

    /**
     * Check if web tools are enabled.
     */
    protected boolean isWebToolsEnabled() {
        return enabled;
    }

    /**
     * Validate a URL for security issues.
     *
     * @param urlString The URL to validate
     * @throws IllegalArgumentException if URL is invalid or unsafe
     */
    protected void validateUrl(String urlString) throws IllegalArgumentException {
        if (urlString == null || urlString.isEmpty()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        try {
            URI uri = new URI(urlString);

            // Check scheme
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only HTTP and HTTPS URLs are allowed");
            }

            // Check host
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("URL must have a valid host");
            }

            // SSRF protection - check for private networks
            if (ssrfProtectionEnabled && isPrivateNetworkUrl(urlString)) {
                throw new IllegalArgumentException(
                    "Access to private/local networks is not allowed for security reasons");
            }

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + e.getMessage());
        }
    }

    /**
     * Check if a URL points to a private or local network.
     *
     * @param url The URL to check
     * @return true if URL is private/local network
     */
    private boolean isPrivateNetworkUrl(String url) {
        for (Pattern pattern : PRIVATE_NETWORK_PATTERNS) {
            if (pattern.matcher(url).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get maximum number of redirects allowed.
     */
    protected int getMaxRedirects() {
        return maxRedirects;
    }

    /**
     * Check if SSRF protection is enabled.
     */
    protected boolean isSsrfProtectionEnabled() {
        return ssrfProtectionEnabled;
    }
}
