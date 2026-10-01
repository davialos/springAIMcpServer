package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.mcp.server.McpTransportMode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Top-level {@code @ConfigurationProperties} for the framework, bound under {@code dynamic.ai.agent}.
 *
 * <p>All sub-records use {@link DefaultValue} annotations so a host application does not need to
 * specify any property to get a working default configuration.
 *
 * @param environment     environment identification and safety policy
 * @param security        principal mapping and authorization settings
 * @param mcp             MCP server transport and origin configuration
 * @param query           dynamic query bulkhead settings
 * @param scan            {@code @Ai*} annotation scan settings
 * @param write           reviewed change-proposal settings (LLD-11 §12)
 * @param budget          budget enforcement in the invocation path (F-70, LLD-10 §6)
 * @param review          approval policy for publishing configuration (F-64)
 * @param chat            agent chat endpoint limits (LLD-13)
 * @param conversations   conversation history recording and retention (F-44)
 * @param store           background maintenance of the {@code dynamic_ai} store (LLD-15 §10, LLD-09 §4)
 * @param memory          the model's chat memory (OQ-45)
 * @param model           model provider resilience (OQ-47)
 */
@NullMarked
@ConfigurationProperties(prefix = "dynamic.ai.agent")
public record DaiProperties(
        @DefaultValue Environment environment,
        @DefaultValue Security security,
        @DefaultValue Mcp mcp,
        @DefaultValue Query query,
        @DefaultValue Scan scan,
        @DefaultValue Write write,
        @DefaultValue Budget budget,
        @DefaultValue Review review,
        @DefaultValue Chat chat,
        @DefaultValue Conversations conversations,
        @DefaultValue Store store,
        @DefaultValue Memory memory,
        @DefaultValue Model model) {

    /**
     * Model provider resilience (OQ-47). A provider that fails {@code failureThreshold} calls in a row is skipped for
     * {@code breakerOpenFor}, then probed with one call; agents with a fallback selection fail over to it meanwhile.
     * State is per node.
     *
     * @param failureThreshold consecutive failures that open a provider's breaker (at least 1)
     * @param breakerOpenFor   how long an open breaker refuses calls (positive)
     */
    public record Model(
            @DefaultValue("5") int failureThreshold,
            @DefaultValue("30s") Duration breakerOpenFor) {
        /** Validates the settings. */
        public Model {
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("dynamic.ai.agent.model.failure-threshold must be >= 1");
            }
            if (breakerOpenFor.isNegative() || breakerOpenFor.isZero()) {
                throw new IllegalArgumentException("dynamic.ai.agent.model.breaker-open-for must be positive");
            }
        }
    }

    /**
     * The model's chat memory (OQ-45). With a persistence unit present the memory lives in PostgreSQL so every
     * replica reads the same window (ADR-0021); it holds what the model is shown, redacted like transcripts, and
     * expires on its own clock, independent of conversation history.
     *
     * @param persistent     keep the memory in {@code dai_chat_memory_message}; {@code false} keeps it in this node's
     *                       heap (lost on restart, not shared across replicas)
     * @param retention      how long a memory lives after its last write (positive, at most 3660 days)
     * @param maxStoredChars longest stored message; longer ones are cut
     */
    public record Memory(
            @DefaultValue("true") boolean persistent,
            @DefaultValue("24h") Duration retention,
            @DefaultValue("100000") int maxStoredChars) {
        /** Validates the settings. */
        public Memory {
            if (retention.isNegative() || retention.isZero() || retention.toDays() > 3660) {
                throw new IllegalArgumentException("dynamic.ai.agent.memory.retention must be 1ms..3660d");
            }
            if (maxStoredChars < 100) {
                throw new IllegalArgumentException("dynamic.ai.agent.memory.max-stored-chars must be >= 100");
            }
        }
    }

    /**
     * Store settings. The store uses the host's {@code DataSource} and the {@code dynamic_ai} schema; for a dedicated
     * database, declare your own {@code DaiPersistenceUnit} bean (see the integration guide).
     *
     * @param maintenance    background jobs of this node
     * @param migrate        run the Flyway migrations at startup (default); {@code false} when a DBA applies them
     * @param validateSchema check at startup that the tables match this build's entities (fail fast on drift)
     */
    public record Store(@DefaultValue Maintenance maintenance,
                        @DefaultValue("true") boolean migrate,
                        @DefaultValue("false") boolean validateSchema) {}

    /**
     * Background maintenance (OQ-46). Every node runs it; work that must happen once per cluster is guarded by a
     * PostgreSQL advisory lock or {@code FOR UPDATE SKIP LOCKED}, so no coordination service is needed (ADR-0021).
     *
     * @param enabled              run the jobs on this node; {@code false} leaves partitioning, retention and
     *                             node bookkeeping to the operator
     * @param cron                 Spring cron (six fields, UTC) of the partition maintenance and retention run
     * @param snapshotPollInterval how often a node checks for a newer published generation (1s..5m); also the
     *                             longest a request-path lookup relies on a cached generation
     * @param heartbeatInterval    how often the node reports itself and its applied generation (5s..10m)
     * @param sweepInterval        how often stale approvals, silent nodes and stuck applies are handled (1m..1d)
     * @param nodeRetention        a node silent this long is removed from the cluster view (at least 1m)
     * @param approvalTtl          an approved revision not published within this time becomes stale (LLD-09 §2)
     * @param applyTimeout         a proposal in APPLYING this long is marked failed for the operator to verify
     * @param nodeId               id of this node; default is the host name plus a random suffix
     */
    public record Maintenance(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 17 3 * * *") String cron,
            @DefaultValue("5s") Duration snapshotPollInterval,
            @DefaultValue("15s") Duration heartbeatInterval,
            @DefaultValue("5m") Duration sweepInterval,
            @DefaultValue("24h") Duration nodeRetention,
            @DefaultValue("30d") Duration approvalTtl,
            @DefaultValue("10m") Duration applyTimeout,
            @Nullable String nodeId) {
        /** Validates the settings. */
        public Maintenance {
            if (!org.springframework.scheduling.support.CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException("dynamic.ai.agent.store.maintenance.cron is not a valid cron");
            }
            range(snapshotPollInterval, Duration.ofSeconds(1), Duration.ofMinutes(5), "snapshot-poll-interval");
            range(heartbeatInterval, Duration.ofSeconds(5), Duration.ofMinutes(10), "heartbeat-interval");
            range(sweepInterval, Duration.ofMinutes(1), Duration.ofDays(1), "sweep-interval");
            range(nodeRetention, Duration.ofMinutes(1), Duration.ofDays(365), "node-retention");
            range(approvalTtl, Duration.ofDays(1), Duration.ofDays(3660), "approval-ttl");
            range(applyTimeout, Duration.ofMinutes(1), Duration.ofDays(1), "apply-timeout");
            if (nodeId != null && (nodeId.isBlank() || nodeId.length() > 255)) {
                throw new IllegalArgumentException("dynamic.ai.agent.store.maintenance.node-id must be 1..255 chars");
            }
        }

        private static void range(Duration value, Duration min, Duration max, String name) {
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("dynamic.ai.agent.store.maintenance." + name + " must be "
                        + min + ".." + max);
            }
        }
    }

    /**
     * Conversation history (F-44). Transcripts contain what users typed, so recording is off by default.
     *
     * @param enabled          store the user message and answer of every successful turn, redacted
     * @param retention        how long a conversation is kept after its last activity (positive, at most 3660 days)
     * @param maxStoredChars   longest stored message; longer ones are cut
     * @param purgeInterval    how often expired conversations are deleted (at least one minute); runs whenever
     *                         the store exists so old transcripts never outlive their retention
     * @param eraseMode        what a user's erase does: {@code RETAIN_FOR_AUDIT} (default) hides the conversation from
     *                         the user and the model but keeps its transcript for {@code auditRetention};
     *                         {@code HARD} deletes the messages at once
     * @param auditRetention   how long an erased conversation is kept for audit in {@code RETAIN_FOR_AUDIT} mode
     *                         (positive, at most 3660 days); administrators can purge earlier
     */
    public record Conversations(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("30d") Duration retention,
            @DefaultValue("100000") int maxStoredChars,
            @DefaultValue("15m") Duration purgeInterval,
            @DefaultValue("RETAIN_FOR_AUDIT") EraseMode eraseMode,
            @DefaultValue("90d") Duration auditRetention) {

        /** What a user's erase does to the stored transcript. */
        public enum EraseMode {
            /** Hide from the user and the model; keep the transcript for audit until {@code auditRetention} ends. */
            RETAIN_FOR_AUDIT,
            /** Delete the transcript at once. */
            HARD
        }

        /** Validates the settings. */
        public Conversations {
            if (retention.isNegative() || retention.isZero() || retention.toDays() > 3660) {
                throw new IllegalArgumentException("dynamic.ai.agent.conversations.retention must be 1ms..3660d");
            }
            if (maxStoredChars < 100) {
                throw new IllegalArgumentException("dynamic.ai.agent.conversations.max-stored-chars must be >= 100");
            }
            if (purgeInterval.compareTo(Duration.ofMinutes(1)) < 0) {
                throw new IllegalArgumentException("dynamic.ai.agent.conversations.purge-interval must be >= 1m");
            }
            if (auditRetention.isNegative() || auditRetention.isZero() || auditRetention.toDays() > 3660) {
                throw new IllegalArgumentException("dynamic.ai.agent.conversations.audit-retention must be 1ms..3660d");
            }
        }
    }

    /**
     * Approval policy for configuration revisions.
     *
     * @param requiredApprovals distinct reviewers who must approve a revision before it can be published
     *                          (at least 1; the author can never review their own revision)
     */
    public record Review(@DefaultValue("1") int requiredApprovals) {
        /** Validates the approval count. */
        public Review {
            if (requiredApprovals < 1 || requiredApprovals > 10) {
                throw new IllegalArgumentException("dynamic.ai.agent.review.required-approvals must be 1..10");
            }
        }
    }

    /**
     * Limits of the agent chat endpoints.
     *
     * @param streamIdleTimeout longest silence between two stream events before the stream ends with a
     *                          {@code model-timeout} error event
     * @param maxMessageChars   longest accepted user message, in characters (an agent's own
     *                          {@code maxInputChars} guardrail applies too when it is smaller)
     */
    public record Chat(
            @DefaultValue("20s") Duration streamIdleTimeout,
            @DefaultValue("32000") int maxMessageChars) {
        /** Validates the limits. */
        public Chat {
            if (streamIdleTimeout.isNegative() || streamIdleTimeout.isZero()) {
                throw new IllegalArgumentException("dynamic.ai.agent.chat.stream-idle-timeout must be positive");
            }
            if (maxMessageChars < 1) {
                throw new IllegalArgumentException("dynamic.ai.agent.chat.max-message-chars must be positive");
            }
        }
    }

    /**
     * Budget enforcement settings.
     *
     * @param enforce   refuse turns once a hard budget limit is reached; when {@code false} usage is still
     *                  recorded and shown but nothing is refused
     * @param cacheTtl  how long a per-(workspace, agent, principal) decision is reused, bounding both the
     *                  database load of the check and how stale it can be; {@code 0} disables the cache
     * @param failOpen  allow the turn when the budget store cannot be read ({@code false} refuses it)
     */
    public record Budget(
            @DefaultValue("true") boolean enforce,
            @DefaultValue("10s") Duration cacheTtl,
            @DefaultValue("true") boolean failOpen) {}

    /**
     * Reviewed change-proposal settings (LLD-11 §12).
     *
     * @param retention       how long proposals are kept after reaching a terminal state
     * @param enabled         let tools in PROPOSE mode create proposals (default off: they answer
     *                        {@code writes_disabled}); nothing is ever written to the host without a person's confirm
     * @param proposalTtl     how long a proposal can be reviewed before it expires (1 minute to 30 days)
     * @param requireApprover every proposal also needs a second person's approval (a delete always does)
     * @param maxConcurrentApplies most confirmed proposals applied at the same time on this node (1..100); more get
     *                        429 and can retry (bulkhead, LLD-12)
     * @param requireBaseVersion  an update or delete proposal must carry the version of the record it changes (the
     *                        tool binding names the id argument and the entity is versioned); otherwise it is refused
     * @param captureBeforeValues also store the record's exposed, non-sensitive values (within the caller's
     *                        clearance) as the before-snapshot for the review diff; off by default because the host's
     *                        row-level visibility is not applied when reading them
     */
    public record Write(
            @DefaultValue("7d") Duration retention,
            @DefaultValue("false") boolean enabled,
            @DefaultValue("15m") Duration proposalTtl,
            @DefaultValue("false") boolean requireApprover,
            @DefaultValue("8") int maxConcurrentApplies,
            @DefaultValue("false") boolean requireBaseVersion,
            @DefaultValue("false") boolean captureBeforeValues) {
        /** Validates the settings. */
        public Write {
            if (maxConcurrentApplies < 1 || maxConcurrentApplies > 100) {
                throw new IllegalArgumentException("dynamic.ai.agent.write.max-concurrent-applies must be 1..100");
            }
            if (proposalTtl.compareTo(Duration.ofMinutes(1)) < 0 || proposalTtl.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("dynamic.ai.agent.write.proposal-ttl must be 1m..30d");
            }
            if (retention.isNegative()) {
                throw new IllegalArgumentException("dynamic.ai.agent.write.retention must not be negative");
            }
        }
    }

    /**
     * Environment identification settings.
     *
     * @param tier                 explicit environment tier (DEV, TEST, STAGE, PROD, UNKNOWN)
     * @param id                   optional stable environment identifier
     * @param applicationName      application name used when generating a default environment id
     * @param prodProfilePatterns  Spring profile patterns that imply production rules (glob, case-insensitive)
     */
    public record Environment(
            @DefaultValue("UNKNOWN") String tier,
            @Nullable String id,
            @Nullable String applicationName,
            @DefaultValue({"prod", "production", "live", "prd", "*-prod"}) List<String> prodProfilePatterns) {}

    /**
     * Principal-mapping and authorization settings.
     *
     * @param localIssuer         issuer string used for sessions not backed by an IdP
     * @param issuerOverride      when non-null, overrides the token issuer in all principal records
     * @param groupsClaim         JWT/SAML claim or LDAP attribute that carries the caller's groups
     * @param clearanceClaim      optional claim name for the caller's data classification clearance
     * @param defaultClearance    clearance applied when the claim is absent
     * @param cacheTtl            principal-mapping cache lifetime (≤ 5 minutes)
     * @param cacheMaxEntries     maximum number of cached principals per node
     * @param staticRoleMappings  bootstrap role-mapping rules not stored in the DB
     * @param attributeClaims     attribute name → claim name for ABAC attribute extraction
     */
    public record Security(
            @DefaultValue("local") String localIssuer,
            @Nullable String issuerOverride,
            @DefaultValue("groups") String groupsClaim,
            @Nullable String clearanceClaim,
            @DefaultValue("INTERNAL") Classification defaultClearance,
            @DefaultValue("5m") Duration cacheTtl,
            @DefaultValue("4096") int cacheMaxEntries,
            List<StaticRoleMapping> staticRoleMappings,
            Map<String, String> attributeClaims) {}

    /**
     * A single bootstrap role-mapping rule, equivalent to a {@code dai_role_mapping} row.
     *
     * @param source      where the matched value comes from (AUTHORITY, SCOPE, LDAP_GROUP, OIDC_CLAIM)
     * @param matchValue  glob pattern compared against values from the source
     * @param role        framework role to grant on a match
     * @param claimName   required when source is OIDC_CLAIM
     * @param workspaceId optional; when present the role is scoped to this workspace
     */
    public record StaticRoleMapping(
            String source,
            String matchValue,
            String role,
            @Nullable String claimName,
            java.util.@Nullable UUID workspaceId) {}

    /**
     * MCP server settings.
     *
     * @param transport          transport mode (STATELESS or STATEFUL); default STATELESS (ADR-0021)
     * @param allowedOrigins     Origin header values allowed for browser-based MCP clients (empty = any server-to-server)
     * @param resourceUri        RFC 9728 resource URI advertised in the protected-resource metadata endpoint
     * @param authorizationServers authorization server URIs included in the protected-resource metadata
     * @param enabled               serve the MCP endpoint (default off, LLD-07 §5); needs the store and at least one
     *                              published tool binding with {@code mcpExposed}
     * @param workspaceId           workspace served when a request names none ({@code X-DAI-Workspace}); unset requires
     *                              the header
     * @param maxRequestBytes       largest accepted request body (1 KiB..16 MiB)
     * @param requireApprovedClient only tokens of MCP clients approved for the workspace are served (LLD-07 §5.3);
     *                              API-key service accounts of the workspace are always allowed
     */
    public record Mcp(
            @DefaultValue("STATELESS") McpTransportMode transport,
            @DefaultValue List<String> allowedOrigins,
            @Nullable String resourceUri,
            @DefaultValue List<String> authorizationServers,
            @DefaultValue("false") boolean enabled,
            @Nullable UUID workspaceId,
            @DefaultValue("1048576") int maxRequestBytes,
            @DefaultValue("true") boolean requireApprovedClient) {
        /** Validates the settings. */
        public Mcp {
            if (maxRequestBytes < 1024 || maxRequestBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("dynamic.ai.agent.mcp.max-request-bytes must be 1 KiB..16 MiB");
            }
        }
    }

    /**
     * Dynamic query engine settings.
     *
     * @param maxConcurrency maximum concurrent JPA queries per node (bulkhead size)
     * @param timeout        maximum query execution time before the bulkhead times out
     * @param aiCriteria     whether {@code criteria} tool bindings work (model-built read queries, LLD-05 §12); they
     *                       still need a published binding and a grant, like every tool. {@code false} skips them all
     */
    public record Query(
            @DefaultValue("20") int maxConcurrency,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("true") boolean aiCriteria) {}

    /**
     * {@code @Ai*} annotation scan settings ({@code dynamic.ai.agent.scan.*}).
     *
     * @param basePackages           packages to scan for {@code @AiExposedAction} and {@code @AiContext}; empty =
     *                               use the host's auto-configuration base packages (recommended)
     * @param strict                 exclude unbounded list-returning actions when {@code true}
     * @param outcomeActionThreshold emit a {@code CONSIDER_OUTCOME_ACTION} hint when an entity has more actions
     *                               than this threshold
     */
    public record Scan(
            @DefaultValue List<String> basePackages,
            @DefaultValue("false") boolean strict,
            @DefaultValue("8") int outcomeActionThreshold) {}
}
