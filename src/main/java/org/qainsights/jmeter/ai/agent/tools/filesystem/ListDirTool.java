package org.qainsights.jmeter.ai.agent.tools.filesystem;

import org.qainsights.jmeter.ai.agent.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tool to list directory contents with recursion support.
 * Based on Nanobot's ListDirTool implementation.
 */
public class ListDirTool extends AbstractFsTool {
    private static final Logger log = LoggerFactory.getLogger(ListDirTool.class);

    // Nanobot's _IGNORE_DIRS set
    private static final Set<String> IGNORE_DIRS = Set.of(
        ".git", "node_modules", "__pycache__", ".venv", "venv",
        "dist", "build", ".tox", ".mypy_cache", ".pytest_cache",
        ".ruff_cache", ".coverage", "htmlcov"
    );

    private static final int DEFAULT_MAX_ENTRIES = 200;

    @Override
    public String getName() {
        return "list_dir";
    }

    @Override
    public String getDescription() {
        return "List the contents of a directory. " +
                "Set recursive=true to explore nested structure. " +
                "Common noise directories (.git, node_modules, __pycache__, etc.) are auto-ignored.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "path": {
                            "type": "string",
                            "description": "The directory path to list (relative or absolute, defaults to current directory)"
                        },
                        "recursive": {
                            "type": "boolean",
                            "description": "Recursively list all files (default: false)"
                        },
                        "max_entries": {
                            "type": "number",
                            "description": "Maximum entries to return (default: 200)",
                            "minimum": 1
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
            String dirPath = getStringParameter(parameters, "path", ".");
            boolean recursive = getBooleanParameter(parameters, "recursive", false);
            int maxEntries = getIntParameter(parameters, "max_entries", DEFAULT_MAX_ENTRIES);

            // Validate and resolve path
            Path path = validateAndResolvePath(dirPath);

            // Check if path exists
            if (!Files.exists(path)) {
                return ToolResult.error("Directory not found: " + path);
            }

            // Check if it's a directory
            if (!Files.isDirectory(path)) {
                return ToolResult.error("Not a directory: " + path);
            }

            // List directory contents
            return listDirectory(path, recursive, maxEntries);

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
     * Nanobot-style: checks ignore dirs in path parts, not just directory name.
     */
    private ToolResult listDirectory(Path path, boolean recursive, int maxEntries) throws IOException {
        List<String> items = new ArrayList<>();
        int total = 0;

        if (recursive) {
            // Nanobot: for item in sorted(dp.rglob("*")):
            // Java doesn't have rglob, use Files.walk() instead
            List<Path> allPaths = new ArrayList<>();
            try (var stream = Files.walk(path)) {
                stream.filter(p -> !p.equals(path))
                    .forEach(allPaths::add);
            }

            // Sort paths alphabetically
            allPaths.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));

            for (Path item : allPaths) {
                // Nanobot: if any(p in self._IGNORE_DIRS for p in item.parts):
                if (isInIgnoreDirs(item)) {
                    continue;
                }
                total++;
                if (items.size() < maxEntries) {
                    // Nanobot: rel = item.relative_to(dp)
                    Path rel = path.relativize(item);
                    // Nanobot: items.append(f"{rel}/" if item.is_dir() else str(rel))
                    if (Files.isDirectory(item)) {
                        items.add(rel.toString() + "/");
                    } else {
                        items.add(rel.toString());
                    }
                }
            }
        } else {
            // Nanobot: for item in sorted(dp.iterdir()):
            List<Path> sortedPaths = new ArrayList<>();
            try (Stream<Path> stream = Files.list(path)) {
                stream.forEach(sortedPaths::add);
            }
            sortedPaths.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

            for (Path item : sortedPaths) {
                // Nanobot: if item.name in self._IGNORE_DIRS:
                if (IGNORE_DIRS.contains(item.getFileName().toString())) {
                    continue;
                }
                total++;
                if (items.size() < maxEntries) {
                    // Nanobot: pfx = "📁 " if item.is_dir() else "📄 "
                    String pfx = Files.isDirectory(item) ? "📁 " : "📄 ";
                    items.add(pfx + item.getFileName().toString());
                }
            }
        }

        // Build result
        if (items.isEmpty() && total == 0) {
            return ToolResult.success("Directory " + path + " is empty");
        }

        String result = String.join("\n", items);

        if (total > maxEntries) {
            result += "\n\n...(truncated, showing first " + maxEntries + " of " + total + " entries)";
        }

        return ToolResult.success(result);
    }

    /**
     * Check if a path should be ignored based on Nanobot's algorithm.
     * Nanobot: if any(p in self._IGNORE_DIRS for p in item.parts)
     * This checks each part of the path, not just the final directory name.
     */
    private boolean isInIgnoreDirs(Path path) {
        // Check each part of the path
        for (int i = 0; i < path.getNameCount(); i++) {
            String part = path.getName(i).toString();
            if (IGNORE_DIRS.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
