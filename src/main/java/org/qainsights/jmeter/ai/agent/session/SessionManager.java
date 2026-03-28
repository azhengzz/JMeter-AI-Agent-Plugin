package org.qainsights.jmeter.ai.agent.session;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages conversation sessions.
 * Provides session creation, retrieval, and persistence.
 */
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, Session> sessions;
    private final Path sessionStorage;
    private final ScheduledExecutorService cleanupExecutor;
    private final long sessionTimeoutMillis;

    public SessionManager() {
        this(getDefaultWorkspace());
    }

    public SessionManager(Path workspace) {
        this.sessionStorage = workspace.resolve("sessions");
        this.sessions = new ConcurrentHashMap<>();
        this.sessionTimeoutMillis = Long.parseLong(AiConfig.getProperty("agent.session.timeout", "3600000"));

        ensureDirectories();
        loadSessions();

        // Schedule periodic cleanup
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "session-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);

        log.info("SessionManager initialized with workspace: {}", sessionStorage);
    }

    private static Path getDefaultWorkspace() {
        String path = AiConfig.getProperty("agent.memory.workspace.path",
                System.getProperty("user.home") + "/.jmeter-ai/agent");
        return Paths.get(path);
    }

    private void ensureDirectories() {
        try {
            if (!Files.exists(sessionStorage)) {
                Files.createDirectories(sessionStorage);
                log.info("Created session directory: {}", sessionStorage);
            }
        } catch (IOException e) {
            log.error("Failed to create session directory", e);
        }
    }

    /**
     * Get or create a session for the given key
     */
    public Session getOrCreate(String sessionKey) {
        return sessions.computeIfAbsent(sessionKey, key -> {
            Session session = new Session(key);
            saveSession(session);
            log.info("Created new session: {}", key);
            return session;
        });
    }

    /**
     * Get an existing session without creating
     */
    public Session get(String sessionKey) {
        return sessions.get(sessionKey);
    }

    /**
     * Get message history for a session
     */
    public java.util.List<org.qainsights.jmeter.ai.agent.model.Message> getHistory(String sessionKey, int maxMessages) {
        Session session = sessions.get(sessionKey);
        if (session == null) {
            return java.util.Collections.emptyList();
        }
        return session.getHistory(maxMessages);
    }

    /**
     * Clear and remove a session
     */
    public void clearSession(String sessionKey) {
        Session removed = sessions.remove(sessionKey);
        if (removed != null) {
            deleteSessionFile(removed);
            log.info("Cleared session: {}", sessionKey);
        }
    }

    /**
     * Save a session to disk
     */
    public void saveSession(Session session) {
        Path sessionFile = getSessionFile(session.getKey());
        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile)) {
            // Write session metadata
            writer.write("# Session: " + session.getKey() + "\n");
            writer.write("# Created: " + session.getCreatedAt() + "\n");
            writer.write("# Updated: " + session.getUpdatedAt() + "\n");
            writer.write("# LastConsolidated: " + session.getLastConsolidatedIndex() + "\n");
            writer.write("# Messages: " + session.getMessageCount() + "\n");
            writer.write("\n");

            // Write messages
            for (org.qainsights.jmeter.ai.agent.model.Message message : session.getMessages()) {
                writer.write(formatMessageForStorage(message));
                writer.write("\n");
            }

        } catch (IOException e) {
            log.error("Failed to save session: {}", session.getKey(), e);
        }
    }

    /**
     * Load sessions from disk
     */
    private void loadSessions() {
        try {
            if (!Files.exists(sessionStorage)) {
                return;
            }

            Files.list(sessionStorage)
                    .filter(p -> p.toString().endsWith(".session"))
                    .forEach(this::loadSessionFile);

            log.info("Loaded {} sessions from disk", sessions.size());

        } catch (IOException e) {
            log.error("Failed to load sessions", e);
        }
    }

    /**
     * Load a single session file
     */
    private void loadSessionFile(Path sessionFile) {
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            String sessionKey = "";
            java.util.List<org.qainsights.jmeter.ai.agent.model.Message> messages = new java.util.ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# Session: ")) {
                    sessionKey = line.substring("# Session: ".length()).trim();
                } else if (line.startsWith("# LastConsolidated: ")) {
                    // Could parse this if needed
                } else if (!line.startsWith("#") && !line.trim().isEmpty()) {
                    // Parse message (simplified - would need proper serialization)
                }
            }

            if (!sessionKey.isEmpty()) {
                Session session = new Session(sessionKey);
                // Would need to deserialize messages properly
                sessions.put(sessionKey, session);
            }

        } catch (IOException e) {
            log.warn("Failed to load session file: {}", sessionFile, e);
        }
    }

    private Path getSessionFile(String sessionKey) {
        // Sanitize session key for filename
        String safeKey = sessionKey.replaceAll("[^a-zA-Z0-9-_]", "_");
        return sessionStorage.resolve(safeKey + ".session");
    }

    private void deleteSessionFile(Session session) {
        Path sessionFile = getSessionFile(session.getKey());
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            log.warn("Failed to delete session file: {}", sessionFile, e);
        }
    }

    private String formatMessageForStorage(org.qainsights.jmeter.ai.agent.model.Message message) {
        return String.format("[%s] %s: %s",
                message.getTimestamp(),
                message.getRole(),
                message.getContent() != null ? message.getContent().replace("\n", "\\n") : "");
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            long ageMillis = session.getAgeMinutes() * 60 * 1000L;
            if (ageMillis > sessionTimeoutMillis) {
                log.info("Removing expired session: {} (age: {} minutes)", session.getKey(), session.getAgeMinutes());
                deleteSessionFile(session);
                return true;
            }
            return false;
        });
    }

    /**
     * Get active session count
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Shutdown the session manager
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Save all sessions
        for (Session session : sessions.values()) {
            saveSession(session);
        }

        log.info("SessionManager shutdown complete");
    }

    @Override
    public String toString() {
        return "SessionManager{" +
                "activeSessions=" + sessions.size() +
                ", storage=" + sessionStorage +
                '}';
    }
}
