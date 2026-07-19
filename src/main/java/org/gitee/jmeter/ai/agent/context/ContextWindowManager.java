package org.gitee.jmeter.ai.agent.context;

import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.MessageListUtils;
import org.gitee.jmeter.ai.agent.model.ToolCall;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Context window manager — in-loop governor for a single agent run.
 *
 * <p>{@link #govern(List, int)} runs every loop iteration, before the LLM call, on a
 * <b>copy</b> of the live message list. It never mutates its input (the input is the
 * persisted conversation and must keep growing intact). Pipeline (Nanobot-aligned):
 * <ol>
 *   <li>estimate tokens; if within budget, return the input unchanged (short-circuit, no copy)</li>
 *   <li>{@code microcompact}: collapse OLDER compactable tool-result contents to a one-line
 *       placeholder (keeps the tool message, preserving tool_call_id pairing); re-estimate;
 *       if now within budget, return</li>
 *   <li>{@code snip}: hard-truncate from the front to a full token budget, keep system msgs,
 *       align the start to a USER message (reach back if none in window), strip leading
 *       orphan TOOL results</li>
 * </ol>
 *
 * <p>Token estimation is delegated to {@link MemoryConsolidator} (single source of truth,
 * jtokkit BPE) when supplied; otherwise a chars/4 heuristic is used as a fallback.
 */
public class ContextWindowManager {
    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    /** Reserves room for output slack + tool-definition tokens (~2-3K for the 20+ JMeter tools,
     *  which estimateMessagesTokens does not count) + estimator error. Sized from the observed
     *  ~2.3K gap between the governor's estimate and the provider's actual promptTokens. */
    private static final int SAFETY_BUFFER = 4096;

    /** Number of newest compactable tool results kept intact by microcompact. */
    private static final int KEEP_RECENT_COMPACTABLE = 10;
    /** Only compact tool results whose content is at least this many chars. */
    private static final int MIN_CHARS_TO_COMPACT = 500;
    /**
     * Tool names whose results are large and re-summarizable. Older results beyond the
     * newest {@link #KEEP_RECENT_COMPACTABLE} are collapsed to a placeholder by microcompact.
     */
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "list_dir", "exec",
            "web_search", "web_fetch"
    );

    public final int maxContextTokens;

    private final MemoryConsolidator memoryConsolidator;

    public ContextWindowManager(int maxContextTokens) {
        this(maxContextTokens, null);
    }

    public ContextWindowManager(int maxContextTokens, MemoryConsolidator memoryConsolidator) {
        this.maxContextTokens = maxContextTokens;
        this.memoryConsolidator = memoryConsolidator;
    }

    public ContextWindowManager() {
        this(Integer.parseInt(AiConfig.getProperty("jmeter.ai.context.window.tokens", "65536")), null);
    }

    private int getMaxCompletionTokens() {
        return Integer.parseInt(AiConfig.getProperty("jmeter.ai.max.tokens", "4096"));
    }

    /**
     * In-loop governor. Never mutates {@code messages}.
     *
     * @param messages        the full, growing message list (the persisted conversation)
     * @param maxOutputTokens effective max output tokens for this run; pass the per-run
     *                        override (e.g. {@code spec.getMaxTokens()}) — {@code null}
     *                        or {@code <= 0} falls back to the configured global default
     *                        ({@code jmeter.ai.max.tokens})
     * @return either {@code messages} itself (short-circuit, under budget) or a fresh
     *         trimmed copy; never a subList view of the input
     */
    public List<Message> govern(List<Message> messages, Integer maxOutputTokens) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        int maxOutput = (maxOutputTokens != null && maxOutputTokens > 0)
                ? maxOutputTokens
                : getMaxCompletionTokens();
        int budget = Math.max(0, maxContextTokens - maxOutput - SAFETY_BUFFER);
        int estimated = estimateTokens(messages);
        if (estimated <= budget) {
            return messages;
        }

        log.info("govern: {} tokens > budget {} (window={}, maxOutput={})",
                estimated, budget, maxContextTokens, maxOutput);

        List<Message> copy = new ArrayList<>(messages);
        int compacted = microcompact(copy);
        int afterCompact = estimateTokens(copy);
        if (afterCompact <= budget) {
            log.info("govern: microcompact collapsed {} result(s); tokens {} -> {} (<= budget {})",
                    compacted, estimated, afterCompact, budget);
            return copy;
        }

        List<Message> snipped = snip(copy, budget);
        int afterSnip = estimateTokens(snipped);
        int dropped = messages.size() - snipped.size();
        if (afterSnip > budget) {
            log.warn("govern: still {} tokens > budget {} after trim (microcompact collapsed {}, snip dropped {}/{} msgs); "
                    + "budget not fully enforceable — sending {} tokens", afterSnip, budget, compacted, dropped,
                    messages.size(), afterSnip);
        } else {
            log.info("govern: microcompact collapsed {}, snip dropped {}/{} msgs; tokens {} -> {}",
                    compacted, dropped, messages.size(), afterCompact, afterSnip);
        }
        return snipped;
    }

    /**
     * Replace OLDER compactable tool-result contents with a one-line placeholder.
     * Keeps the tool message (so tool_call_id pairing is preserved); only the content
     * is collapsed. The newest {@link #KEEP_RECENT_COMPACTABLE} compactable results are
     * left intact; only results of at least {@link #MIN_CHARS_TO_COMPACT} chars are eligible.
     * Mutates {@code msgs} in place (caller passes a copy).
     *
     * @return the number of results collapsed (0 if fewer than {@link #KEEP_RECENT_COMPACTABLE}
     *         eligible results, i.e. microcompact was a no-op)
     */
    private int microcompact(List<Message> msgs) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            if (m.getRole() == Message.Role.TOOL
                    && m.getToolName() != null
                    && COMPACTABLE_TOOLS.contains(m.getToolName())
                    && m.getContent() != null
                    && m.getContent().length() >= MIN_CHARS_TO_COMPACT) {
                candidates.add(i);
            }
        }
        if (candidates.size() <= KEEP_RECENT_COMPACTABLE) {
            return 0;
        }

        int collapseCount = candidates.size() - KEEP_RECENT_COMPACTABLE;
        Set<Integer> toCollapse = Set.copyOf(candidates.subList(0, collapseCount));
        for (int i = 0; i < msgs.size(); i++) {
            if (!toCollapse.contains(i)) {
                continue;
            }
            Message orig = msgs.get(i);
            String placeholder = "[" + orig.getToolName() + " result omitted from context]";
            msgs.set(i, Message.builder()
                    .role(orig.getRole())
                    .content(placeholder)
                    .toolCallId(orig.getToolCallId())
                    .metadata(orig.getMetadata())
                    .build());
        }
        return collapseCount;
    }

    /**
     * Hard-truncate from the front so the kept tail fits {@code budget} tokens. Always
     * keeps leading SYSTEM messages, aligns the kept start to a USER message (reaching
     * back before the window if the window contains no user — some providers reject a
     * non-user first message), and strips any leading orphan TOOL result.
     * Returns a fresh ArrayList; never a subList view.
     */
    private List<Message> snip(List<Message> msgs, int budget) {
        int n = msgs.size();
        // 1. Backward walk: keep the most recent tail that fits the budget.
        int start = n;
        int acc = 0;
        for (int i = n - 1; i >= 0; i--) {
            int t = estimateTokensOne(msgs.get(i));
            if (i < n - 1 && acc + t > budget) {
                start = i + 1;
                break;
            }
            acc += t;
            start = i;
        }
        if (start <= 0) {
            // Whole list fits — return a defensive copy (caller already holds a copy).
            return new ArrayList<>(msgs);
        }

        // 2. Always keep leading SYSTEM messages (buildMessages emits exactly one system[0]).
        int firstNonSystem = 0;
        while (firstNonSystem < start && msgs.get(firstNonSystem).getRole() == Message.Role.SYSTEM) {
            firstNonSystem++;
        }

        // 3. Align start to a USER message. Prefer a user inside the window; if none,
        //    reach back to the last user before the window (accept a slight budget overrun
        //    rather than emit a system->assistant sequence, which Claude/GLM reject).
        int effectiveStart;
        int userAt = MessageListUtils.indexOfNextUser(msgs, start);
        if (userAt >= 0) {
            effectiveStart = userAt;
        } else {
            int prevUser = MessageListUtils.indexOfPrevUser(msgs, start);
            effectiveStart = (prevUser >= firstNonSystem) ? prevUser : firstNonSystem;
        }
        if (effectiveStart < firstNonSystem) {
            effectiveStart = firstNonSystem;
        }

        // 4. Assemble [system block] + tail[effectiveStart, n), then strip a leading orphan
        //    TOOL within the tail (only matters for malformed input; well-formed tails
        //    start at USER -> assistant -> tool and findLegalStart is a no-op).
        List<Message> tail = new ArrayList<>(msgs.subList(effectiveStart, n));
        int legal = MessageListUtils.findLegalStart(tail);
        if (legal > 0) {
            tail = new ArrayList<>(tail.subList(legal, tail.size()));
        }

        List<Message> out = new ArrayList<>(firstNonSystem + tail.size());
        for (int i = 0; i < firstNonSystem; i++) {
            out.add(msgs.get(i));
        }
        out.addAll(tail);
        return out;
    }

    private int estimateTokens(List<Message> messages) {
        if (memoryConsolidator != null) {
            return memoryConsolidator.estimateMessagesTokens(messages);
        }
        int chars = 0;
        for (Message m : messages) {
            chars += msgLength(m) + 16;
        }
        return chars / 4;
    }

    private int estimateTokensOne(Message m) {
        if (memoryConsolidator != null) {
            return memoryConsolidator.estimateMessageTokens(m);
        }
        return (msgLength(m) + 16) / 4;
    }

    // --- Internal: character-based fallback estimator (used only when no MemoryConsolidator) ---

    private int msgLength(Message msg) {
        int len = 0;
        if (msg.getContent() != null) len += msg.getContent().length();
        if (msg.getReasoningContent() != null) len += msg.getReasoningContent().length();
        if (msg.hasToolCalls()) {
            for (ToolCall tc : msg.getToolCalls()) {
                if (tc.getArguments() != null) len += tc.getArguments().toString().length();
            }
        }
        if (msg.getToolCallId() != null) len += msg.getToolCallId().length();
        return len;
    }
}
