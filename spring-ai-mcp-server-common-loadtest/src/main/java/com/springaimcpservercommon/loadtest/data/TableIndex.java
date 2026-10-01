package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.EntityTable;
import com.springaimcpservercommon.loadtest.model.Names;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves names seen in an API (a path segment {@code orders}, a foreign key prefix {@code customer}, an entity
 * name) to a table, combining the project's JPA entities with the database's own metadata. Either source alone is
 * enough; with both, the database decides which tables and columns really exist.
 */
public final class TableIndex {

    /**
     * A resolved table.
     *
     * @param schema   schema, if known
     * @param table    table name
     * @param idColumn single-column primary key, if known
     * @param entity   the JPA entity, if the sources declare one
     * @param db       the database table, if metadata was read
     */
    public record TableRef(@Nullable String schema, String table, @Nullable String idColumn,
                           @Nullable EntityTable entity, @Nullable DbTable db) {
    }

    private final List<EntityTable> entities;
    private final List<DbTable> dbTables;
    private final boolean authoritative;

    /**
     * Creates an index over a live database's metadata, which decides which tables and columns exist.
     *
     * @param entities JPA entities from the source scan
     * @param dbTables tables from JDBC metadata (empty when no database is configured)
     */
    public TableIndex(List<EntityTable> entities, List<DbTable> dbTables) {
        this(entities, dbTables, true);
    }

    /**
     * Creates an index.
     *
     * @param entities      JPA entities from the source scan
     * @param dbTables      tables from JDBC metadata or from the project's DDL scripts
     * @param authoritative {@code true} when {@code dbTables} come from the live database: an entity or column
     *                      it lacks does not exist; {@code false} for tables read from DDL scripts, which may be
     *                      incomplete (Hibernate {@code ddl-auto}, statements the reader skips) and only add facts
     */
    public TableIndex(List<EntityTable> entities, List<DbTable> dbTables, boolean authoritative) {
        this.entities = List.copyOf(entities);
        this.dbTables = List.copyOf(dbTables);
        this.authoritative = authoritative;
    }

    /**
     * Whether the index knows any table at all.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return entities.isEmpty() && dbTables.isEmpty();
    }

    /**
     * Resolves a hint to a table.
     *
     * @param hint entity name, collection name or foreign-key prefix
     * @return the table, if one matches
     */
    public Optional<TableRef> resolve(@Nullable String hint) {
        if (hint == null || hint.isBlank()) {
            return Optional.empty();
        }
        String n = Names.normalize(hint);
        Set<String> forms = new java.util.HashSet<>(List.of(n, Names.singular(n)));
        for (EntityTable e : entities) {
            String en = Names.normalize(e.entityName());
            String tn = Names.normalize(e.table());
            if (forms.contains(en) || forms.contains(tn) || forms.contains(Names.singular(tn))) {
                DbTable db = dbTable(e.schema(), e.table());
                if (authoritative && !dbTables.isEmpty() && db == null) {
                    continue; // entity maps to a table this database does not have
                }
                String id = e.idColumn() != null ? e.idColumn() : db != null ? singlePk(db) : null;
                return Optional.of(new TableRef(db != null ? db.schema() : e.schema(),
                        db != null ? db.name() : e.table(), id, e, db));
            }
        }
        List<DbTable> exact = new ArrayList<>();
        List<DbTable> suffix = new ArrayList<>();
        for (DbTable t : dbTables) {
            String tn = Names.normalize(t.name());
            if (forms.contains(tn) || forms.contains(Names.singular(tn))) {
                exact.add(t);
            } else if (tn.endsWith(Names.singular(n)) || tn.endsWith(n)) {
                suffix.add(t); // tbl_user, app_users
            }
        }
        List<DbTable> pick = !exact.isEmpty() ? exact : suffix.size() == 1 ? suffix : List.of();
        if (pick.isEmpty()) {
            return Optional.empty();
        }
        DbTable t = pick.getFirst();
        return Optional.of(new TableRef(t.schema(), t.name(), singlePk(t), null, t));
    }

    /**
     * Column of a table for a request field name: the entity's mapping first, then a fuzzy match against the
     * database columns ({@code firstName} ↔ {@code first_name}).
     *
     * @param t     table
     * @param field field name
     * @return the column, if found (and present in the database when metadata was read)
     */
    public Optional<String> column(TableRef t, String field) {
        String candidate = null;
        if (t.entity() != null) {
            candidate = t.entity().fieldColumns().get(field);
            if (candidate == null) {
                candidate = t.entity().joinColumns().get(field);
            }
        }
        if (t.db() != null) {
            String wanted = Names.normalize(candidate != null ? candidate : field);
            for (String c : t.db().columns().keySet()) {
                if (Names.normalize(c).equals(wanted)) {
                    return Optional.of(c);
                }
            }
            return authoritative ? Optional.empty() : Optional.ofNullable(candidate);
        }
        return Optional.ofNullable(candidate);
    }

