package com.springaimcpservercommon.persistence.audit;

import com.springaimcpservercommon.core.hash.Sha256;
import com.springaimcpservercommon.persistence.support.CanonicalJson;
import com.springaimcpservercommon.persistence.support.UtcTimes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Canonical serialization of an audit event for its chain hash — <strong>format version 1</strong>. Changing
 * anything here invalidates every stored hash; a new format needs a new version tag and verification of both.
 *
 * <h2>Specification</h2>
 * The canonical form is a JSON array written with {@link CanonicalJson} (no whitespace, strings escaped as specified
 * there, UTF-8), containing exactly these 24 elements in this order:
 * <ol start="0">
 *   <li>the literal string {@code "dai-audit-v1"}</li>
 *   <li>{@code id} — lowercase UUID string</li>
 *   <li>{@code occurred_at} — UTC, {@code yyyy-MM-ddTHH:mm:ss.SSSSSSZ}: always six fractional digits (microseconds,
 *       the precision of {@code timestamptz}; values are truncated to microseconds before they are stored)</li>
 *   <li>{@code chain_id} — string</li>
 *   <li>{@code chain_seq} — JSON integer</li>
 *   <li>{@code category}</li>
 *   <li>{@code action}</li>
 *   <li>{@code plane}</li>
 *   <li>{@code actor_id}</li>
 *   <li>{@code actor_type}</li>
 *   <li>{@code on_behalf_of_id}</li>
 *   <li>{@code workspace_id}</li>
 *   <li>{@code resource_type}</li>
 *   <li>{@code resource_id}</li>
 *   <li>{@code decision}</li>
 *   <li>{@code reason}</li>
 *   <li>{@code environment_id}</li>
 *   <li>{@code trace_id}</li>
 *   <li>{@code turn_id}</li>
 *   <li>{@code tool_invocation_id}</li>
 *   <li>{@code proposal_id}</li>
 *   <li>{@code mcp_session_id}</li>
 *   <li>{@code details} — the JSON object itself (not a string), in canonical form: keys sorted, numbers normalised,
 *       so the text PostgreSQL returns from {@code jsonb} hashes identically to the text that was inserted</li>
 *   <li>{@code prev_hash} — {@code sha256:<hex>} of the previous event, or the chain's genesis hash
 *       {@code Sha256.of(chain_id)} for sequence 1</li>
 * </ol>
 * Enumerations are their constant names; UUIDs are lowercase strings; absent values are JSON {@code null}.
 * {@code hash = "sha256:" + hex(SHA-256(UTF-8(canonical form)))}.
 */
public final class AuditCanonicalForm {

    /** Version tag, element 0 of the canonical form. */
    public static final String VERSION = "dai-audit-v1";

    private AuditCanonicalForm() {
    }

    /**
     * Returns the canonical form of an event (its stored {@code hash} is not part of it).
     *
     * @param e the event
     * @return canonical JSON array text
     */
    public static String canonical(AuditEvent e) {
        List<@Nullable Object> fields = new ArrayList<>(24);
        fields.add(VERSION);
        fields.add(uuid(e.getId()));
        fields.add(UtcTimes.format(e.getOccurredAt()));
        fields.add(e.getChainId());
        fields.add(e.getChainSeq());
        fields.add(e.getCategory().name());
        fields.add(e.getAction());
        fields.add(e.getPlane().name());
        fields.add(uuid(e.getActorId()));
        fields.add(e.getActorType().name());
        fields.add(uuid(e.getOnBehalfOfId()));
        fields.add(uuid(e.getWorkspaceId()));
        fields.add(e.getResourceType());
        fields.add(e.getResourceId());
        fields.add(e.getDecision().name());
        fields.add(e.getReason());
        fields.add(e.getEnvironmentId());
        fields.add(e.getTraceId());
        fields.add(uuid(e.getTurnId()));
        fields.add(uuid(e.getToolInvocationId()));
        fields.add(uuid(e.getProposalId()));
        fields.add(uuid(e.getMcpSessionId()));
        String details = e.getDetailsJson();
        fields.add(details == null ? null : CanonicalJson.parse(details));
        fields.add(e.getPrevHash());
        return CanonicalJson.write(fields);
    }

    /**
     * Computes the chain hash of an event.
     *
     * @param e the event
     * @return {@code sha256:<hex>}
     */
    public static String hash(AuditEvent e) {
        return Sha256.of(canonical(e));
    }

    /**
     * Genesis hash of a chain: the {@code prev_hash} of its first event.
     *
     * @param chainId chain id
     * @return {@code Sha256.of(chainId)}
     */
    public static String genesis(String chainId) {
        return Sha256.of(chainId);
    }

    private static @Nullable String uuid(@Nullable UUID value) {
        return value == null ? null : value.toString();
    }
}
