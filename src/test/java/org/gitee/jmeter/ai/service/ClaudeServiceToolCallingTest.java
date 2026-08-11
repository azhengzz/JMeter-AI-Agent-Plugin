package org.gitee.jmeter.ai.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.core.type.TypeReference;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the Claude tool-calling mapping logic (input schema, message mapping, and
 * response parsing) without a live Anthropic instance. These helpers are exercised
 * directly, mirroring {@code OllamaToolCallingTest}. SDK response types are mocked.
 */
class ClaudeServiceToolCallingTest {

    private ClaudeService service;

    @BeforeEach
    void setUp() {
        service = new ClaudeService();
    }

    // --- Task 2.1: buildInputSchema ---

    @Test
    void buildInputSchemaMapsTypePropertiesRequiredAndEnum() {
        Map<String, Object> nameProp = Map.of(
            "type", "string",
            "description", "the name",
            "enum", List.of("a", "b"));
        Map<String, Object> params = Map.of(
            "type", "object",
            "properties", Map.of("name", nameProp),
            "required", List.of("name"));

        Tool.InputSchema schema = service.buildInputSchema(params);

        assertEquals("object", schema._type().convert(String.class));
        assertTrue(schema.properties().isPresent(), "properties must be present");
        assertTrue(schema.required().isPresent(), "required must be present");
        assertTrue(schema.required().get().contains("name"));

        Map<String, JsonValue> props = schema.properties().get()._additionalProperties();
        assertTrue(props.containsKey("name"));
        Map<String, Object> nameSchema = props.get("name").convert(new TypeReference<Map<String, Object>>() {});
        assertEquals("string", nameSchema.get("type"));
        assertEquals(List.of("a", "b"), nameSchema.get("enum"));
    }

    @Test
    void buildInputSchemaDefaultsToObjectTypeWhenMissing() {
        // null or empty parameters must still yield a valid "object" schema.
        Tool.InputSchema empty = service.buildInputSchema(Map.of());
        assertEquals("object", empty._type().convert(String.class));
        assertFalse(empty.properties().isPresent());

        Tool.InputSchema nullParams = service.buildInputSchema(null);
        assertEquals("object", nullParams._type().convert(String.class));
    }

    // --- Task 2.2: message mapping ---

    @Test
    void addMessagesSkipsSystemAndExtractSystemCapturesItTopLevel() {
        List<Message> messages = List.of(
            Message.system("you are helpful"),
            Message.user("hi"));

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .maxTokens(1024).model("claude-sonnet");
        service.addMessages(builder, messages);

        List<MessageParam> params = builder.build().messages();
        // SYSTEM is NOT emitted as a message (consumed by top-level system).
        assertEquals(1, params.size());
        assertEquals(MessageParam.Role.USER, params.get(0).role());
        assertEquals("you are helpful", service.extractSystem(messages));
    }

