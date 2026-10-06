package com.springaimcpservercommon.ruleengine.admin;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** List rows of rules and groups. */
public final class Summaries {

    private Summaries() {
    }

    /**
     * A rule in a list.
     *
     * @param id                 rule id
     * @param organizationId     organization, or {@code null} = whole tenant
     * @param moduleCode         module
     * @param code               rule code
     * @param name               published (or first draft) name
     * @param status             DRAFT, ACTIVE or RETIRED
     * @param expression         published (or first draft) expression
     * @param openRevisionNo     the revision in flight, if any
     * @param openRevisionState  its state, if any
     */
    public record RuleSummary(UUID id, @Nullable UUID organizationId, String moduleCode, String code, String name,
                              String status, String expression, @Nullable Integer openRevisionNo,
                              @Nullable String openRevisionState) {
    }

    /**
     * A group in a list.
     *
     * @param id                 group id
     * @param organizationId     organization, or {@code null} = whole tenant
     * @param moduleCode         module
     * @param code               group code
     * @param name               name
     * @param status             DRAFT, ACTIVE or RETIRED
     * @param policy             evaluation policy
     * @param openRevisionNo     the revision in flight, if any
     * @param openRevisionState  its state, if any
     */
    public record GroupSummary(UUID id, @Nullable UUID organizationId, String moduleCode, String code, String name,
                               String status, String policy, @Nullable Integer openRevisionNo,
                               @Nullable String openRevisionState) {
    }
}
