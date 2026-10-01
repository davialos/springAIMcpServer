package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a text column of an entity as <em>context about that particular record</em>: a note, a history, a remark
 * written for people. Whenever a dynamic query returns rows of the entity, the value of this column in each row
 * travels with the row (under {@code _context}, labelled), even when the query did not select it, so the AI and MCP
 * callers know more about that specific entry, not just what the column means in general
 * ({@link AiEntityProperty#meaning()}).
 *
 * <p>Put it on a {@code String} field that is also annotated with {@link AiEntityProperty}. The attribute's
 * governance applies unchanged: a {@link AiEntityProperty#sensitive() sensitive}, disabled or hidden column is never
 * delivered, and neither is one classified above the caller's clearance. The text is stored data, possibly typed by
 * users; the AI is told to treat it as information about the record and never as instructions, and it is cut at
 * {@link #maxChars()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface AiRowContext {

    /**
     * Name the context is shown under, for example {@code "Support notes"}. Defaults to the column's
     * {@link AiEntityProperty#meaning()}.
     *
     * @return the label or empty
     */
    String label() default "";

    /**
     * Most characters delivered per row (50..2000); longer text is cut.
     *
     * @return the limit
     */
    int maxChars() default 500;
}
