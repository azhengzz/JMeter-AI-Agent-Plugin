package org.qainsights.jmeter.ai.intellisense;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides intellisense/autocomplete for AI chat commands.
 */
public class CommandIntellisenseProvider {
    private final List<String> commands;

    public CommandIntellisenseProvider() {
        commands = new ArrayList<>();
        // Slash commands
        commands.add("/new");
        commands.add("/status");
        commands.add("/help");
        // Agent commands
        commands.add("@code");
        commands.add("@wrap");
        commands.add("@lint");
        commands.add("@usage");
        commands.add("@optimize");
        commands.add("@this");
    }

    public List<String> getSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        for (String cmd : commands) {
            if (cmd.startsWith(prefix)) {
                suggestions.add(cmd);
            }
        }
        return suggestions;
    }
}
