package org.gitee.jmeter.ai.agent.tools.filesystem;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Tool to write content to files with automatic directory creation.
 * Based on Nanobot's WriteFileTool implementation.
 */
public class WriteFileTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Write content to a file. " +
                "Creates the file and any necessary parent directories automatically. " +
                "Overwrites the file if it already exists. " +
                "Supports both text files and code files with UTF-8 encoding.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "file_path": {
                            "type": "string",
                            "description": "The path to the file to write (relative or absolute)"
                        },
                        "content": {
                            "type": "string",
                            "description": "The content to write to the file"
                        }
                    },
                    "required": ["file_path", "content"]
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
            String content = getRequiredParameter(parameters, "content");

            // Validate and resolve path
            Path path = validateAndResolvePath(filePath);

            // Check if path is a directory
            if (Files.exists(path) && Files.isDirectory(path)) {
                return ToolResult.error("Path is a directory, not a file: " + path);
            }

            // Create parent directories if they don't exist
            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created directory: {}", parentDir);
            }

            // Write content to file
            Files.writeString(path, content, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Wrote {} characters to file: {}", content.length(), path);

            return ToolResult.success(
                "Successfully wrote " + content.length() + " characters to: " + path);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for write_file: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (IOException e) {
            log.error("Error writing file", e);
            return ToolResult.error("Error writing file: " + e.getMessage());
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
