package com.springaimcpservercommon.core.catalog;

import com.springaimcpservercommon.annotations.Classification;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A scanned attribute of a catalog entity ({@code @AiEntityProperty}, LLD-02 §2).
 *
 * <p>Sensitive attributes are part of the catalog (so that masking knows about them) but are never exposed to
 * a model. The classification is kept as declared: {@link Classification#INHERIT} is resolved against the
 * <em>effective</em> entity classification during policy merging.
 *
 * @param ref            {@code attr:<entity class>#<name>}
 * @param name           attribute name as known to the JPA metamodel
 * @param javaType       fully qualified (possibly generic) Java type name
 * @param meaning        plain-English meaning for the LLM (≤ 256 chars)
 * @param sensitive      never sent to the LLM, masked everywhere
 * @param writable       may be edited in a reviewed write proposal (LLD-11)
 * @param classification declared classification, possibly {@link Classification#INHERIT}
 * @param identifier     whether this is (part of) the entity identifier
 * @param rowContextLabel label the value is delivered under when the column is per-record context
 *                       ({@code @AiRowContext}), or {@code null}
 * @param rowContextMaxChars most characters delivered per row; 0 when the column is not row context
 */
public record AttributeDescriptor(CatalogElementRef ref, String name, String javaType, String meaning,
                                  boolean sensitive, boolean writable, Classification classification,
                                  boolean identifier, @Nullable String rowContextLabel, int rowContextMaxChars) {

    /** Creates an attribute that is not row context. */
    public AttributeDescriptor(CatalogElementRef ref, String name, String javaType, String meaning,
                               boolean sensitive, boolean writable, Classification classification,
                               boolean identifier) {
        this(ref, name, javaType, meaning, sensitive, writable, classification, identifier, null, 0);
    }

    /** @return whether the column carries per-record context ({@code @AiRowContext}) */
    public boolean rowContext() {
        return rowContextMaxChars > 0;
    }


    /** Validates components. */
    public AttributeDescriptor {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(meaning, "meaning");
        Objects.requireNonNull(classification, "classification");
        if (rowContextMaxChars < 0 || rowContextMaxChars > 2000) {
            throw new IllegalArgumentException("rowContextMaxChars must be 0..2000");
        }
        if (ref.kind() != CatalogElementRef.Kind.ATTR) {
            throw new IllegalArgumentException("attribute ref must be of kind ATTR: " + ref);
        }
    }

    /**
     * Builds the canonical attribute reference.
     *
     * @param entityClassName fully qualified entity class name
     * @param attributeName   attribute name
     * @return {@code attr:<entity>#<attribute>}
     */
    public static CatalogElementRef refOf(String entityClassName, String attributeName) {
        return new CatalogElementRef(CatalogElementRef.Kind.ATTR, entityClassName + "#" + attributeName);
    }
}
