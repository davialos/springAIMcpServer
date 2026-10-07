package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Loads realistic data <em>volume</em> straight into a test database, for soak and capacity runs where 5 seeded rows
 * per table say nothing about a table of a million. This is the one tool of the module that writes to a database,
 * so it is opt-in and explicit: the caller names every table and row count, a production-looking URL is refused
 * unless allowed, and every insert is a batched, parameterized statement inside a transaction per batch.
 * <p>
 * Tables are filled parents first (single and composite foreign keys). Columns the database fills (identity,
 * auto-increment, serial, defaults) are left out; foreign keys take keys of existing parent rows (rows loaded in this
 * run included); primary keys the application assigns and unique columns get distinct values; text respects the
 * column length; types the loader does not know are left {@code NULL} when nullable. Values are reproducible for a
 * given seed.
 */
public final class BulkLoader implements AutoCloseable {

    /** URLs that look like production: refused unless {@link #allowProduction(boolean)}. */
    public static final Pattern PRODUCTION = Pattern.compile("(?i)(^|[^a-z])(prod|production|live)([^a-z]|$)");
    private static final int PARENT_SAMPLE = 100_000;
    private static final String[] WORDS = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
            "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra",
            "tango", "uniform", "victor", "whiskey", "xray", "yankee", "zulu"};
    private static final String[] FIRST = {"Ada", "Alan", "Grace", "Linus", "Margaret", "Dennis", "Barbara",
            "Ken", "Frances", "Edsger", "Radia", "Tim", "Hedy", "John", "Katherine", "Guido"};
    private static final String[] LAST = {"Lovelace", "Turing", "Hopper", "Torvalds", "Hamilton", "Ritchie",
            "Liskov", "Thompson", "Allen", "Dijkstra", "Perlman", "Berners-Lee", "Lamarr", "McCarthy", "Johnson"};

    private final DatabaseSampler db;
    private final Connection connection;
    private final Consumer<String> log;
    private int batchSize = 1_000;
    private long seed = 42;
    private boolean allowProduction;
    private final String url;

    /**
     * The outcome of a load.
     *
     * @param inserted rows inserted per table, in load order
     * @param took     wall time
     */
    public record Result(Map<String, Long> inserted, Duration took) {
    }

    private BulkLoader(DatabaseSampler db, String url, Consumer<String> log) {
        this.db = db;
        this.connection = db.connection();
        this.url = url;
        this.log = log;
    }

    /**
     * Connects for writing.
     *
     * @param url      JDBC URL of a test database
     * @param user     user
     * @param password password
     * @param schema   schema to read, or {@code null}
     * @param log      progress lines (never row values)
     * @return a loader
     * @throws SQLException when the connection fails
     */
    public static BulkLoader connect(String url, @Nullable String user, @Nullable String password,
                                     @Nullable String schema, Consumer<String> log) throws SQLException {
        return new BulkLoader(DatabaseSampler.connect(url, user, password, schema), url, log);
    }

    /**
     * Rows per batch and transaction.
     *
     * @param n default 1000
     * @return this
     */
    public BulkLoader batchSize(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = n;
        return this;
    }

    /**
     * Random seed (same seed, same values).
     *
     * @param seed default 42
     * @return this
     */
    public BulkLoader seed(long seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Allows a URL matching {@link #PRODUCTION}.
     *
     * @param allow default {@code false}
     * @return this
     */
    public BulkLoader allowProduction(boolean allow) {
        this.allowProduction = allow;
        return this;
    }

    /**
     * The tables known to the loader.
     *
     * @return tables
     */
    public List<DbTable> tables() {
        return db.tables();
    }

    /**
     * Inserts rows.
     *
     * @param rowsPerTable table name (or {@code schema.table}) → number of rows to add
     * @return rows inserted per table
     * @throws SQLException on database errors (the failing batch is rolled back)
     * @throws IllegalArgumentException for unknown tables, missing parent rows, or a production-looking URL
     */
    public Result load(Map<String, Long> rowsPerTable) throws SQLException {
        if (!allowProduction && PRODUCTION.matcher(url).find()) {
            throw new IllegalArgumentException("refusing to bulk-load " + url.replaceAll("password=[^&;]*",
                    "password=***") + ": it looks like production (allowProduction to override)");
        }
        Map<DbTable, Long> wanted = new LinkedHashMap<>();
        rowsPerTable.forEach((name, n) -> wanted.put(find(name), n));
        Instant start = Instant.now();
        Random random = new Random(seed);
        Map<String, Long> inserted = new LinkedHashMap<>();
        boolean autoCommit = connection.getAutoCommit();
        connection.setReadOnly(false); // explicit: the only writing path of this module
        connection.setAutoCommit(false);
        try {
            for (DbTable t : order(wanted.keySet())) {
                long n = wanted.get(t);
                inserted.put(t.name(), insert(t, n, random));
                log.accept("bulk: " + t.name() + " +" + n + " rows");
            }
        } finally {
            connection.setAutoCommit(autoCommit);
            connection.setReadOnly(true);
        }
        return new Result(inserted, Duration.between(start, Instant.now()));
    }

    private DbTable find(String name) {
        String n = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        String schema = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : null;
        return db.tables().stream()
                .filter(t -> t.name().equalsIgnoreCase(n) && (schema == null || schema.equalsIgnoreCase(t.schema())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown table " + name));
    }

    /** Parents first among the selected tables (Kahn); a cycle keeps its declaration order. */
    static List<DbTable> order(Set<DbTable> tables) {
        List<DbTable> pending = new ArrayList<>(tables);
        List<DbTable> out = new ArrayList<>();
        while (!pending.isEmpty()) {
            DbTable next = null;
            for (DbTable t : pending) {
                if (parents(t).stream().noneMatch(p -> pending.stream().anyMatch(o -> o != t
                        && o.name().equalsIgnoreCase(p)))) {
                    next = t;
                    break;
                }
            }
            if (next == null) {
                next = pending.getFirst();
            }
            pending.remove(next);
            out.add(next);
        }
        return out;
    }

    private static Set<String> parents(DbTable t) {
        Set<String> out = new LinkedHashSet<>();
        t.foreignKeys().values().forEach(p -> out.add(p.table()));
        t.compositeForeignKeys().forEach(k -> out.add(k.target().table()));
        out.remove(t.name());
        return out;
    }

    // ── one table ──────────────────────────────────────────────────────────────────────────────────────

    private long insert(DbTable t, long rows, Random random) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (String c : t.columns().keySet()) {
            if (!t.generatedColumns().contains(c)) {
                columns.add(c);
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(t.name() + ": every column is filled by the database");
        }
        Map<String, List<Object>> parentKeys = new LinkedHashMap<>();
        for (var fk : t.foreignKeys().entrySet()) {
            if (columns.contains(fk.getKey())) {
                parentKeys.put(fk.getKey(), parentValues(t, fk.getValue(), fk.getKey()));
            }
        }
        Map<DbTable.CompositeForeignKey, List<Object>> parentTuples = new LinkedHashMap<>();
        for (DbTable.CompositeForeignKey k : t.compositeForeignKeys()) {
            if (columns.containsAll(k.columns())) {
                parentTuples.put(k, parentValues(t, k.target(), String.join(",", k.columns())));
            }
        }
        Map<String, Long> counters = new LinkedHashMap<>();
        for (String c : columns) {
            if (t.primaryKey().contains(c) || t.uniqueColumns().contains(c)) {
                counters.put(c, nextNumber(t, c));
            }
        }
        // a key made only of foreign keys (join table): distinct parent combinations
        boolean joinTable = !t.primaryKey().isEmpty() && t.primaryKey().stream().allMatch(c -> parentKeys.containsKey(c)
                || t.compositeKeyOf(c).isPresent());
        Set<List<Object>> usedKeys = joinTable ? existingKeys(t) : Set.of();

        String sql = "INSERT INTO " + db.qualifiedName(t) + " (" + String.join(", ",
                columns.stream().map(db::quoted).toList()) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        long done = 0;
        int attempts = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int inBatch = 0;
            while (done < rows) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (var e : parentTuples.entrySet()) {
                    Object tuple = pick(e.getValue(), random);
                    List<?> values = tuple instanceof List<?> l ? l : List.of(tuple);
                    for (int i = 0; i < e.getKey().columns().size(); i++) {
                        row.put(e.getKey().columns().get(i), values.get(i));
                    }
                }
                for (var e : parentKeys.entrySet()) {
                    row.putIfAbsent(e.getKey(), pick(e.getValue(), random));
                }
                if (joinTable) {
                    List<Object> key = t.primaryKey().stream().map(row::get).toList();
                    if (!usedKeys.add(key)) {
                        if (++attempts > rows * 20 + 1000) {
                            log.accept("bulk: " + t.name() + " ran out of distinct parent combinations after " + done
                                    + " rows");
                            break;
                        }
                        continue;
                    }
                }
                for (String c : columns) {
                    if (!row.containsKey(c)) {
                        row.put(c, value(t, c, counters, random));
                    }
                }
                for (int i = 0; i < columns.size(); i++) {
                    ps.setObject(i + 1, row.get(columns.get(i)));
                }
                ps.addBatch();
                done++;
                if (++inBatch == batchSize) {
                    flush(ps);
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                flush(ps);
            }
        } catch (SQLException e) {
            connection.rollback();
            throw new SQLException(t.name() + ": " + e.getMessage(), e.getSQLState(), e);
        }
        return done;
    }

    private void flush(PreparedStatement ps) throws SQLException {
        ps.executeBatch();
        connection.commit();
    }

    private List<Object> parentValues(DbTable child, PoolRef parent, String columns) throws SQLException {
        List<Object> values = db.sample(parent, PARENT_SAMPLE);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(child.name() + "." + columns + " references " + parent.table()
                    + ", which has no rows: load " + parent.table() + " too (or first)");
        }
        return values;
    }

    private Set<List<Object>> existingKeys(DbTable t) throws SQLException {
        Set<List<Object>> out = new HashSet<>();
        String sql = "SELECT " + String.join(", ", t.primaryKey().stream().map(db::quoted).toList()) + " FROM "
                + db.qualifiedName(t);
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<Object> key = new ArrayList<>();
                for (int i = 1; i <= t.primaryKey().size(); i++) {
                    key.add(DatabaseSampler.jsonValue(rs.getObject(i)));
                }
                out.add(key);
            }
        }
        return out;
    }

    /** Numeric keys continue after the current maximum; other unique values use the row count as suffix. */
    private long nextNumber(DbTable t, String column) throws SQLException {
        String type = t.columns().get(column).toLowerCase(Locale.ROOT);
        String sql = numeric(type) ? "SELECT MAX(" + db.quoted(column) + ") FROM " + db.qualifiedName(t)
                : "SELECT COUNT(*) FROM " + db.qualifiedName(t);
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next() && rs.getObject(1) != null) {
                return ((Number) rs.getObject(1)).longValue() + 1;
            }
        }
        return 1;
    }

    private static Object pick(List<Object> values, Random random) {
        Object v = values.get(random.nextInt(values.size()));
        return v instanceof Long l && l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE ? l : v;
    }

    private static boolean numeric(String type) {
        return type.contains("int") || type.contains("serial") || type.contains("numeric")
                || type.contains("decimal") || type.contains("number");
    }

    /** A value for a column, by type, name and constraints. */
    private Object value(DbTable t, String column, Map<String, Long> counters, Random random) {
        String type = t.columns().get(column).toLowerCase(Locale.ROOT);
        String name = column.toLowerCase(Locale.ROOT);
        Integer size = t.columnSizes().get(column);
        Long counter = counters.get(column);
        if (counter != null) {
            counters.put(column, counter + 1);
        }
        if (type.contains("bool") || type.equals("bit")) {
            return random.nextBoolean();
        }
        if (type.contains("int") || type.contains("serial")) {
            if (counter != null) {
                return type.contains("big") || type.equals("int8") ? (Object) counter : (Object) counter.intValue();
            }
            int bound = type.contains("small") || type.equals("int2") || type.contains("tiny") ? 100 : 10_000;
            return type.contains("big") || type.equals("int8") ? (Object) (long) (1 + random.nextInt(bound))
                    : (Object) (1 + random.nextInt(bound));
        }
        if (type.contains("numeric") || type.contains("decimal") || type.contains("money") || type.equals("number")) {
            if (counter != null) {
                return BigDecimal.valueOf(counter);
            }
            return BigDecimal.valueOf(random.nextDouble() * 1000).setScale(2, RoundingMode.HALF_UP);
        }
        if (type.contains("float") || type.contains("double") || type.equals("real")) {
            return random.nextDouble() * 1000;
        }
        if (type.equals("uuid") || type.equals("uniqueidentifier")) {
            return new UUID(random.nextLong(), random.nextLong());
        }
        if (type.contains("timestamp") || type.contains("datetime")) {
            return Timestamp.from(Instant.parse("2025-01-01T00:00:00Z").plusSeconds(random.nextInt(60 * 86_400 * 6)));
        }
        if (type.equals("date")) {
            return java.sql.Date.valueOf(LocalDate.of(2025, 1, 1).plusDays(random.nextInt(600)));
        }
        if (type.contains("char") || type.contains("text") || type.contains("clob") || type.equals("string")) {
            String v = text(name, counter, random);
            return size != null && v.length() > size ? truncateKeepingSuffix(v, size, counter) : v;
        }
        if (type.contains("json")) {
            return "{}";
        }
        if (t.requiredColumns().contains(column)) {
            throw new IllegalArgumentException(t.name() + "." + column + " is required but its type " + type
                    + " is not supported by the bulk loader: give it a default or leave the table out");
        }
        return null;
    }

    private static String text(String name, @Nullable Long counter, Random random) {
        String suffix = counter == null ? "" : "-" + counter;
        if (name.contains("email") || name.contains("mail")) {
            return "user" + (counter != null ? counter : random.nextInt(1_000_000)) + "@example.com";
        }
        if (name.contains("first")) {
            return FIRST[random.nextInt(FIRST.length)] + suffix;
        }
        if (name.contains("last") || name.contains("surname")) {
            return LAST[random.nextInt(LAST.length)] + suffix;
        }
        if (name.equals("name") || name.endsWith("_name") || name.endsWith("name")) {
            return FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)] + suffix;
        }
        if (name.contains("phone")) {
            return "+1555" + String.format(Locale.ROOT, "%07d", counter != null ? counter : random.nextInt(9_999_999));
        }
        if (name.contains("code") || name.contains("sku") || name.contains("ref")) {
            return "C" + (counter != null ? counter : random.nextInt(1_000_000));
        }
        int words = 1 + random.nextInt(6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            sb.append(i == 0 ? "" : " ").append(WORDS[random.nextInt(WORDS.length)]);
        }
        return sb + suffix;
    }

    private static String truncateKeepingSuffix(String v, int size, @Nullable Long counter) {
        if (counter == null) {
            return v.substring(0, size);
        }
        String suffix = Long.toString(counter);
        if (suffix.length() >= size) {
            return suffix.substring(suffix.length() - size);
        }
        return v.substring(0, size - suffix.length()) + suffix;
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
