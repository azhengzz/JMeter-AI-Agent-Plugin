package org.qainsights.jmeter.ai.agent.tools.skill;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.qainsights.jmeter.ai.agent.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool to read skill resource files from JAR resources.
 * Skill resource files are located at /skills/jmeter/ in the JAR.
 * Use this tool to read SKILL.md files and their referenced documentation.
 */
public class ReadSkillResourceTool extends AbstractTool {
    private static final Logger log = LoggerFactory.getLogger(ReadSkillResourceTool.class);
    private static final int DEFAULT_LIMIT_LINES = 2000;
    private static final int MAX_CHARS = 128_000;
    private static final String SKILLS_BASE_PATH = "/skills/jmeter/";

    private final ClassLoader classLoader;

    public ReadSkillResourceTool() {
        this.classLoader = getClass().getClassLoader();
    }

    @Override
    public String getName() {
        return "read_skill_resource";
    }

    @Override
    public String getDescription() {
        return "Read a skill resource file stored in JAR (NOT read_file). " +
                "Skill resources include SKILL.md files and their referenced documentation. " +
                "Common paths: 'SKILL.md', 'references/samplers/HTTP Request.md', 'references/standards.md', etc. " +
                "Supports pagination with offset and limit parameters.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "resource_path": {
                            "type": "string",
                            "description": "The path to the skill resource file (e.g., 'SKILL.md', 'references/samplers/HTTP Request.md', 'references/standards.md')"
                        },
                        "offset": {
                            "type": "number",
                            "description": "The line number to start reading from (1-based, default: 1)"
                        },
                        "limit": {
                            "type": "number",
                            "description": "The maximum number of lines to read (default: 2000)"
                        }
                    },
                    "required": ["resource_path"]
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        try {
            String resourcePath = getRequiredParameter(parameters, "resource_path");
            int offset = getIntParameter(parameters, "offset", 1);
            int limit = getIntParameter(parameters, "limit", DEFAULT_LIMIT_LINES);

            // Normalize path separators to forward slashes (JAR resources use /)
            String normalizedPath = normalizePathSeparators(resourcePath);

            // Build full resource path
            String fullPath = SKILLS_BASE_PATH + normalizedPath;

            // Try to read from resources
            String content = readResourceAsString(fullPath);

            if (content == null) {
                // Try URL-encoded version for paths with spaces
                String encodedPath = normalizedPath.replace(" ", "%20");
                fullPath = SKILLS_BASE_PATH + encodedPath;
                content = readResourceAsString(fullPath);
            }

            if (content == null) {
                return ToolResult.error(
                    "Skill resource not found: " + resourcePath + "\n" +
                    "Tried paths:\n" +
                    "  - " + SKILLS_BASE_PATH + normalizedPath + "\n" +
                    "  - " + SKILLS_BASE_PATH + normalizedPath.replace(" ", "%20") + "\n\n" +
                    "Available resources can be found in the skills/jmeter/ directory."
                );
            }

            // Apply pagination
            return applyPagination(content, resourcePath, offset, limit);

        } catch (Exception e) {
            log.error("Error reading skill resource", e);
            return ToolResult.error("Error reading skill resource: " + e.getMessage());
        }
    }

    /**
     * Normalize path separators to forward slashes.
     * JAR resources always use forward slashes regardless of OS.
     */
    private String normalizePathSeparators(String path) {
        if (path == null) {
            return null;
        }
        try {
            // Use Paths to normalize the path (handles .., ., redundant separators)
            String normalized = Paths.get(path).normalize().toString();
            // Replace system path separator with forward slash for JAR resources
            return normalized.replace(java.io.File.separatorChar, '/');
        } catch (Exception e) {
            // Fallback for invalid paths
            log.debug("Failed to normalize path with Paths: {}", path);
            return path.replaceAll("[\\\\/]+", "/");
        }
    }

    /**
     * Apply pagination to content.
     */
    private ToolResult applyPagination(String content, String resourcePath, int offset, int limit) {
        // Split into lines
        String[] linesArray = content.split("\\r?\\n");
        List<String> lines = new ArrayList<>(List.of(linesArray));

        int totalLines = lines.size();

        // Validate and adjust offset
        if (offset < 1) {
            offset = 1;
        }
        if (offset > totalLines) {
            return ToolResult.success(
                "File: " + resourcePath + "\n" +
                "Error: offset " + offset + " is beyond end of file (" + totalLines + " lines)"
            );
        }

        // Calculate range
        int startLine = offset - 1;  // Convert to 0-based
        int endLine = Math.min(startLine + limit, totalLines);

        // Build result with line numbers
        StringBuilder result = new StringBuilder();
        result.append("File: ").append(resourcePath).append(" (skill resource)\n");

        // Check if result would be too large and trim if necessary
        int estimatedChars = 0;
        List<String> numberedLines = new ArrayList<>();
        for (int i = startLine; i < endLine; i++) {
            String line = (i + 1) + "| " + lines.get(i);
            estimatedChars += line.length() + 1;

            if (estimatedChars > MAX_CHARS && i < endLine - 1) {
                // Trim at this point
                endLine = i;
                break;
            }
            numberedLines.add(line);
        }

        // Add line content
        for (String line : numberedLines) {
            result.append(line).append("\n");
        }

        // Add end message
        if (endLine < totalLines) {
            result.append("\n(Showing lines ").append(offset).append("-").append(endLine)
                  .append(" of ").append(totalLines)
                  .append(". Use offset=").append(endLine + 1).append(" to continue.)");
        } else {
            result.append("\n(End of file — ").append(totalLines).append(" lines total)");
        }

        return ToolResult.success(result.toString());
    }

    /**
     * Read a resource from the classpath as a string.
     */
    private String readResourceAsString(String resourcePath) {
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Resource not found: {}", resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Error reading resource: {}", resourcePath, e);
            return null;
        }
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
