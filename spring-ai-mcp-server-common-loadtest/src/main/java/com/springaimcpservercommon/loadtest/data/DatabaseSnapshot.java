package com.springaimcpservercommon.loadtest.data;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Saves and restores the application's tables of a <em>test</em> database, so consecutive load runs start from the
 * same data and their reports are comparable (the regression gate needs that). PostgreSQL: a snapshot is a schema
 * {@code loadtest_snapshot_<name>} holding a copy of every table; restoring truncates the application tables (in
 * one statement, so foreign keys hold), copies the rows back parents first and moves every serial/identity sequence
 * past the restored maximum. Other databases: use their backup tooling (or Flyway {@code clean}/{@code migrate} plus
 * a data script) — this class refuses them with that advice.
 */
public final class DatabaseSnapshot implements AutoCloseable {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,40}");
    private final DatabaseSampler db;
    private final Connection connection;
    private final String url;
    private final Consumer<String> log;
    private boolean allowProduction;

    private DatabaseSnapshot(DatabaseSampler db, String url, Consumer<String> log) {
        this.db = db;
        this.connection = db.connection();
        this.url = url;
        this.log = log;
        if (!db.product().contains("postgres")) {
            throw new IllegalArgumentException("snapshots support PostgreSQL; for " + db.product()
                    + " use its backup tooling, or Flyway clean + migrate with a data script");
        }
    }

    /**
     * Connects to a test database.
     *
     * @param url      JDBC URL
     * @param user     user
     * @param password password
     * @param schema   application schema, or {@code null} for every non-system schema
     * @param log      progress lines
     * @return the snapshot tool
     * @throws SQLException when the connection fails
     */
    public static DatabaseSnapshot connect(String url, @Nullable String user, @Nullable String password,
                                           @Nullable String schema, Consumer<String> log) throws SQLException {
        DatabaseSampler db = DatabaseSampler.connect(url, user, password, schema);
        try {
            return new DatabaseSnapshot(db, url, log);
        } catch (IllegalArgumentException e) {
            db.close();
            throw e;
        }
    }

    /**
     * Allows a production-looking URL ({@link BulkLoader#PRODUCTION}).
     *
     * @param allow default {@code false}
     * @return this
     */
    public DatabaseSnapshot allowProduction(boolean allow) {
        this.allowProduction = allow;
        return this;
    }

    private static String schemaOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("snapshot name: 1-40 of a-z, 0-9, _ (" + name + ")");
        }
        return "loadtest_snapshot_" + n;
    }

    private List<DbTable> applicationTables() {
        return db.tables().stream()
                .filter(t -> t.schema() == null || !t.schema().toLowerCase(Locale.ROOT).startsWith("loadtest_snapshot_"))
                .toList();
    }

    /**
     * Copies every application table into the snapshot (replacing a snapshot of the same name).
     *
     * @param name snapshot name ({@code baseline})
     * @return tables saved
     * @throws SQLException on database errors (nothing is kept half-written: one transaction)
     */
    public int save(String name) throws SQLException {
        guard();
        String snap = schemaOf(name);
        List<DbTable> tables = applicationTables();
        write(st -> {
            st.execute("DROP SCHEMA IF EXISTS " + db.quoted(snap) + " CASCADE");
            st.execute("CREATE SCHEMA " + db.quoted(snap));
            for (DbTable t : tables) {
                st.execute("CREATE TABLE " + db.quoted(snap) + "." + db.quoted(copyName(t)) + " AS TABLE "
                        + db.qualifiedName(t));
            }
        });
        log.accept("snapshot: saved " + tables.size() + " tables as " + name);
        return tables.size();
    }

    /**
     * Restores the application tables from a snapshot.
     *
     * @param name snapshot name
     * @return tables restored
     * @throws SQLException on database errors (rolled back as a whole)
     * @throws IllegalArgumentException when the snapshot does not exist
     */
    public int restore(String name) throws SQLException {
        guard();
        String snap = schemaOf(name);
        if (!exists(snap)) {
            throw new IllegalArgumentException("no snapshot " + name + " (save it first)");
        }
        Set<String> saved = savedTables(snap);
        List<DbTable> tables = BulkLoader.order(new LinkedHashSet<>(applicationTables())).stream()
                .filter(t -> saved.contains(copyName(t).toLowerCase(Locale.ROOT))).toList();
        write(st -> {
            st.execute("TRUNCATE " + String.join(", ", tables.stream().map(db::qualifiedName).toList()) + " CASCADE");
            for (DbTable t : tables) {
                List<String> cols = t.columns().keySet().stream().map(db::quoted).toList();
                String colList = String.join(", ", cols);
                st.execute("INSERT INTO " + db.qualifiedName(t) + " (" + colList + ") OVERRIDING SYSTEM VALUE SELECT "
                        + colList + " FROM " + db.quoted(snap) + "." + db.quoted(copyName(t)));
                resetSequences(st, t);
            }
        });
        log.accept("snapshot: restored " + tables.size() + " tables from " + name);
        return tables.size();
    }

    /**
     * Snapshots present in the database.
     *
     * @return snapshot names
     * @throws SQLException on database errors
     */
    public List<String> list() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT schema_name FROM information_schema.schemata "
                     + "WHERE schema_name LIKE 'loadtest\\_snapshot\\_%' ORDER BY schema_name")) {
            while (rs.next()) {
                out.add(rs.getString(1).substring("loadtest_snapshot_".length()));
            }
        }
        return out;
    }

    /**
     * Drops a snapshot.
     *
     * @param name snapshot name
     * @throws SQLException on database errors
     */
    public void drop(String name) throws SQLException {
        guard();
        String snap = schemaOf(name);
        write(st -> st.execute("DROP SCHEMA IF EXISTS " + db.quoted(snap) + " CASCADE"));
        log.accept("snapshot: dropped " + name);
    }

    /** Tables of different application schemas keep distinct names inside the snapshot schema. */
    private static String copyName(DbTable t) {
        return t.schema() == null || t.schema().equalsIgnoreCase("public") ? t.name() : t.schema() + "__" + t.name();
    }

    private void resetSequences(Statement st, DbTable t) throws SQLException {
        for (String c : t.generatedColumns()) {
            String type = t.columns().get(c).toLowerCase(Locale.ROOT);
            if (!(type.contains("int") || type.contains("serial"))) {
                continue;
            }
            String table = t.schema() == null ? db.quoted(t.name()) : db.quoted(t.schema()) + "." + db.quoted(t.name());
            try (ResultSet rs = st.executeQuery("SELECT pg_get_serial_sequence('" + table.replace("'", "''") + "', '"
                    + c.replace("'", "''") + "')")) {
                if (!rs.next() || rs.getString(1) == null) {
                    continue; // a default that is not a sequence
                }
            }
            st.execute("SELECT setval(pg_get_serial_sequence('" + table.replace("'", "''") + "', '"
                    + c.replace("'", "''") + "'), COALESCE((SELECT MAX(" + db.quoted(c) + ") FROM "
                    + db.qualifiedName(t) + "), 0) + 1, false)");
        }
    }

    private boolean exists(String snap) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.schemata WHERE schema_name = '"
                     + snap + "'")) {
            return rs.next();
        }
    }

    private Set<String> savedTables(String snap) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                     + snap + "'")) {
            while (rs.next()) {
                out.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private void guard() {
        if (!allowProduction && BulkLoader.PRODUCTION.matcher(url).find()) {
            throw new IllegalArgumentException("refusing to snapshot/restore a production-looking database "
                    + "(allowProduction to override)");
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(Statement st) throws SQLException;
    }

    private void write(Work work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setReadOnly(false);
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(0);
            work.run(st);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
            connection.setReadOnly(true);
        }
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
