package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool calling is mandatory: an {@link AiService} that does not support it must abort
 * before the first LLM call with a clear error, never silently degrading to text.
 */
class AgentRunnerToolCallingRequirementTest {

    /** A service that advertises NO tool-calling support and fails if its tool path runs. */
    private static class TextOnlyAiService implements AiService {
        final AtomicBoolean toolPathCalled = new AtomicBoolean();

        @Override public String getName() { return "text-only"; }
        @Override public GenerationSettings getGenerationSettings() {
            return new GenerationSettings(0.7, 1024, null);
        }
        @Override public void setGenerationSettings(GenerationSettings settings) { }
        @Override public boolean supportsToolCalling() { return false; }
        @Override public LLMResponse generateResponseWithTools(List<Message> messages, List<ToolDefinition> tools) {
            toolPathCalled.set(true);
            return LLMResponse.text("should not be reached");
        }
    }

    private static AgentLoop newLoop(AiService ai) throws Exception {
        Path workspace = Files.createTempDirectory("toolcall-test");
        ToolRegistry registry = new ToolRegistry();
        MemoryStore memory = new MemoryStore(workspace);
        SessionManager sessions = new SessionManager(workspace);
        ContextBuilder context = new ContextBuilder(memory, workspace);
        return new AgentLoop(registry, memory,
            new MemoryConsolidator(memory, ai, sessions, context, registry),
            context, sessions, ai);
    }

    @Test
    void unsupportedServiceAbortsBeforeCallingLlm() throws Exception {
        TextOnlyAiService ai = new TextOnlyAiService();
        AgentLoop loop = newLoop(ai);
        try {
            AgentResponse response = loop.processMessage("analyze the plan", "chat:test", null)
                .get(20, TimeUnit.SECONDS);

            assertNotNull(response);
            String content = response.getContent();
            assertNotNull(content, "abort reason must reach the user");
            assertTrue(content.contains("does not support tool calling"),
                "should explain the model lacks tool calling: " + content);
            assertFalse(ai.toolPathCalled.get(),
                "the tool-calling path must never be entered for an unsupported service");
        } finally {
            loop.shutdown();
        }
    }
}
