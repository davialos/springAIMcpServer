package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Read-only access to the target project's database: lists tables, samples real column values and checks which
 * given values exist. Only tables and columns reported by JDBC metadata are ever queried, and identifiers are
 * quoted with the driver's quote string, so names from configuration cannot inject SQL. Values are always bound
 * as parameters. The connection is read-only and every statement has a timeout (release-it: bounded calls).
 */
public final class DatabaseSampler implements AutoCloseable {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog", "pg_toast",
            "sys", "mysql", "performance_schema", "dynamic_ai");
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final int VERIFY_CHUNK = 500;

    private final Connection connection;
    private final String quote;
    private final String product;
    private final @Nullable String catalog;
    private final Map<String, DbTable> tables = new LinkedHashMap<>();

    /**
     * Wraps an open connection (closed by {@link #close()}).
     *
     * @param connection JDBC connection
     * @param schema     schema to read, or {@code null} for all non-system schemas
     * @throws SQLException on metadata errors
     */
    public DatabaseSampler(Connection connection, @Nullable String schema) throws SQLException {
        this.connection = connection;
        connection.setReadOnly(true);
        DatabaseMetaData md = connection.getMetaData();
        String q = md.getIdentifierQuoteString();
        this.quote = q == null || q.isBlank() ? "" : q.trim();
        this.product = md.getDatabaseProductName().toLowerCase(Locale.ROOT);
        // MySQL/MariaDB expose databases as catalogs: without the current one, metadata spans every database
        this.catalog = product.contains("mysql") || product.contains("mariadb") ? connection.getCatalog() : null;
        readMetadata(md, schema);
    }

    /**
     * Opens a connection with {@link DriverManager}.
     *
     * @param url      JDBC URL
     * @param user     user name, or {@code null}
     * @param password password, or {@code null}
     * @param schema   schema to read, or {@code null}
     * @return the sampler
     * @throws SQLException when the connection fails
     */
    public static DatabaseSampler connect(String url, @Nullable String user, @Nullable String password,
                                         @Nullable String schema) throws SQLException {
        DriverManager.setLoginTimeout(10);
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            return new DatabaseSampler(c, schema);
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private void readMetadata(DatabaseMetaData md, @Nullable String schema) throws SQLException {
        try (ResultSet rs = md.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                String t = rs.getString("TABLE_NAME");
                if ((s != null && SYSTEM_SCHEMAS.contains(s.toLowerCase(Locale.ROOT)))
                        || t.toLowerCase(Locale.ROOT).contains("schema_history")
                        || t.toLowerCase(Locale.ROOT).startsWith("databasechangelog")
                        || product.contains("microsoft") && (t.startsWith("spt_")
                        || t.equalsIgnoreCase("MSreplication_options"))) { // shipped with SQL Server's master
                    continue;
                }
                tables.put(qualified(s, t), new DbTable(s, t, Map.of(), List.of()));
            }
        }
        for (Map.Entry<String, DbTable> e : new ArrayList<>(tables.entrySet())) {
            DbTable t = e.getValue();
            Map<String, String> columns = new LinkedHashMap<>();
            Map<String, Integer> sizes = new LinkedHashMap<>();
            Set<String> required = new LinkedHashSet<>();
            Set<String> generated = new LinkedHashSet<>();
            try (ResultSet rs = md.getColumns(catalog, t.schema(), t.name(), "%")) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    columns.put(column, type);
                    String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
                    int size = rs.getInt("COLUMN_SIZE");
                    if (size > 0 && size < Integer.MAX_VALUE && (lower.contains("char") || lower.contains("text"))) {
                        sizes.put(column, size);
                    }
                    boolean byDatabase = "YES".equalsIgnoreCase(optional(rs, "IS_AUTOINCREMENT"))
                            || "YES".equalsIgnoreCase(optional(rs, "IS_GENERATEDCOLUMN"))
                            || lower.contains("serial") || lower.contains("identity")
                            || rs.getString("COLUMN_DEF") != null;
                    if (byDatabase) {
                        generated.add(column);
                    } else if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                        required.add(column);
                    }
                }
            }
            Map<Integer, String> pk = new TreeMap<>();
            try (ResultSet rs = md.getPrimaryKeys(catalog, t.schema(), t.name())) {
                while (rs.next()) {
                    pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
            }
            record FkColumn(String constraint, int seq, String column, @Nullable String targetSchema,
                            String targetTable, String targetColumn) {
            }
            List<FkColumn> fkColumns = new ArrayList<>();
            try (ResultSet rs = md.getImportedKeys(catalog, t.schema(), t.name())) {
                while (rs.next()) {
                    fkColumns.add(new FkColumn(rs.getString("FK_NAME") + "/" + rs.getString("PKTABLE_NAME"),
                            rs.getInt("KEY_SEQ"), rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_SCHEM"),
                            rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
                }
            } catch (SQLException ex) {
                // a driver without foreign-key metadata: relationships come from JPA only
            }
            Map<String, List<FkColumn>> byConstraint = new LinkedHashMap<>();
            fkColumns.forEach(f -> byConstraint.computeIfAbsent(f.constraint(), k -> new ArrayList<>()).add(f));
            Map<String, PoolRef> singleColumnFks = new LinkedHashMap<>();
            List<DbTable.CompositeForeignKey> composite = new ArrayList<>();
            for (List<FkColumn> key : byConstraint.values()) {
                key.sort(java.util.Comparator.comparingInt(FkColumn::seq));
                FkColumn first = key.getFirst();
                if (key.size() == 1) {
                    singleColumnFks.put(first.column(), new PoolRef(first.targetSchema(), first.targetTable(),
                            first.targetColumn()));
                } else { // filled from one parent row: sampled as tuples of the referenced columns
                    composite.add(new DbTable.CompositeForeignKey(key.stream().map(FkColumn::column).toList(),
                            new PoolRef(first.targetSchema(), first.targetTable(), String.join(",",
                                    key.stream().map(FkColumn::targetColumn).toList()))));
                }
            }
            Map<String, Set<String>> indexColumns = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(catalog, t.schema(), t.name(), true, true)) {
                while (rs.next()) {
                    String index = rs.getString("INDEX_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (index != null && column != null) {
                        indexColumns.computeIfAbsent(index, k -> new LinkedHashSet<>()).add(column);
                    }
                }
            } catch (SQLException ex) {
                // no index metadata: uniqueness comes from JPA only
            }
            Set<String> unique = new LinkedHashSet<>();
            indexColumns.values().stream().filter(c -> c.size() == 1).forEach(unique::addAll);
            if (pk.size() == 1) {
                unique.removeAll(pk.values()); // the primary key's own index says nothing new
            }
            e.setValue(new DbTable(t.schema(), t.name(), columns, new ArrayList<>(pk.values()), singleColumnFks,
                    sizes, unique, composite, required, generated));
        }
    }

    private static @Nullable String optional(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null; // JDBC 4.1 column a driver does not report
        }
    }

    private static String qualified(@Nullable String schema, String table) {
        return (schema == null ? "" : schema + ".") + table;
    }

    /**
     * Tables found (system schemas, the library's own {@code dynamic_ai} schema and migration history excluded).
     *
     * @return tables
     */
    public List<DbTable> tables() {
        return List.copyOf(tables.values());
    }

    /**
     * Samples up to {@code limit} distinct, non-null values of a column, in random order.
     *
     * @param pool  column
     * @param limit maximum number of values
     * @return JSON-friendly values (numbers, strings, booleans)
     * @throws SQLException on query errors
     * @throws IllegalArgumentException when the column is not in the metadata
     */
    public List<Object> sample(PoolRef pool, int limit) throws SQLException {
        DbTable table = table(pool);
        List<String> columns = new ArrayList<>();
        for (String c : pool.column().split(",")) {
            columns.add(column(table, c.trim()));
        }
        // Distinct values in a derived table, randomly ordered outside it (DISTINCT + ORDER BY RANDOM() in one
        // SELECT is rejected by PostgreSQL). Statement timeout and max rows bound the cost on large tables.
        // Several columns (a composite foreign key's target) are sampled together: one tuple per parent row.
        List<String> quoted = columns.stream().map(this::q).toList();
        String sql = "SELECT d.* FROM (SELECT DISTINCT " + String.join(", ", quoted) + " FROM " + from(table)
                + " WHERE " + String.join(" AND ", quoted.stream().map(c -> c + " IS NOT NULL").toList()) + ") d";
        List<Object> out = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            st.setMaxRows(limit);
            try (ResultSet rs = st.executeQuery(sql + randomOrder())) {
                while (rs.next() && out.size() < limit) {
                    if (columns.size() == 1) {
                        out.add(jsonValue(rs.getObject(1)));
                    } else {
                        List<Object> tuple = new ArrayList<>();
                        for (int i = 1; i <= columns.size(); i++) {
                            tuple.add(jsonValue(rs.getObject(i)));
                        }
                        out.add(tuple);
                    }
                }
            }
        }
        return out;
    }

    private String randomOrder() {
        if (product.contains("mysql") || product.contains("mariadb")) {
            return " ORDER BY RAND()";
        }
        if (product.contains("microsoft")) {
            return " ORDER BY NEWID()";
        }
        if (product.contains("oracle")) {
            return " ORDER BY DBMS_RANDOM.VALUE";
        }
        return " ORDER BY RANDOM()"; // PostgreSQL, H2, SQLite, HSQLDB
    }

    /**
     * Checks which of the given values exist in a column ("real data, checked in the database").
     *
     * @param pool   column
     * @param values candidate values (strings or numbers)
     * @return the subset that exists, in input order
     * @throws SQLException on query errors
     */
    public List<Object> existing(PoolRef pool, List<?> values) throws SQLException {
        DbTable table = table(pool);
        if (pool.column().contains(",")) {
            return List.of(); // tuples (composite keys) are sampled, never checked value by value
        }
        String column = column(table, pool.column());
        String type = table.columns().get(column).toLowerCase(Locale.ROOT);
        Map<String, Object> found = new HashMap<>();
        List<Object> typed = new ArrayList<>();
        for (Object v : values) {
            Object t = typed(v, type);
            if (t != null) {
                typed.add(t);
            }
        }
        for (int i = 0; i < typed.size(); i += VERIFY_CHUNK) {
            List<Object> chunk = typed.subList(i, Math.min(typed.size(), i + VERIFY_CHUNK));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT " + q(column) + " FROM " + from(table) + " WHERE " + q(column) + " IN (" + marks + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (int j = 0; j < chunk.size(); j++) {
                    ps.setObject(j + 1, chunk.get(j));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object v = jsonValue(rs.getObject(1));
                        found.put(String.valueOf(v), v);
                    }
                }
            }
        }
        List<Object> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object v : values) {
            String k = String.valueOf(v);
            if (found.containsKey(k) && seen.add(k)) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * Whether a pool's table and column exist.
     *
     * @param pool column
     * @return {@code true} when both are in the metadata
     */
    public boolean has(PoolRef pool) {
        try {
            DbTable table = table(pool);
            for (String c : pool.column().split(",")) {
                column(table, c.trim());
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    DbTable table(PoolRef pool) {
        for (DbTable t : tables.values()) {
            if (t.name().equalsIgnoreCase(pool.table())
                    && (pool.schema() == null || pool.schema().equalsIgnoreCase(t.schema()))) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown table " + pool.key());
    }

    private static String column(DbTable table, String column) {
        for (String c : table.columns().keySet()) {
            if (c.equalsIgnoreCase(column)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown column " + table.name() + "." + column);
    }

    private String q(String identifier) {
        return quote + identifier.replace(quote.isEmpty() ? "\0" : quote, quote + quote) + quote;
    }

    private String from(DbTable t) {
        return t.schema() == null ? q(t.name()) : q(t.schema()) + "." + q(t.name());
    }

    private static @Nullable Object typed(Object v, String type) {
        String s = String.valueOf(v);
        try {
            if (type.contains("int") || type.equals("serial") || type.equals("bigserial")) {
                return Long.valueOf(s.contains(".") ? s.substring(0, s.indexOf('.')) : s);
            }
            if (type.contains("numeric") || type.contains("decimal") || type.contains("float")
                    || type.contains("double") || type.contains("real")) {
                return new BigDecimal(s);
            }
            if (type.equals("uuid")) {
                return UUID.fromString(s);
            }
        } catch (IllegalArgumentException e) {
            return null; // cannot exist in a column of that type
        }
        return s;
    }

    /**
     * Converts a JDBC value into something JSON can carry without loss of meaning.
     *
     * @param v JDBC value
     * @return number, boolean or string
     */
    static Object jsonValue(Object v) {
        return switch (v) {
            case Integer i -> i.longValue();
            case Long l -> l;
            case Short s -> s.longValue();
            case BigDecimal d -> d.stripTrailingZeros().scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(1L << 53)) < 0
                    ? (Object) d.longValueExact() : d;
            case Number n -> n;
            case Boolean b -> b;
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Timestamp t -> t.toInstant().toString();
            case TemporalAccessor t -> t.toString();
            default -> v.toString();
        };
    }

    /** The underlying connection, for the explicit write tools of this package (bulk load, snapshots). */
    Connection connection() {
        return connection;
    }

    /** The database product name, lower case. */
    String product() {
        return product;
    }

    /** Quotes an identifier with the driver's quote string. */
    String quoted(String identifier) {
        return q(identifier);
    }

    /** The table's qualified, quoted name. */
    String qualifiedName(DbTable t) {
        return from(t);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
