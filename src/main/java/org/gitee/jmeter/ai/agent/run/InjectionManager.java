package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages per-session injection routing for mid-turn message injection.
 *
 * <p><b>队列归回合所有，map 条目只作路由槽：</b> {@link #register} creates a fresh queue owned by the submitting turn and
 * puts it into the routing map (newest turn wins). The turn task captures the queue
 * handle and drains/cleans up <em>by handle</em>, never by session-key lookup — a
 * dying turn therefore cannot steal a successor turn's messages, and
 * {@link #cleanup} removes the routing slot only if it still points at the caller's
 * own queue (a successor's slot survives). {@code signalCancel} calls
 * {@link #cancelRouting} so a dying session immediately stops being injectable:
 * new messages can only start new turns.
 *
 * <p>Thread safety: {@code computeIfPresent}-based offer and {@code remove}-based
 * cancel execute atomically under the ConcurrentHashMap bin lock — an offer either
 * lands in a live, routed queue or observes the slot gone; it can never write into
 * a detached queue while reporting success. LinkedBlockingQueue supports concurrent
 * producers (Swing EDT / ipc-worker) and consumers (turn task).
 */
public class InjectionManager {
    private static final Logger log = LoggerFactory.getLogger(InjectionManager.class);

    /**
     * One queue entry. Carries the entry's origin so cleanup can distinguish user
     * messages (re-publishable as fresh turns) from subagent announcements (dropped on
     * cancel/settle; their results remain queryable via {@code subagent_status}).
     */
    public static final class InjectionItem {
        private final String text;
        private final boolean announcement;

        public InjectionItem(String text, boolean announcement) {
            this.text = text;
            this.announcement = announcement;
        }

        public String getText() {
            return text;
        }

        public boolean isAnnouncement() {
            return announcement;
        }
    }

    private final int maxQueueSize;

    private final ConcurrentHashMap<String, LinkedBlockingQueue<InjectionItem>> injectionQueues = new ConcurrentHashMap<>();

    public InjectionManager() {
        this.maxQueueSize = AiConfig.getInjectionQueueSize();
    }

    /**
     * Create this turn's private queue and take over the session's routing slot.
     * Called on the SUBMITTING thread before the turn task is handed to the
     * executor — mid-turn offers during the [submit→pickup] window must find a
     * routed queue (otherwise they fall through to Phase 3 as competing turns).
     *
     * <p>{@code put} (replace) semantics are intentional: routing must always point
     * at the newest turn. The replaced queue stays alive in its owning turn's hands
     * (captured handle) and is drained/cleaned by that turn itself.
     *
     * @return the queue handle the turn must use for drain and cleanup
     */
    public LinkedBlockingQueue<InjectionItem> register(String sessionKey) {
        LinkedBlockingQueue<InjectionItem> queue = new LinkedBlockingQueue<>(maxQueueSize);
        injectionQueues.put(sessionKey, queue);
        log.debug("Registered injection queue for session {}", sessionKey);
        return queue;
    }

    /**
     * Offer a user message to the session's routed queue. Equivalent to
     * {@code offer(sessionKey, message, false)}.
     */
    public boolean offer(String sessionKey, String message) {
        return offer(sessionKey, message, false);
    }

    /**
     * Offer a message to the session's routed queue, tagging subagent announcements.
     * Called from producer threads (Swing EDT, ipc-worker, subagent completion).
     *
     * <p>Atomic with {@link #cancelRouting}: both run under the map's bin lock, so the
     * offer either lands in a queue that is still routed, or the slot is already gone
     * and this returns false. There is no window where a message is written into a
     * detached queue while the caller is told it was queued.
     *
     * @return true if the message was queued, false if the slot is absent or the queue is full
     */
    public boolean offer(String sessionKey, String message, boolean announcement) {
        AtomicReference<Boolean> offered = new AtomicReference<>(Boolean.FALSE);
        LinkedBlockingQueue<InjectionItem> routed = injectionQueues.computeIfPresent(sessionKey,
                (key, queue) -> {
                    offered.set(queue.offer(new InjectionItem(message, announcement)));
                    return queue;
                });
        if (routed == null) {
            return false;
        }
        if (!offered.get()) {
            log.warn("Injection queue full for session {}, dropping message", sessionKey);
            return false;
        }
        log.debug("Message offered to injection queue for session {}", sessionKey);
        return true;
    }

    /**
     * Drain up to {@code limit} entries from the given turn's queue.
     * Called by the turn task at injection checkpoints — by handle, so a turn only
     * ever consumes its own queue.
     */
    public List<InjectionItem> drain(LinkedBlockingQueue<InjectionItem> queue, int limit) {
        if (queue == null) {
            return Collections.emptyList();
        }
        List<InjectionItem> items = new ArrayList<>();
        while (items.size() < limit) {
            InjectionItem item = queue.poll();
            if (item == null) break;
            items.add(item);
        }
        if (!items.isEmpty()) {
            log.debug("Drained {} messages from an injection queue", items.size());
        }
        return items;
    }

    /**
     * Drain like {@link #drain(LinkedBlockingQueue, int)}, but if nothing is ready,
     * block until one entry arrives or the timeout elapses.
     *
     * <p>Used for subagent turn-confluence: the main agent parks here while a
     * subagent is still running so its result is consumed in the same turn.
     *
     * <p>This is invoked through {@code AgentRunSpec.injectionCallback}, a
     * {@code Function} which cannot declare checked exceptions — so an interrupt
     * (Stop button) is caught here, the interrupt flag is restored, and an empty
     * list is returned. The agent loop then exits at its next abort check.
     * This method never throws.
     */
    public List<InjectionItem> drainBlocking(LinkedBlockingQueue<InjectionItem> queue, int limit, long timeoutMs) {
        List<InjectionItem> items = drain(queue, limit);
        if (!items.isEmpty() || queue == null) {
            return items;
        }

        try {
            InjectionItem first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (first == null) {
                log.warn("Timed out after {}ms waiting for a subagent result", timeoutMs);
                return items;
            }
            items.add(first);
            // Take whatever else already arrived, without blocking again.
            items.addAll(drain(queue, limit - items.size()));
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Interrupted while waiting for a subagent result");
            return items;
        }
    }

    /**
     * Turn teardown: drain the turn's own queue and remove the routing slot
     * <em>only if it still points at this queue</em> — a successor turn that already
     * re-registered must keep its slot (identity-conditional remove, mirroring
     * Nanobot loop.py's {@code pending_queues.get(key) is pending} check).
     *
     * @return leftover entries still in the turn's queue (caller filters/re-publishes)
     */
    public List<InjectionItem> cleanup(String sessionKey, LinkedBlockingQueue<InjectionItem> queue) {
        if (queue == null) {
            return Collections.emptyList();
        }
        List<InjectionItem> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        injectionQueues.remove(sessionKey, queue);

        if (!remaining.isEmpty()) {
            log.info("Cleanup: {} remaining messages for session {}", remaining.size(), sessionKey);
        }
        return remaining;
    }

    /**
     * Remove the session's routing slot, making the dying session immediately
     * non-injectable (new messages must start new turns). Called by
     * {@code AgentLoop.signalCancel}. The dying turn's queue object stays reachable
     * through its captured handle: a picked-up turn drains it in its finally block;
     * a cancelled-before-pickup turn's guard task (see {@code AgentLoop.startTurn})
     * drains it when the executor dequeues it.
     */
    public void cancelRouting(String sessionKey) {
        injectionQueues.remove(sessionKey);
    }

    /**
     * Check if a session has a routed queue (i.e., the newest turn is injectable).
     * This is the single source of truth for both Phase 2 routing and the public
     * {@code hasActiveRun} — a cancelled (dying) session reads false because
     * {@link #cancelRouting} already removed its slot.
     */
    public boolean hasActiveRun(String sessionKey) {
        return injectionQueues.containsKey(sessionKey);
    }
}
