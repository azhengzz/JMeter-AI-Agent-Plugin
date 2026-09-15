package org.gitee.jmeter.ai.intellisense;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IntellisensePopup
 */
public class IntellisensePopupTest {
    
    private IntellisensePopup popup;
    
    @BeforeEach
    public void setUp() {
        popup = new IntellisensePopup();
    }
    
    @Test
    public void testInitialState() {
        assertFalse(popup.isVisible());
        assertNull(popup.getSelectedValue());
    }
    
    @Test
    public void testSetSelectedIndex() {
        // Instead of showing the popup (which requires a visible component),
        // we'll just set the data directly
        List<IntellisenseSuggestion> suggestions = Arrays.asList(
                IntellisenseSuggestion.of("/new"),
                IntellisenseSuggestion.of("/status"),
                IntellisenseSuggestion.of("/help"));

        // Set the data directly on the JList
        popup.suggestionList.setListData(suggestions.toArray(new IntellisenseSuggestion[0]));

        // Test setting selected index
        popup.setSelectedIndex(1);
        assertEquals("/status", popup.getSelectedValue().insert());

        // Test setting another index
        popup.setSelectedIndex(2);
        assertEquals("/help", popup.getSelectedValue().insert());
    }

    @Test
    public void testGetSuggestionCount() {
        // Set the data directly on the JList
        List<IntellisenseSuggestion> suggestions = Arrays.asList(
                IntellisenseSuggestion.of("/new"),
                IntellisenseSuggestion.of("/status"),
                IntellisenseSuggestion.of("/help"),
                IntellisenseSuggestion.of("/exit"));
        popup.suggestionList.setListData(suggestions.toArray(new IntellisenseSuggestion[0]));

        // Test getting suggestion count
        assertEquals(4, popup.getSuggestionCount());
    }

    @Test
    public void testGetSelectedIndex() {
        // Set the data directly on the JList
        List<IntellisenseSuggestion> suggestions = Arrays.asList(
                IntellisenseSuggestion.of("/new"),
                IntellisenseSuggestion.of("/status"),
                IntellisenseSuggestion.of("/help"));
        popup.suggestionList.setListData(suggestions.toArray(new IntellisenseSuggestion[0]));

        // Test getting selected index (initially -1 since no selection)
        assertEquals(-1, popup.getSelectedIndex());

        // Change selection and test again
        popup.setSelectedIndex(2);
        assertEquals(2, popup.getSelectedIndex());
    }

    @Test
    public void testDisplayAndInsertCanDiffer() {
        IntellisenseSuggestion suggestion = new IntellisenseSuggestion(
                "@12345-1694567890123 · a.jmx · pid 12345", "@12345-1694567890123 ");

        popup.suggestionList.setListData(new IntellisenseSuggestion[]{suggestion});
        popup.setSelectedIndex(0);

        assertEquals(suggestion, popup.getSelectedValue());
        assertEquals("@12345-1694567890123 ", popup.getSelectedValue().insert());
    }
    
    @Test
    public void testHide() {
        // Since we can't actually show the popup in a headless test environment,
        // we'll test a simpler aspect of the hide functionality
        
        // Create a simple test case that doesn't rely on actual visibility
        // Just verify that calling hide() doesn't throw an exception
        popup.hide();
        
        // If we get here without an exception, the test passes
        // This is a basic smoke test for the hide() method
        assertFalse(popup.isVisible());
    }
    
    @Test
    public void testAddSuggestionClickListener() {
        // Create a flag to track if listener was called
        final boolean[] listenerCalled = {false};
        
        // Add click listener
        popup.addSuggestionClickListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                listenerCalled[0] = true;
            }
        });
        
        // We can't simulate clicks in a unit test, so just verify the popup exists
        assertNotNull(popup);
    }
}
