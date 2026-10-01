package com.springaimcpservercommon.loadtest.model;

/**
 * Where a request parameter travels.
 */
public enum ParamLocation {
    /** A URI template variable, e.g. {@code {id}}. */
    PATH,
    /** A query-string parameter. */
    QUERY,
    /** A request header. */
    HEADER;

    /**
     * Key segment used in generated field keys ({@code <apiId>.<segment>.<name>}).
     *
     * @return lower-case segment
     */
    public String segment() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
