package org.qainsights.jmeter.ai.agent.tools.filesystem;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool to edit files with intelligent text matching.
 * Based on Nanobot's EditFileTool implementation.
 *
 * Supports three matching modes:
 * 1. Exact match - matches the old_string exactly
 * 2. Whitespace-normalized - ignores differences in whitespace
 * 3. Sliding window - tries to find the best match in a sliding window
 */
public class EditFileTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(EditFileTool.class);

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Edit a file by replacing text. " +
                "Finds the old_string in the file and replaces it with new_string. " +
                "Supports intelligent matching including whitespace-normalized matching " +
                "and sliding window matching for fuzzy matches. " +
                "Returns a summary of changes made including line numbers.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "file_path": {
                            "type": "string",
                            "description": "The path to the file to edit (relative or absolute)"
                        },
                        "old_string": {
                            "type": "string",
                            "description": "The text to search for and replace"
                        },
                        "new_string": {
                            "type": "string",
                            "description": "The new text to replace the old_string with"
                        },
                        "match_mode": {
                            "type": "string",
                            "enum": ["exact", "normalized", "sliding"],
                            "description": "Matching mode: exact (default), normalized (ignores whitespace), sliding (fuzzy match)"
                        }
                    },
                    "required": ["file_path", "old_string", "new_string"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (!isFsToolsEnabled()) {
            return ToolResult.error("Filesystem tools are disabled. Enable them in configuration.");
        }

        try {
            String filePath = getRequiredParameter(parameters, "file_path").toString();
            String oldString = getRequiredParameter(parameters, "old_string").toString();
            String newString = getRequiredParameter(parameters, "new_string").toString();
            String matchMode = getParameter(parameters, "match_mode", "exact");

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

            // Read file content
            String content = Files.readString(path);

            // Perform the edit based on match mode
            EditResult result = performEdit(content, oldString, newString, matchMode);

            if (!result.found) {
                return ToolResult.error(
                    "Could not find the old_string in the file. " +
                    "The text to replace was not found. Make sure the old_string matches exactly.");
            }

            // Write the modified content back
            Files.writeString(path, result.modifiedContent);

            log.info("Edited file: {}, replacements: {}", path, result.replacements);

            return ToolResult.success(buildSuccessMessage(path, result));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for edit_file: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (IOException e) {
            log.error("Error editing file", e);
            return ToolResult.error("Error editing file: " + e.getMessage());
        }
    }

    /**
     * Perform the edit operation based on match mode.
     */
    private EditResult performEdit(String content, String oldString, String newString, String matchMode) {
        EditResult result = new EditResult();

        switch (matchMode.toLowerCase()) {
            case "normalized":
                result = performNormalizedEdit(content, oldString, newString);
                break;
            case "sliding":
                result = performSlidingEdit(content, oldString, newString);
                break;
            case "exact":
            default:
                result = performExactEdit(content, oldString, newString);
                break;
        }

        return result;
    }

    /**
     * Perform exact string replacement.
     */
    private EditResult performExactEdit(String content, String oldString, String newString) {
        EditResult result = new EditResult();

        if (!content.contains(oldString)) {
            result.found = false;
            return result;
        }

        result.modifiedContent = content.replace(oldString, newString);
        result.found = true;
        result.replacements = countOccurrences(content, oldString);
        result.matchMode = "exact";

        return result;
    }

    /**
     * Perform whitespace-normalized replacement.
     */
    private EditResult performNormalizedEdit(String content, String oldString, String newString) {
        EditResult result = new EditResult();

        // Normalize whitespace in both content and old string
        String normalizedContent = normalizeWhitespace(content);
        String normalizedOld = normalizeWhitespace(oldString);

        if (!normalizedContent.contains(normalizedOld)) {
            result.found = false;
            return result;
        }

        // Find all matches with line numbers
        List<MatchInfo> matches = findNormalizedMatches(content, oldString);

        if (matches.isEmpty()) {
            result.found = false;
            return result;
        }

        // Apply replacements (in reverse order to preserve line numbers)
        String modified = content;
        for (int i = matches.size() - 1; i >= 0; i--) {
            MatchInfo match = matches.get(i);
            modified = match.original.substring(0, match.start) +
                       newString +
                       match.original.substring(match.end);
        }

        result.modifiedContent = modified;
        result.found = true;
        result.replacements = matches.size();
        result.matchLines = matches.stream().map(m -> m.lineNumber).toList();
        result.matchMode = "normalized";

        return result;
    }

    /**
     * Perform sliding window fuzzy matching.
     */
    private EditResult performSlidingEdit(String content, String oldString, String newString) {
        EditResult result = new EditResult();

        // Split into lines
        String[] lines = content.split("\n");
        String[] oldLines = oldString.split("\n");

        // Find best match using sliding window
        MatchInfo bestMatch = findBestSlidingMatch(lines, oldLines);

        if (bestMatch == null) {
            result.found = false;
            return result;
        }

        // Apply the replacement
        StringBuilder modified = new StringBuilder();
        for (int i = 0; i < bestMatch.start; i++) {
            modified.append(lines[i]).append("\n");
        }
        modified.append(newString).append("\n");
        for (int i = bestMatch.end; i < lines.length; i++) {
            modified.append(lines[i]).append("\n");
        }

        result.modifiedContent = modified.toString();
        result.found = true;
        result.replacements = 1;
        result.matchLines = List.of(bestMatch.lineNumber);
        result.matchMode = "sliding";

        return result;
    }

    /**
     * Find matches using normalized whitespace comparison.
     */
    private List<MatchInfo> findNormalizedMatches(String content, String oldString) {
        List<MatchInfo> matches = new ArrayList<>();
        String[] contentLines = content.split("\n");
        String[] oldLines = oldString.split("\n");

        // Try to find the old string in the content
        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldLines.length; j++) {
                String normalizedContentLine = normalizeWhitespace(contentLines[i + j]);
                String normalizedOldLine = normalizeWhitespace(oldLines[j]);
                if (!normalizedContentLine.equals(normalizedOldLine)) {
                    match = false;
                    break;
                }
            }

            if (match) {
                // Calculate start and end positions
                int startPos = 0;
                for (int k = 0; k < i; k++) {
                    startPos += contentLines[k].length() + 1; // +1 for newline
                }
                int endPos = startPos;
                for (int k = i; k < i + oldLines.length; k++) {
                    endPos += contentLines[k].length() + 1;
                }

                matches.add(new MatchInfo(startPos, endPos, i + 1, content));
            }
        }

        return matches;
    }

    /**
     * Find the best sliding window match.
     */
    private MatchInfo findBestSlidingMatch(String[] contentLines, String[] oldLines) {
        MatchInfo bestMatch = null;
        int bestScore = 0;

        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            int score = 0;
            for (int j = 0; j < oldLines.length; j++) {
                String normalizedContent = normalizeWhitespace(contentLines[i + j]);
                String normalizedOld = normalizeWhitespace(oldLines[j]);
                if (normalizedContent.equals(normalizedOld)) {
                    score++;
                } else if (normalizedContent.contains(normalizedOld) || normalizedOld.contains(normalizedContent)) {
                    score += 0.5;
                }
            }

            // Require at least 50% match
            if (score >= oldLines.length * 0.5 && score > bestScore) {
                bestScore = score;
                bestMatch = new MatchInfo(i, i + oldLines.length, i + 1, null);
            }
        }

        return bestMatch;
    }

    /**
     * Normalize whitespace by collapsing multiple spaces/tabs into single space.
     */
    private String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
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
     * Build success message with details.
     */
    private String buildSuccessMessage(Path path, EditResult result) {
        StringBuilder message = new StringBuilder();
        message.append("Successfully edited file: ").append(path).append("\n");
        message.append("Mode: ").append(result.matchMode).append("\n");
        message.append("Replacements: ").append(result.replacements).append("\n");

        if (result.matchLines != null && !result.matchLines.isEmpty()) {
            message.append("Modified lines: ");
            for (int i = 0; i < result.matchLines.size(); i++) {
                if (i > 0) message.append(", ");
                message.append(result.matchLines.get(i));
            }
            message.append("\n");
        }

        return message.toString();
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
     * Get parameter with default value.
     */
    private String getParameter(Map<String, Object> parameters, String key, String defaultValue) {
        return getStringParameter(parameters, key, defaultValue);
    }

    /**
     * Result of edit operation.
     */
    private static class EditResult {
        String modifiedContent;
        boolean found;
        int replacements;
        List<Integer> matchLines;
        String matchMode;
    }

    /**
     * Match information.
     */
    private static class MatchInfo {
        final int start;
        final int end;
        final int lineNumber;
        final String original;

        MatchInfo(int start, int end, int lineNumber, String original) {
            this.start = start;
            this.end = end;
            this.lineNumber = lineNumber;
            this.original = original;
        }
    }
}
