package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.core.catalog.EffectiveCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Built-in {@link PromptValidator} that rejects prompts unrelated to the host's business (LLD-06 §8, F-76), judged
 * against the domain the host described in code: {@code @AiContext} descriptions and keywords of entities,
 * {@code @AiEntityProperty} meanings, operation intents and keywords, as merged into the effective catalog
 * (LLD-03), plus the agent's topic allow-list and scope keywords.
 *
 * <p>A prompt passes when its {@link BusinessVocabulary.Relevance#score() relevance} reaches the policy's
 * {@code minRelevance}. Prompts with fewer than {@code minTermsToJudge} content words (greetings, thanks,
 * follow-ups like "and the second one?") are not judged, and nothing is judged when the catalog exposes nothing,
 * so the check never blocks a conversation it cannot reason about.
 *
 * <p>The vocabulary is built once per catalog generation and swapped atomically (ADR-0021: recomputed from an
 * immutable snapshot, no shared mutable state). Thread-safe.
 */
public final class BusinessScopeValidator implements PromptValidator {

    private static final int MAX_TOPICS_SHOWN = 6;

    private record Cached(long generation, String policyFingerprint, BusinessVocabulary vocabulary) {
    }

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Override
    public PromptVerdict validate(PromptValidationRequest request) {
        InputValidationPolicy policy = request.policy();
        if (!policy.businessScope()) {
            return PromptVerdict.allow();
        }
        BusinessVocabulary vocabulary = vocabulary(request.catalog().get());
        List<String> extra = new ArrayList<>(policy.scopeKeywords());
        extra.addAll(request.topicAllowList());
        if (vocabulary.isEmpty() && extra.isEmpty()) {
            return PromptVerdict.allow();
        }
        BusinessVocabulary.Relevance r = vocabulary.relevance(request.prompt(), extra);
        if (r.contentTerms() < policy.minTermsToJudge() || r.score() >= policy.minRelevance()) {
            return PromptVerdict.allow();
        }
        return PromptVerdict.reject("off_topic", offTopicMessage(vocabulary, request.topicAllowList()),
                List.of("relevance=" + Math.round(r.score() * 100) + "%"));
    }

    @Override
    public String name() {
        return "business-scope";
    }

    /**
     * The vocabulary of a catalog generation, built on first use and reused until the generation changes.
     *
     * @param catalog the effective catalog
     * @return its vocabulary
     */
    public BusinessVocabulary vocabulary(EffectiveCatalog catalog) {
        Cached c = cache.get();
        if (c != null && c.generation() == catalog.generation()
                && c.policyFingerprint().equals(catalog.policyFingerprint())) {
            return c.vocabulary();
        }
        BusinessVocabulary built = BusinessVocabulary.of(catalog);
        cache.set(new Cached(catalog.generation(), catalog.policyFingerprint(), built));
        return built;
    }

    private static String offTopicMessage(BusinessVocabulary vocabulary, List<String> topicAllowList) {
        List<String> topics = new ArrayList<>(topicAllowList);
        if (topics.isEmpty()) {
            topics.addAll(vocabulary.topics());
        }
        if (topics.isEmpty()) {
            return "Your question does not seem to relate to what this assistant covers. Please ask about the "
                    + "business data and services it supports.";
        }
        String shown = String.join(", ", topics.subList(0, Math.min(MAX_TOPICS_SHOWN, topics.size())));
        return "Your question does not seem to relate to what this assistant covers. It can help with: " + shown
                + (topics.size() > MAX_TOPICS_SHOWN ? " and more." : ".");
    }
}
