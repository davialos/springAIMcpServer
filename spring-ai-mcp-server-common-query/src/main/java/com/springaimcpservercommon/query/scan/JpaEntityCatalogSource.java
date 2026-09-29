package com.springaimcpservercommon.query.scan;

import com.springaimcpservercommon.annotations.AiContext;
import com.springaimcpservercommon.annotations.AiEntityProperty;
import com.springaimcpservercommon.annotations.AiQueryConstraints;
import com.springaimcpservercommon.annotations.Classification;
import com.springaimcpservercommon.core.catalog.AttributeDescriptor;
import com.springaimcpservercommon.core.catalog.CatalogElementRef;
import com.springaimcpservercommon.core.catalog.EntityCatalogSource;
import com.springaimcpservercommon.core.catalog.EntityDescriptor;
import com.springaimcpservercommon.core.catalog.RelationDescriptor;
import com.springaimcpservercommon.core.catalog.ScanIssue;
import com.springaimcpservercommon.core.catalog.ScanIssueCode;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link EntityCatalogSource} that walks the host's JPA metamodel and reads {@code @AiContext},
 * {@code @AiEntityProperty} and {@code @AiQueryConstraints} from entity classes (LLD-02 §3.2).
 *
 * <p>Only entities annotated with {@code @AiContext} are included. Attributes without
 * {@code @AiEntityProperty} stay hidden. A single bad entity or attribute is skipped and reported as
 * {@link ScanIssueCode#SCAN_FAILED} — never propagates (fail the feature, not the host).
 */
@NullMarked
public final class JpaEntityCatalogSource implements EntityCatalogSource {

    private static final Logger log = LoggerFactory.getLogger(JpaEntityCatalogSource.class);

    private final EntityManagerFactory entityManagerFactory;
    private final String id;

    /**
     * Creates the source.
     *
     * @param entityManagerFactory the host's entity manager factory
     * @param id                   stable source id (e.g. the persistence-unit name); recorded in each EntityDescriptor
     */
    public JpaEntityCatalogSource(EntityManagerFactory entityManagerFactory, String id) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String sourceId() {
        return id;
    }

    @Override
    public List<EntityDescriptor> scanEntities(Consumer<ScanIssue> issues) {
        List<EntityDescriptor> result = new ArrayList<>();
        for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
            try {
                EntityDescriptor descriptor = scanEntity(entityType, issues);
                if (descriptor != null) {
                    result.add(descriptor);
                }
            } catch (RuntimeException | LinkageError e) {
                String typeName = entityType.getJavaType().getName();
                issues.accept(ScanIssue.ofSubject(ScanIssueCode.SCAN_FAILED, typeName,
                        "scanning entity failed (" + e.getClass().getSimpleName() + "); entity skipped", true));
                log.debug("AI entity scan failed for {}", typeName, e);
            }
        }
        return result;
    }

    private @Nullable EntityDescriptor scanEntity(EntityType<?> entityType, Consumer<ScanIssue> issues) {
        Class<?> javaClass = entityType.getJavaType();
        AiContext ctx = javaClass.getAnnotation(AiContext.class);
        if (ctx == null) {
            return null;
        }
        String javaType = javaClass.getName();
        CatalogElementRef ref = CatalogElementRef.entity(javaType);
        String name = ctx.name().isBlank() ? javaClass.getSimpleName() : ctx.name();
        Classification classification = ctx.classification() == Classification.INHERIT
                ? Classification.INTERNAL : ctx.classification();

        AiQueryConstraints constraints = javaClass.getAnnotation(AiQueryConstraints.class);
        int maxLimit = constraints != null ? Math.max(1, constraints.maxLimit()) : 50;
        List<String> mandatoryFilters = constraints != null ? List.of(constraints.mandatoryFilters()) : List.of();

        List<AttributeDescriptor> attributes = new ArrayList<>();
        List<RelationDescriptor> relations = new ArrayList<>();

        for (Attribute<?, ?> attr : entityType.getAttributes()) {
            try {
                Attribute.PersistentAttributeType type = attr.getPersistentAttributeType();
                boolean isAssociation = type == Attribute.PersistentAttributeType.ONE_TO_ONE
                        || type == Attribute.PersistentAttributeType.ONE_TO_MANY
                        || type == Attribute.PersistentAttributeType.MANY_TO_ONE
                        || type == Attribute.PersistentAttributeType.MANY_TO_MANY;
                if (isAssociation) {
                    RelationDescriptor rel = buildRelation(attr);
                    if (rel != null) {
                        relations.add(rel);
                    }
                } else {
                    AttributeDescriptor attrDesc = buildAttribute(javaType, attr, entityType);
                    if (attrDesc != null) {
                        attributes.add(attrDesc);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                issues.accept(ScanIssue.ofSubject(ScanIssueCode.SCAN_FAILED, javaType + "#" + attr.getName(),
                        "scanning attribute failed (" + e.getClass().getSimpleName() + "); attribute skipped", true));
            }
        }
        attributes.sort(Comparator.comparing(AttributeDescriptor::name));
        relations.sort(Comparator.comparing(RelationDescriptor::name));

        return new EntityDescriptor(ref, javaType, name, ctx.description(),
                List.of(ctx.keywords()), classification, maxLimit, mandatoryFilters,
                attributes, relations, id);
    }

    private @Nullable AttributeDescriptor buildAttribute(String entityType, Attribute<?, ?> attr,
                                                          EntityType<?> entityMeta) {
        Field field = findField(attr.getDeclaringType().getJavaType(), attr.getName());
        if (field == null) {
            return null;
        }
        AiEntityProperty prop = field.getAnnotation(AiEntityProperty.class);
        if (prop == null) {
            return null;
        }
        boolean isId = attr instanceof SingularAttribute<?, ?> sa && sa.isId();
        CatalogElementRef ref = new CatalogElementRef(CatalogElementRef.Kind.ATTR, entityType + "#" + attr.getName());
        return new AttributeDescriptor(ref, attr.getName(), attr.getJavaType().getName(),
                prop.meaning(), prop.sensitive(), prop.writable(), prop.classification(), isId);
    }

    private @Nullable RelationDescriptor buildRelation(Attribute<?, ?> attr) {
        Class<?> targetClass = targetClass(attr);
        if (targetClass == null || targetClass.getAnnotation(AiContext.class) == null) {
            return null;
        }
        RelationDescriptor.Kind kind = switch (attr.getPersistentAttributeType()) {
            case ONE_TO_ONE   -> RelationDescriptor.Kind.ONE_TO_ONE;
            case ONE_TO_MANY  -> RelationDescriptor.Kind.ONE_TO_MANY;
            case MANY_TO_ONE  -> RelationDescriptor.Kind.MANY_TO_ONE;
            case MANY_TO_MANY -> RelationDescriptor.Kind.MANY_TO_MANY;
            default           -> null;
        };
        if (kind == null) {
            return null;
        }
        CatalogElementRef target = CatalogElementRef.entity(targetClass.getName());
        Field field = findField(attr.getDeclaringType().getJavaType(), attr.getName());
        @Nullable String mappedBy = null;
        boolean optional = false;
        if (field != null) {
            mappedBy = switch (attr.getPersistentAttributeType()) {
                case ONE_TO_MANY -> {
                    OneToMany ann = field.getAnnotation(OneToMany.class);
                    yield ann != null && !ann.mappedBy().isBlank() ? ann.mappedBy() : null;
                }
                case ONE_TO_ONE -> {
                    OneToOne ann = field.getAnnotation(OneToOne.class);
                    yield ann != null && !ann.mappedBy().isBlank() ? ann.mappedBy() : null;
                }
                case MANY_TO_MANY -> {
                    ManyToMany ann = field.getAnnotation(ManyToMany.class);
                    yield ann != null && !ann.mappedBy().isBlank() ? ann.mappedBy() : null;
                }
                default -> null;
            };
            optional = switch (attr.getPersistentAttributeType()) {
                case MANY_TO_ONE -> {
                    ManyToOne ann = field.getAnnotation(ManyToOne.class);
                    yield ann == null || ann.optional();
                }
                case ONE_TO_ONE -> {
                    OneToOne ann = field.getAnnotation(OneToOne.class);
                    yield ann == null || ann.optional();
                }
                default -> false;
            };
        }
        return new RelationDescriptor(attr.getName(), kind, target, mappedBy, optional);
    }

    private static @Nullable Class<?> targetClass(Attribute<?, ?> attr) {
        if (attr instanceof PluralAttribute<?, ?, ?> pa) {
            return pa.getElementType().getJavaType();
        }
        return attr.getJavaType();
    }

    private static @Nullable Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
