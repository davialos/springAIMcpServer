package com.springaimcpservercommon.autoconfigure;

import com.springaimcpservercommon.core.environment.Capability;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Instant;
import java.util.List;

/**
 * Break-glass override (LLD-12 §2.3), bound under {@code dynamic.ai.agent.environment.production-override}: turns
 * listed capabilities on under production rules for a limited time, for an incident or an urgent change. Off unless
 * {@code capabilities} is set. Every use is logged and audited ({@code PRODUCTION_OVERRIDE_USED}). It expires by
 * itself, without a restart.
 *
 * <p>Example: {@code capabilities=AUTHORING, expires-at=2026-10-01T18:00:00Z, reason=INC-1234}. The expiry may be at
 * most 72 hours after startup. {@code QUERY_PREVIEW} can never be enabled in production.
 *
 * @param capabilities capabilities to enable (AUTHORING, INTROSPECTION, PLAYGROUND, CONFIG_CHANGES_UI)
 * @param expiresAt    mandatory expiry instant (UTC)
 * @param reason       mandatory justification, for example an incident or change id
 */
@ConfigurationProperties(prefix = "dynamic.ai.agent.environment.production-override")
public record DaiProductionOverrideProperties(
        @DefaultValue List<Capability> capabilities,
        @Nullable Instant expiresAt,
        @Nullable String reason) {}
