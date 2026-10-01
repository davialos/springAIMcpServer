package com.springaimcpservercommon.core.display;

import com.springaimcpservercommon.core.guard.PiiType;
import com.springaimcpservercommon.core.json.CanonicalJson;

import java.util.Map;
import java.util.Objects;

/**
 * A rendered, display-safe answer (LLD-06 §8.3): a tree of plain values ready to serialise, whose layout was decided
 * by the backend (template or auto layout) and from which personal data has been removed.
 *
 * <pre>{@code
 * {"version": 1,
 *  "blocks": [
 *    {"type": "text", "text": "Here are the open orders."},
 *    {"type": "fields", "title": "Customer", "items": [{"key": "name", "label": "Name", "format": "text", "value": "Ann"}]},
 *    {"type": "table", "title": "Orders", "columns": [{"key": "id", "label": "Order #", "format": "text"}],
 *     "rows": [["A-1"]], "totalRows": 1, "truncated": false},
 *    {"type": "section", "title": "…", "blocks": [ … ]}],
 *  "redactions": {"EMAIL": 1},
 *  "masked": 0}
 * }</pre>
 *
 * @param tree       the display tree (immutable)
 * @param redactions personal-data values removed, per type
 * @param masked     values masked because of their key or the template
 */
public record StructuredResponse(Map<String, Object> tree, Map<PiiType, Integer> redactions, int masked) {

    /** Copies the tree and counts. */
    @SuppressWarnings("unchecked")
    public StructuredResponse {
        Objects.requireNonNull(tree, "tree");
        tree = (Map<String, Object>) Objects.requireNonNull(CanonicalJson.immutableCopy(tree));
        redactions = Map.copyOf(redactions);
    }

    /**
     * The tree as canonical JSON (ADR-0020).
     *
     * @return JSON text
     */
    public String toJson() {
        return CanonicalJson.write(tree);
    }
}
