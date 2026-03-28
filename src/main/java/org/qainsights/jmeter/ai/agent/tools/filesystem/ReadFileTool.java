package org.qainsights.jmeter.ai.agent.tools.filesystem;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tool to read file contents with line-based pagination.
 * Based on Nanobot's ReadFileTool implementation.
 */
public class ReadFileTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    private static final int DEFAULT_LIMIT_LINES = 2000;
    private static final List<String> BINARY_EXTENSIONS = List.of(
        ".exe", ".dll", ".so", ".dylib", ".bin", ".class", ".jar", ".zip",
        ".tar", ".gz", ".7z", ".rar", ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".ppt", ".pptx", ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico",
        ".mp3", ".mp4", ".avi", ".mov", ".wav", ".flac", ".ogg", ".wmv"
    );

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file. " +
                "Supports line-based pagination with offset and limit parameters. " +
                "Automatically detects and handles binary files, images, and text files. " +
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

            // Check for binary file extensions
            String fileName = path.getFileName().toString().toLowerCase();
            for (String ext : BINARY_EXTENSIONS) {
                if (fileName.endsWith(ext)) {
                    return ToolResult.error(
                        "Cannot read binary file: " + path + " (extension: " + ext + ")");
                }
            }

            // Read file content with pagination
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
     */
    private ToolResult readFileContent(Path path, int offset, int limit) throws IOException {
        List<String> lines = Files.readAllLines(path);

        // Adjust offset to 0-based index
        int startLine = Math.max(0, offset - 1);
        int endLine = Math.min(lines.size(), startLine + limit);

        // Check if offset is beyond file
        if (startLine >= lines.size()) {
            return ToolResult.success(
                "File has " + lines.size() + " lines. Offset " + offset + " is beyond end of file.");
        }

        // Build result with line numbers
        StringBuilder content = new StringBuilder();
        content.append("File: ").append(path).append("\n");
        content.append("Showing lines ").append(offset).append("-").append(endLine);
        content.append(" of ").append(lines.size()).append(" total lines\n\n");

        for (int i = startLine; i < endLine; i++) {
            content.append(String.format("%6d->%s", i + 1, lines.get(i))).append("\n");
        }

        // Add continuation message if there are more lines
        if (endLine < lines.size()) {
            content.append("\n... (").append(lines.size() - endLine).append(" more lines)");
        }

        return ToolResult.success(content.toString());
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
