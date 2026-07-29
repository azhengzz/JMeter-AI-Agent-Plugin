package org.gitee.jmeter.ai.agent.subagent;

import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The headline isolation guarantee: a subagent must leave no trace in the main
 * session. Asserted against the real filesystem, not just the spec flags.
 */
class SubagentSessionPollutionTest {

    private static class InstantAiService implements AiService {
        @Override public String generateResponse(List<String> conversation) { return "summary"; }
        @Override public String generateResponse(List<String> conversation, String model) { return "summary"; }
        @Override public String getName() { return "fake"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            return LLMResponse.text("summary");
        }
    }

    @Test
    void aSubagentRunLeavesNoTraceOnDisk() throws Exception {
        Path workspace = Files.createTempDirectory("subagent-pollution");
        SessionManager sessions = new SessionManager(workspace);

        // A real main session with real history on disk.
        var main = sessions.getOrCreate("chat:main");
        main.addMessage(Message.user("hello"));
        main.addMessage(Message.assistant("hi", null));
        sessions.saveSession(main);

        List<Path> filesBefore = listFiles(workspace);
        int sessionsBefore = sessions.getActiveSessionCount();
        long mainSizeBefore = totalBytes(workspace);

        CountDownLatch delivered = new CountDownLatch(1);
        SubagentManager manager = new SubagentManager(
            new InstantAiService(), SubagentTestSupport.contextBuilder(), sessions,
            new ToolRegistry(), (key, tok, msg) -> { delivered.countDown(); return true; });
        try {
            manager.spawn("analyse the plan", "audit", "chat:main", null);
            assertTrue(delivered.await(15, TimeUnit.SECONDS), "subagent should finish");

            assertEquals(filesBefore, listFiles(workspace),
                "a subagent must not create or remove any session file");
            assertEquals(mainSizeBefore, totalBytes(workspace),
                "the main session's bytes on disk must be untouched");
            assertEquals(sessionsBefore, sessions.getActiveSessionCount(),
                "a subagent must not register a session with SessionManager");
            assertTrue(listFiles(workspace).stream().noneMatch(p -> p.getFileName().toString().contains("subagent")),
                "no subagent session file may be left behind");

            // The main session's own history is unchanged.
            var reloaded = sessions.getOrCreate("chat:main");
            assertEquals(2, reloaded.getUnconsolidatedMessages().size(),
                "the main conversation must still hold exactly its own two messages");
        } finally {
            manager.shutdown();
        }
    }

    private static List<Path> listFiles(Path root) throws Exception {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static long totalBytes(Path root) throws Exception {
        long total = 0;
        for (Path p : listFiles(root)) {
            total += Files.size(p);
        }
        return total;
    }
}
