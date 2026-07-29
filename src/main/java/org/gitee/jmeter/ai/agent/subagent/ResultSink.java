package org.gitee.jmeter.ai.agent.subagent;

/**
 * Where a finished subagent hands its result back to the main agent.
 *
 * <p>Exists to break the cycle between {@code AgentLoop} (which owns the
 * injection queues) and {@link SubagentManager} (which the loop holds): the
 * manager is handed {@code agentLoop::offerInjection} as a plain function
 * instead of a back-reference to the loop.
 */
@FunctionalInterface
public interface ResultSink {

    /**
     * Offer a subagent result to the given session's injection queue, but only if
     * {@code turnToken} still identifies that session's active turn.
     *
     * <p>The validation and the enqueue happen together on the loop's side. Checking
     * first and offering afterwards would leave a window in which the turn ends in
     * between: the queued text would then be re-published as a fresh user turn whose
     * content is a subagent announcement — the exact derailment the token prevents.
     *
     * @param sessionKey the MAIN session key the subagent was spawned from
     * @param turnToken the turn that spawned it, or null to skip the check
     * @param message the rendered announcement text
     * @return true if it was queued; false if the turn is gone or has no queue
     *         (the caller then keeps the result for status queries)
     */
    boolean offer(String sessionKey, Object turnToken, String message);
}
