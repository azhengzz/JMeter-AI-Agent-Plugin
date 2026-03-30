package org.qainsights.jmeter.ai.agent.tools.filesystem;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool to edit files with intelligent text matching.
 * Based on Nanobot's EditFileTool implementation.
 *
 * Features:
 * - Exact match replacement
 * - Whitespace-normalized matching (ignores differences in whitespace)
 * - Sliding window fuzzy matching
 * - CRLF line ending preservation
 * - Multiple occurrence warning
 */
public class EditFileTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(EditFileTool.class);

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Edit a file by replacing old_text with new_text. " +
                "Supports minor whitespace/line-ending differences. " +
                "Set replace_all=true to replace every occurrence.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "file_path": {
                            "type": "string",
                            "description": "The file path to edit (relative or absolute)"
                        },
                        "old_text": {
                            "type": "string",
                            "description": "The text to find and replace"
                        },
                        "new_text": {
                            "type": "string",
                            "description": "The text to replace with"
                        },
                        "replace_all": {
                            "type": "boolean",
                            "description": "Replace all occurrences (default: false)"
                        }
                    },
                    "required": ["file_path", "old_text", "new_text"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (!isFsToolsEnabled()) {
            return ToolResult.error("Filesystem tools are disabled. Enable them in configuration.");
        }

        try {
            String filePath = getRequiredParameter(parameters, "file_path");
            String oldText = getRequiredParameter(parameters, "old_text");
            String newText = getRequiredParameter(parameters, "new_text");
            boolean replaceAll = getBooleanParameter(parameters, "replace_all", false);

            // Validate and resolve path
            Path path = validateAndResolvePath(filePath);

            // Check if file exists
            if (!Files.exists(path)) {
                return ToolResult.error("File not found: " + path);
            }

            // Check if it's a directory
            if (Files.isDirectory(path)) {
                return ToolResult.error("Path is a directory, not a file: " + path);
            }

            // Read file as bytes to detect line endings (Nanobot-style)
            byte[] raw = Files.readAllBytes(path);
            boolean usesCrlf = containsCrlf(raw);

            // Decode with CRLF normalization
            String content = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");

            // Normalize old_text and new_text to LF for comparison
            String normalizedOldText = oldText.replace("\r\n", "\n");
            String normalizedNewText = newText.replace("\r\n", "\n");

            // Find match using Nanobot's algorithm
            MatchResult matchResult = findMatch(content, normalizedOldText);

            if (matchResult.match == null) {
                return ToolResult.error(buildNotFoundMessage(normalizedOldText, content, path.toString()));
            }

            // Check for multiple occurrences if replace_all is false
            if (matchResult.count > 1 && !replaceAll) {
                return ToolResult.error(
                    "Warning: old_text appears " + matchResult.count + " times. " +
                    "Provide more context to make it unique, or set replace_all=true."
                );
            }

            // Apply replacement
            String newContent;
            if (replaceAll) {
                newContent = content.replace(matchResult.match, normalizedNewText);
            } else {
                newContent = replaceFirst(content, matchResult.match, normalizedNewText);
            }

            // Restore original line endings if needed
            if (usesCrlf) {
                newContent = newContent.replace("\n", "\r\n");
            }

            // Write back to file
            Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));

            log.info("Edited file: {}, replacements: {}", path, replaceAll ? matchResult.count : 1);

            return ToolResult.success("Successfully edited " + path);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for edit_file: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (IOException e) {
            log.error("Error editing file", e);
            return ToolResult.error("Error editing file: " + e.getMessage());
        }
    }

    /**
     * Check if byte array contains CRLF line endings.
     * Nanobot: uses_crlf = b"\r\n" in raw
     */
    private boolean containsCrlf(byte[] raw) {
        for (int i = 0; i < raw.length - 1; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n') {
                return true;
            }
        }
        return false;
    }

    /**
     * Find match using Nanobot's algorithm.
     * 1. Try exact match first
     * 2. Then try line-trimmed sliding window match
     */
    private MatchResult findMatch(String content, String oldText) {
        // First try exact match
        if (content.contains(oldText)) {
            int count = countOccurrences(content, oldText);
            return new MatchResult(oldText, count);
        }

        // Try line-trimmed sliding window (Nanobot's _find_match function)
        String[] oldLines = oldText.split("\n");
        if (oldLines.length == 0) {
            return new MatchResult(null, 0);
        }

        // Strip whitespace from each line
        List<String> strippedOld = new ArrayList<>();
        for (String line : oldLines) {
            strippedOld.add(line.strip());
        }

        String[] contentLines = content.split("\n");
        List<String> candidates = new ArrayList<>();

        // Sliding window search
        for (int i = 0; i <= contentLines.length - strippedOld.size(); i++) {
            // Build window from content lines
            List<String> window = new ArrayList<>();
            for (int j = 0; j < strippedOld.size(); j++) {
                window.add(contentLines[i + j].strip());
            }

            // Compare with stripped old text
            if (window.equals(strippedOld)) {
                // Reconstruct the matched fragment from original content
                StringBuilder matchedFragment = new StringBuilder();
                for (int j = 0; j < strippedOld.size(); j++) {
                    if (j > 0) matchedFragment.append("\n");
                    matchedFragment.append(contentLines[i + j]);
                }
                candidates.add(matchedFragment.toString());
            }
        }

        if (candidates.isEmpty()) {
            return new MatchResult(null, 0);
        }

        // Return the first candidate with count
        String match = candidates.get(0);
        int count = candidates.size();
        return new MatchResult(match, count);
    }

    /**
     * Replace only the first occurrence.
     */
    private String replaceFirst(String content, String target, String replacement) {
        int index = content.indexOf(target);
        if (index == -1) {
            return content;
        }
        return content.substring(0, index) + replacement +
               content.substring(index + target.length());
    }

    /**
     * Count occurrences of substring in text.
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    /**
     * Build "not found" error message with best match suggestion.
     * Based on Nanobot's _not_found_msg method.
     */
    private String buildNotFoundMessage(String oldText, String content, String path) {
        String[] oldLines = oldText.split("\n", -1);
        String[] contentLines = content.split("\n", -1);
        int window = oldLines.length;

        // Find best match using simple similarity ratio
        float bestRatio = 0.0f;
        int bestStart = 0;

        for (int i = 0; i < Math.max(1, contentLines.length - window + 1); i++) {
            float ratio = calculateSimilarity(oldLines,
                    java.util.Arrays.copyOfRange(contentLines, i, Math.min(i + window, contentLines.length)));
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestStart = i;
            }
        }

        if (bestRatio > 0.5f) {
            // Build simple diff-like message
            StringBuilder sb = new StringBuilder();
            sb.append("Error: old_text not found in ").append(path).append(".\n");
            sb.append("Best match (").append((int)(bestRatio * 100)).append("% similar) at line ")
              .append(bestStart + 1).append(":\n");
            sb.append("\nExpected (old_text):\n");
            for (String line : oldLines) {
                sb.append("  ").append(line).append("\n");
            }
            sb.append("\nActual (at line ").append(bestStart + 1).append("):\n");
            int endLine = Math.min(bestStart + window, contentLines.length);
            for (int i = bestStart; i < endLine; i++) {
                sb.append("  ").append(contentLines[i]).append("\n");
            }
            return sb.toString();
        }

        return "Error: old_text not found in " + path + ". No similar text found. Verify the file content.";
    }

    /**
     * Calculate similarity ratio between two string arrays.
     */
    private float calculateSimilarity(String[] a, String[] b) {
        if (a.length != b.length) {
            return 0.0f;
        }

        int matches = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i].strip().equals(b[i].strip())) {
                matches++;
            }
        }

        return (float) matches / a.length;
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
     * Result of match operation.
     */
    private static class MatchResult {
        final String match;
        final int count;

        MatchResult(String match, int count) {
            this.match = match;
            this.count = count;
        }
    }
}
