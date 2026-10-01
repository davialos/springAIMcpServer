package com.springaimcpservercommon.core.guard;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redacts personal data from an answer that arrives in chunks (a streamed turn, LLD-13) without ever sending part of
 * a value before it can be recognised.
 *
 * <p>Chunks are appended to a pending buffer. Only text that lies at least {@link #HOLD_BACK} characters before the
 * end of the buffer is released, cut at a whitespace boundary and never inside a detected value, so a value split
 * across chunks ({@code "jane.doe@exa"} + {@code "mple.com"}) is complete before any of it can leave. A single
 * unbroken run longer than {@link #MAX_PENDING} characters is released at the hold-back limit to bound memory.
 * {@link #flush()} releases the rest when the stream ends.
 *
 * <p>One instance per stream; not thread-safe (a Reactor pipeline delivers chunks serially).
 */
public final class StreamingPiiRedactor {

    /** Characters kept back from the end of the buffer; longer than any built-in value format. */
    public static final int HOLD_BACK = 128;
    /** Largest pending buffer before text is released without a whitespace boundary. */
    public static final int MAX_PENDING = 8_192;

    private final PiiRedactor redactor;
    private final StringBuilder pending = new StringBuilder();
    private final Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
    private boolean withheld;

    /**
     * Creates a streaming redactor.
     *
     * @param redactor the redactor whose detectors run over the buffer
     */
    public StreamingPiiRedactor(PiiRedactor redactor) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * Adds a chunk and returns the redacted text that is now safe to send (possibly empty).
     *
     * @param chunk next piece of the answer
     * @return text to send now
     */
    public String push(String chunk) {
        if (withheld) {
            return "";
        }
        pending.append(chunk);
        if (pending.length() <= HOLD_BACK) {
            return "";
        }
        int limit = pending.length() - HOLD_BACK;
        int cut = limit;
        while (cut > 0 && !Character.isWhitespace(pending.charAt(cut - 1))) {
            cut--;
        }
        if (cut == 0) {
            if (pending.length() < MAX_PENDING) {
                return "";
            }
            cut = limit;
        }
        String buffer = pending.toString();
        List<PiiMatch> matches;
        try {
            matches = redactor.find(buffer);
        } catch (PiiRedactor.DetectionFailedException e) {
            return withhold();
        }
        for (PiiMatch m : matches) {
            if (m.start() < cut && m.end() > cut) {
                cut = m.start();
                break;
            }
        }
        if (cut <= 0) {
            return "";
        }
        String released = PiiRedactor.apply(buffer, matches, cut, counts);
        pending.delete(0, cut);
        return released;
    }

    /**
     * Releases everything still pending, redacted. Call once when the stream ends.
     *
     * @return the remaining text
     */
    public String flush() {
        if (withheld || pending.isEmpty()) {
            return "";
        }
        String buffer = pending.toString();
        pending.setLength(0);
        List<PiiMatch> matches;
        try {
            matches = redactor.find(buffer);
        } catch (PiiRedactor.DetectionFailedException e) {
            return withhold();
        }
        return PiiRedactor.apply(buffer, matches, buffer.length(), counts);
    }

    /**
     * Values removed so far, per type.
     *
     * @return a snapshot of the counts
     */
    public Map<PiiType, Integer> counts() {
        return counts.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(counts));
    }

    /**
     * Whether the rest of the stream was withheld because a detector failed.
     *
     * @return {@code true} once withheld
     */
    public boolean withheld() {
        return withheld;
    }

    private String withhold() {
        withheld = true;
        pending.setLength(0);
        return " " + PiiRedactor.WITHHELD;
    }
}
