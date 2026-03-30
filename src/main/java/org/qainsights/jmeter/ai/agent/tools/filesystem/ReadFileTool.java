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
 * Tool to read file contents with line-based pagination.
 * Based on Nanobot's ReadFileTool implementation.
 * Uses content-based detection instead of extension whitelisting.
 */
public class ReadFileTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    private static final int DEFAULT_LIMIT_LINES = 2000;
    private static final int MAX_CHARS = 128_000;

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file. " +
                "Supports line-based pagination with offset and limit parameters. " +
                "Automatically detects and handles binary files and images. " +
                "Returns file contents with line numbers for easy reference.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "file_path": {
                            "type": "string",
                            "description": "The path to the file to read (relative or absolute)"
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
                    "required": ["file_path"]
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
            int offset = getIntParameter(parameters, "offset", 1);
            int limit = getIntParameter(parameters, "limit", DEFAULT_LIMIT_LINES);

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

            // Read file content with pagination (Nanobot-style content detection)
            return readFileContent(path, offset, limit);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for read_file: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (IOException e) {
            log.error("Error reading file", e);
            return ToolResult.error("Error reading file: " + e.getMessage());
        }
    }

    /**
     * Read file content with line-based pagination.
     * Uses Nanobot-style content detection:
     * 1. Read file as bytes
     * 2. Detect MIME type
     * 3. Handle images specially
     * 4. Try UTF-8 decode, fall back to binary error
     */
    private ToolResult readFileContent(Path path, int offset, int limit) throws IOException {
        // Read file as raw bytes (Nanobot-style)
        byte[] raw = Files.readAllBytes(path);

        // Check for empty file
        if (raw.length == 0) {
            return ToolResult.success("(Empty file: " + path + ")");
        }

        // Detect MIME type
        String mime = detectMimeType(path, raw);

        // Handle image files
        if (mime != null && mime.startsWith("image/")) {
            return ToolResult.success(
                "(Image file: " + path + " - " + mime + ")\n" +
                "Images cannot be displayed as text. File size: " + raw.length + " bytes."
            );
        }

        // Try to decode as UTF-8 text
        String textContent;
        try {
            textContent = new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // UTF-8 decode failed - this is a binary file
            return ToolResult.error(
                "Cannot read binary file: " + path + "\n" +
                "MIME type: " + (mime != null ? mime : "unknown") + "\n" +
                "File size: " + raw.length + " bytes\n" +
                "Binary files cannot be displayed as text. Only UTF-8 text and images are supported."
            );
        }

        // Split into lines
        String[] linesArray = textContent.split("\\r?\\n");
        List<String> lines = new ArrayList<>(List.of(linesArray));

        int totalLines = lines.size();

        // Validate and adjust offset
        if (offset < 1) {
            offset = 1;
        }
        if (offset > totalLines) {
            return ToolResult.success(
                "File: " + path + "\n" +
                "Error: offset " + offset + " is beyond end of file (" + totalLines + " lines)"
            );
        }

        // Calculate range
        int startLine = offset - 1;  // Convert to 0-based
        int endLine = Math.min(startLine + limit, totalLines);

        // Build result with line numbers (Nanobot-style format: "1| content")
        StringBuilder result = new StringBuilder();
        result.append("File: ").append(path).append("\n");

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

        // Add line content (Nanobot-style format: "1| content")
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
     * Detect MIME type of a file.
     * Uses Files.probeContentType() for system-level detection,
     * with fallback to extension-based detection for common types.
     */
    private String detectMimeType(Path path, byte[] raw) {
        // Try system-level MIME detection first
        try {
            String mime = Files.probeContentType(path);
            if (mime != null) {
                return mime;
            }
        } catch (IOException e) {
            // Ignore and fall back to extension detection
        }

        // Fallback to extension-based detection for common types
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".java") ||
            fileName.endsWith(".py") || fileName.endsWith(".js") || fileName.endsWith(".json") ||
            fileName.endsWith(".xml") || fileName.endsWith(".yaml") || fileName.endsWith(".yml") ||
            fileName.endsWith(".sh") || fileName.endsWith(".bat") || fileName.endsWith(".properties")) {
            return "text/plain";
        }
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "text/html";
        }
        if (fileName.endsWith(".css")) {
            return "text/css";
        }
        if (fileName.endsWith(".csv")) {
            return "text/csv";
        }
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (fileName.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (fileName.endsWith(".zip") || fileName.endsWith(".jar")) {
            return "application/zip";
        }
        if (fileName.endsWith(".gz")) {
            return "application/gzip";
        }
        if (fileName.endsWith(".tar")) {
            return "application/x-tar";
        }
        if (fileName.endsWith(".json")) {
            return "application/json";
        }
        if (fileName.endsWith(".xml")) {
            return "application/xml";
        }

        // Unknown MIME type
        return null;
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
