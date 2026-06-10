package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages per-session injection queues for mid-turn message injection.
 *
 * Allows the Swing UI thread to offer messages into a session's queue
 * while the agent executor thread drains them at injection checkpoints.
 *
 * Thread safety: LinkedBlockingQueue supports concurrent producers (Swing EDT)
 * and consumers (agent executor). ConcurrentHashMap provides safe queue lookup.
 */
public class InjectionManager {
    private static final Logger log = LoggerFactory.getLogger(InjectionManager.class);

    private final int maxQueueSize;
    private final int maxInjectionsPerTurn;

    private final ConcurrentHashMap<String, LinkedBlockingQueue<String>> injectionQueues = new ConcurrentHashMap<>();

    public InjectionManager() {
        this.maxQueueSize = Integer.parseInt(
            AiConfig.getProperty("jmeter.ai.injection.queue.size", "20"));
        this.maxInjectionsPerTurn = Integer.parseInt(
            AiConfig.getProperty("jmeter.ai.injection.max.per.turn", "3"));
    }

    /**
     * Register an injection queue for a session.
     * Called at the start of an agent run.
     */
    public void register(String sessionKey) {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(maxQueueSize);
        injectionQueues.put(sessionKey, queue);
        log.debug("Registered injection queue for session {}", sessionKey);
    }

    /**
     * Offer a message to the session's injection queue.
     * Called from the Swing EDT thread (non-blocking).
     *
     * @return true if the message was queued, false if the queue is full or no active run
     */
    public boolean offer(String sessionKey, String message) {
        LinkedBlockingQueue<String> queue = injectionQueues.get(sessionKey);
        if (queue == null) {
            return false;
        }
        boolean offered = queue.offer(message);
        if (!offered) {
            log.warn("Injection queue full for session {}, dropping message", sessionKey);
        } else {
            log.debug("Message offered to injection queue for session {} (size={})",
                sessionKey, queue.size());
        }
        return offered;
    }

    /**
     * Drain up to maxInjectionsPerTurn messages from the session's queue.
     * Called from the agent executor thread at injection checkpoints.
     *
     * @param sessionKey the session key
     * @param limit maximum number of messages to drain (typically MAX_INJECTIONS_PER_TURN)
     * @return list of drained messages, possibly empty
     */
    public List<String> drain(String sessionKey, int limit) {
        LinkedBlockingQueue<String> queue = injectionQueues.get(sessionKey);
        if (queue == null) {
            return Collections.emptyList();
        }

        List<String> items = new ArrayList<>();
        while (items.size() < limit) {
            String msg = queue.poll();
            if (msg == null) break;
            items.add(msg);
        }

        if (!items.isEmpty()) {
            log.debug("Drained {} messages from injection queue for session {}",
                items.size(), sessionKey);
        }
        return items;
    }

    /**
     * Unregister and clean up the injection queue for a session.
     * Called in the finally block after an agent run completes.
     *
     * @return any remaining messages that were still in the queue
     */
    public List<String> cleanup(String sessionKey) {
        LinkedBlockingQueue<String> queue = injectionQueues.remove(sessionKey);
        if (queue == null) {
            return Collections.emptyList();
        }

        List<String> remaining = new ArrayList<>();
        queue.drainTo(remaining);

        if (!remaining.isEmpty()) {
            log.info("Cleanup: {} remaining messages for session {}",
                remaining.size(), sessionKey);
        }
        return remaining;
    }

    /**
     * Check if a session has an active injection queue (i.e., an agent run is in progress).
     */
    public boolean hasActiveRun(String sessionKey) {
        return injectionQueues.containsKey(sessionKey);
    }
}
