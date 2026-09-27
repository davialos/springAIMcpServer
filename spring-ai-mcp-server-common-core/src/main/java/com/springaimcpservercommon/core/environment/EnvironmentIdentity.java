package com.springaimcpservercommon.core.environment;

import java.util.List;
import java.util.Objects;

/**
 * The resolved identity of the running environment (LLD-12 §2).
 *
 * @param tier                resolved tier; a conflict resolves to {@link EnvironmentTier#PROD}
 * @param environmentId       environment id, e.g. {@code orders-prod-eu}
 * @param source              which signal determined the tier
 * @param conflict            {@code true} if an explicit non-prod tier met an active production profile
 *                            (log CRITICAL, audit {@code ENVIRONMENT_CONFLICT})
 * @param matchedProdProfiles active profiles that matched a production pattern
 * @param explanation         safe startup-log text explaining the decision and how to declare the tier
 */
public record EnvironmentIdentity(EnvironmentTier tier, String environmentId, Source source, boolean conflict,
                                  List<String> matchedProdProfiles, String explanation) {

    /** Which signal determined the tier. */
    public enum Source {
        /** {@code dynamic.ai.agent.environment.tier}. */
        EXPLICIT,
        /** An active profile matched a production pattern. */
        PROFILE_HEURISTIC,
        /** Nothing set: UNKNOWN. */
        DEFAULT
    }

    /** Validates components and copies collections. */
    public EnvironmentIdentity {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(explanation, "explanation");
        matchedProdProfiles = List.copyOf(matchedProdProfiles);
        if (conflict && tier != EnvironmentTier.PROD) {
            throw new IllegalArgumentException("a tier conflict must resolve to PROD");
        }
    }

    /**
     * Whether production rules apply (PROD, UNKNOWN, or a conflict).
     *
     * @return {@code true} under production rules
     */
    public boolean productionRules() {
        return tier.productionRules();
    }
}
