package com.springaimcpservercommon.ai.tool;

import com.springaimcpservercommon.core.json.CanonicalJson;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The standard shape returned to the model by every tool call (LLD-07 §3a).
 *
 * <p>Using a single shape for all tools lets the model distinguish status codes and hints without
 * magic-string parsing. Rules:
 * <ul>
 *   <li>{@code hints} are deterministic (from the catalog, never from an LLM, never containing data rows).</li>
 *   <li>{@code appliedFilters} echoes non-sensitive parameters only; sensitive ones appear as {@code "***"}.</li>
 *   <li>No existence oracle: row-level security filters appear as {@link ToolResultStatus#EMPTY},
 *       not as "hidden rows exist".</li>
 *   <li>{@code error} carries a stable {@code code} and a safe {@code message}, never a stack trace.</li>
 * </ul>
 *
 * @param tool            the tool name
 * @param status          execution outcome
 * @param entity          logical entity name from the catalog (never the physical table); may be {@code null}
 * @param appliedFilters  echo of non-sensitive effective filters applied to the query
 * @param count           number of data rows / items returned
 * @param data            result rows / items (serializable values)
 * @param hints           deterministic hints from the catalog (sibling actions, enum values)
 * @param truncated       whether the result was cut by a row cap or char limit
 * @param hasMore         whether there are more rows on the next page
 * @param nextCursor      opaque cursor for the next page; {@code null} when there is no next page
 * @param errorCode       stable error code (only set when status is {@link ToolResultStatus#ERROR})
 * @param errorMessage    safe, displayable message (only set when status is {@link ToolResultStatus#ERROR})
 * @param proposalId      id of the created proposal (only set when status is {@link ToolResultStatus#PROPOSED})
 */
public record ToolResultEnvelope(
        String tool,
        ToolResultStatus status,
        @Nullable String entity,
        Map<String, Object> appliedFilters,
        int count,
        List<Object> data,
        List<String> hints,
        boolean truncated,
        boolean hasMore,
        @Nullable String nextCursor,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Nullable String proposalId) {

    /** Validates and copies collections. */
    public ToolResultEnvelope {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(status, "status");
        if (count < 0) throw new IllegalArgumentException("count must be >= 0");
        appliedFilters = Collections.unmodifiableMap(new LinkedHashMap<>(appliedFilters));
        data = List.copyOf(data);
        hints = List.copyOf(hints);
    }

    /**
     * Serializes the envelope to a JSON string using {@link CanonicalJson}.
     * This is what the model receives as the tool result.
     *
     * @return JSON string
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tool", tool);
        map.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        if (entity != null) map.put("entity", entity);
        map.put("applied", Map.of("filters", appliedFilters));
        map.put("count", count);
        map.put("data", data);
        if (!hints.isEmpty()) map.put("hints", hints);
        map.put("truncated", truncated);
        map.put("page", pageMap());
        if (errorCode != null) {
            map.put("error", Map.of("code", errorCode, "message", Objects.requireNonNullElse(errorMessage, "")));
        }
        if (proposalId != null) map.put("proposalId", proposalId);
        return CanonicalJson.write(map);
    }

    private Map<String, Object> pageMap() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("hasMore", hasMore);
        page.put("nextCursor", nextCursor);
        return page;
    }

    // ── factory helpers ────────────────────────────────────────────────────────

    /**
     * Creates an envelope for a successful query result.
     *
     * @param tool          tool name
     * @param entity        logical entity name
     * @param rows          result rows
     * @param filters       applied non-sensitive filters
     * @param truncated     whether rows were capped
     * @param hasMore       whether more rows exist
     * @param nextCursor    optional cursor
     * @return the envelope
     */
    public static ToolResultEnvelope ok(String tool, @Nullable String entity, List<Object> rows,
                                         Map<String, Object> filters, boolean truncated,
                                         boolean hasMore, @Nullable String nextCursor) {
        return new ToolResultEnvelope(
                tool,
                rows.isEmpty() ? ToolResultStatus.EMPTY : (truncated ? ToolResultStatus.TRUNCATED : ToolResultStatus.OK),
                entity, filters, rows.size(), rows, List.of(),
                truncated, hasMore, nextCursor, null, null, null);
    }

    /**
     * Creates an envelope for a permission denial.
     *
     * @param tool tool name
     * @return the envelope
     */
    public static ToolResultEnvelope notPermitted(String tool) {
        return new ToolResultEnvelope(tool, ToolResultStatus.NOT_PERMITTED, null,
                Map.of(), 0, List.of(), List.of(), false, false, null, null, null, null);
    }

    /**
     * Creates an envelope for a safe error.
     *
     * @param tool    tool name
     * @param code    stable error code
     * @param message safe message
     * @return the envelope
     */
    public static ToolResultEnvelope error(String tool, String code, String message) {
        return new ToolResultEnvelope(tool, ToolResultStatus.ERROR, null,
                Map.of(), 0, List.of(), List.of(), false, false, null, code, message, null);
    }

    /**
     * Creates an envelope for a created change proposal.
     *
     * @param tool       tool name
     * @param proposalId the proposal id
     * @param summary    human-readable summary for the model
     * @return the envelope
     */
    public static ToolResultEnvelope proposed(String tool, String proposalId, String summary) {
        return new ToolResultEnvelope(tool, ToolResultStatus.PROPOSED, null,
                Map.of(), 0, List.of(), List.of(summary), false, false, null, null, null, proposalId);
    }
}
