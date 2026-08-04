package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.MessageListUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Represents a conversation session with the Agent.
 * Contains message history and metadata.
 */
public class Session {
    private final String key;
    private final List<Message> messages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int lastConsolidatedIndex;

    public Session(String key) {
        this.key = key;
        this.messages = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.lastConsolidatedIndex = 0;
    }

    public String getKey() {
        return key;
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public int getLastConsolidatedIndex() {
        return lastConsolidatedIndex;
    }

    public void setLastConsolidatedIndex(int index) {
        this.lastConsolidatedIndex = index;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Add a message to the session
     */
    public void addMessage(Message message) {
        if (message != null) {
            messages.add(message);
            updatedAt = LocalDateTime.now();
        }
    }

    /**
     * Add multiple messages to the session
     */
    public void addMessages(List<Message> newMessages) {
        if (newMessages != null) {
            messages.addAll(newMessages);
            updatedAt = LocalDateTime.now();
        }
    }

    /**
     * Get message history for LLM input (Nanobot: get_history).
     * 1. Only return unconsolidated messages (after lastConsolidatedIndex)
     * 2. Slice to the most recent maxMessages
     * 3. Drop leading non-user messages (user-turn alignment)
     * 4. Skip orphaned tool results (_find_legal_start)
     */
    public List<Message> getHistory(int maxMessages) {
        // Step 1: unconsolidated = self.messages[self.last_consolidated:]
        List<Message> unconsolidated = (lastConsolidatedIndex >= messages.size())
                ? Collections.emptyList()
                : new ArrayList<>(messages.subList(lastConsolidatedIndex, messages.size()));

        // Step 2: sliced = unconsolidated[-max_messages:]
        List<Message> sliced = unconsolidated;
        if (maxMessages > 0 && maxMessages < unconsolidated.size()) {
            sliced = new ArrayList<>(unconsolidated.subList(unconsolidated.size() - maxMessages, unconsolidated.size()));
        }

        // Step 3: Drop leading non-user messages (Nanobot: for i, message in enumerate(sliced): if user → break)
        for (int i = 0; i < sliced.size(); i++) {
            if (sliced.get(i).getRole() == Message.Role.USER) {
                sliced = sliced.subList(i, sliced.size());
                break;
            }
        }

        // Step 4: Skip orphaned tool results (Nanobot: _find_legal_start)
        int legalStart = MessageListUtils.findLegalStart(sliced);
        if (legalStart > 0) {
            sliced = sliced.subList(legalStart, sliced.size());
        }

        // Step 5: Output cleaning — only keep fields relevant for LLM (Nanobot: role, content, tool_calls, tool_call_id, name)
        List<Message> out = new ArrayList<>(sliced.size());
        for (Message msg : sliced) {
            out.add(Message.builder()
                    .role(msg.getRole())
                    .content(msg.getContent() != null ? msg.getContent() : "")
                    .toolCalls(msg.hasToolCalls() ? msg.getToolCalls() : null)
                    .toolCallId(msg.getToolCallId())
                    .metadata(msg.getToolName() != null
                            ? Collections.singletonMap("toolName", msg.getToolName())
                            : null)
                    .build());
        }
        return out;
    }

    /**
     * Get messages after the last consolidation index.
     */
    public List<Message> getUnconsolidatedMessages() {
        if (lastConsolidatedIndex >= messages.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(messages.subList(lastConsolidatedIndex, messages.size()));
    }

    /**
     * Get messages in a specific index range [from, to).
     */
    public List<Message> getMessagesInRange(int from, int to) {
        if (from >= messages.size() || from >= to) {
            return Collections.emptyList();
        }
        return new ArrayList<>(messages.subList(from, Math.min(to, messages.size())));
    }

    /**
     * Get total message count
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * Clear all messages from the session
     */
    public void clear() {
        messages.clear();
        lastConsolidatedIndex = 0;
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if session is empty
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * Get age of session in minutes
     */
    public long getAgeMinutes() {
        return java.time.Duration.between(createdAt, LocalDateTime.now()).toMinutes();
    }

    @Override
    public String toString() {
        return "Session{" +
                "key='" + key + '\'' +
                ", messageCount=" + messages.size() +
                ", lastConsolidatedIndex=" + lastConsolidatedIndex +
                ", ageMinutes=" + getAgeMinutes() +
                '}';
    }
}