    /**
     * The table a reference field of a resource points to, following the entity relationship graph:
     * {@code ownerId} on {@code Deal} → {@code Deal.owner} is a {@code @ManyToOne AppUser} → {@code users.id}; or,
     * without JPA, the database foreign key of the matching column ({@code author_id → users.id}).
     *
     * @param t     the resource's table
     * @param field request field name ({@code ownerId}, {@code owner_id}, {@code owner})
     * @return the referenced primary key, if the relationship is known
     */
    public Optional<PoolRef> reference(TableRef t, String field) {
        String prefix = field.replaceAll("(?i)[_-]?(id|uuid|guid|key)$", "");
        if (t.entity() != null) {
            for (var e : t.entity().fieldReferences().entrySet()) {
                if (Names.normalize(e.getKey()).equals(Names.normalize(prefix))
                        || Names.normalize(e.getKey()).equals(Names.normalize(field))) {
                    Optional<TableRef> target = resolve(e.getValue());
                    if (target.isPresent() && target.get().idColumn() != null) {
                        return Optional.of(new PoolRef(target.get().schema(), target.get().table(),
                                target.get().idColumn()));
                    }
                    String join = t.entity().joinColumns().get(e.getKey());
                    if (join != null && t.db() != null) {
                        Optional<PoolRef> fk = foreignKey(t.db(), join);
                        if (fk.isPresent()) {
                            return fk;
                        }
                    }
                }
            }
        }
        if (t.db() != null) {
            for (String candidate : List.of(field, Names.snakeCase(field), Names.snakeCase(prefix) + "_id")) {
                Optional<PoolRef> fk = foreignKey(t.db(), candidate);
                if (fk.isPresent()) {
                    return fk;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The one table holding a unique column with this name: {@code {username}} in {@code /profiles/{username}}
     * addresses {@code users.username}, although no table is called {@code profiles}. Ambiguous names (unique in
     * several tables) and primary keys named {@code id} resolve to nothing.
     *
     * @param field path parameter or field name
     * @return the column, if exactly one table has it as a unique key
     */
    public Optional<PoolRef> uniqueOwner(String field) {
        String wanted = Names.normalize(field);
        if (wanted.equals("id")) {
            return Optional.empty();
        }
        java.util.Map<String, PoolRef> found = new java.util.LinkedHashMap<>();
        for (EntityTable e : entities) {
            for (String f : e.uniqueFields()) {
                if (Names.normalize(f).equals(wanted)) {
                    resolve(e.entityName()).flatMap(t -> column(t, f).map(c -> new PoolRef(t.schema(), t.table(), c)))
                            .ifPresent(p -> found.put(p.table().toLowerCase(java.util.Locale.ROOT), p));
                }
            }
        }
        for (DbTable t : dbTables) {
            for (String c : t.uniqueColumns()) {
                if (Names.normalize(c).equals(wanted)) {
                    found.putIfAbsent(t.name().toLowerCase(java.util.Locale.ROOT),
                            new PoolRef(t.schema(), t.name(), c));
                }
            }
        }
        return found.size() == 1 ? Optional.of(found.values().iterator().next()) : Optional.empty();
    }

    private static Optional<PoolRef> foreignKey(DbTable db, String column) {
        for (var e : db.foreignKeys().entrySet()) {
            if (Names.normalize(e.getKey()).equals(Names.normalize(column))) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * What the table says about a request field's values: the column's maximum length and whether it is unique.
     *
     * @param maxLength declared length of a text column, or {@code null}
     * @param unique    the column has a unique constraint (generated values must not repeat)
     */
    public record ColumnFacts(@Nullable Integer maxLength, boolean unique) {
    }

    /**
     * Column facts for a field of a resource, from JPA ({@code @Column(length, unique)}) and/or database
     * metadata (size, unique index); the stricter length wins.
     *
     * @param t     table
     * @param field field name
     * @return facts, if the field maps to a column
     */
    public Optional<ColumnFacts> facts(TableRef t, String field) {
        Integer length = null;
        boolean unique = false;
        boolean found = false;
        if (t.entity() != null) {
            for (String f : t.entity().fieldColumns().keySet()) {
                if (Names.normalize(f).equals(Names.normalize(field))) {
                    length = t.entity().columnLengths().get(f);
                    unique = t.entity().uniqueFields().contains(f);
                    found = true;
                }
            }
        }
        if (t.db() != null) {
            Optional<String> column = column(t, field);
            if (column.isPresent()) {
                Integer size = t.db().columnSizes().get(column.get());
                if (size != null) {
                    length = length == null ? size : Math.min(length, size);
                }
                unique = unique || t.db().uniqueColumns().contains(column.get())
                        && !t.db().primaryKey().contains(column.get());
                found = true;
            }
        }
        return found ? Optional.of(new ColumnFacts(length, unique)) : Optional.empty();
    }

    /**
     * Whether a field of an entity is marked sensitive in the sources.
     *
     * @param t     table
     * @param field field name
     * @return {@code true} when sensitive
     */
    public boolean sensitive(TableRef t, String field) {
        return Names.isSensitive(field) || (t.entity() != null && t.entity().sensitiveFields().contains(field));
    }

    private @Nullable DbTable dbTable(@Nullable String schema, String table) {
        for (DbTable t : dbTables) {
            if (t.name().equalsIgnoreCase(table) && (schema == null || schema.equalsIgnoreCase(t.schema()))) {
                return t;
            }
        }
        return null;
    }

    private static @Nullable String singlePk(DbTable t) {
        if (t.primaryKey().size() == 1) {
            return t.primaryKey().getFirst();
        }
        return t.columns().keySet().stream().filter(c -> c.equalsIgnoreCase("id")).findFirst().orElse(null);
    }
}
