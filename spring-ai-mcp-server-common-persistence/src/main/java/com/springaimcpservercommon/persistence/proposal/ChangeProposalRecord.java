package com.springaimcpservercommon.persistence.proposal;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.persistence.support.Checks;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One record touched by a proposal ({@code dai_change_proposal_record}); part of the {@link ChangeProposal}
 * aggregate and only changed through it.
 */
@Entity
@Table(name = "dai_change_proposal_record")
public class ChangeProposalRecord {

    @EmbeddedId
    private ChangeProposalRecordId id;

    @MapsId("proposalId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false, updatable = false)
    private ChangeProposal proposal;

    @Column(name = "entity_ref", nullable = false, updatable = false)
    private String entityRef;

    @Column(name = "entity_id", updatable = false)
    private @Nullable String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_values", updatable = false)
    private @Nullable String beforeValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_values")
    private @Nullable String afterValues;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_version_kind", updatable = false)
    private @Nullable BaseVersionKind baseVersionKind;

    @Column(name = "base_version_value", updatable = false)
    private @Nullable String baseVersionValue;

    /** For JPA only. */
    protected ChangeProposalRecord() {
    }

    static ChangeProposalRecord of(ChangeProposal proposal, int seq, NewProposalRecord data) {
        ChangeProposalRecord r = new ChangeProposalRecord();
        r.id = new ChangeProposalRecordId(proposal.getId(), seq);
        r.proposal = proposal;
        CatalogElementRef ref = Checks.required(data.entityRef(), "entityRef");
        if (ref.kind() != CatalogElementRef.Kind.ENTITY) {
            throw new IllegalArgumentException("record entityRef must be an entity: reference: " + ref);
        }
        r.entityRef = ref.toString();
        r.entityId = Checks.optionalText(data.entityId(), "entityId", 512);
        r.beforeValues = Checks.optionalJsonObject(data.beforeValuesJson(), "beforeValuesJson");
        r.afterValues = Checks.optionalJsonObject(data.afterValuesJson(), "afterValuesJson");
        if (r.beforeValues == null && r.afterValues == null) {
            throw new IllegalArgumentException("record " + seq + " needs before or after values");
        }
        r.baseVersionKind = data.baseVersionKind();
        r.baseVersionValue = Checks.optionalText(data.baseVersionValue(), "baseVersionValue", 512);
        if ((r.baseVersionKind == null) != (r.baseVersionValue == null)) {
            throw new IllegalArgumentException("baseVersionKind and baseVersionValue must be set together");
        }
        return r;
    }

    void replaceAfterValues(String afterValuesJson) {
        String canonical = Checks.optionalJsonObject(afterValuesJson, "afterValuesJson");
        if (canonical == null) {
            throw new IllegalArgumentException("afterValuesJson is required");
        }
        afterValues = canonical;
    }

    /** @return record position */
    public int getSeq() {
        return id.getSeq();
    }

    /** @return entity reference */
    public CatalogElementRef getEntityRef() {
        return CatalogElementRef.parse(entityRef);
    }

    /** @return record id as text, if any */
    public @Nullable String getEntityId() {
        return entityId;
    }

    /** @return masked before values (JSON object), if any */
    public @Nullable String getBeforeValuesJson() {
        return beforeValues;
    }

    /** @return proposed values (JSON object), if any */
    public @Nullable String getAfterValuesJson() {
        return afterValues;
    }

    /** @return base version kind, if any */
    public @Nullable BaseVersionKind getBaseVersionKind() {
        return baseVersionKind;
    }

    /** @return base version value, if any */
    public @Nullable String getBaseVersionValue() {
        return baseVersionValue;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChangeProposalRecord other && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
