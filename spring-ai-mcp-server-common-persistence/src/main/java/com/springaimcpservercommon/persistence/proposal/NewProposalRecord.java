package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import org.jspecify.annotations.Nullable;

/**
 * One record touched by a proposal.
 *
 * @param entityRef        entity reference ({@code entity:} kind)
 * @param entityId         id of the record as text; {@code null} for CREATE
 * @param beforeValuesJson masked JSON object of the exposed attributes before the change (never sensitive values)
 * @param afterValuesJson  JSON object of the proposed values
 * @param baseVersionKind  kind of the version token the change is based on, if any
 * @param baseVersionValue version token value; set exactly when the kind is set
 */
public record NewProposalRecord(
        CatalogElementRef entityRef,
        @Nullable String entityId,
        @Nullable String beforeValuesJson,
        @Nullable String afterValuesJson,
        @Nullable BaseVersionKind baseVersionKind,
        @Nullable String baseVersionValue) {
}
