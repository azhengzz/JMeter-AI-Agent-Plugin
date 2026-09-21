package org.gitee.jmeter.ai.intellisense;

/**
 * A single intellisense suggestion: the text shown in the popup ({@code display})
 * and the text inserted into the input box on selection ({@code insert}).
 */
public record IntellisenseSuggestion(String display, String insert) {

    /**
     * Creates a suggestion whose display and insert text are the same.
     */
    public static IntellisenseSuggestion of(String text) {
        return new IntellisenseSuggestion(text, text);
    }

    /**
     * The default JList renderer shows toString(); display is what the user sees.
     */
    @Override
    public String toString() {
        return display;
    }
}
