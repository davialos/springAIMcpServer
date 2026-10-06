package com.springaimcpservercommon.loadtest.schema;

import com.springaimcpservercommon.loadtest.data.DatabaseSampler;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Column;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Constraint;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.ConstraintType;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.EnumType;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Extension;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Identity;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Index;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Kind;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Name;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Sequence;
import com.springaimcpservercommon.loadtest.schema.SchemaSnapshot.Table;
import org.jspecify.annotations.Nullable;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the current structure of a database into a {@link SchemaSnapshot}: the live state, not the migration
 * scripts that were meant to produce it.
 * <p>
 * On PostgreSQL 12+ the system catalogs are queried and the server's own formatting functions
 * ({@code pg_get_constraintdef}, {@code pg_get_indexdef}, {@code pg_get_viewdef}, {@code format_type}) produce the
 * definitions, so check constraints, partitioning, identity columns, generated columns, enum types, views and
 * sequences are exact. Any other database is read through standard JDBC metadata (tables, views, columns, keys,
 * indexes), which cannot see check constraints or view queries; the snapshot says so in its notes.
 * <p>
 * The connection is switched to read-only, every statement has a timeout (release-it: bounded calls), identifiers
 * are quoted with {@link SqlNames} and only structure is read: row data never is, except the optional exact
 * {@code count(*)} per table. System schemas, the library's own {@code dynamic_ai} schema, extension-owned objects
 * and migration history tables are left out.
 */
public final class SchemaReader {

    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final int MIN_POSTGRES_MAJOR = 12;

    /**
     * What to read.
     *
     * @param schema           narrow to one schema, or {@code null} for every non-system schema
     * @param rowCounts        also run an exact {@code count(*)} per table (can be slow on big tables)
     * @param jdbcMetadataOnly never use the PostgreSQL catalogs, even on PostgreSQL
     */
    public record Options(@Nullable String schema, boolean rowCounts, boolean jdbcMetadataOnly) {

        /**
         * Every non-system schema, no row counts, catalogs when available.
         *
         * @return the defaults
         */
        public static Options defaults() {
            return new Options(null, false, false);
        }

        /**
         * One schema, no row counts.
         *
         * @param schema schema name, or {@code null} for all
         * @return options
         */
        public static Options schema(@Nullable String schema) {
            return new Options(schema, false, false);
        }

        /**
         * The same options with row counts on or off.
         *
         * @param on whether to count rows
         * @return a copy
         */
        public Options withRowCounts(boolean on) {
            return new Options(schema, on, jdbcMetadataOnly);
        }
    }

    private final Connection connection;
    private final Options options;
    private final List<String> notes = new ArrayList<>();

    private SchemaReader(Connection connection, Options options) {
        this.connection = connection;
        this.options = options;
    }

