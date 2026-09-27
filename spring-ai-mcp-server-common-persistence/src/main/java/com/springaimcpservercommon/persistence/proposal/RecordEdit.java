package com.springaimcpservercommon.persistence.proposal;

import java.util.Objects;

/**
 * An owner's edit of one record of a proposal: the new proposed values.
 *
 * @param seq             record position in the proposal
 * @param afterValuesJson new proposed values as a JSON object
 */
public record RecordEdit(int seq, String afterValuesJson) {

    /**
     * Validates the components.
     */
    public RecordEdit {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative");
        }
        Objects.requireNonNull(afterValuesJson, "afterValuesJson");
    }
}
