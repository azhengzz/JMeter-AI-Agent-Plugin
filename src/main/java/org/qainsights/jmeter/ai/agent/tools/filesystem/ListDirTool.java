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
import java.util.stream.Stream;

/**
 * Tool to list directory contents with recursion support.
 * Based on Nanobot's ListDirTool implementation.
 */
public class ListDirTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(ListDirTool.class);

    // Common noise directories to ignore
    private static final List<String> NOISE_DIRECTORIES = List.of(
        "node_modules", ".git", ".svn", ".hg", ".idea", ".vscode",
        "__pycache__", "*.pyc", ".pytest_cache", ".mypy_cache",
        "target", "build", "dist", "out", ".gradle",
        ".venv", "venv", "env", ".env", "virtualenv",
        ".DS_Store", "Thumbs.db", ".cache"
    );

    @Override
    public String getName() {
        return "list_dir";
    }

    @Override
    public String getDescription() {
        return "List the contents of a directory. " +
                "Supports recursive listing with depth control. " +
                "Automatically filters out common noise directories (node_modules, .git, etc.). " +
                "Shows file sizes and types for easy navigation.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "dir_path": {
                            "type": "string",
                            "description": "The path to the directory to list (relative or absolute, defaults to current directory)"
                        },
                        "recursive": {
                            "type": "boolean",
                            "description": "Whether to list recursively (default: false)"
                        },
                        "depth": {
                            "type": "number",
                            "description": "Maximum recursion depth (default: 3, use 0 for unlimited)"
                        }
                    },
                    "required": []
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (!isFsToolsEnabled()) {
            return ToolResult.error("Filesystem tools are disabled. Enable them in configuration.");
        }

        try {
            String dirPath = getStringParameter(parameters, "dir_path", ".");
            boolean recursive = getBooleanParameter(parameters, "recursive", false);
            int depth = getIntParameter(parameters, "depth", 3);

            // Validate and resolve path
            Path path = validateAndResolvePath(dirPath);

            // Check if path exists
            if (!Files.exists(path)) {
                return ToolResult.error("Directory not found: " + path);
            }

            // Check if it's a directory
            if (!Files.isDirectory(path)) {
                return ToolResult.error("Path is not a directory: " + path);
            }

            // List directory contents
            return listDirectory(path, recursive, depth);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter for list_dir: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (IOException e) {
            log.error("Error listing directory", e);
            return ToolResult.error("Error listing directory: " + e.getMessage());
        }
    }

    /**
     * List directory contents with optional recursion.
     */
    private ToolResult listDirectory(Path path, boolean recursive, int maxDepth) throws IOException {
        StringBuilder result = new StringBuilder();
        result.append("Directory: ").append(path).append("\n");

        if (recursive) {
            result.append("Mode: recursive (max depth: ").append(maxDepth).append(")\n");
        } else {
            result.append("Mode: non-recursive\n");
        }
        result.append("\n");

        List<DirEntry> entries = new ArrayList<>();

        if (recursive) {
            // Recursive listing
            listDirectoryRecursive(path, entries, "", 0, maxDepth);
        } else {
            // Non-recursive listing
            try (Stream<Path> stream = Files.list(path)) {
                stream.forEach(p -> {
                    if (!isNoiseDirectory(p)) {
                        entries.add(new DirEntry(p, "", false));
                    }
                });
            }
        }

        // Build result string
        if (entries.isEmpty()) {
            result.append("(empty directory)");
        } else {
            // Sort entries: directories first, then files
            entries.sort((a, b) -> {
                if (a.isDirectory != b.isDirectory) {
                    return a.isDirectory ? -1 : 1;
                }
                return a.path.getFileName().toString().compareToIgnoreCase(
                    b.path.getFileName().toString());
            });

            for (DirEntry entry : entries) {
                result.append(entry.format());
            }
        }

        // Add summary
        result.append("\nSummary: ");
        result.append(entries.stream().filter(e -> e.isDirectory).count()).append(" directories, ");
        result.append(entries.stream().filter(e -> !e.isDirectory).count()).append(" files");

        return ToolResult.success(result.toString());
    }

    /**
     * Recursively list directory contents.
     */
    private void listDirectoryRecursive(Path path, List<DirEntry> entries, String prefix,
                                        int currentDepth, int maxDepth) throws IOException {
        if (maxDepth > 0 && currentDepth >= maxDepth) {
            return;
        }

        try (Stream<Path> stream = Files.list(path)) {
            List<Path> children = stream.toList();
            children.sort((a, b) -> {
                // Sort: directories first, then alphabetically
                boolean aIsDir = Files.isDirectory(a);
                boolean bIsDir = Files.isDirectory(b);
                if (aIsDir != bIsDir) {
                    return aIsDir ? -1 : 1;
                }
                return a.getFileName().toString().compareToIgnoreCase(
                    b.getFileName().toString());
            });

            int index = 0;
            for (Path child : children) {
                if (isNoiseDirectory(child)) {
                    continue;
                }

                boolean isLast = ++index == children.size();
                String connector = isLast ? "+-- " : "+-- ";
                String childPrefix = prefix + (isLast ? "    " : "|   ");

                entries.add(new DirEntry(child, prefix + connector, true));

                if (Files.isDirectory(child)) {
                    listDirectoryRecursive(child, entries, childPrefix, currentDepth + 1, maxDepth);
                }
            }
        }
    }

    /**
     * Check if a path is a noise directory that should be ignored.
     */
    private boolean isNoiseDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }

        String name = path.getFileName().toString();
        return NOISE_DIRECTORIES.contains(name) || name.startsWith(".");
    }

    /**
     * Directory entry for formatting.
     */
    private static class DirEntry {
        final Path path;
        final String prefix;
        final boolean showTree;
        final boolean isDirectory;
        final long size;

        DirEntry(Path path, String prefix, boolean showTree) {
            this.path = path;
            this.prefix = prefix;
            this.showTree = showTree;
            this.isDirectory = Files.isDirectory(path);
            this.size = isDirectory ? 0 : getFileSize(path);
        }

        private static long getFileSize(Path path) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return -1;
            }
        }

        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix);

            String name = path.getFileName().toString();
            if (isDirectory) {
                sb.append("[DIR]  ").append(name);
            } else {
                sb.append("[FILE] ").append(name);
                if (size >= 0) {
                    sb.append(" (").append(formatSize(size)).append(")");
                }
            }
            sb.append("\n");

            return sb.toString();
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            } else if (bytes < 1024 * 1024) {
                return String.format("%.1f KB", bytes / 1024.0);
            } else if (bytes < 1024 * 1024 * 1024) {
                return String.format("%.1f MB", bytes / (1024.0 * 1024));
            } else {
                return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
            }
        }
    }
}
