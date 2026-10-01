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

    /**
     * Creates an index.
     *
     * @param entities JPA entities from the source scan
     * @param dbTables tables from JDBC metadata (empty when no database is configured)
     */
    public TableIndex(List<EntityTable> entities, List<DbTable> dbTables) {
        this.entities = List.copyOf(entities);
        this.dbTables = List.copyOf(dbTables);
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
                if (!dbTables.isEmpty() && db == null) {
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
            return Optional.empty();
        }
        return Optional.ofNullable(candidate);
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
