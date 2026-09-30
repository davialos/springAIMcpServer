package com.springaimcpservercommon.core.lint;

import java.util.List;

/**
 * SPI: finds personal data in free text so it can be masked before a transcript is stored (F-76, OQ-44). The default
 * is {@link RegexPiiDetector}; a host with a real DLP service or NER model supplies its own bean.
 *
 * <p>Implementations must be fast, thread-safe, side-effect free, and must never log the text or the matches.
 */
@FunctionalInterface
public interface PiiDetector {

    /** A detector that finds nothing. */
    PiiDetector NONE = text -> List.of();

    /**
     * One piece of personal data.
     *
     * @param label what it is, upper snake case, shown in its replacement ({@code [EMAIL]})
     * @param start first character, inclusive
     * @param end   end, exclusive
     */
    record Match(String label, int start, int end) {
        /** Validates the span. */
        public Match {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid span " + start + ".." + end);
            }
        }
    }

    /**
     * Finds personal data.
     *
     * @param text the text
     * @return non-overlapping matches in order of position; empty when there are none
     */
    List<Match> find(String text);
}
