package com.springaimcpservercommon.core.display;

import java.util.List;

/** A display template is not valid; carries every problem found, each prefixed with its JSON location. */
public final class DisplayTemplateException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The problems found. */
    private final List<String> errors;

    /**
     * Creates the exception.
     *
     * @param errors problems, each prefixed with its location ({@code $.blocks[1].columns[0].path: …})
     */
    public DisplayTemplateException(List<String> errors) {
        super("invalid display template: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * The problems found.
     *
     * @return problems with their locations
     */
    public List<String> errors() {
        return errors;
    }
}
