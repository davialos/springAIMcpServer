package com.springaimcpservercommon.core.guard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which prompt checks run for an agent and how strict they are (LLD-06 §8). An agent carries its own policy; the host
 * sets a floor ({@code dynamic.ai.agent.guardrails.*}) and the effective policy is the {@link #strictest} of the two,
 * so an agent can tighten the host's settings but never loosen them.
 *
 * @param threatDetection  reject prompts that try to manipulate the assistant or attack the system
 *                         ({@link MaliciousPromptValidator})
 * @param businessScope    reject prompts unrelated to the business domain described by the effective catalog
 *                         ({@link BusinessScopeValidator})
 * @param minRelevance     share of a prompt's content words that must relate to the domain, 0–1
 * @param minTermsToJudge  prompts with fewer content words are not judged for scope (greetings, follow-ups such as
 *                         "and the second one?"); at least 1
 * @param scopeKeywords    extra domain terms that count as in scope besides the catalog's names, descriptions and
 *                         keywords
 */
public record InputValidationPolicy(boolean threatDetection, boolean businessScope, double minRelevance,
                                    int minTermsToJudge, List<String> scopeKeywords) {

    /** Default relevance threshold. */
    public static final double DEFAULT_MIN_RELEVANCE = 0.25;
    /** Default minimum number of content words before scope is judged. */
    public static final int DEFAULT_MIN_TERMS = 2;

    /** No prompt validation. */
    public static final InputValidationPolicy OFF =
            new InputValidationPolicy(false, false, DEFAULT_MIN_RELEVANCE, DEFAULT_MIN_TERMS, List.of());

    /** Validates ranges and copies the keywords. */
    public InputValidationPolicy {
        if (Double.isNaN(minRelevance) || minRelevance < 0 || minRelevance > 1) {
            throw new IllegalArgumentException("minRelevance must be between 0 and 1");
        }
        if (minTermsToJudge < 1) {
            throw new IllegalArgumentException("minTermsToJudge must be >= 1");
        }
        Objects.requireNonNull(scopeKeywords, "scopeKeywords");
        scopeKeywords = List.copyOf(scopeKeywords);
    }

    /**
     * Whether any check is enabled.
     *
     * @return {@code true} if a validator has work to do
     */
    public boolean enabled() {
        return threatDetection || businessScope;
    }

    /**
     * Combines two policies into the stricter one: a check runs if either enables it, the higher relevance and the
     * lower judging threshold apply, and the scope keywords are united.
     *
     * @param other the other policy (typically the host floor)
     * @return the combined policy
     */
    public InputValidationPolicy strictest(InputValidationPolicy other) {
        Set<String> keywords = new LinkedHashSet<>(scopeKeywords);
        keywords.addAll(other.scopeKeywords);
        return new InputValidationPolicy(threatDetection || other.threatDetection,
                businessScope || other.businessScope,
                Math.max(minRelevance, other.minRelevance),
                Math.min(minTermsToJudge, other.minTermsToJudge),
                List.copyOf(keywords));
    }
}
