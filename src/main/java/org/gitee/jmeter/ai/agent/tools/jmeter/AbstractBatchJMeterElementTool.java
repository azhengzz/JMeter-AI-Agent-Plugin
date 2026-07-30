package org.gitee.jmeter.ai.agent.tools.jmeter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.EdtRunner;
import org.gitee.jmeter.ai.agent.tools.jmeter.utils.JMeterTreeUtils;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Abstract base class for batch JMeter element tools (batch_update / batch_toggle /
 * batch_delete / batch_move). Provides shared batch scaffolding: elementIds parsing,
 * batch-size guard, find-all-first resolution, current-node de-selection, per-element
 * result tracking, and aggregated result formatting.
 *
 * <p>Extends {@link AbstractJMeterElementTool} so that property-based batch tools
 * (e.g. {@code BatchUpdateJMeterElementTool}) can reuse this scaffolding alongside the
 * schema/property machinery. Structural batch tools (toggle/delete/move) inherit that
 * machinery unused but it is null-safe and side-effect free.
 *
 * <p>All helpers are {@code protected static} and stateless, so any subclass can invoke
 * them unqualified.
 */
public abstract class AbstractBatchJMeterElementTool extends AbstractJMeterElementTool {

    /** Maximum number of elements a single batch operation may target. */
    protected static final int MAX_BATCH_SIZE = 50;

    /**
     * Parse the {@code elementIds} parameter (expected to be a List of numbers) into a
     * list of {@code Integer}. Non-Number entries are silently dropped. Returns an empty
     * list if the raw value is not a List.
     */
    protected static List<Integer> parseElementIds(Object raw) {
        List<Integer> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number n) {
                    ids.add(n.intValue());
                }
            }
        }
        return ids;
    }

    /**
     * Validate parsed elementIds: must be non-empty and within {@link #MAX_BATCH_SIZE}.
     *
     * @return {@code ToolResult.error(...)} on violation, or {@code ToolResult.success("")} if valid
     */
    protected ToolResult validateBatchSize(List<Integer> ids) {
        if (ids.isEmpty()) {
            return ToolResult.error("elementIds must be a non-empty array with at most " + MAX_BATCH_SIZE + " elements");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            return ToolResult.error("elementIds exceeds maximum batch size of " + MAX_BATCH_SIZE +
                    ". Got " + ids.size() + " elements.");
        }
        return ToolResult.success("");
    }

    /**
     * Resolve every elementId to its tree node up front (find-all-first). Preserves input
     * order via a {@link LinkedHashMap}. Any id that does not resolve is collected in
     * {@code notFound} so the caller can abort before mutating anything.
     */
    protected static ResolvedNodes resolveNodeMap(JMeterTreeNode root, List<Integer> ids) {
        Map<Integer, JMeterTreeNode> nodeMap = new LinkedHashMap<>();
        List<Integer> notFound = new ArrayList<>();
        for (Integer id : ids) {
            JMeterTreeNode node = JMeterTreeUtils.findNodeByElementId(root, id);
            if (node == null) {
                notFound.add(id);
            } else {
                nodeMap.put(id, node);
            }
        }
        return new ResolvedNodes(nodeMap, notFound);
    }

    /**
     * De-select the current tree node (select row 0) before a batch mutation, to avoid the
     * GUI panel binding a node that is about to be modified off-EDT. Failures are only warned.
     */
    protected static void deselectCurrentNode(GuiPackage guiPackage) {
        Exception err = EdtRunner.run(guiPackage,
                () -> guiPackage.getTreeListener().getJTree().setSelectionRow(0));
        if (err != null) {
            LoggerFactory.getLogger(AbstractBatchJMeterElementTool.class)
                    .warn("Failed to de-select current node before batch operation", err);
        }
    }

    /**
     * Build the aggregated batch result: a header line reporting the verb + success/total
     * counts, an optional header-extra block, then a {@code Details:} section (all succeeded)
     * or {@code Succeeded:}/{@code Failed:} sections (partial). Each per-element line is
     * formatted by {@code detailFmt}.
     *
     * @param verbPast   lower-case past-tense verb, e.g. {@code "updated"}, {@code "deleted"}
     * @param headerExtra optional extra header content (e.g. updated-properties block), may be null
     * @param detailFmt  formats the per-element detail suffix (without the leading "- elementId N: ...")
     */
    protected ToolResult buildBatchResult(List<ElementResult> results, String verbPast,
                                          String headerExtra, Function<ElementResult, String> detailFmt) {
        long successCount = results.stream().filter(r -> r.success).count();
        int total = results.size();

        StringBuilder sb = new StringBuilder();
        String action = (successCount == total ? "Successfully " + verbPast : capitalize(verbPast));
        sb.append(action).append(" ").append(successCount).append(" of ").append(total)
                .append(" elements\n");

        if (headerExtra != null && !headerExtra.isEmpty()) {
            sb.append(headerExtra);
        }

        List<ElementResult> failed = results.stream().filter(r -> !r.success).toList();
        if (failed.isEmpty()) {
            sb.append("\nDetails:\n");
            for (ElementResult r : results) {
                sb.append("- elementId ").append(r.elementId).append(": \"").append(r.elementName)
                        .append("\" - ").append(detailFmt.apply(r)).append("\n");
            }
        } else {
            List<ElementResult> succeeded = results.stream().filter(r -> r.success).toList();
            if (!succeeded.isEmpty()) {
                sb.append("\nSucceeded:\n");
                for (ElementResult r : succeeded) {
                    sb.append("- elementId ").append(r.elementId).append(": \"").append(r.elementName)
                            .append("\" - ").append(detailFmt.apply(r)).append("\n");
                }
            }
            sb.append("\nFailed:\n");
            for (ElementResult r : failed) {
                sb.append("- elementId ").append(r.elementId).append(": \"").append(r.elementName)
                        .append("\" - ").append(r.error != null ? r.error : detailFmt.apply(r)).append("\n");
            }
        }

        return ToolResult.success(sb.toString());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Result of {@link #resolveNodeMap}: resolved nodes (ordered) + ids that could not be found. */
    protected record ResolvedNodes(Map<Integer, JMeterTreeNode> nodeMap, List<Integer> notFound) {
    }

    /** Per-element outcome of a batch operation, used to build the aggregated result. */
    protected static final class ElementResult {
        final int elementId;
        final String elementName;
        final boolean success;
        final String error;
        final String note;

        private ElementResult(int elementId, String elementName, boolean success, String error, String note) {
            this.elementId = elementId;
            this.elementName = elementName;
            this.success = success;
            this.error = error;
            this.note = note;
        }

        /** Element processed successfully. */
        static ElementResult ok(int id, String name) {
            return new ElementResult(id, name, true, null, null);
        }

        /** Element succeeded with an informational note (e.g. "already disabled", "removed with ancestor"). */
        static ElementResult noted(int id, String name, String note) {
            return new ElementResult(id, name, true, null, note);
        }

        /** Element failed; counted separately in the aggregated result. */
        static ElementResult fail(int id, String name, String error) {
            return new ElementResult(id, name, false, error, null);
        }

        boolean hasNote() {
            return note != null;
        }
    }
}
