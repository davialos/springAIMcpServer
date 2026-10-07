package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.Names;
import com.springaimcpservercommon.loadtest.model.ParamLocation;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which request fields draw real values from a database column. Default rules, cautious on purpose:
 * <ol>
 *   <li>explicit bindings ({@code --bind key=table.column} or {@code user.json → bindings}) always win;</li>
 *   <li>identifiers: {@code {id}} → primary key of the collection named before it ({@code /orders/{id}} →
 *       {@code orders.id}); {@code customerId}/{@code customer_id} → primary key of {@code customer(s)};</li>
 *   <li>other path parameters ({@code /articles/{slug}}, {@code /profiles/{username}}) → that column of the
 *       resource's table, else the one table where a column of that name is unique;</li>
 *   <li>query filters of the resource ({@code GET /users?email=}) → the matching column, so searches hit rows;</li>
 *   <li>never a sensitive field (secrets, personal identifiers, CONFIDENTIAL/RESTRICTED classification) unless
 *       explicitly bound: real values are copied into {@code data/real.json}.</li>
 * </ol>
 * Other body fields stay generated (dummy/random), which also avoids unique-constraint collisions on creates.
 */
public final class RealDataBinder {

    /**
     * What the binder needs to know about a field.
     *
     * @param key          field key ({@link FieldKeys})
     * @param name         field name
     * @param location     parameter location, or {@code null} for body fields
     * @param kind         semantic kind
     * @param sensitive    sensitive per discovery
     * @param resourceHint entity/collection the field belongs to, if known
     * @param ownerKey     API id or schema name that owns the field (for {@code owner.name} explicit bindings)
     */
    public record FieldContext(String key, String name, @Nullable ParamLocation location, FieldKind kind,
                               boolean sensitive, @Nullable String resourceHint, String ownerKey) {
    }

    private final TableIndex index;
    private final Map<String, PoolRef> explicit;

    /**
     * Creates a binder.
     *
     * @param index    table index
     * @param explicit explicit bindings: field key, {@code owner.field} or {@code *.field} → pool
     */
    public RealDataBinder(TableIndex index, Map<String, PoolRef> explicit) {
        this.index = index;
        this.explicit = new LinkedHashMap<>(explicit);
    }

    /**
     * Column facts (length, uniqueness) of a field of its resource's table.
     *
     * @param f field
     * @return facts, if the field maps to a column
     */
    public Optional<TableIndex.ColumnFacts> facts(FieldContext f) {
        return index.resolve(f.resourceHint()).flatMap(t -> index.facts(t, f.name()));
    }

    /**
     * A field's binding: the pool and, for a composite foreign key's column, its position in the sampled tuple.
     *
     * @param pool      pool
     * @param component position in a tuple pool, or {@code -1} for a single-column pool
     */
    public record Binding(PoolRef pool, int component) {
    }

    /**
     * Binds a field, with the tuple position for composite foreign keys.
     *
     * @param f field
     * @return the binding, if the field should use real data
     */
    public Optional<Binding> bindDetailed(FieldContext f) {
        if (!f.sensitive() && !index.isEmpty() && explicitFor(f).isEmpty()) {
            Optional<TableIndex.TupleReference> tuple = index.resolve(f.resourceHint())
                    .flatMap(t -> index.tupleReference(t, f.name()));
            if (tuple.isPresent()) {
                return Optional.of(new Binding(tuple.get().pool(), tuple.get().component()));
            }
        }
        return bind(f).map(p -> new Binding(p, -1));
    }

