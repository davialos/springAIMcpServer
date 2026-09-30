package com.springaimcpservercommon.core.environment;

import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Default {@link EnvironmentSafetyPolicy} implementing LLD-12 §2.1 and §2.2.
 *
 * <p><b>Tier resolution</b> (strictest wins):
 * <ol>
 *   <li>explicit {@code environment.tier} is preferred;</li>
 *   <li>active profiles are matched case-insensitively against glob patterns (default
 *       {@code prod, production, live, prd, *-prod}); a match means at least PROD;</li>
 *   <li>explicit DEV/TEST/STAGE plus a production profile is a <em>conflict</em> ⇒ PROD;</li>
 *   <li>nothing set (or an unparseable explicit value) ⇒ UNKNOWN, handled as PROD.</li>
 * </ol>
 * Profiles can only make the tier stricter, never unlock anything.
 *
 * <p><b>Capabilities</b>: data plane, reviewed writes, MCP server and ops views exist in every tier; authoring,
 * introspection, playground and UI config changes are off under production rules unless a still-valid
 * {@link ProductionOverride} lists them; query preview is never available under production rules. The override
 * expiry is evaluated with the injected {@link Clock} on every call.
 */
public final class DefaultEnvironmentSafetyPolicy implements EnvironmentSafetyPolicy {

    /** Default production profile patterns (LLD-12 §6). */
    public static final List<String> DEFAULT_PROD_PROFILE_PATTERNS = List.of("prod", "production", "live", "prd", "*-prod");

    private final List<Pattern> patterns;
    private final @Nullable ProductionOverride override;
    private final Clock clock;

    /**
     * Creates the policy.
     *
     * @param prodProfilePatterns glob patterns ({@code *} and {@code ?} wildcards) for production profiles
     * @param override            production override, if configured (already validated)
     * @param clock               clock for override expiry
     */
    public DefaultEnvironmentSafetyPolicy(List<String> prodProfilePatterns, @Nullable ProductionOverride override,
                                          Clock clock) {

        this.patterns = List.copyOf(prodProfilePatterns).stream().map(DefaultEnvironmentSafetyPolicy::glob).toList();
        this.override = override;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Policy with the default patterns, no override and the UTC system clock.
     *
     * @return the default policy
     */
    public static DefaultEnvironmentSafetyPolicy defaults() {
        return new DefaultEnvironmentSafetyPolicy(DEFAULT_PROD_PROFILE_PATTERNS, null, Clock.systemUTC());
    }

    @Override
    public EnvironmentIdentity identify(EnvironmentSignals signals) {
        List<String> matched = new ArrayList<>();
        for (String profile : signals.activeProfiles()) {
            if (matchesProdPattern(profile)) {
                matched.add(profile);
            }
        }
        boolean prodProfile = !matched.isEmpty();
        String raw = signals.explicitTier();
        Optional<EnvironmentTier> explicit = raw == null || raw.isBlank() ? Optional.empty() : EnvironmentTier.parse(raw);

        EnvironmentTier tier;
        EnvironmentIdentity.Source source;
        boolean conflict = false;
        String explanation;
        if (explicit.isPresent()) {
            source = EnvironmentIdentity.Source.EXPLICIT;
            EnvironmentTier declared = explicit.get();
            if (prodProfile && !declared.productionRules()) {
                tier = EnvironmentTier.PROD;
                conflict = true;
                explanation = "CONFLICT: environment.tier=" + declared + " but production profile(s) " + matched
                        + " are active; treating the environment as PROD (fail closed)";
            } else if (prodProfile && declared == EnvironmentTier.UNKNOWN) {
                tier = EnvironmentTier.PROD;
                explanation = "environment.tier=UNKNOWN and production profile(s) " + matched + " active: PROD";
            } else {
                tier = declared;
                explanation = "environment.tier=" + declared + " (explicit)";
            }
        } else if (prodProfile) {
            tier = EnvironmentTier.PROD;
            source = EnvironmentIdentity.Source.PROFILE_HEURISTIC;
            explanation = "production profile(s) " + matched + " active: PROD"
                    + (raw != null && !raw.isBlank() ? "; the configured environment.tier value is not a valid tier" : "");
        } else {
            tier = EnvironmentTier.UNKNOWN;
            source = raw != null && !raw.isBlank() ? EnvironmentIdentity.Source.EXPLICIT : EnvironmentIdentity.Source.DEFAULT;
            explanation = (raw != null && !raw.isBlank()
                    ? "environment.tier value is not one of DEV, TEST, STAGE, PROD"
                    : "environment.tier is not set")
                    + "; tier UNKNOWN is handled as PROD. Declare dynamic.ai.agent.environment.tier=dev locally";
        }
        return new EnvironmentIdentity(tier, environmentId(signals, tier), source, conflict, matched, explanation);
    }

    @Override
    public boolean isEnabled(Capability capability, EnvironmentIdentity identity) {
        if (!capability.restrictedInProduction()) {
            return true;
        }
        if (!identity.productionRules()) {
            return true;
        }
        if (!capability.overridableInProduction()) {
            return false;
        }
        return override != null && override.enables(capability, clock.instant());
    }

    @Override
    public boolean enabledByOverride(Capability capability, EnvironmentIdentity identity) {
        return capability.restrictedInProduction() && identity.productionRules() && capability.overridableInProduction()
                && override != null && override.enables(capability, clock.instant());
    }

    /**
     * Whether a profile matches one of the production patterns (case-insensitive).
     *
     * @param profile profile name
     * @return {@code true} on a match
     */
    public boolean matchesProdPattern(String profile) {
        String p = profile.trim().toLowerCase(Locale.ROOT);
        for (Pattern pattern : patterns) {
            if (pattern.matcher(p).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The configured override, if any.
     *
     * @return the override
     */
    public Optional<ProductionOverride> override() {
        return Optional.ofNullable(override);
    }

    private static String environmentId(EnvironmentSignals signals, EnvironmentTier tier) {
        String id = signals.environmentId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        String app = signals.applicationName();
        String prefix = app == null || app.isBlank() ? "application" : app;
        return prefix + "-" + tier.name().toLowerCase(Locale.ROOT);
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
