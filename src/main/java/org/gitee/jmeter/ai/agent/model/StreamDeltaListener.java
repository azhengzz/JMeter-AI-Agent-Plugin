package org.gitee.jmeter.ai.agent.model;

/**
 * Raw per-fragment streaming sink a provider delivers into while draining its
 * native stream. The provider reports fragments as they arrive (no
 * segmentation, no throttling, no cumulative text) — think-tag segmentation,
 * ~100ms coalescing and cumulative event payloads are the emitter's job
 * (it wraps this interface), so providers stay dumb transports.
 *
 * Present on {@link LlmCallOptions} only when the runner wants streaming for
 * this call (hook asked for it and the streaming escape hatch is on); a null
 * listener means "call non-streaming" and providers MUST NOT construct it.
 */
public interface StreamDeltaListener {

    /** Visible reply text fragment from the content channel (may contain inline think tags). */
    void onContentDelta(String text);

    /** Fragment from the provider's structured reasoning channel (never contains think tags). */
    void onReasoningDelta(String text);
}
