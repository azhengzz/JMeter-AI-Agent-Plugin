package org.gitee.jmeter.ai.intellisense;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import javax.swing.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InputBoxIntellisense
 * Note: This test uses Mockito to mock JTextArea and KeyEvent interactions
 */
public class InputBoxIntellisenseTest {

    private InputBoxIntellisense intellisense;

    @BeforeEach
    public void setUp() {
        // We can't directly test the InputBoxIntellisense with mocks due to its design
        // Instead, we'll create a real instance but test limited functionality
        intellisense = new InputBoxIntellisense(new JTextArea());
    }

    @Test
    public void testConstructorDoesNotThrowException() {
        // Simply verify that constructing the class doesn't throw an exception
        assertNotNull(intellisense);
    }

    @Test
    public void testSlashRoutesToCommandProvider() {
        List<IntellisenseSuggestion> suggestions = intellisense.suggestionsFor('/', "/s");

        assertEquals(1, suggestions.size());
        assertEquals("/status ", suggestions.get(0).insert());
        assertEquals("/status", suggestions.get(0).display());
    }

    @Test
    public void testAtWithoutProviderYieldsNoSuggestions() {
        assertTrue(intellisense.suggestionsFor('@', "@").isEmpty());
    }

    @Test
    public void testAtRoutesToMentionProvider() {
        IntellisenseSuggestion instance = new IntellisenseSuggestion(
                "@12345-1694567890123 · a.jmx · pid 12345", "@12345-1694567890123 ");
        InputBoxIntellisense withMentions = new InputBoxIntellisense(new JTextArea(),
                new FakeMentionProvider(instance));

        List<IntellisenseSuggestion> suggestions = withMentions.suggestionsFor('@', "@123");

        assertEquals(1, suggestions.size());
        assertEquals("@12345-1694567890123 ", suggestions.get(0).insert());
    }

    @Test
    public void testSlashDoesNotRouteToMentionProvider() {
        InputBoxIntellisense withMentions = new InputBoxIntellisense(new JTextArea(),
                new FakeMentionProvider(new IntellisenseSuggestion("@x", "@x ")));

        // '/' must keep routing to commands even when a mention provider exists
        List<IntellisenseSuggestion> suggestions = withMentions.suggestionsFor('/', "/n");

        assertEquals(1, suggestions.size());
        assertEquals("/new ", suggestions.get(0).insert());
    }

    /**
     * 回归("/" 命令 Enter 死循环):接受补全后插入文本作为 prefix 不得再匹配任何候选,
     * 否则弹窗复弹,后续 Enter 全被弹窗分支消费,消息永远发不出。插入文本带尾随空格
     * (与 @-mention 同模式)即满足此不变量。
     */
    @Test
    public void testInsertedCommandTextDismissesPopup() {
        for (IntellisenseSuggestion suggestion : intellisense.suggestionsFor('/', "/")) {
            assertTrue(suggestion.insert().endsWith(" "),
                    "insert must carry a trailing space: " + suggestion.insert());
            assertTrue(intellisense.suggestionsFor('/', suggestion.insert()).isEmpty(),
                    "inserted text must not re-match as a prefix: " + suggestion.insert());
        }
    }

    /** Minimal mention provider fake that always returns the given suggestions. */
    private static final class FakeMentionProvider implements MentionSuggestionProvider {
        private final List<IntellisenseSuggestion> suggestions;

        FakeMentionProvider(IntellisenseSuggestion... suggestions) {
            this.suggestions = List.of(suggestions);
        }

        @Override
        public List<IntellisenseSuggestion> getSuggestions(String prefix) {
            return suggestions;
        }

        @Override
        public void addRefreshCallback(Runnable callback) {
            // no refreshes in the fake
        }
    }

    /**
     * 回归(IME 中文搜索失效):候选刷新不能只依赖 keyReleased——IME 提交/粘贴只改文档、
     * 不发 key 事件。文档变化必须触发一次 updateIntellisense(经 invokeLater 排空 EDT 队列后
     * 可观察到 provider 收到了以触发字符开头的 prefix)。fake 恒返回空建议,避开 headless
     * 下 JPopupMenu.show 的不可用性。
     */
    @Test
    public void testDocumentChangeRefreshesSuggestionsWithoutKeyEvents() throws Exception {
        List<String> seenPrefixes = new java.util.concurrent.CopyOnWriteArrayList<>();
        MentionSuggestionProvider recording = new MentionSuggestionProvider() {
            @Override
            public List<IntellisenseSuggestion> getSuggestions(String prefix) {
                seenPrefixes.add(prefix);
                return List.of();
            }

            @Override
            public void addRefreshCallback(Runnable callback) {
                // no refreshes in the fake
            }
        };
        JTextArea textArea = new JTextArea();
        new InputBoxIntellisense(textArea, recording);

        // 模拟 IME 提交:整个场景在 EDT 上执行(真实 IME 的 commit 发生在 EDT 且 caret
        // 随输入法处理同步移动)。文档事件排队的更新在本块结束后才运行,届时 caret 已落位。
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                textArea.setText("@");
                textArea.getDocument().insertString(textArea.getDocument().getLength(), "概率", null);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            textArea.setCaretPosition(textArea.getText().length());
        });

        // 排空 EDT 队列(invokeLater FIFO,哨兵排在刷新之后)
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(done::countDown);
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertTrue(seenPrefixes.contains("@概率"), "seen=" + seenPrefixes);
    }
    
    /**
     * Test that demonstrates how the class should work with keyboard events.
     * This is more of a documentation test than an actual functional test
     * since we can't easily simulate keyboard events in a unit test.
     */
    @Test
    public void testKeyboardEventHandling() {
        // Create a text area for demonstration
        JTextArea textArea = new JTextArea();
        
        // Set some text with a command
        textArea.setText("Hello @c");
        textArea.setCaretPosition(textArea.getText().length());
        
        // Create the intellisense
        InputBoxIntellisense intellisense = new InputBoxIntellisense(textArea);
        
        // In a real scenario:
        // 1. When user types '@', suggestions would appear
        // 2. When user presses down arrow, selection would move down
        // 3. When user presses up arrow, selection would move up
        // 4. When user presses Tab or Enter, selected command would be inserted
        // 5. When user presses Escape, popup would be hidden
        
        // This test just verifies the class can be instantiated and used
        assertNotNull(intellisense);
    }
    
    /**
     * Tests the behavior of the insertSelectedCommand method indirectly.
     * Note: This test is limited since we can't easily test private methods
     * or simulate the full keyboard interaction.
     */
    @Test
    public void testCommandInsertion() {
        // Create a real text area for testing
        JTextArea textArea = new JTextArea();
        textArea.setText("Hello /new");
        textArea.setCaretPosition(textArea.getText().length());
        
        // Create intellisense
        InputBoxIntellisense intellisense = new InputBoxIntellisense(textArea);
        
        // In a real scenario, when Tab or Enter is pressed with a suggestion selected,
        // the text would be replaced with the selected command
        
        // We can't directly test this without refactoring the class to make methods public
        // or adding specific test hooks, but the class should handle this scenario
        assertNotNull(intellisense);
    }
}
