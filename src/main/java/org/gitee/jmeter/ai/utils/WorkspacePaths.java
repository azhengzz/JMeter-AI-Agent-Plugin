package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized workspace and built-in resource path resolution for the Agent.
 *
 * <p>Single source of truth for two distinct path concerns, previously duplicated
 * (and inconsistently normalized) across {@code AgentConfig}, {@code MemoryStore},
 * {@code SessionManager} and {@code SystemPrompt}:
 * <ul>
 *   <li><b>Workspace</b> — the user data area (MEMORY.md, sessions/, user skills),
 *       configurable via {@code agent.workspace.path}.</li>
 *   <li><b>Built-in skills</b> — the program resource area
 *       ({@code {jmeter.home}/bin/jmeter-agent/skills}), fixed regardless of the
 *       workspace setting so built-in skills remain available even when the
 *       workspace is redirected.</li>
 * </ul>
 *
 * <p>Workspace resolution order:
 * <ol>
 *   <li>{@code agent.workspace.path} property (backslashes normalized to '/')</li>
 *   <li>{@code {jmeter.home}/bin/jmeter-agent} (default; co-located with skills/ipc)</li>
 *   <li>{@code {user.home}/.jmeter-ai/agent} (fallback when JMeter home is unavailable)</li>
 * </ol>
 * Every branch returns an absolute, normalized path.
 */
public final class WorkspacePaths {

    private static final String WORKSPACE_PROPERTY = "agent.workspace.path";

    private WorkspacePaths() {
    }

    /**
     * Resolve the workspace root (user data area).
     *
     * @return absolute, normalized workspace path (never null)
     */
    public static Path resolveWorkspace() {
        String configuredPath = AiConfig.getProperty(WORKSPACE_PROPERTY, null);
        if (configuredPath != null && !configuredPath.isEmpty()) {
            // Normalize backslashes for cross-platform consistency (Windows configs may use '\').
            String fixedPath = configuredPath.replace('\\', '/');
            return Paths.get(fixedPath).toAbsolutePath().normalize();
        }

        String jmeterHome = JMeterUtils.getJMeterHome();
        if (jmeterHome != null) {
            return Paths.get(jmeterHome, "bin", "jmeter-agent").toAbsolutePath().normalize();
        }

        // Fallback only when JMeter home is not yet initialized (e.g. early class-load).
        return Paths.get(System.getProperty("user.home"))
                .resolve(".jmeter-ai").resolve("agent")
                .toAbsolutePath().normalize();
    }

    /**
     * Resolve the built-in skills directory (program resource area, fixed at
     * {@code {jmeter.home}/bin/jmeter-agent/skills}). This is independent of the
     * configurable workspace so that built-in skills remain available even when
     * {@code agent.workspace.path} points elsewhere.
     *
     * <p>Callers (e.g. {@code SkillsLoader}) are expected to verify the result
     * exists before use.
     *
     * @return built-in skills path (falls back to {@code <workspace>/skills} when
     *         JMeter home is unavailable, so the result is never null)
     */
    public static Path builtinSkillsDir() {
        String jmeterHome = JMeterUtils.getJMeterHome();
        if (jmeterHome != null) {
            return Paths.get(jmeterHome, "bin", "jmeter-agent", "skills").toAbsolutePath().normalize();
        }
        return resolveWorkspace().resolve("skills");
    }
}
