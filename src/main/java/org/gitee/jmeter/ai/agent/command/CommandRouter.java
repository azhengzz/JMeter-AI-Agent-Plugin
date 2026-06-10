package org.gitee.jmeter.ai.agent.command;

import java.util.*;

/**
 * Pure dict-based command dispatch.
 * Java equivalent of Nanobot's CommandRouter.
 *
 * Four tiers checked in order:
 *   1. priority  — exact-match commands handled before the dispatch lock
 *   2. exact     — exact-match commands handled inside the dispatch lock
 *   3. prefix    — longest-prefix-first match (e.g. "/team ")
 *   4. interceptors — fallback predicates
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

    /** Register a priority command (handled before the session lock). */
    public void priority(String cmd, CommandHandler handler) {
        priorityHandlers.put(cmd.toLowerCase(), handler);
    }

    /** Register an exact-match command (handled inside the session lock). */
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

    /** Dispatch a priority command. Called without the session lock. */
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
