package org.qainsights.jmeter.ai.agent.session;

import org.qainsights.jmeter.ai.agent.model.Message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * Get message history up to maxMessages
     */
    public List<Message> getHistory(int maxMessages) {
        if (maxMessages <= 0) {
            return new ArrayList<>(messages);
        }

        int fromIndex = Math.max(0, messages.size() - maxMessages);
        return new ArrayList<>(messages.subList(fromIndex, messages.size()));
    }

    /**
     * Get messages after the last consolidation index
     */
    public List<Message> getUnconsolidatedMessages() {
        if (lastConsolidatedIndex >= messages.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(messages.subList(lastConsolidatedIndex, messages.size()));
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
