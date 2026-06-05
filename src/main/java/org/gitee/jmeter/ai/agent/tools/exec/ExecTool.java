package org.gitee.jmeter.ai.agent.tools.exec;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.tools.ValidationResult;
import org.gitee.jmeter.ai.utils.AiConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool to execute shell commands with safety guards.
 * Ported from nanobot's ExecTool (shell.py).
 */
public class ExecTool extends AbstractTool {

    private static final int MAX_TIMEOUT = 600; // 最大超时秒数
    private static final int MAX_OUTPUT = 10_000; // 输出超过此长度时截断，保留 head + tail
    private static final int HEAD_CHARS = 4_000; // 截断时保留的前部字符数
    private static final int TAIL_CHARS = 4_000; // 截断时保留的尾部字符数

    private static final String[] DEFAULT_DENY_PATTERNS = {
        "\\brm\\s+-[rf]{1,2}\\b",
        "\\bdel\\s+/[fq]\\b",
        "\\brmdir\\s+/s\\b",
        "(?:^|[;&|]\\s*)format\\b",
        "\\b(mkfs|diskpart)\\b",
        "\\bdd\\s+if=",
        ">\\s*/dev/sd",
        ">\\s*/dev/hd",
        "\\b(shutdown|reboot|poweroff)\\b",
        ":\\(\\)\\s*\\{.*\\};\\s*:",
    };

    private final boolean enabled;
    private final int defaultTimeout;
    private final String configuredWorkingDir;
    private final String pathAppend;
    private final List<Pattern> denyPatterns;

    public ExecTool() {
        this.enabled = Boolean.parseBoolean(
            AiConfig.getProperty("agent.tools.exec.enabled", "false"));
        this.defaultTimeout = Integer.parseInt(
            AiConfig.getProperty("agent.tools.exec.timeout", "60"));
        this.configuredWorkingDir = AiConfig.getProperty(
            "agent.tools.exec.working.dir", "").isEmpty()
            ? null : AiConfig.getProperty("agent.tools.exec.working.dir", "");
        this.pathAppend = AiConfig.getProperty(
            "agent.tools.exec.path.append", "");

        String denyConfig = AiConfig.getProperty(
            "agent.tools.exec.deny.patterns", "");
        if (denyConfig.isEmpty()) {
            this.denyPatterns = compilePatterns(DEFAULT_DENY_PATTERNS);
        } else {
            this.denyPatterns = compilePatterns(denyConfig.split(","));
        }
    }

    @Override
    public String getName() {
        return "exec";
    }

    @Override
    public String getDescription() {
        return "Execute a shell command and return its output. "
            + "Supports timeout and working directory configuration. "
            + "Use with caution — commands run on the host system.";
    }

    @Override
    public String getParameterSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "The shell command to execute"
                },
                "working_dir": {
                  "type": "string",
                  "description": "Optional working directory for the command"
                },
                "timeout": {
                  "type": "integer",
                  "description": "Timeout in seconds (default 60, max 600)",
                  "minimum": 1,
                  "maximum": 600
                }
              },
              "required": ["command"]
            }""";
    }

    @Override
    public boolean hasRequiredParameters() {
        return true;
    }

    @Override
    public ValidationResult validateParameters(Map<String, Object> parameters) {
        ValidationResult.Builder builder = ValidationResult.builder();

        Object command = parameters.get("command");
        if (command == null || command.toString().trim().isEmpty()) {
            builder.addError("Parameter 'command' is required and must not be empty");
        }

        int timeout = getIntParameter(parameters, "timeout", defaultTimeout);
        if (timeout < 1 || timeout > MAX_TIMEOUT) {
            builder.addError("Parameter 'timeout' must be between 1 and " + MAX_TIMEOUT);
        }

        return builder.build();
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        if (!enabled) {
            return ToolResult.error(
                "Exec tool is disabled. Enable it via agent.tools.exec.enabled=true");
        }

        String command = getStringParameter(parameters, "command", null);
        if (command == null || command.trim().isEmpty()) {
            return ToolResult.error("Parameter 'command' is required");
        }

        String safetyError = validateCommandSafety(command);
        if (safetyError != null) {
            return ToolResult.error(safetyError);
        }

        String workingDir = getStringParameter(parameters, "working_dir",
            configuredWorkingDir != null ? configuredWorkingDir : null);
        int timeout = Math.min(
            getIntParameter(parameters, "timeout", defaultTimeout), MAX_TIMEOUT);

        return runCommand(command.trim(), workingDir, timeout);
    }

    private String validateCommandSafety(String command) {
        String lower = command.toLowerCase();
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(lower).find()) {
                log.warn("Command blocked by safety guard, matched pattern: {}",
                    pattern.pattern());
                return "Error: Command blocked by safety guard "
                    + "(dangerous pattern detected)";
            }
        }
        return null;
    }

    private ToolResult runCommand(String command, String workingDir, int timeoutSecs) {
        File workDir = resolveWorkingDir(workingDir);
        if (workDir != null && !workDir.exists()) {
            return ToolResult.error(
                "Working directory does not exist: " + workDir.getAbsolutePath());
        }

        ProcessBuilder pb = createProcessBuilder(command, workDir);

        try {
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.debug("Output reader error: {}", e.getMessage());
                }
            }, "exec-tool-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(timeoutSecs, java.util.concurrent.TimeUnit.SECONDS);
            outputReader.join(2000);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error(
                    "Command timed out after " + timeoutSecs + " seconds");
            }

            int exitCode = process.exitValue();
            String rawOutput = output.toString();
            String truncated = truncateOutput(rawOutput);

            StringBuilder result = new StringBuilder();
            result.append("Exit code: ").append(exitCode).append("\n");
            if (!truncated.isEmpty()) {
                result.append(truncated);
            }

            return ToolResult.success(result.toString());

        } catch (IOException e) {
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Command execution interrupted");
        }
    }

    private ProcessBuilder createProcessBuilder(String command, File workingDir) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        pb.redirectErrorStream(true);

        if (!pathAppend.isEmpty()) {
            Map<String, String> env = pb.environment();
            String pathKey = isWindows ? "Path" : "PATH";
            String currentPath = env.getOrDefault(pathKey, "");
            env.put(pathKey, currentPath + File.pathSeparator + pathAppend);
        }

        return pb;
    }

    private File resolveWorkingDir(String workingDir) {
        if (workingDir == null || workingDir.isEmpty()) {
            return configuredWorkingDir != null
                ? new File(configuredWorkingDir) : null;
        }
        return new File(workingDir);
    }

    private String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT) {
            return output;
        }
        String head = output.substring(0, HEAD_CHARS);
        String tail = output.substring(output.length() - TAIL_CHARS);
        int omitted = output.length() - HEAD_CHARS - TAIL_CHARS;
        return head
            + "\n\n...("
            + String.format("%,d", omitted)
            + " characters truncated)\n\n"
            + tail;
    }

    private static List<Pattern> compilePatterns(String[] regexes) {
        List<Pattern> patterns = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            String trimmed = regex.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE));
            }
        }
        return patterns;
    }
}