    @Test
    void buildAssistantBlocksEmitsTextAndToolUseBlocks() {
        Message assistant = Message.assistant("ack", List.of(
            new ToolCall("tu-1", "search", Map.of("q", "jmeter"))));

        List<ContentBlockParam> blocks = service.buildAssistantBlocks(assistant);

        // One leading text block + one tool_use block.
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).text().isPresent());
        assertEquals("ack", blocks.get(0).text().get().text());

        ContentBlockParam toolUseBlock = blocks.stream()
            .filter(b -> b.toolUse().isPresent())
            .findFirst()
            .orElseThrow();
        ToolUseBlockParam tu = toolUseBlock.toolUse().get();
        assertEquals("tu-1", tu.id());
        assertEquals("search", tu.name());
        // Input arguments round-trip through JsonValue.
        assertEquals("jmeter", tu.input()._additionalProperties().get("q").convert(String.class));
    }

    @Test
    void buildAssistantBlocksEmitsOnlyToolUseWhenContentAbsent() {
        Message assistant = Message.assistant(null, List.of(
            new ToolCall("tu-1", "search", Map.of())));

        List<ContentBlockParam> blocks = service.buildAssistantBlocks(assistant);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).toolUse().isPresent());
    }

    @Test
    void buildToolResultBlockLinksToolUseId() {
        Message tool = Message.tool("tu-1", "search", "result-text");

        ContentBlockParam block = service.buildToolResultBlock(tool);

        assertTrue(block.toolResult().isPresent());
        assertEquals("tu-1", block.toolResult().get().toolUseId());
    }

    @Test
    void addMessagesCoalescesConsecutiveToolResultsIntoSingleUserMessage() {
        List<Message> messages = List.of(
            Message.assistant("ack", List.of(
                new ToolCall("tu-1", "a", Map.of()),
                new ToolCall("tu-2", "b", Map.of()))),
            Message.tool("tu-1", "a", "r1"),
            Message.tool("tu-2", "b", "r2"));

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .maxTokens(1024).model("claude-sonnet");
        service.addMessages(builder, messages);

        List<MessageParam> params = builder.build().messages();
        // Assistant turn, then a single USER message carrying both tool results.
        assertEquals(2, params.size());
        assertEquals(MessageParam.Role.ASSISTANT, params.get(0).role());
        assertEquals(MessageParam.Role.USER, params.get(1).role());
    }

    // --- Task 2.3: toLLMResponse ---

    @Test
    void toLLMResponseMapsToolUseBlocksToToolCalls() {
        ToolUseBlock tu = mock(ToolUseBlock.class);
        when(tu.id()).thenReturn("tu-1");
        when(tu.name()).thenReturn("create_element");
        when(tu._input()).thenReturn(JsonValue.from(Map.of("type", "ThreadGroup")));

        ContentBlock block = mock(ContentBlock.class);
        when(block.isToolUse()).thenReturn(true);
        when(block.asToolUse()).thenReturn(tu);

        com.anthropic.models.messages.Message msg = mockResponse(List.of(block), Optional.empty(), 10L, 5L);

        LLMResponse response = service.toLLMResponse(msg, "claude-sonnet");

        assertTrue(response.hasToolCalls());
        assertEquals("tool_calls", response.getFinishReason());
        assertEquals(1, response.getToolCalls().size());
        ToolCall call = response.getToolCalls().get(0);
        assertEquals("tu-1", call.getId());
        assertEquals("create_element", call.getName());
        assertEquals("ThreadGroup", call.getArguments().get("type"));
        assertEquals(10, response.getUsage().get("prompt_tokens"));
        assertEquals(5, response.getUsage().get("completion_tokens"));
    }

    @Test
    void toLLMResponseMapsPlainTextAndStopReason() {
        TextBlock text = mock(TextBlock.class);
        when(text.text()).thenReturn("hello");

        ContentBlock block = mock(ContentBlock.class);
        when(block.isToolUse()).thenReturn(false);
        when(block.isText()).thenReturn(true);
        when(block.asText()).thenReturn(text);

        StopReason stop = mock(StopReason.class);
        when(stop.known()).thenReturn(StopReason.Known.END_TURN);

        com.anthropic.models.messages.Message msg = mockResponse(List.of(block), Optional.of(stop), 3L, 7L);

        LLMResponse response = service.toLLMResponse(msg, "claude");

        assertFalse(response.hasToolCalls());
        assertEquals("stop", response.getFinishReason());
        assertEquals("hello", response.getContent());
    }

    @Test
    void toLLMResponseCapturesReasoningContent() {
        ThinkingBlock thinking = mock(ThinkingBlock.class);
        when(thinking.thinking()).thenReturn("let me think");

        TextBlock text = mock(TextBlock.class);
        when(text.text()).thenReturn("answer");

        ContentBlock thinkBlock = mock(ContentBlock.class);
        when(thinkBlock.isToolUse()).thenReturn(false);
        when(thinkBlock.isText()).thenReturn(false);
        when(thinkBlock.isThinking()).thenReturn(true);
        when(thinkBlock.asThinking()).thenReturn(thinking);

        ContentBlock textBlock = mock(ContentBlock.class);
        when(textBlock.isToolUse()).thenReturn(false);
        when(textBlock.isText()).thenReturn(true);
        when(textBlock.asText()).thenReturn(text);

        com.anthropic.models.messages.Message msg =
            mockResponse(List.of(thinkBlock, textBlock), Optional.empty(), 0L, 0L);

        LLMResponse response = service.toLLMResponse(msg, "claude");

        assertEquals("answer", response.getContent());
        assertTrue(response.getReasoningContent().contains("let me think"));
    }

    // --- Task 2.4: mapStopReason ---

    @Test
    void mapStopReasonMapsKnownValues() {
        assertEquals("length", service.mapStopReason(Optional.of(stopReason(StopReason.Known.MAX_TOKENS))));
        assertEquals("stop", service.mapStopReason(Optional.of(stopReason(StopReason.Known.END_TURN))));
        assertEquals("stop", service.mapStopReason(Optional.of(stopReason(StopReason.Known.STOP_SEQUENCE))));
        assertEquals("stop", service.mapStopReason(Optional.of(stopReason(StopReason.Known.PAUSE_TURN))));
        assertEquals("stop", service.mapStopReason(Optional.of(stopReason(StopReason.Known.REFUSAL))));
        assertEquals("stop", service.mapStopReason(Optional.empty()));
    }

    // --- Task 2.5 fix: forced tool_choice ---

    @Test
    void buildToolCallParamsForcesSpecificToolOnlyWhenRequested() {
        ToolDefinition td = ToolDefinition.builder()
            .name("save_memory").description("save memory")
            .parameters(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
            .build();
        List<Message> messages = List.of(Message.user("consolidate this"));

        // Auto mode: no tool_choice set; tools still attached.
        MessageCreateParams autoParams = service.buildToolCallParams(messages, List.of(td), null);
        assertTrue(autoParams.toolChoice().isEmpty(), "auto mode must not set tool_choice");
        assertTrue(autoParams.tools().isPresent());
        assertEquals(1, autoParams.tools().get().size());

        // Forced mode: tool_choice pins the named tool (MemoryConsolidator's save_memory path).
        MessageCreateParams forcedParams = service.buildToolCallParams(messages, List.of(td), "save_memory");
        assertTrue(forcedParams.toolChoice().isPresent(), "forced mode must set tool_choice");
        ToolChoice choice = forcedParams.toolChoice().get();
        assertTrue(choice.isTool());
        assertEquals("save_memory", choice.asTool().name());
    }

    // --- Regression: supportsToolCalling is an UNCONDITIONAL backend capability. ---
    //     It must NOT derive from the model id. A prefix/family check here has broken
    //     the agent three times (provider-prefix ×2, claude-fable-* family-list gap),
    //     because AgentRunner's pre-flight guard treats a false return as fatal. The
    //     "anthropic:" prefix is stripped by AiServiceFactory.bareModelName before
    //     setModel; re-validating it here is the trap.

    @Test
    void supportsToolCallingIsUnconditionalAndModelAgnostic() {
        // Bare id, new family, prefixed id, a non-claude id, and null must ALL report
        // support — the method no longer inspects the model string.
        for (String model : new String[] {
            "claude-opus-5", "claude-fable-5", "claude-3-sonnet-20240229",
            "anthropic:claude-opus-5",   // prefixed — stripping is the factory's job
            "gpt-4o",                     // non-claude — proves it's not a family check
            "",                           // unset sentinel — same class uses null||isEmpty() to detect unset; must stay unconditional
            null                          // must not dereference currentModelId
        }) {
            service.setModel(model);
            assertTrue(service.supportsToolCalling(),
                "supportsToolCalling must be unconditional for model: " + model);
        }
    }

    private StopReason stopReason(StopReason.Known known) {
        StopReason sr = mock(StopReason.class);
        when(sr.known()).thenReturn(known);
        return sr;
    }

    private com.anthropic.models.messages.Message mockResponse(
            List<ContentBlock> content, Optional<StopReason> stopReason, long inputTokens, long outputTokens) {
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(inputTokens);
        when(usage.outputTokens()).thenReturn(outputTokens);

        com.anthropic.models.messages.Message msg = mock(com.anthropic.models.messages.Message.class);
        when(msg.content()).thenReturn(content);
        when(msg.stopReason()).thenReturn(stopReason);
        when(msg.usage()).thenReturn(usage);
        return msg;
    }
}
