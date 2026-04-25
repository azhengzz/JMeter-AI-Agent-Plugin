package org.qainsights.jmeter.ai.agent.command;

import org.qainsights.jmeter.ai.agent.config.AgentConfig;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.session.Session;
import org.qainsights.jmeter.ai.utils.VersionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Built-in slash command handlers.
 * Java equivalent of Nanobot's builtin.py.
 */
public class BuiltinCommands {
    private static final Logger log = LoggerFactory.getLogger(BuiltinCommands.class);

    /** Cancel all active tasks for the session. Priority command. */
    public static String cmdStop(CommandContext ctx) {
        boolean cancelled = ctx.getLoop().cancelActiveTask(ctx.getSessionKey());
        return cancelled ? "Stopped active task." : "No active task to stop.";
    }

    /** Start a fresh session. Exact command. */
    public static String cmdNew(CommandContext ctx) {
        Session session = ctx.getSessionOrCreate();
        List<Message> snapshot = session.getUnconsolidatedMessages();

        session.clear();
        ctx.getLoop().getSessionManager().saveSession(session);
        ctx.getLoop().getSessionManager().invalidate(session.getKey());

        if (!snapshot.isEmpty()) {
            ctx.getLoop().getMemoryConsolidator().archiveMessagesAsync(snapshot);
        }

        log.info("Session cleared (archived {} messages)", snapshot.size());
        return "New session started.";
    }

    /** Build a status snapshot for the session. Registered as both priority and exact. */
    public static String cmdStatus(CommandContext ctx) {
        String version = VersionUtils.getVersion();
        String model = ctx.getLoop().getAiService().getName();

        Map<String, Integer> lastUsage = ctx.getLoop().getLastUsage();
        int lastIn = lastUsage.getOrDefault("prompt_tokens", 0);
        int lastOut = lastUsage.getOrDefault("completion_tokens", 0);

        AgentConfig config = AgentConfig.getInstance();
        int ctxTotal = config.getContextWindowTokens();
        int ctxEst = 0;
        try {
            ctxEst = ctx.getLoop().getMemoryConsolidator().estimateSessionTokens(ctx.getSessionOrCreate());
        } catch (Exception ignored) {}
        if (ctxEst <= 0) {
            ctxEst = lastIn;
        }
        int ctxPct = ctxTotal > 0 ? (int) ((ctxEst / (double) ctxTotal) * 100) : 0;
        String ctxUsedStr = ctxEst >= 1000 ? (ctxEst / 1000) + "k" : String.valueOf(ctxEst);
        String ctxTotalStr = ctxTotal > 0 ? (ctxTotal / 1024) + "k" : "n/a";

        Session session = ctx.getSessionOrCreate();
        int sessionMsgCount = session.getMessageCount();

        Instant start = ctx.getLoop().getStartTime();
        long uptimeS = Duration.between(start, Instant.now()).getSeconds();
        String uptime = uptimeS >= 3600
                ? (uptimeS / 3600) + "h " + ((uptimeS % 3600) / 60) + "m"
                : (uptimeS / 60) + "m " + (uptimeS % 60) + "s";

        return "Gitee Ai - JMeter Agent v" + version + "\n" +
               "Model: " + model + "\n" +
               "Tokens: " + lastIn + " in / " + lastOut + " out\n" +
               "Context: " + ctxUsedStr + "/" + ctxTotalStr + " (" + ctxPct + "%)\n" +
               "Session: " + sessionMsgCount + " messages\n" +
               "Uptime: " + uptime;
    }

    /** Return available slash commands. Exact command. */
    public static String cmdHelp(CommandContext ctx) {
        return String.join("\n",
                "Gitee Ai commands:",
                "/new — Start a new conversation",
                "/stop — Stop the current task",
                "/status — Show bot status",
                "/help — Show available commands"
        );
    }

    /** Register the default set of slash commands. */
    public static void registerBuiltinCommands(CommandRouter router) {
        router.priority("/stop", BuiltinCommands::cmdStop);
        router.priority("/status", BuiltinCommands::cmdStatus);
        router.exact("/new", BuiltinCommands::cmdNew);
        router.exact("/status", BuiltinCommands::cmdStatus);
        router.exact("/help", BuiltinCommands::cmdHelp);
    }
}