    private Optional<PoolRef> explicitFor(FieldContext f) {
        for (String k : List.of(f.key(), f.ownerKey() + "." + f.name(), "*." + f.name())) {
            PoolRef p = explicit.get(k);
            if (p != null) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * Binds a field.
     *
     * @param f field
     * @return the pool, if the field should use real data
     */
    public Optional<PoolRef> bind(FieldContext f) {
        for (String k : List.of(f.key(), f.ownerKey() + "." + f.name(), "*." + f.name())) {
            PoolRef p = explicit.get(k);
            if (p != null) {
                return Optional.of(p);
            }
        }
        if (f.sensitive() || index.isEmpty()) {
            return Optional.empty();
        }
        if (f.kind().identifier() || f.kind() == FieldKind.CODE && f.location() == ParamLocation.PATH) {
            Optional<PoolRef> id = identifier(f);
            if (id.isPresent() || f.location() != ParamLocation.PATH) {
                return id;
            }
        }
        Optional<PoolRef> naturalKey = naturalKeyReference(f.name());
        if (naturalKey.isPresent()) {
            return naturalKey;
        }
        if (f.location() == ParamLocation.PATH || f.location() == ParamLocation.QUERY && filterable(f.kind())) {
            Optional<PoolRef> own = index.resolve(f.resourceHint()).flatMap(t -> index.sensitive(t, f.name())
                    ? Optional.empty() : index.column(t, f.name()).map(c -> new PoolRef(t.schema(), t.table(), c)));
            if (own.isPresent() || f.location() == ParamLocation.QUERY) {
                return own;
            }
            // a path parameter always addresses a row: {username} → the table where username is unique
            return index.uniqueOwner(f.name());
        }
        return Optional.empty();
    }

    private Optional<PoolRef> identifier(FieldContext f) {
        String n = f.name();
        String normalized = Names.normalize(n);
        if (normalized.equals("id") || normalized.equals("uuid") || f.kind() == FieldKind.CODE) {
            return index.resolve(f.resourceHint()).flatMap(t -> {
                String col = f.kind() == FieldKind.CODE ? index.column(t, n).orElse(null) : t.idColumn();
                return col == null ? Optional.empty() : Optional.of(new PoolRef(t.schema(), t.table(), col));
            });
        }
        // ownerId on Deal → the relationship Deal.owner (JPA) or the owner_user_id foreign key (database)
        Optional<TableIndex.TableRef> own = index.resolve(f.resourceHint());
        if (own.isPresent()) {
            Optional<PoolRef> related = index.reference(own.get(), n);
            if (related.isPresent()) {
                return related;
            }
        }
        // customerId, customer_id, customerUuid → table customer(s), its primary key
        String prefix = n.replaceAll("(?i)[_-]?(id|uuid|guid)$", "");
        Optional<TableIndex.TableRef> target = index.resolve(prefix);
        if (target.isPresent() && target.get().idColumn() != null) {
            TableIndex.TableRef t = target.get();
            return Optional.of(new PoolRef(t.schema(), t.table(), t.idColumn()));
        }
        // Or a column of the field's own resource with that exact name (e.g. orders.external_id).
        return index.resolve(f.resourceHint()).flatMap(t -> index.column(t, n)
                .map(c -> new PoolRef(t.schema(), t.table(), c)));
    }

    /**
     * {@code productSku}, {@code customer_code}: an entity name followed by that entity's own key field is a
     * reference to the entity's primary key, even when the key is not called {@code id}.
     */
    private Optional<PoolRef> naturalKeyReference(String name) {
        String[] words = Names.snakeCase(name).split("_");
        for (int split = words.length - 1; split >= 1; split--) {
            String entity = String.join("", java.util.Arrays.copyOfRange(words, 0, split));
            String key = String.join("", java.util.Arrays.copyOfRange(words, split, words.length));
            Optional<TableIndex.TableRef> t = index.resolve(entity);
            if (t.isEmpty() || t.get().idColumn() == null) {
                continue;
            }
            String idField = t.get().entity() != null && t.get().entity().idField() != null
                    ? t.get().entity().idField() : t.get().idColumn();
            if (Names.normalize(idField).equals(key) || Names.normalize(t.get().idColumn()).equals(key)) {
                return Optional.of(new PoolRef(t.get().schema(), t.get().table(), t.get().idColumn()));
            }
        }
        return Optional.empty();
    }

    private static boolean filterable(FieldKind kind) {
        return switch (kind) {
            case PAGE, PAGE_SIZE, SORT, SEARCH, BOOLEAN, PASSWORD, TOKEN -> false;
            default -> true;
        };
    }
}
