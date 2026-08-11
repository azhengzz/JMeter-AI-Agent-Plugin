package org.gitee.jmeter.ai.service;

import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatToolCalls;
import io.github.ollama4j.tools.OllamaToolCallsFunction;
import io.github.ollama4j.tools.Tools;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests the Ollama tool-calling mapping logic (tool definitions, assistant tool
 * calls, and response parsing) without a live Ollama instance. These helpers are
 * stateless and exercised statically.
 */
class OllamaToolCallingTest {

    @Test
    void mapToolsTranslatesDefinitionsWithPropertiesRequiredAndEnum() {
        Map<String, Object> nameProp = Map.of(
            "type", "string",
            "description", "the name",
            "enum", List.of("a", "b"));
        Map<String, Object> params = Map.of(
            "type", "object",
            "properties", Map.of("name", nameProp),
            "required", List.of("name"));

        ToolDefinition td = ToolDefinition.builder()
            .name("search")
            .description("search things")
            .parameters(params)
            .build();

        List<Tools.Tool> mapped = OllamaAiService.mapTools(List.of(td));

        assertEquals(1, mapped.size());
        Tools.Tool tool = mapped.get(0);
        assertEquals("function", tool.getType(), "tool type must be function");
        assertEquals("search", tool.getToolSpec().getName());
        assertEquals("search things", tool.getToolSpec().getDescription());
        assertNull(tool.getToolFunction(), "must not register an auto-executing ToolFunction");

        Tools.Property nameProperty = tool.getToolSpec().getParameters().getProperties().get("name");
        assertNotNull(nameProperty);
        assertEquals("string", nameProperty.getType());
        assertEquals("the name", nameProperty.getDescription());
        assertEquals(List.of("a", "b"), nameProperty.getEnumValues());
        assertTrue(tool.getToolSpec().getParameters().getRequired().contains("name"),
            "required list must carry 'name'");
    }

    @Test
    void mapToolsHandlesNullAndEmpty() {
        assertTrue(OllamaAiService.mapTools(null).isEmpty());
        assertTrue(OllamaAiService.mapTools(List.of()).isEmpty());

        // A tool with no parameters must still map without throwing.
        Tools.Tool bare = OllamaAiService.mapTools(
            List.of(ToolDefinition.builder().name("bare").description("d").build())).get(0);
        assertNotNull(bare.getToolSpec().getParameters());
    }

    @Test
    void mapToolCallsRoundTripsAssistantToolCalls() {
        List<OllamaChatToolCalls> mapped = OllamaAiService.mapToolCalls(
            List.of(new ToolCall("call-1", "search", Map.of("q", "jmeter"))));

        assertEquals(1, mapped.size());
        assertEquals("call-1", mapped.get(0).getId());
        assertEquals("search", mapped.get(0).getFunction().getName());
        assertEquals("jmeter", mapped.get(0).getFunction().getArguments().get("q"));
    }

    @Test
    void buildLLMResponseMapsToolCallsResponse() {
        OllamaChatMessage msg = new OllamaChatMessage();
        // Tool-call responses carry no text content (response stays null); only toolCalls is set.
        msg.setToolCalls(List.of(
            new OllamaChatToolCalls("tc-9", new OllamaToolCallsFunction("create_element", Map.of("type", "ThreadGroup")))));

        LLMResponse response = OllamaAiService.buildLLMResponse(msg);

        assertTrue(response.hasToolCalls());
        assertEquals("tool_calls", response.getFinishReason());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("create_element", response.getToolCalls().get(0).getName());
        assertEquals("ThreadGroup", response.getToolCalls().get(0).getArguments().get("type"));
    }

    @Test
    void buildLLMResponseMapsPlainTextResponse() {
        OllamaChatMessage msg = new OllamaChatMessage();
        msg.setResponse("all done");

        LLMResponse response = OllamaAiService.buildLLMResponse(msg);

        assertFalse(response.hasToolCalls());
        assertEquals("stop", response.getFinishReason());
        assertEquals("all done", response.getContent());
    }

    @Test
    void appendMessageMapsAllRolesForMultiturnReplay() {
        OllamaChatRequest request = OllamaChatRequest.builder();
        request = OllamaAiService.appendMessage(request, Message.system("sys"));
        request = OllamaAiService.appendMessage(request, Message.user("hi"));
        request = OllamaAiService.appendMessage(request,
            Message.assistant("thinking", List.of(new ToolCall("c1", "search", Map.of("q", "x")))));
        request = OllamaAiService.appendMessage(request, Message.tool("c1", "search", "result"));

        List<OllamaChatMessage> msgs = request.getMessages();
        assertEquals(4, msgs.size());

        assertEquals(OllamaChatMessageRole.SYSTEM, msgs.get(0).getRole());
        assertEquals("sys", msgs.get(0).getResponse());
        assertEquals(OllamaChatMessageRole.USER, msgs.get(1).getRole());
        assertEquals("hi", msgs.get(1).getResponse());
        assertEquals(OllamaChatMessageRole.ASSISTANT, msgs.get(2).getRole());
        assertNotNull(msgs.get(2).getToolCalls(), "prior assistant tool calls must be replayed");
        assertEquals("c1", msgs.get(2).getToolCalls().get(0).getId());
        assertEquals(OllamaChatMessageRole.TOOL, msgs.get(3).getRole());
        assertEquals("result", msgs.get(3).getResponse());
    }
}
