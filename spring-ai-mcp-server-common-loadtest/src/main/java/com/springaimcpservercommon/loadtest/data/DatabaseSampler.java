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
import java.util.Objects;
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

    /**
     * Whether a schema belongs to the database or the library rather than to the host's own data (system
     * catalogs and the library's {@code dynamic_ai} schema).
     *
     * @param schema schema name
     * @return {@code true} when it is skipped by sampling and by schema snapshots
     */
    public static boolean isSystemSchema(String schema) {
        String s = schema.toLowerCase(Locale.ROOT);
        return SYSTEM_SCHEMAS.contains(s) || s.startsWith("pg_");
    }

    /**
     * Whether a table only records schema migrations (Flyway / Liquibase history).
     *
     * @param table table name
     * @return {@code true} for migration history tables
     */
    public static boolean isMigrationHistory(String table) {
        return table.toLowerCase(Locale.ROOT).contains("schema_history");
    }

    private final Connection connection;
    private final String quote;
    private final String product;
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
        try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                String t = rs.getString("TABLE_NAME");
                if ((s != null && isSystemSchema(s)) || isMigrationHistory(t)) {
                    continue;
                }
                tables.put(qualified(s, t), new DbTable(s, t, Map.of(), List.of()));
            }
        }
        for (Map.Entry<String, DbTable> e : new ArrayList<>(tables.entrySet())) {
            DbTable t = e.getValue();
            Map<String, String> columns = new LinkedHashMap<>();
            Map<String, Integer> sizes = new LinkedHashMap<>();
            try (ResultSet rs = md.getColumns(null, t.schema(), t.name(), "%")) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    columns.put(column, type);
                    String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
                    int size = rs.getInt("COLUMN_SIZE");
                    if (size > 0 && size < Integer.MAX_VALUE && (lower.contains("char") || lower.contains("text"))) {
                        sizes.put(column, size);
                    }
                }
            }
            Map<Integer, String> pk = new TreeMap<>();
            try (ResultSet rs = md.getPrimaryKeys(null, t.schema(), t.name())) {
                while (rs.next()) {
                    pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                }
            }
            record FkColumn(String constraint, String column, PoolRef target) {
            }
            List<FkColumn> fkColumns = new ArrayList<>();
            try (ResultSet rs = md.getImportedKeys(null, t.schema(), t.name())) {
                while (rs.next()) {
                    fkColumns.add(new FkColumn(rs.getString("FK_NAME") + "/" + rs.getString("PKTABLE_NAME"),
                            rs.getString("FKCOLUMN_NAME"), new PoolRef(rs.getString("PKTABLE_SCHEM"),
                            rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME"))));
                }
            } catch (SQLException ex) {
                // a driver without foreign-key metadata: relationships come from JPA only
            }
            Map<String, Long> width = new HashMap<>();
            fkColumns.forEach(f -> width.merge(f.constraint(), 1L, Long::sum));
            Map<String, PoolRef> singleColumnFks = new LinkedHashMap<>();
            for (FkColumn f : fkColumns) {
                if (width.get(f.constraint()) == 1) { // composite keys cannot be filled from one pool
                    singleColumnFks.put(f.column(), f.target());
                }
            }
            Map<String, Set<String>> indexColumns = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(null, t.schema(), t.name(), true, true)) {
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
                    sizes, unique));
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
        if (pool.isQuery()) {
            return sampleQuery(pool, limit);
        }
        DbTable table = table(pool);
        String column = column(table, pool.column());
        // Distinct values in a derived table, randomly ordered outside it (DISTINCT + ORDER BY RANDOM() in one
        // SELECT is rejected by PostgreSQL). Statement timeout and max rows bound the cost on large tables.
        String sql = "SELECT v FROM (SELECT DISTINCT " + q(column) + " AS v FROM " + from(table)
                + " WHERE " + q(column) + " IS NOT NULL) d";
        List<Object> out = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            st.setMaxRows(limit);
            try (ResultSet rs = st.executeQuery(sql + randomOrder())) {
                while (rs.next() && out.size() < limit) {
                    out.add(jsonValue(rs.getObject(1)));
                }
            }
        }
        return out;
    }

    /**
     * Runs a query pool's statement and returns the first column of up to {@code limit} rows, distinct, non-null
     * and in random order. The statement was checked by {@link #requireReadOnlySelect(String)} when the pool was
     * created and runs on the read-only connection with a statement timeout and a row cap.
     */
    private List<Object> sampleQuery(PoolRef pool, int limit) throws SQLException {
        String sql = DatabaseSampler.requireReadOnlySelect(Objects.requireNonNull(pool.sql()));
        Set<Object> seen = new LinkedHashSet<>();
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            st.setMaxRows(Math.max(limit * 4, limit)); // headroom: duplicates and nulls are dropped below
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next() && seen.size() < limit) {
                    Object v = rs.getObject(1);
                    if (v != null) {
                        seen.add(jsonValue(v));
                    }
                }
            }
        }
        List<Object> out = new ArrayList<>(seen);
        java.util.Collections.shuffle(out); // a query without ORDER BY RANDOM() would otherwise bias to its first rows
        return out;
    }

    /**
     * Samples several columns of one table <em>as rows</em>: the values at the same index of the returned lists
     * come from the same row. Used so a request that carries several fields of one table (an order's id and its
     * customer id, a SKU and its warehouse) sends values that belong together instead of unrelated rows. Only
     * rows with no null in any of the columns are used.
     *
     * @param pools table-column pools of one table (at least two)
     * @param limit maximum number of rows
     * @return pool key → values, all lists of the same length and order; empty when no complete row exists
     * @throws SQLException             on query errors
     * @throws IllegalArgumentException when the pools are not all columns of one known table
     */
    public Map<String, List<Object>> sampleRows(List<PoolRef> pools, int limit) throws SQLException {
        DbTable table = table(pools.getFirst());
        List<String> columns = new ArrayList<>();
        for (PoolRef p : pools) {
            if (p.isQuery() || table(p) != table) {
                throw new IllegalArgumentException("pools of one table expected: " + p.key());
            }
            columns.add(column(table, p.column()));
        }
        String list = columns.stream().map(this::q).collect(java.util.stream.Collectors.joining(", "));
        String notNull = columns.stream().map(c -> q(c) + " IS NOT NULL")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "SELECT " + list + " FROM (SELECT DISTINCT " + list + " FROM " + from(table) + " WHERE "
                + notNull + ") d" + randomOrder();
        Map<String, List<Object>> out = new LinkedHashMap<>();
        pools.forEach(p -> out.put(p.key(), new ArrayList<>()));
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            st.setMaxRows(limit);
            try (ResultSet rs = st.executeQuery(sql)) {
                int rows = 0;
                while (rs.next() && rows++ < limit) {
                    for (int i = 0; i < pools.size(); i++) {
                        out.get(pools.get(i).key()).add(jsonValue(rs.getObject(i + 1)));
                    }
                }
            }
        }
        return out.values().iterator().next().isEmpty() ? Map.of() : out;
    }

    private static final java.util.regex.Pattern READ_ONLY_START =
            java.util.regex.Pattern.compile("(?is)^\\s*(select|with)\\b.*");
    private static final java.util.regex.Pattern WRITE_WORD = java.util.regex.Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute|copy|into"
                    + "|lock|vacuum|set)\\b");

    /**
     * Checks that a statement from configuration is a single read-only {@code SELECT} / {@code WITH} query and
     * returns it without a trailing semicolon. Defence in depth: the connection is read-only and the statement
     * has a timeout as well. A string literal that contains a write keyword is rejected too (false positive,
     * on purpose).
     *
     * @param sql statement text
     * @return the normalised statement
     * @throws IllegalArgumentException when it is empty, has several statements or contains a write keyword
     */
    static String requireReadOnlySelect(String sql) {
        String s = sql == null ? "" : sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty() || !READ_ONLY_START.matcher(s).matches()) {
            throw new IllegalArgumentException("a query pool must be a SELECT or WITH statement: " + abbreviate(s));
        }
        if (s.contains(";")) {
            throw new IllegalArgumentException("a query pool must be a single statement: " + abbreviate(s));
        }
        java.util.regex.Matcher m = WRITE_WORD.matcher(s);
        if (m.find()) {
            throw new IllegalArgumentException("a query pool must be read-only, found '" + m.group(1) + "': "
                    + abbreviate(s));
        }
        return s.replaceAll("\\s+", " ");
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
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
        if (pool.isQuery()) {
            return new ArrayList<>(values); // the query defines which values are valid; nothing to look up by column
        }
        DbTable table = table(pool);
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
        if (pool.isQuery()) {
            return true;
        }
        try {
            column(table(pool), pool.column());
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

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
