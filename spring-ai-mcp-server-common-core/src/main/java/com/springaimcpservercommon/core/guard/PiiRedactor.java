package com.springaimcpservercommon.core.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Replaces personal and secret data in text with typed placeholders such as {@code [redacted email]} (LLD-06 §8,
 * F-76). Runs every configured {@link PiiDetector}, merges overlapping ranges (the union is redacted, so a range two
 * detectors disagree about is never partly shown) and reports how many values of each type it removed, never the
 * values themselves.
 *
 * <p><b>Fail closed:</b> when a detector throws, the whole text is replaced by {@link #WITHHELD}; showing text that
 * could not be checked is worse than showing nothing.
 *
 * <p>Immutable and thread-safe.
 */
public final class PiiRedactor {

    private static final Logger LOG = LoggerFactory.getLogger(PiiRedactor.class);

    /** Replaces a whole text whose check failed. */
    public static final String WITHHELD = "[content withheld: it could not be checked for personal data]";

    /**
     * Result of redacting a text.
     *
     * @param text     the redacted text
     * @param counts   number of values removed per type (empty when nothing was found)
     * @param withheld whether the whole text was withheld because a detector failed
     */
    public record Redaction(String text, Map<PiiType, Integer> counts, boolean withheld) {

        /** Copies the counts. */
        public Redaction {
            Objects.requireNonNull(text, "text");
            counts = counts.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(counts));
        }

        /**
         * Whether anything was removed.
         *
         * @return {@code true} if the text differs from the input
         */
        public boolean changed() {
            return withheld || !counts.isEmpty();
        }
    }

    /** Thrown by {@link #find(String)} when a detector fails; callers fail closed. */
    public static final class DetectionFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DetectionFailedException(String detector, RuntimeException cause) {
            super("PII detector failed: " + detector, cause);
        }
    }

    private final List<PiiDetector> detectors;

    /**
     * Creates a redactor over the given detectors.
     *
     * @param detectors detectors to run, in order; at least one
     */
    public PiiRedactor(List<? extends PiiDetector> detectors) {
        if (detectors.isEmpty()) {
            throw new IllegalArgumentException("at least one PiiDetector is required");
        }
        this.detectors = List.copyOf(detectors);
    }

    /**
     * Redactor with the built-in {@link RegexPiiDetector} only.
     *
     * @return the default redactor
     */
    public static PiiRedactor defaults() {
        return new PiiRedactor(List.of(new RegexPiiDetector()));
    }

    /**
     * Finds personal data: sorted by start, non-overlapping (overlapping detections are merged into one range that
     * keeps the type of the earliest).
     *
     * @param text text to inspect
     * @return merged matches
     * @throws DetectionFailedException when a detector throws or returns a range outside the text
     */
    public List<PiiMatch> find(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<PiiMatch> all = new ArrayList<>();
        for (PiiDetector detector : detectors) {
            try {
                for (PiiMatch m : detector.detect(text)) {
                    if (m.end() > text.length()) {
                        throw new IllegalStateException("range beyond end of text");
                    }
                    all.add(m);
                }
            } catch (RuntimeException e) {
                LOG.warn("PII detector {} failed; the text is withheld", detector.getClass().getName(), e);
                throw new DetectionFailedException(detector.getClass().getName(), e);
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        all.sort(Comparator.comparingInt(PiiMatch::start).thenComparing(Comparator.comparingInt(PiiMatch::end)
                .reversed()));
        List<PiiMatch> merged = new ArrayList<>();
        PiiMatch current = all.getFirst();
        for (int i = 1; i < all.size(); i++) {
            PiiMatch next = all.get(i);
            if (next.start() < current.end()) {
                current = new PiiMatch(current.type(), current.start(), Math.max(current.end(), next.end()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    /**
     * Whether a text contains any personal data.
     *
     * @param text text to inspect
     * @return {@code true} if something would be redacted (or the check failed)
     */
    public boolean containsPii(String text) {
        try {
            return !find(text).isEmpty();
        } catch (DetectionFailedException e) {
            return true;
        }
    }

    /**
     * Redacts a text.
     *
     * @param text text to redact
     * @return redacted text and counts; {@link #WITHHELD} when a detector failed
     */
    public Redaction redact(String text) {
        List<PiiMatch> matches;
        try {
            matches = find(text);
        } catch (DetectionFailedException e) {
            return new Redaction(WITHHELD, Map.of(), true);
        }
        if (matches.isEmpty()) {
            return new Redaction(text, Map.of(), false);
        }
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        return new Redaction(apply(text, matches, text.length(), counts), counts, false);
    }

    /**
     * Replaces the given (sorted, non-overlapping) matches that end at or before {@code limit} and returns the
     * redacted prefix {@code text[0, limit)}. Shared with {@link StreamingPiiRedactor}.
     */
    static String apply(String text, List<PiiMatch> matches, int limit, Map<PiiType, Integer> counts) {
        StringBuilder sb = new StringBuilder(limit + 16);
        int pos = 0;
        for (PiiMatch m : matches) {
            if (m.end() > limit) {
                break;
            }
            sb.append(text, pos, m.start()).append(m.type().placeholder());
            counts.merge(m.type(), 1, Integer::sum);
            pos = m.end();
        }
        sb.append(text, pos, limit);
        return sb.toString();
    }
}
