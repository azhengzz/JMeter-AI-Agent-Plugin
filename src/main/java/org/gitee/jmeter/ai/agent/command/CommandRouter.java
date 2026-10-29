package org.gitee.jmeter.ai.agent.command;

import java.util.*;

/**
 * Pure dict-based command dispatch.
 * Java equivalent of Nanobot's CommandRouter.
 *
 * Four tiers checked in order:
 *   1. priority  — exact-match commands dispatched immediately on the caller
 *                  thread, before the busy-check (answers even mid-turn)
 *   2. exact     — exact-match commands dispatched via dispatch(): mid-turn on
 *                  the caller thread, or idle inside the turn's executor thread
 *   3. prefix    — longest-prefix-first match (e.g. "/goal ")
 *   4. interceptors — fallback null-decline handlers (none registered today)
 */
public class CommandRouter {

    @FunctionalInterface
    public interface CommandHandler {
        String handle(CommandContext ctx);
    }

    private final Map<String, CommandHandler> priorityHandlers = new LinkedHashMap<>();
    private final Map<String, CommandHandler> exactHandlers = new LinkedHashMap<>();
    private final List<AbstractMap.SimpleEntry<String, CommandHandler>> prefixHandlers = new ArrayList<>();
    private final List<CommandHandler> interceptors = new ArrayList<>();

    /** Register a priority command (dispatched on the caller thread, before the busy-check). */
    public void priority(String cmd, CommandHandler handler) {
        priorityHandlers.put(cmd.toLowerCase(), handler);
    }

    /** Register an exact-match command (dispatched via dispatch(): mid-turn on the caller thread, or idle inside the turn). */
    public void exact(String cmd, CommandHandler handler) {
        exactHandlers.put(cmd.toLowerCase(), handler);
    }

    /** Register a prefix-match command (longest-prefix-first). */
    public void prefix(String pfx, CommandHandler handler) {
        prefixHandlers.add(new AbstractMap.SimpleEntry<>(pfx.toLowerCase(), handler));
        prefixHandlers.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
    }

    /** Register a fallback interceptor. */
    public void intercept(CommandHandler handler) {
        interceptors.add(handler);
    }

    /** Quick check: is this text a priority command? */
    public boolean isPriority(String text) {
        return text != null && priorityHandlers.containsKey(text.trim().toLowerCase());
    }

    /** Quick check: is this text a dispatchable command (exact or prefix match)? */
    public boolean isDispatchable(String text) {
        if (text == null) return false;
        String cmd = text.trim().toLowerCase();
        if (exactHandlers.containsKey(cmd)) return true;
        for (AbstractMap.SimpleEntry<String, CommandHandler> entry : prefixHandlers) {
            if (cmd.startsWith(entry.getKey())) return true;
        }
        return false;
    }

    /** Dispatch a priority command. Called on the caller thread, before the busy-check. */
    public String dispatchPriority(CommandContext ctx) {
        CommandHandler handler = priorityHandlers.get(ctx.getRaw().toLowerCase());
        if (handler != null) {
            return handler.handle(ctx);
        }
        return null;
    }

    /** Try exact, prefix, then interceptors. Returns null if unhandled. */
    public String dispatch(CommandContext ctx) {
        String cmd = ctx.getRaw().toLowerCase();

        // Tier 2: Exact match
        CommandHandler exactHandler = exactHandlers.get(cmd);
        if (exactHandler != null) {
            return exactHandler.handle(ctx);
        }

        // Tier 3: Prefix match (longest first)
        for (AbstractMap.SimpleEntry<String, CommandHandler> entry : prefixHandlers) {
            if (cmd.startsWith(entry.getKey())) {
                ctx.setArgs(ctx.getRaw().substring(entry.getKey().length()));
                return entry.getValue().handle(ctx);
            }
        }

        // Tier 4: Interceptors
        for (CommandHandler interceptor : interceptors) {
            String result = interceptor.handle(ctx);
            if (result != null) {
                return result;
            }
        }

        return null;
    }
}
