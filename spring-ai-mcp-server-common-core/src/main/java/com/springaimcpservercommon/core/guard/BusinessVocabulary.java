package com.springaimcpservercommon.core.guard;

import com.springaimcpservercommon.core.catalog.EffectiveAttribute;
import com.springaimcpservercommon.core.catalog.EffectiveCatalog;
import com.springaimcpservercommon.core.catalog.EffectiveEntity;
import com.springaimcpservercommon.core.catalog.EffectiveOperation;
import com.springaimcpservercommon.core.catalog.RelationDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The business domain as a set of terms, derived from what the host told us about its code (LLD-02, LLD-03): the
 * names, descriptions and keywords of enabled entities, the names and meanings of their enabled attributes, their
 * relation names, and the tool names, descriptions and keywords of enabled operations. Entity names and keywords and
 * operation keywords are <em>strong</em> terms: a direct hit on one of them weighs double.
 *
 * <p>Immutable; built once per catalog generation by {@link BusinessScopeValidator}.
 */
public final class BusinessVocabulary {

    /** Shortest stem that may match another by prefix ("invoic" ~ "invoice"). */
    private static final int MIN_PREFIX = 5;

    private final Set<String> terms;
    private final Set<String> strong;
    private final List<String> topics;

    private BusinessVocabulary(Set<String> terms, Set<String> strong, List<String> topics) {
        this.terms = Set.copyOf(terms);
        this.strong = Set.copyOf(strong);
        this.topics = List.copyOf(topics);
    }

    /**
     * Builds the vocabulary of a catalog.
     *
     * @param catalog effective catalog; disabled entities, attributes and operations are left out
     * @return the vocabulary
     */
    public static BusinessVocabulary of(EffectiveCatalog catalog) {
        Set<String> terms = new HashSet<>();
        Set<String> strong = new HashSet<>();
        List<String> topics = new ArrayList<>();
        for (EffectiveEntity e : catalog.entities().values()) {
            if (!e.enabled()) {
                continue;
            }
            topics.add(e.name());
            addAll(strong, e.name());
            e.keywords().forEach(k -> addAll(strong, k));
            addAll(terms, e.description());
            for (EffectiveAttribute a : e.attributes().values()) {
                if (a.enabled()) {
                    addAll(terms, a.name());
                    addAll(terms, a.meaning());
                }
            }
            for (RelationDescriptor r : e.relations()) {
                addAll(terms, r.name());
            }
        }
        for (EffectiveOperation o : catalog.enabledOperations()) {
            addAll(terms, o.toolName());
            addAll(terms, o.description());
            o.keywords().forEach(k -> addAll(strong, k));
        }
        terms.addAll(strong);
        return new BusinessVocabulary(terms, strong, topics);
    }

    private static void addAll(Set<String> into, String text) {
        into.addAll(Terms.of(text));
    }

    /**
     * Entity names, for telling the caller what the assistant can help with.
     *
     * @return enabled entity names in catalog order
     */
    public List<String> topics() {
        return topics;
    }

    /**
     * Whether the vocabulary is empty (no enabled entity or operation), in which case scope cannot be judged.
     *
     * @return {@code true} if there is nothing to compare with
     */
    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /**
     * How a prompt relates to the domain.
     *
     * @param contentTerms number of content terms in the prompt
     * @param matched      content terms found in the vocabulary (or the extra keywords)
     * @param strongHits   of those, terms that are entity names or keywords
     * @param score        {@code min(1, (matched + strongHits) / contentTerms)}; 1 when there are no content terms
     */
    public record Relevance(int contentTerms, int matched, int strongHits, double score) {
    }

    /**
     * Scores a prompt against the vocabulary plus extra terms.
     *
     * @param prompt        the user's message
     * @param extraKeywords additional in-scope terms (agent topic allow-list, scope keywords); treated as strong
     * @return the relevance
     */
    public Relevance relevance(String prompt, Collection<String> extraKeywords) {
        Set<String> extra = new HashSet<>();
        extraKeywords.forEach(k -> extra.addAll(Terms.of(k)));
        List<String> words = Terms.of(prompt);
        if (words.isEmpty()) {
            return new Relevance(0, 0, 0, 1.0);
        }
        int matched = 0;
        int strongHits = 0;
        for (String w : words) {
            boolean isStrong = matches(w, strong) || matches(w, extra);
            if (isStrong || matches(w, terms)) {
                matched++;
                if (isStrong) {
                    strongHits++;
                }
            }
        }
        double score = Math.min(1.0, (matched + strongHits) / (double) words.size());
        return new Relevance(words.size(), matched, strongHits, score);
    }

    private static boolean matches(String word, Set<String> set) {
        if (set.contains(word)) {
            return true;
        }
        if (word.length() < MIN_PREFIX) {
            return false;
        }
        for (String t : set) {
            if (t.length() >= MIN_PREFIX && (t.startsWith(word) || word.startsWith(t))) {
                return true;
            }
        }
        return false;
    }
}
