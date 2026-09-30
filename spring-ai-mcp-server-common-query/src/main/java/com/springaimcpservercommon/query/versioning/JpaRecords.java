package com.springaimcpservercommon.query.versioning;

import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only access to single records of the host's entities through the JPA metamodel: which entity a catalog reference
 * names, its id and version attributes, and a JPQL projection of some attributes of one record. Plain JPQL over
 * metamodel names (which are trusted, and still checked to be identifiers), so it works with any provider and never
 * loads or modifies an entity.
 */
@NullMarked
final class JpaRecords {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * What is known about one entity.
     *
     * @param jpqlName    the entity name for JPQL
     * @param idName      the single id attribute
     * @param idType      java type of the id
     * @param versionName the {@code @Version} attribute, or {@code null}
     * @param type        the metamodel type
     */
    record Meta(String jpqlName, String idName, Class<?> idType, @Nullable String versionName, EntityType<?> type) {
    }

    /** Result of a projection. */
    sealed interface Row permits Row.Found, Row.Missing, Row.Unsupported {
        /** The record exists; values are in the order of the requested attributes. */
        record Found(Object[] values) implements Row {
        }

        /** No such record. */
        record Missing() implements Row {
        }

        /** The entity, its id type or an attribute cannot be read this way. */
        record Unsupported() implements Row {
        }
    }

    private final EntityManagerFactory emf;

    JpaRecords(EntityManagerFactory emf) {
        this.emf = Objects.requireNonNull(emf, "emf");
    }

    /** The entity a reference names, if it is a mapped entity with a single, readable id attribute. */
    Optional<Meta> meta(CatalogElementRef entity) {
        if (entity.kind() != CatalogElementRef.Kind.ENTITY) {
            return Optional.empty();
        }
        for (EntityType<?> type : emf.getMetamodel().getEntities()) {
            if (!type.getJavaType().getName().equals(entity.value())) {
                continue;
            }
            if (!type.hasSingleIdAttribute() || !IDENTIFIER.matcher(type.getName()).matches()) {
                return Optional.empty();
            }
            String idName = null;
            Class<?> idType = null;
            String versionName = null;
            for (SingularAttribute<?, ?> attribute : type.getSingularAttributes()) {
                if (attribute.isId()) {
                    idName = attribute.getName();
                    idType = attribute.getJavaType();
                } else if (attribute.isVersion()) {
                    versionName = attribute.getName();
                }
            }
            if (idName == null || idType == null || !IDENTIFIER.matcher(idName).matches()
                    || (versionName != null && !IDENTIFIER.matcher(versionName).matches())) {
                return Optional.empty();
            }
            return Optional.of(new Meta(type.getName(), idName, idType, versionName, type));
        }
        return Optional.empty();
    }

    /** Whether the id type can be built from text. */
    static boolean supportedIdType(Class<?> type) {
        return type == Long.class || type == long.class || type == Integer.class || type == int.class
                || type == Short.class || type == short.class || type == String.class || type == UUID.class
                || type == BigInteger.class;
    }

    /** The id in its entity type, or empty when the text is not a valid id of that type. */
    static Optional<Object> parseId(Class<?> type, String text) {
        try {
            if (type == String.class) {
                return Optional.of(text);
            }
            if (type == UUID.class) {
                return Optional.of(UUID.fromString(text));
            }
            if (type == Long.class || type == long.class) {
                return Optional.of(Long.parseLong(text));
            }
            if (type == Integer.class || type == int.class) {
                return Optional.of(Integer.parseInt(text));
            }
            if (type == Short.class || type == short.class) {
                return Optional.of(Short.parseShort(text));
            }
            if (type == BigInteger.class) {
                return Optional.of(new BigInteger(text));
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Whether an attribute is a plain (basic) singular attribute, safe to project.
     *
     * @param meta the entity
     * @param name attribute name
     */
    static boolean isBasic(Meta meta, String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            return false;
        }
        try {
            SingularAttribute<?, ?> attribute = meta.type().getSingularAttribute(name);
            return attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Reads some attributes of one record with a JPQL projection.
     *
     * @param meta       the entity
     * @param entityId   the record's id in text form
     * @param attributes basic attributes to read (checked with {@link #isBasic})
     * @return the values in the order of {@code attributes}
     */
    Row read(Meta meta, String entityId, List<String> attributes) {
        if (!supportedIdType(meta.idType()) || attributes.isEmpty()
                || attributes.stream().anyMatch(a -> !isBasic(meta, a))) {
            return new Row.Unsupported();
        }
        Optional<Object> id = parseId(meta.idType(), entityId);
        if (id.isEmpty()) {
            return new Row.Missing();
        }
        StringBuilder jpql = new StringBuilder("select ");
        for (int i = 0; i < attributes.size(); i++) {
            jpql.append(i == 0 ? "" : ", ").append("e.").append(attributes.get(i));
        }
        jpql.append(" from ").append(meta.jpqlName()).append(" e where e.").append(meta.idName()).append(" = :id");
        EntityManager em = emf.createEntityManager();
        try {
            List<?> rows = em.createQuery(jpql.toString()).setParameter("id", id.get()).setMaxResults(1)
                    .getResultList();
            if (rows.isEmpty()) {
                return new Row.Missing();
            }
            Object first = rows.getFirst();
            return new Row.Found(first instanceof Object[] array ? array : new Object[] {first});
        } finally {
            em.close();
        }
    }
}
