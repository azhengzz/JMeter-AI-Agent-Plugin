package org.gitee.jmeter.ai.agent.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure utility functions over {@link Message} lists, shared by session slicing
 * (Session.getHistory) and in-loop context governance (ContextWindowManager.govern).
 *
 * <p>Extracted from {@code Session.findLegalStart} so the governor can reuse the
 * same legal-start / user-alignment logic without depending on Session.
 */
public final class MessageListUtils {

    private MessageListUtils() {
    }

    /**
     * Find the first index from which every TOOL message's {@code toolCallId} is
     * declared by an earlier ASSISTANT message's {@code tool_calls[].id}.
     * Ported from Nanobot's {@code _find_legal_message_start}. Does NOT require
     * user-first — only that the kept tail has no leading orphan TOOL result.
     *
     * <p>When an orphan TOOL is encountered the scan restarts at {@code i + 1} and
     * clears the declared set: any later TOOL must be re-justified by an ASSISTANT
     * that also lies in the kept tail. The single forward pass already visits every
     * index once, so no inner rescan is needed (an earlier version had a dead rescan
     * loop that never executed — deliberately omitted here).
     */
    public static int findLegalStart(List<Message> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return 0;
        }
        Set<String> declared = new HashSet<>();
        int start = 0;

        for (int i = 0; i < msgs.size(); i++) {
            Message msg = msgs.get(i);
            if (msg.getRole() == Message.Role.ASSISTANT && msg.hasToolCalls()) {
                for (ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        declared.add(tc.getId());
                    }
                }
            } else if (msg.getRole() == Message.Role.TOOL) {
                String tid = msg.getToolCallId();
                if (tid == null || !declared.contains(tid)) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }

        return start;
    }

    /**
     * Index of the first USER message at or after {@code fromIndex}, or -1 if none.
     */
    public static int indexOfNextUser(List<Message> msgs, int fromIndex) {
        if (msgs == null) {
            return -1;
        }
        for (int i = Math.max(0, fromIndex); i < msgs.size(); i++) {
            if (msgs.get(i).getRole() == Message.Role.USER) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the last USER message strictly before {@code fromIndex}, or -1 if none.
     */
    public static int indexOfPrevUser(List<Message> msgs, int fromIndex) {
        if (msgs == null) {
            return -1;
        }
        for (int i = Math.min(msgs.size() - 1, fromIndex - 1); i >= 0; i--) {
            if (msgs.get(i).getRole() == Message.Role.USER) {
                return i;
            }
        }
        return -1;
    }
}
