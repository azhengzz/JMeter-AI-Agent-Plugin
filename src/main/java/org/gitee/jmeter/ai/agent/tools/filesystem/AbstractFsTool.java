package org.gitee.jmeter.ai.agent.tools.filesystem;

import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for filesystem tools with common security and path resolution logic.
 */
public abstract class AbstractFsTool extends AbstractTool {
    private static final Logger log = LoggerFactory.getLogger(AbstractFsTool.class);

    private final List<Path> allowedDirectories;
    private final List<Path> deniedPaths;
    private final boolean enabled;

    public AbstractFsTool() {
        this.allowedDirectories = new ArrayList<>();
        this.deniedPaths = new ArrayList<>();
        this.enabled = Boolean.parseBoolean(AiConfig.getProperty("agent.tools.filesystem.enabled", "false"));
        initializeAllowedDirectories();
        initializeDeniedPaths();
    }

    /**
     * Initialize allowed directories from configuration.
     * Format: agent.tools.filesystem.allowed.dirs=/path1,/path2
     */
    private void initializeAllowedDirectories() {
        String configDirs = AiConfig.getProperty("agent.tools.filesystem.allowed.dirs", "");
        if (configDirs.isEmpty()) {
            // Default to user home and current working directory
            allowedDirectories.add(Path.of(System.getProperty("user.home")));
            String workDir = System.getProperty("user.dir");
            if (workDir != null) {
                allowedDirectories.add(Path.of(workDir));
            }
        } else {
            String[] dirs = configDirs.split(",");
            for (String dir : dirs) {
                dir = dir.trim();
                if (!dir.isEmpty()) {
                    try {
                        Path path = Paths.get(dir).toAbsolutePath().normalize();
                        if (Files.exists(path) && Files.isDirectory(path)) {
                            allowedDirectories.add(path);
                            log.info("Added allowed directory: {}", path);
                        }
                    } catch (Exception e) {
                        log.warn("Invalid directory in config: {}", dir, e);
                    }
                }
            }
        }
    }

    /**
     * Initialize denied paths from configuration.
     * Format: agent.tools.filesystem.denied.dirs=/path1,/path2/file.txt
     * Unlike allowed directories, denied paths do not need to exist and can be files or directories.
     */
    private void initializeDeniedPaths() {
        String configPaths = AiConfig.getProperty("agent.tools.filesystem.denied.dirs", "");
        if (configPaths.isEmpty()) {
            return;
        }
        String[] paths = configPaths.split(",");
        for (String p : paths) {
            p = p.trim();
            if (!p.isEmpty()) {
                try {
                    Path path = Paths.get(p).toAbsolutePath().normalize();
                    deniedPaths.add(path);
                    log.info("Added denied path: {}", path);
                } catch (Exception e) {
                    log.warn("Invalid denied path in config: {}", p, e);
                }
            }
        }
    }

    /**
     * Check if filesystem tools are enabled.
     */
    protected boolean isFsToolsEnabled() {
        return enabled;
    }

    /**
     * Validate and resolve a file path.
     * Prevents path traversal attacks and ensures path is within allowed directories.
     *
     * @param filePath The file path to validate
     * @return Resolved absolute path
     * @throws IllegalArgumentException if path is invalid or outside allowed directories
     */
    protected Path validateAndResolvePath(String filePath) throws IOException {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be empty");
        }

        Path path = Paths.get(filePath).toAbsolutePath().normalize();

        // Check if path is within allowed directories
        boolean isAllowed = false;
        for (Path allowedDir : allowedDirectories) {
            if (path.startsWith(allowedDir)) {
                isAllowed = true;
                break;
            }
        }

        if (!isAllowed) {
            throw new IllegalArgumentException(
                "Path is outside allowed directories: " + path + ". Allowed: " + allowedDirectories);
        }

        // Check if path is denied
        if (isPathDenied(path)) {
            throw new IllegalArgumentException(
                "Access denied: path is in the denied list: " + path);
        }

        // Prevent path traversal with .. components
        if (path.toString().contains("..")) {
            Path normalized = path.normalize();
            if (!normalized.equals(path)) {
                throw new IllegalArgumentException("Path traversal not allowed");
            }
        }

        return path;
    }

    /**
     * Check if a path is within allowed directories without throwing.
     * Also checks that the path is not in the denied list.
     */
    protected boolean isPathAllowed(Path path) {
        for (Path allowedDir : allowedDirectories) {
            if (path.startsWith(allowedDir)) {
                return !isPathDenied(path);
            }
        }
        return false;
    }

    /**
     * Check if a path is denied by configuration.
     * If a denied path is a directory, any file under it is denied.
     * If a denied path is a file, only that exact file is denied.
     */
    protected boolean isPathDenied(Path path) {
        for (Path denied : deniedPaths) {
            if (path.startsWith(denied)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the list of allowed directories.
     */
    protected List<Path> getAllowedDirectories() {
        return new ArrayList<>(allowedDirectories);
    }

    /**
     * Get the list of denied paths.
     */
    protected List<Path> getDeniedPaths() {
        return new ArrayList<>(deniedPaths);
    }
}
