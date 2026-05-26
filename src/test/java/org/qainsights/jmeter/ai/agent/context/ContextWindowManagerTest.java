package org.qainsights.jmeter.ai.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.qainsights.jmeter.ai.agent.model.Message;
import org.qainsights.jmeter.ai.agent.model.ToolCall;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ContextWindowManager.trimToContextWindow using the real session file
 * to verify that tool call chains are never broken during trimming.
 */
class ContextWindowManagerTest {

    private static final String SESSION_FILE = "D:/WorkHome/git/gitee/osc/apache-jmeter-5.6.3/bin/jmeter-agent/sessions/jmeter-ai-chat.jsonl";
    private static List<Message> sessionMessages;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadSession() throws Exception {
        File file = new File(SESSION_FILE);
        if (!file.exists()) {
            return;
        }
        sessionMessages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.contains("\"_type\":\"metadata\"")) {
                    continue;
                }
                try {
                    JsonNode node = mapper.readTree(line);
                    Message msg = parseMessage(node);
                    if (msg != null) {
                        sessionMessages.add(msg);
                    }
                } catch (Exception e) {
                    System.out.println("Skipping malformed line " + lineNum + ": " + e.getMessage());
                }
            }
        }
    }

    private static Message parseMessage(JsonNode node) {
        String roleStr = node.path("role").asText("");
        Message.Role role = switch (roleStr) {
            case "system" -> Message.Role.SYSTEM;
            case "user" -> Message.Role.USER;
            case "assistant" -> Message.Role.ASSISTANT;
            case "tool" -> Message.Role.TOOL;
            default -> null;
        };
        if (role == null) return null;

        String content = node.path("content").asText(null);
        if ("null".equals(content)) content = null;

        String toolCallId = node.path("tool_call_id").asText(null);
        if ("null".equals(toolCallId)) toolCallId = null;

        String reasoningContent = node.path("reasoning_content").asText(null);
        if ("null".equals(reasoningContent)) reasoningContent = null;

        List<ToolCall> toolCalls = null;
        JsonNode toolCallsNode = node.path("tool_calls");
        if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
            toolCalls = new ArrayList<>();
            for (JsonNode tcNode : toolCallsNode) {
                String id = tcNode.path("id").asText(null);
                String name = tcNode.path("function").path("name").asText(null);
                String argsStr = tcNode.path("function").path("arguments").asText("{}");
                Map<String, Object> args = parseArgs(argsStr);
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        return Message.builder()
                .role(role)
                .content(content)
                .toolCalls(toolCalls)
                .toolCallId(toolCallId)
                .reasoningContent(reasoningContent)
                .build();
    }

    private static Map<String, Object> parseArgs(String argsStr) {
        try {
            JsonNode argsNode = mapper.readTree(argsStr);
            Map<String, Object> map = new LinkedHashMap<>();
            argsNode.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean sessionFileExists() {
        return new File(SESSION_FILE).exists();
    }

    /**
     * Rough token estimate: ~1 token per 4 chars + 4 overhead per message.
     * Used to simulate what MemoryConsolidator.estimateSessionTokens would return.
     */
    private int roughEstimate(List<Message> messages) {
        int chars = 0;
        for (Message msg : messages) {
            if (msg.getRole() == Message.Role.SYSTEM) continue;
            if (msg.getContent() != null) chars += msg.getContent().length();
            if (msg.getReasoningContent() != null) chars += msg.getReasoningContent().length();
            if (msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getArguments() != null) chars += tc.getArguments().toString().length();
                }
            }
            if (msg.getToolCallId() != null) chars += msg.getToolCallId().length();
            chars += 16; // overhead
        }
        return chars / 4 + 2000; // +2000 for system prompt
    }

    private void assertNoOrphanedToolMessages(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == Message.Role.TOOL) {
                boolean foundInitiator = false;
                for (int j = i - 1; j >= 0; j--) {
                    Message prev = messages.get(j);
                    if (prev.getRole() == Message.Role.ASSISTANT && prev.hasToolCalls()) {
                        foundInitiator = true;
                        break;
                    }
                    if (prev.getRole() == Message.Role.USER || prev.getRole() == Message.Role.SYSTEM) {
                        break;
                    }
                }
                assertTrue(foundInitiator,
                        "Orphaned TOOL message at index " + i + " — no preceding ASSISTANT with tool_calls found");
            }
        }
    }

    // --- Tests ---

    @Test
    @EnabledIf("sessionFileExists")
    void fullSession_noTrim_needed() {
        ContextWindowManager mgr = new ContextWindowManager(65536);
        int estimated = roughEstimate(sessionMessages);
        List<Message> trimmed = mgr.trimToContextWindow(sessionMessages, estimated, 65536);
        assertNoOrphanedToolMessages(trimmed);
    }

    @Test
    @EnabledIf("sessionFileExists")
    void aggressiveTrim_10000tokens() {
        ContextWindowManager mgr = new ContextWindowManager(10000);
        int estimated = roughEstimate(sessionMessages);
        List<Message> trimmed = mgr.trimToContextWindow(sessionMessages, estimated, 10000);
        System.out.println("=== Trim to 10000 tokens ===");
        System.out.println("Original: " + sessionMessages.size() + " messages, estimated=" + estimated);
        System.out.println("Trimmed:  " + trimmed.size() + " messages");
        printMessageSequence(trimmed);
        assertNoOrphanedToolMessages(trimmed);
    }

    @Test
    @EnabledIf("sessionFileExists")
    void aggressiveTrim_5000tokens() {
        ContextWindowManager mgr = new ContextWindowManager(5000);
        int estimated = roughEstimate(sessionMessages);
        List<Message> trimmed = mgr.trimToContextWindow(sessionMessages, estimated, 5000);
        System.out.println("=== Trim to 5000 tokens ===");
        System.out.println("Original: " + sessionMessages.size() + " messages, estimated=" + estimated);
        System.out.println("Trimmed:  " + trimmed.size() + " messages");
        printMessageSequence(trimmed);
        assertNoOrphanedToolMessages(trimmed);
    }

    @Test
    @EnabledIf("sessionFileExists")
    void aggressiveTrim_2000tokens() {
        ContextWindowManager mgr = new ContextWindowManager(2000);
        int estimated = roughEstimate(sessionMessages);
        List<Message> trimmed = mgr.trimToContextWindow(sessionMessages, estimated, 2000);
        System.out.println("=== Trim to 2000 tokens ===");
        System.out.println("Original: " + sessionMessages.size() + " messages, estimated=" + estimated);
        System.out.println("Trimmed:  " + trimmed.size() + " messages");
        printMessageSequence(trimmed);
        assertNoOrphanedToolMessages(trimmed);
    }

    @Test
    @EnabledIf("sessionFileExists")
    void trim_firstMessageMustNotBeTool() {
        ContextWindowManager mgr = new ContextWindowManager(2000);
        int estimated = roughEstimate(sessionMessages);
        List<Message> trimmed = mgr.trimToContextWindow(sessionMessages, estimated, 2000);
        if (!trimmed.isEmpty()) {
            assertNotEquals(Message.Role.TOOL, trimmed.get(0).getRole(),
                    "Trimmed list must not start with a TOOL message");
        }
    }

    @Test
    @EnabledIf("sessionFileExists")
    void sweep_allTokenLimits() {
        System.out.println("=== Sweep test: token limits from 1000 to 65536 ===");
        int estimated = roughEstimate(sessionMessages);
        for (int limit = 1000; limit <= 65536; limit += 1000) {
            ContextWindowManager mgr = new ContextWindowManager(limit);
            List<Message> trimmed = mgr.trimToContextWindow(sessionMessages, estimated, limit);
            assertNoOrphanedToolMessages(trimmed);
            if (!trimmed.isEmpty() && trimmed.size() < sessionMessages.size()) {
                System.out.println("  limit=" + limit + " => kept " + trimmed.size() + "/" + sessionMessages.size()
                        + " messages, first=" + trimmed.get(0).getRole());
            }
        }
    }

    // --- Helpers ---

    private void printMessageSequence(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String detail = "";
            if (msg.hasToolCalls()) {
                detail = " [tool_calls=" + msg.getToolCalls().size() + "]";
            } else if (msg.getRole() == Message.Role.TOOL) {
                detail = " [toolCallId=" + msg.getToolCallId() + "]";
            }
            String preview = msg.getContent() != null && !msg.getContent().isEmpty()
                    ? msg.getContent().substring(0, Math.min(60, msg.getContent().length())).replace("\n", " ")
                    : "(empty)";
            System.out.printf("  [%3d] %-9s%s %s%n", i, msg.getRole(), detail, preview);
        }
    }
}
