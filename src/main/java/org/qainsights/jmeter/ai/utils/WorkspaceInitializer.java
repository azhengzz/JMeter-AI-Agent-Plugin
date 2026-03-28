package org.qainsights.jmeter.ai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Workspace template initializer.
 * Syncs bundled template files to the workspace directory.
 * Based on Nanobot's sync_workspace_templates logic.
 */
public class WorkspaceInitializer {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);

    // Template files to sync
    private static final String[] TEMPLATE_FILES = {
        "AGENTS.md",
        "SOUL.md",
        "USER.md",
        "TOOLS.md"
    };

    // Subdirectories to create
    private static final String[] SUBDIRECTORIES = {
        "memory",
        "sessions",
        "skills"
    };

    /**
     * Initialize workspace with template files.
     * Only creates missing files - never overwrites existing content.
     *
     * @param workspace The workspace path
     * @return List of created file paths (relative to workspace)
     */
    public static List<String> initialize(Path workspace) {
        return initialize(workspace, false);
    }

    /**
     * Initialize workspace with template files.
     *
     * @param workspace The workspace path
     * @param silent If true, don't log created files
     * @return List of created file paths (relative to workspace)
     */
    public static List<String> initialize(Path workspace, boolean silent) {
        List<String> created = new ArrayList<>();

        try {
            // Ensure workspace exists
            if (!Files.exists(workspace)) {
                Files.createDirectories(workspace);
                log.info("Created workspace directory: {}", workspace);
            }

            // Create subdirectories
            for (String subdir : SUBDIRECTORIES) {
                Path dir = workspace.resolve(subdir);
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                    if (!silent) {
                        log.debug("Created directory: {}", subdir);
                    }
                }
            }

            // Copy template files (only if they don't exist)
            for (String filename : TEMPLATE_FILES) {
                Path dest = workspace.resolve(filename);
                if (!Files.exists(dest)) {
                    if (copyTemplate(filename, dest)) {
                        created.add(filename);
                        if (!silent) {
                            log.info("Created template: {}", filename);
                        }
                    }
                }
            }

            // Copy memory/MEMORY.md template
            Path memoryDir = workspace.resolve("memory");
            Path memoryFile = memoryDir.resolve("MEMORY.md");
            if (!Files.exists(memoryFile)) {
                if (copyTemplate("memory/MEMORY.md", memoryFile)) {
                    created.add("memory/MEMORY.md");
                    if (!silent) {
                        log.info("Created template: memory/MEMORY.md");
                    }
                }
            }

            // Create empty HISTORY.md if it doesn't exist
            Path historyFile = memoryDir.resolve("HISTORY.md");
            if (!Files.exists(historyFile)) {
                Files.createFile(historyFile);
                if (!silent) {
                    log.info("Created: memory/HISTORY.md");
                }
            }

            if (!created.isEmpty() && !silent) {
                log.info("Workspace initialized with {} template file(s)", created.size());
            }

        } catch (IOException e) {
            log.error("Failed to initialize workspace", e);
        }

        return created;
    }

    /**
     * Copy a template file from resources to destination.
     *
     * @param resourcePath The resource path (relative to templates/)
     * @param destination The destination file path
     * @return true if copy succeeded
     */
    private static boolean copyTemplate(String resourcePath, Path destination) {
        InputStream is = null;
        try {
            String fullPath = "templates/" + resourcePath;
            is = WorkspaceInitializer.class.getClassLoader().getResourceAsStream(fullPath);

            if (is == null) {
                log.warn("Template not found in resources: {}", fullPath);
                return false;
            }

            Files.createDirectories(destination.getParent());
            Files.copy(is, destination, StandardCopyOption.REPLACE_EXISTING);
            return true;

        } catch (IOException e) {
            log.error("Failed to copy template: {}", resourcePath, e);
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Check if workspace has been initialized.
     *
     * @param workspace The workspace path
     * @return true if workspace contains template files
     */
    public static boolean isInitialized(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return false;
        }

        // Check if key template files exist
        Path agentsFile = workspace.resolve("AGENTS.md");
        return Files.exists(agentsFile);
    }
}
