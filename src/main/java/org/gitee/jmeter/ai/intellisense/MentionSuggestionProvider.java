package org.gitee.jmeter.ai.intellisense;

import java.util.List;

/**
 * Provider of '@'-mention suggestions for the chat input box.
 */
public interface MentionSuggestionProvider {

    /**
     * Returns the suggestions matching the typed prefix (which starts with '@').
     * Called on the EDT while the user types; implementations MUST NOT block.
     */
    List<IntellisenseSuggestion> getSuggestions(String prefix);

    /**
     * Registers a callback invoked on the EDT whenever the underlying
     * suggestion source refreshes, so a visible popup can update in place.
     */
    void addRefreshCallback(Runnable callback);
}
