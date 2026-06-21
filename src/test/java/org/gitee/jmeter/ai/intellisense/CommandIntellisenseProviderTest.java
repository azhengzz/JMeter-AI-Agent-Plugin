package org.gitee.jmeter.ai.intellisense;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CommandIntellisenseProvider
 */
public class CommandIntellisenseProviderTest {

    @Test
    public void testGetSuggestionsWithExactMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("/new");

        assertEquals(1, suggestions.size());
        assertEquals("/new", suggestions.get(0));
    }

    @Test
    public void testGetSuggestionsWithPartialMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("/s");

        assertTrue(suggestions.contains("/status"));
        // Should not contain commands that don't start with /s
        assertFalse(suggestions.contains("/new"));
    }

    @Test
    public void testGetSuggestionsWithNoMatch() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("/xyz");

        assertTrue(suggestions.isEmpty());
    }

    @Test
    public void testGetSuggestionsWithSlashOnly() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        List<String> suggestions = provider.getSuggestions("/");

        // Should return all slash commands
        assertTrue(suggestions.contains("/new"));
        assertTrue(suggestions.contains("/status"));
        assertTrue(suggestions.contains("/help"));
    }
}