    /**
     * Opens a connection with {@link DriverManager}, reads the schema and closes the connection.
     *
     * @param url      JDBC URL
     * @param user     user name, or {@code null}
     * @param password password, or {@code null}
     * @param options  what to read
     * @return the snapshot
     * @throws SQLException when connecting or reading fails
     */
    public static SchemaSnapshot read(String url, @Nullable String user, @Nullable String password,
                                      Options options) throws SQLException {
        DriverManager.setLoginTimeout(10);
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            return read(c, options);
        }
    }

    /**
     * Reads the schema over an open connection (left open). The connection is read-only while reading and its
     * previous mode is restored.
     *
     * @param connection JDBC connection
     * @param options    what to read
     * @return the snapshot
     * @throws SQLException when reading fails
     */
    public static SchemaSnapshot read(Connection connection, Options options) throws SQLException {
        boolean wasReadOnly = connection.isReadOnly();
        connection.setReadOnly(true);
        try {
            return new SchemaReader(connection, options).read();
        } finally {
            try {
                connection.setReadOnly(wasReadOnly);
            } catch (SQLException ignored) {
                // the connection is going away or does not support toggling; nothing to restore
            }
        }
    }

    private SchemaSnapshot read() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        String product = md.getDatabaseProductName();
        boolean postgres = product.toLowerCase(Locale.ROOT).contains("postgres");
        boolean catalogs = postgres && !options.jdbcMetadataOnly() && md.getDatabaseMajorVersion() >= MIN_POSTGRES_MAJOR;
        if (postgres && !catalogs && !options.jdbcMetadataOnly()) {
            notes.add("PostgreSQL " + md.getDatabaseMajorVersion() + " is older than " + MIN_POSTGRES_MAJOR
                    + ": read through JDBC metadata");
        }
        String quote = md.getIdentifierQuoteString();
        quote = quote == null || quote.isBlank() ? "" : quote.trim();
        boolean upper = md.storesUpperCaseIdentifiers();
        Content content = catalogs ? readCatalogs() : readMetadata(md);
        List<Table> tables = content.tables;
        if (options.rowCounts()) {
            tables = countRows(tables, new SqlNames(quote, upper));
        }
        return new SchemaSnapshot(product, md.getDatabaseProductVersion(), options.schema(), Instant.now(), quote,
                upper, content.extensions, content.schemas, content.enums, content.sequences, tables, notes);
    }

    private record Content(List<Extension> extensions, List<String> schemas, List<EnumType> enums,
                           List<Sequence> sequences, List<Table> tables) {
    }

    // ── PostgreSQL catalogs ────────────────────────────────────────────────────────────────────────────

    private Content readCatalogs() throws SQLException {
        String previousPath = scalar("SELECT current_setting('search_path')");
        // Catalog formatting functions print names relative to the search_path; with only pg_catalog on it every
        // object is schema-qualified, so the DDL replays under any search_path.
        exec("SELECT set_config('search_path', 'pg_catalog', false)");
        try {
            List<String> schemas = includedSchemas();
            if (schemas.isEmpty()) {
                notes.add(options.schema() == null ? "no user schemas found"
                        : "schema " + options.schema() + " not found");
                return new Content(List.of(), List.of(), List.of(), List.of(), List.of());
            }
            Array schemaArray = connection.createArrayOf("text", schemas.toArray());
            Map<Long, TableBuilder> relations = readRelations(schemaArray);
            Array oids = connection.createArrayOf("bigint", relations.keySet().toArray());
            readColumns(oids, relations);
            readConstraints(oids, relations);
            readIndexes(oids, relations);
            List<Sequence> sequences = readSequences(schemaArray);
            return new Content(readExtensions(), schemas, readEnums(schemaArray), sequences,
                    order(relations, viewEdges(oids)));
        } finally {
            try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
                ps.setString(1, previousPath);
                ps.execute();
            }
        }
    }

    private List<String> includedSchemas() throws SQLException {
        List<String> all = new ArrayList<>();
        try (Statement st = statement(); ResultSet rs = st.executeQuery(
                "SELECT nspname FROM pg_namespace WHERE nspname <> 'information_schema' ORDER BY nspname")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!DatabaseSampler.isSystemSchema(name)) {
                    all.add(name);
                }
            }
        }
        String wanted = options.schema();
        if (wanted == null) {
            return all;
        }
        List<String> exact = all.stream().filter(s -> s.equals(wanted)).toList();
        return exact.isEmpty() ? all.stream().filter(s -> s.equalsIgnoreCase(wanted)).toList() : exact;
    }

    private List<Extension> readExtensions() throws SQLException {
        List<Extension> out = new ArrayList<>();
        try (Statement st = statement(); ResultSet rs = st.executeQuery(
                "SELECT e.extname, n.nspname, e.extversion FROM pg_extension e "
                        + "JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname <> 'plpgsql' "
                        + "ORDER BY e.extname")) {
            while (rs.next()) {
                out.add(new Extension(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return out;
    }

    private List<EnumType> readEnums(Array schemas) throws SQLException {
        List<EnumType> out = new ArrayList<>();
        String sql = "SELECT n.nspname, t.typname, array_agg(e.enumlabel::text ORDER BY e.enumsortorder) "
                + "FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid JOIN pg_namespace n ON n.oid = t.typnamespace "
                + "WHERE n.nspname = ANY (?) GROUP BY n.nspname, t.typname ORDER BY n.nspname, t.typname";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, schemas);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EnumType(rs.getString(1), rs.getString(2), strings(rs.getArray(3))));
                }
            }
        }
        return out;
    }

    private List<Sequence> readSequences(Array schemas) throws SQLException {
        List<Sequence> out = new ArrayList<>();
        // identity columns own their sequence implicitly (GENERATED … AS IDENTITY recreates it): skip those
        String sql = "SELECT n.nspname, c.relname, format_type(s.seqtypid, NULL), s.seqstart, s.seqincrement, "
                + "s.seqmin, s.seqmax, s.seqcache, s.seqcycle, o.nspname, o.relname, o.attname::text "
                + "FROM pg_sequence s JOIN pg_class c ON c.oid = s.seqrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "LEFT JOIN LATERAL (SELECT tn.nspname, t.relname, a.attname FROM pg_depend d "
                + "  JOIN pg_class t ON t.oid = d.refobjid JOIN pg_namespace tn ON tn.oid = t.relnamespace "
                + "  JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid "
                + "  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid "
                + "  AND d.refclassid = 'pg_class'::regclass AND d.deptype = 'a') o ON true "
                + "WHERE n.nspname = ANY (?) AND NOT EXISTS (SELECT 1 FROM pg_depend d "
                + "  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('i', 'e')) "
                + "ORDER BY n.nspname, c.relname";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, schemas);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ownerTable = rs.getString(11);
                    out.add(new Sequence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getBoolean(9),
                            ownerTable == null ? null : new Name(rs.getString(10), ownerTable), rs.getString(12)));
                }
            }
        }
        return out;
    }

    /** A table under construction (the snapshot's records are immutable). */
    private static final class TableBuilder {
        final long oid;
        final @Nullable String schema;
        final String name;
        final Kind kind;
        final @Nullable String comment;
        final @Nullable String partitionKey;
        final @Nullable Name partitionOf;
        final @Nullable String partitionBound;
        final @Nullable String viewDefinition;
        final List<Column> columns = new ArrayList<>();
        final List<Constraint> constraints = new ArrayList<>();
        final List<Index> indexes = new ArrayList<>();

        TableBuilder(long oid, @Nullable String schema, String name, Kind kind, @Nullable String comment,
                     @Nullable String partitionKey, @Nullable Name partitionOf, @Nullable String partitionBound,
                     @Nullable String viewDefinition) {
            this.oid = oid;
            this.schema = schema;
            this.name = name;
            this.kind = kind;
            this.comment = comment;
            this.partitionKey = partitionKey;
            this.partitionOf = partitionOf;
            this.partitionBound = partitionBound;
            this.viewDefinition = viewDefinition;
        }

        Table build() {
            return new Table(schema, name, kind, columns, constraints, indexes, partitionKey, partitionOf,
                    partitionBound, viewDefinition, comment, null);
        }
    }

    private Map<Long, TableBuilder> readRelations(Array schemas) throws SQLException {
        Map<Long, TableBuilder> out = new LinkedHashMap<>();
        String sql = "SELECT c.oid::bigint, n.nspname, c.relname, c.relkind::text, obj_description(c.oid, 'pg_class'), "
                + "CASE WHEN c.relkind = 'p' THEN pg_get_partkeydef(c.oid) END, pn.nspname, pc.relname, "
                + "CASE WHEN c.relispartition THEN pg_get_expr(c.relpartbound, c.oid) END, "
                + "CASE WHEN c.relkind IN ('v', 'm') THEN pg_get_viewdef(c.oid, true) END "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "LEFT JOIN pg_inherits i ON i.inhrelid = c.oid AND c.relispartition "
                + "LEFT JOIN pg_class pc ON pc.oid = i.inhparent LEFT JOIN pg_namespace pn ON pn.oid = pc.relnamespace "
                + "WHERE n.nspname = ANY (?) AND c.relkind IN ('r', 'p', 'v', 'm') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass "
                + "  AND d.objid = c.oid AND d.deptype = 'e') "
                + "ORDER BY n.nspname, c.relname";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, schemas);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(3);
                    if (DatabaseSampler.isMigrationHistory(table)) {
                        continue;
                    }
                    Kind kind = switch (rs.getString(4)) {
                        case "p" -> Kind.PARTITIONED_TABLE;
                        case "v" -> Kind.VIEW;
                        case "m" -> Kind.MATERIALIZED_VIEW;
                        default -> Kind.TABLE;
                    };
                    String parent = rs.getString(8);
                    out.put(rs.getLong(1), new TableBuilder(rs.getLong(1), rs.getString(2), table, kind,
                            rs.getString(5), rs.getString(6), parent == null ? null : new Name(rs.getString(7), parent),
                            rs.getString(9), viewQuery(rs.getString(10))));
                }
            }
        }
        return out;
    }

    private static @Nullable String viewQuery(@Nullable String definition) {
        if (definition == null) {
            return null;
        }
        String d = definition.strip();
        return d.endsWith(";") ? d.substring(0, d.length() - 1).stripTrailing() : d;
    }

    private void readColumns(Array oids, Map<Long, TableBuilder> relations) throws SQLException {
        String sql = "SELECT a.attrelid::bigint, a.attname::text, format_type(a.atttypid, a.atttypmod), a.attnotnull, "
                + "a.attidentity::text, a.attgenerated::text, pg_get_expr(d.adbin, d.adrelid), "
                + "col_description(a.attrelid, a.attnum) "
                + "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                + "WHERE a.attrelid::bigint = ANY (?) AND a.attnum > 0 AND NOT a.attisdropped "
                + "ORDER BY a.attrelid, a.attnum";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, oids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableBuilder t = relations.get(rs.getLong(1));
                    String identity = rs.getString(5);
                    String generated = rs.getString(6);
                    String expression = rs.getString(7);
                    boolean isGenerated = !generated.isEmpty();
                    t.columns.add(new Column(rs.getString(2), rs.getString(3), !rs.getBoolean(4),
                            isGenerated ? null : expression,
                            identity.equals("a") ? Identity.ALWAYS : identity.equals("d") ? Identity.BY_DEFAULT : null,
                            isGenerated ? expression : null, generated.equals("v"), rs.getString(8)));
                }
            }
        }
    }

    private void readConstraints(Array oids, Map<Long, TableBuilder> relations) throws SQLException {
        // conparentid <> 0: inherited from a partitioned parent, recreated by the parent's own constraint
        String sql = "SELECT c.conrelid::bigint, c.conname::text, c.contype::text, pg_get_constraintdef(c.oid, true), "
                + "(SELECT array_agg(a.attname::text ORDER BY k.ord) FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord) "
                + "  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum), "
                + "rn.nspname, rc.relname "
                + "FROM pg_constraint c LEFT JOIN pg_class rc ON rc.oid = c.confrelid AND c.contype = 'f' "
                + "LEFT JOIN pg_namespace rn ON rn.oid = rc.relnamespace "
                + "WHERE c.conrelid::bigint = ANY (?) AND c.conparentid = 0 AND c.contype IN ('p', 'u', 'c', 'x', 'f') "
                + "ORDER BY c.conrelid, c.contype, c.conname";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, oids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ConstraintType type = switch (rs.getString(3)) {
                        case "p" -> ConstraintType.PRIMARY_KEY;
                        case "u" -> ConstraintType.UNIQUE;
                        case "c" -> ConstraintType.CHECK;
                        case "x" -> ConstraintType.EXCLUDE;
                        default -> ConstraintType.FOREIGN_KEY;
                    };
                    String target = rs.getString(7);
                    relations.get(rs.getLong(1)).constraints.add(new Constraint(rs.getString(2), type,
                            strings(rs.getArray(5)), rs.getString(4),
                            target == null ? null : new Name(rs.getString(6), target)));
                }
            }
        }
    }

    private void readIndexes(Array oids, Map<Long, TableBuilder> relations) throws SQLException {
        // Indexes behind a primary key / unique / exclusion constraint come back as that constraint; indexes of a
        // partition that its parent's index created are recreated by the parent's.
        String sql = "SELECT i.indrelid::bigint, ic.relname::text, pg_get_indexdef(i.indexrelid), i.indisunique, "
                + "(SELECT array_agg(a.attname::text ORDER BY k.ord) FROM unnest(i.indkey::int2[]) WITH ORDINALITY "
                + "  k(attnum, ord) JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) "
                + "FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                + "WHERE i.indrelid::bigint = ANY (?) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid "
                + "  AND k.conrelid = i.indrelid AND k.contype IN ('p', 'u', 'x')) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_inherits h WHERE h.inhrelid = i.indexrelid) "
                + "ORDER BY i.indrelid, ic.relname";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, oids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    relations.get(rs.getLong(1)).indexes.add(new Index(rs.getString(2), rs.getBoolean(4),
                            strings(rs.getArray(5)), rs.getString(3)));
                }
            }
        }
    }

    /** View → the relations its query reads (only views and materialized views matter for ordering). */
    private Map<Long, Set<Long>> viewEdges(Array oids) throws SQLException {
        Map<Long, Set<Long>> edges = new HashMap<>();
        String sql = "SELECT DISTINCT v.oid::bigint, d.refobjid::bigint FROM pg_rewrite r "
                + "JOIN pg_class v ON v.oid = r.ev_class JOIN pg_depend d ON d.objid = r.oid "
                + "AND d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass "
                + "AND d.refobjid <> v.oid WHERE v.relkind IN ('v', 'm') AND v.oid::bigint = ANY (?)";
        try (PreparedStatement ps = prepared(sql)) {
            ps.setArray(1, oids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    edges.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getLong(2));
                }
            }
        }
        return edges;
    }

    /** Creation order: tables (parents before partitions), then views (dependencies first). */
    private static List<Table> order(Map<Long, TableBuilder> relations, Map<Long, Set<Long>> viewDeps) {
        Comparator<TableBuilder> byName = Comparator.comparing((TableBuilder t) -> t.schema == null ? "" : t.schema)
                .thenComparing(t -> t.name);
        Map<String, TableBuilder> byQualified = new HashMap<>();
        relations.values().forEach(t -> byQualified.put(key(t.schema, t.name), t));
        List<TableBuilder> tables = new ArrayList<>();
        List<TableBuilder> views = new ArrayList<>();
        for (TableBuilder t : relations.values()) {
            (t.kind == Kind.VIEW || t.kind == Kind.MATERIALIZED_VIEW ? views : tables).add(t);
        }
        Map<TableBuilder, Integer> depth = new HashMap<>();
        for (TableBuilder t : tables) {
            int d = 0;
            TableBuilder p = t;
            while (p.partitionOf != null && d < 64) {
                p = byQualified.get(key(p.partitionOf.schema(), p.partitionOf.name()));
                if (p == null) {
                    break;
                }
                d++;
            }
            depth.put(t, d);
        }
        tables.sort(Comparator.<TableBuilder>comparingInt(depth::get).thenComparing(byName));
        views.sort(byName);
        List<Table> out = new ArrayList<>();
        tables.forEach(t -> out.add(t.build()));
        Set<Long> placed = new HashSet<>();
        Set<Long> viewOids = new HashSet<>();
        views.forEach(v -> viewOids.add(v.oid));
        List<TableBuilder> pending = new ArrayList<>(views);
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                TableBuilder v = it.next();
                boolean ready = viewDeps.getOrDefault(v.oid, Set.of()).stream()
                        .noneMatch(dep -> viewOids.contains(dep) && !placed.contains(dep));
                if (ready) {
                    out.add(v.build());
                    placed.add(v.oid);
                    it.remove();
                    progressed = true;
                }
            }
            if (!progressed) { // cannot happen for valid views; never loop forever
                pending.forEach(v -> out.add(v.build()));
                break;
            }
        }
        return out;
    }

    private static String key(@Nullable String schema, String name) {
        return (schema == null ? "" : schema) + "\0" + name;
    }

    // ── JDBC metadata fallback ─────────────────────────────────────────────────────────────────────────

    private Content readMetadata(DatabaseMetaData md) throws SQLException {
        notes.add("Read through JDBC metadata: check constraints, view queries, enum types, sequences and index "
                + "options are not visible and are not part of this DDL");
        String quote = md.getIdentifierQuoteString();
        SqlNames names = new SqlNames(quote == null || quote.isBlank() ? "" : quote.trim(), md.storesUpperCaseIdentifiers());
        Map<String, TableBuilder> builders = new LinkedHashMap<>();
        Set<String> schemas = new LinkedHashSet<>();
        try (ResultSet rs = md.getTables(null, options.schema(), "%", new String[]{"TABLE", "VIEW"})) {
            long oid = 0;
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                if ((schema != null && DatabaseSampler.isSystemSchema(schema))
                        || DatabaseSampler.isMigrationHistory(table)) {
                    continue;
                }
                boolean view = "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"));
                builders.put(key(schema, table), new TableBuilder(oid++, schema, table,
                        view ? Kind.VIEW : Kind.TABLE, emptyToNull(rs.getString("REMARKS")), null, null, null, null));
                if (schema != null) {
                    schemas.add(schema);
                }
            }
        }
        for (TableBuilder t : builders.values()) {
            readMetadataTable(md, names, t);
        }
        List<TableBuilder> sorted = new ArrayList<>(builders.values());
        sorted.sort(Comparator.comparing((TableBuilder t) -> t.kind == Kind.VIEW ? 1 : 0)
                .thenComparing(t -> t.schema == null ? "" : t.schema).thenComparing(t -> t.name));
        if (sorted.stream().anyMatch(t -> t.kind == Kind.VIEW)) {
            notes.add("views are listed without their query (JDBC metadata does not provide it)");
        }
        return new Content(List.of(), new ArrayList<>(schemas), List.of(), List.of(),
                sorted.stream().map(TableBuilder::build).toList());
    }

    private void readMetadataTable(DatabaseMetaData md, SqlNames names, TableBuilder t) throws SQLException {
        try (ResultSet rs = md.getColumns(null, t.schema, t.name, "%")) {
            while (rs.next()) {
                String type = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                boolean digitsNull = rs.wasNull();
                String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
                String declared = type == null ? "varchar" : type;
                if (size > 0 && size < Integer.MAX_VALUE && !declared.contains("(")) {
                    if (lower.contains("char") || lower.equals("bit") || lower.equals("varbit")) {
                        declared = type + "(" + size + ")";
                    } else if ((lower.equals("numeric") || lower.equals("decimal") || lower.equals("number"))
                            && size < 1000) {
                        declared = type + "(" + size + (digitsNull ? "" : "," + digits) + ")";
                    }
                }
                boolean auto = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
                boolean generated = "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"));
                String def = rs.getString("COLUMN_DEF");
                t.columns.add(new Column(rs.getString("COLUMN_NAME"), declared,
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, auto || generated ? null : def,
                        auto ? Identity.AUTO_INCREMENT : null, generated ? def : null, false,
                        emptyToNull(rs.getString("REMARKS"))));
            }
        }
        if (t.kind == Kind.VIEW) {
            return;
        }
        Map<Integer, String> pk = new TreeMap<>();
        String[] pkName = new String[1];
        try (ResultSet rs = md.getPrimaryKeys(null, t.schema, t.name)) {
            while (rs.next()) {
                pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                pkName[0] = rs.getString("PK_NAME");
            }
        }
        if (!pk.isEmpty()) {
            List<String> cols = List.copyOf(pk.values());
            t.constraints.add(new Constraint(pkName[0], ConstraintType.PRIMARY_KEY, cols,
                    "PRIMARY KEY (" + names.columns(cols) + ")", null));
        }
        readMetadataForeignKeys(md, names, t);
        readMetadataIndexes(md, names, t, List.copyOf(pk.values()));
    }

    private void readMetadataForeignKeys(DatabaseMetaData md, SqlNames names, TableBuilder t) throws SQLException {
        record Fk(String name, @Nullable String schema, String table, int update, int delete,
                  Map<Integer, String[]> columns) {
        }
        Map<String, Fk> fks = new LinkedHashMap<>();
        try (ResultSet rs = md.getImportedKeys(null, t.schema, t.name)) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                String pkSchema = rs.getString("PKTABLE_SCHEM");
                String pkTable = rs.getString("PKTABLE_NAME");
                int update = rs.getInt("UPDATE_RULE");
                int delete = rs.getInt("DELETE_RULE");
                String id = (name == null ? t.name + "_" + rs.getString("FKCOLUMN_NAME") : name) + "\0" + pkTable;
                Fk fk = fks.computeIfAbsent(id, k -> new Fk(name, pkSchema, pkTable, update, delete, new TreeMap<>()));
                fk.columns.put(rs.getInt("KEY_SEQ"),
                        new String[]{rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")});
            }
        } catch (SQLException e) {
            notes.add("foreign keys of " + t.name + " not readable: " + e.getMessage());
            return;
        }
        for (Fk fk : fks.values()) {
            List<String> own = fk.columns.values().stream().map(c -> c[0]).toList();
            List<String> target = fk.columns.values().stream().map(c -> c[1]).toList();
            Name references = new Name(fk.schema, fk.table);
            t.constraints.add(new Constraint(fk.name, ConstraintType.FOREIGN_KEY, own,
                    "FOREIGN KEY (" + names.columns(own) + ") REFERENCES " + names.qualified(references) + " ("
                            + names.columns(target) + ")" + rule(" ON UPDATE ", fk.update)
                            + rule(" ON DELETE ", fk.delete), references));
        }
    }

    private static String rule(String prefix, int rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> prefix + "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> prefix + "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> prefix + "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> prefix + "RESTRICT";
            default -> "";
        };
    }

    private void readMetadataIndexes(DatabaseMetaData md, SqlNames names, TableBuilder t, List<String> pk)
            throws SQLException {
        Map<String, Map<Integer, String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, t.schema, t.name, false, true)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index == null || column == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                columns.computeIfAbsent(index, k -> new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"), column);
                unique.put(index, !rs.getBoolean("NON_UNIQUE"));
            }
        } catch (SQLException e) {
            notes.add("indexes of " + t.name + " not readable: " + e.getMessage());
            return;
        }
        for (var e : columns.entrySet()) {
            List<String> cols = List.copyOf(e.getValue().values());
            if (cols.equals(pk) && unique.get(e.getKey())) {
                continue; // the primary key's own index
            }
            boolean u = unique.get(e.getKey());
            t.indexes.add(new Index(e.getKey(), u, cols, "CREATE " + (u ? "UNIQUE " : "") + "INDEX "
                    + names.id(e.getKey()) + " ON " + names.qualified(t.schema, t.name) + " (" + names.columns(cols)
                    + ")"));
        }
    }

    // ── row counts and helpers ─────────────────────────────────────────────────────────────────────────

    private List<Table> countRows(List<Table> tables, SqlNames names) {
        List<Table> out = new ArrayList<>();
        for (Table t : tables) {
            if (t.view() || t.partitionOf() != null) {
                out.add(t); // a partitioned parent already counts its partitions
                continue;
            }
            try (Statement st = statement(); ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM " + names.qualified(t.schema(), t.name()))) {
                out.add(rs.next() ? t.withRowCount(rs.getLong(1)) : t);
            } catch (SQLException e) {
                notes.add("row count of " + t.name() + " failed: " + e.getMessage());
                out.add(t);
            }
        }
        return out;
    }

    private Statement statement() throws SQLException {
        Statement st = connection.createStatement();
        st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return st;
    }

    private PreparedStatement prepared(String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return ps;
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = statement()) {
            st.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Statement st = statement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object values = array.getArray();
        List<String> out = new ArrayList<>();
        for (Object v : (Object[]) values) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static @Nullable String emptyToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
