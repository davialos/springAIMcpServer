package com.springaimcpservercommon.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a public method of a Spring bean as an action that AI agents and MCP clients may use as a tool.
 *
 * <p>The safe default is {@link #readOnly()} {@code = true}: the action runs in a read-only scope where any write
 * is vetoed (ADR-0014). Actions declared {@code readOnly = false} are never executed by a model; calling them
 * creates a change proposal that the user must review and confirm (ADR-0009, LLD-11).
 *
 * <p>Tools always execute through the Spring proxy and as the calling user, so the method's own
 * {@code @PreAuthorize}/{@code @Transactional} semantics still apply (ADR-0008).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AiExposedAction {

    /**
     * What the action accomplishes, for the LLM. Mandatory, at most 1024 characters.
     *
     * @return the intent
     */
    String intent();

    /**
     * {@code true} (default) for reads. {@code false} marks a write: it becomes a proposal-only tool.
     *
     * @return whether the action only reads
     */
    boolean readOnly() default true;

    /**
     * Tool name ({@code ^[a-z][a-z0-9_]{2,63}$}); defaults to the snake_case method name. Keep it stable:
     * evaluations, audit and client configurations key on it.
     *
     * @return the tool name or empty for the default
     */
    String name() default "";

    /**
     * Whether repeating the call with the same arguments has no additional effect (enables result caching).
     *
     * @return whether the action is idempotent
     */
    boolean idempotent() default false;

    /**
     * Domain terms or synonyms for tool search.
     *
     * @return keywords, possibly empty
     */
    String[] keywords() default {};
}
