package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.data.DbTable;
import com.springaimcpservercommon.loadtest.data.PoolRef;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the tables a Spring project's own DDL declares (Flyway migrations, Liquibase formatted-SQL changelogs,
 * {@code schema.sql}), replaying the scripts in migration order: {@code CREATE TABLE} with inline and table-level
 * primary keys, foreign keys ({@code REFERENCES}), unique constraints and {@code varchar(n)} lengths;
 * {@code ALTER TABLE … ADD/DROP/RENAME/ALTER/MODIFY}; {@code CREATE UNIQUE INDEX}; {@code DROP TABLE}.
 * <p>
 * This gives projects without JPA entities (MyBatis, JdbcTemplate, jOOQ, Spring Data JDBC) the same
 * relationship graph JDBC metadata would, without a running database. Schemas that declare no foreign-key
 * constraints (common with MySQL/MyBatis) get them inferred by naming: a column {@code author_id} with no
 * declared reference points at the primary key of table {@code author}/{@code authors} when exactly one exists.
 * It is a tolerant reader, not a SQL parser: statements it does not understand are skipped. A live database's
 * metadata, when reachable, is preferred over it.
 */
public final class SqlSchemaReader {

    private static final String IDENT = "(?:`[^`]+`|\"[^\"]+\"|\\[[^\\]]+]|[\\w$]+)";
    private static final String NAME = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^create\\s+(?:or\\s+replace\\s+)?(?:(?:global|local)\\s+)?(?:temporary\\s+|temp\\s+|unlogged\\s+)?"
                    + "table\\s+(?:if\\s+not\\s+exists\\s+)?(" + NAME + ")\\s*\\(", FLAGS);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?(" + NAME + ")\\s+(.*)$", FLAGS);
    private static final Pattern UNIQUE_INDEX = Pattern.compile(
            "^create\\s+unique\\s+(?:clustered\\s+|nonclustered\\s+)?index\\s+(?:concurrently\\s+)?"
                    + "(?:if\\s+not\\s+exists\\s+)?(?:" + NAME + "\\s+)?on\\s+(?:only\\s+)?(" + NAME + ")\\s*"
                    + "(?:using\\s+\\w+\\s*)?\\(([^()]*)\\)", FLAGS);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "^drop\\s+table\\s+(?:if\\s+exists\\s+)?(.+?)(?:\\s+(?:cascade|restrict))?$", FLAGS);
    private static final Pattern RENAME_TABLE = Pattern.compile(
            "^(?:rename\\s+table\\s+)(" + NAME + ")\\s+to\\s+(" + NAME + ")$", FLAGS);
    private static final Pattern COLUMN_DEF = Pattern.compile("^(" + IDENT + ")\\s+(.*)$", FLAGS);
    private static final Pattern WORD = Pattern.compile("\\G\\s*([a-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile(
            "\\G\\s*\\(\\s*(\\d+)\\s*(?:,\\s*\\d+\\s*)?(?:byte|char)?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> COLUMN_KEYWORDS = Set.of("not", "null", "primary", "unique", "references",
            "default", "constraint", "check", "generated", "auto_increment", "identity", "collate", "comment", "on",
            "as", "character", "charset");
    private static final Pattern REFERENCES = Pattern.compile(
            "\\breferences\\s+(" + NAME + ")\\s*(?:\\(\\s*(" + IDENT + ")\\s*\\))?", FLAGS);
    private static final Pattern PRIMARY_KEY = Pattern.compile(
            "^primary\\s+key\\s*(?:clustered\\s+|nonclustered\\s+)?(?:" + IDENT + "\\s*)?\\(([^()]*)\\)", FLAGS);
    private static final Pattern UNIQUE = Pattern.compile(
            "^unique\\s*(?:key|index)?\\s*(?:nulls\\s+(?:not\\s+)?distinct\\s*)?(?:" + IDENT
                    + "\\s*)?\\(([^()]*)\\)", FLAGS);
    private static final Pattern FOREIGN_KEY = Pattern.compile(
            "^foreign\\s+key\\s*(?:" + IDENT + "\\s*)?\\(([^()]*)\\)\\s*references\\s+(" + NAME + ")\\s*"
                    + "(?:\\(([^()]*)\\))?", FLAGS);
    private static final Pattern FLYWAY = Pattern.compile("^([VvRrUu])(\\d+(?:[._]\\d+)*)?__.*");
    private static final Set<String> TABLE_LEVEL = Set.of("constraint", "primary", "unique", "foreign", "key",
            "index", "check", "exclude", "fulltext", "spatial", "like", "period", "inherits");

    private final Consumer<String> log;

    /**
     * Creates a reader.
     *
     * @param log receives a summary line
     */
    public SqlSchemaReader(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Reads the tables the project's schema scripts declare.
     *
     * @param projectDir project root
     * @return tables in declaration order; empty when the project has no schema scripts
     */
    public List<DbTable> read(Path projectDir) {
        LiquibaseChangelogReader.Result liquibase = LiquibaseChangelogReader.read(projectDir, log);
        List<Path> scripts = new ArrayList<>(ProjectFiles.schemaScripts(projectDir));
        scripts.removeIf(p -> liquibase.files().contains(p.toAbsolutePath().normalize())); // read in changelog order
        scripts.removeIf(p -> FLYWAY.matcher(p.getFileName().toString()).matches()
                && Character.toUpperCase(p.getFileName().toString().charAt(0)) == 'U'); // Flyway undo
        scripts.sort(MIGRATION_ORDER);
        List<String> texts = new ArrayList<>();
        for (Path p : scripts) {
            try {
                texts.add(Files.readString(p));
            } catch (IOException e) {
                log.accept("schema: cannot read " + p + " (" + e.getMessage() + ")");
            }
        }
        if (!liquibase.statements().isEmpty()) {
            texts.add(String.join(";\n", liquibase.statements()));
        }
        List<DbTable> tables = parse(texts);
        if (!tables.isEmpty()) {
            long fks = tables.stream().mapToLong(t -> t.foreignKeys().size() + t.compositeForeignKeys().size()).sum();
            long changelogs = liquibase.files().stream()
                    .filter(f -> !f.getFileName().toString().endsWith(".sql")).count();
            log.accept("schema: " + tables.size() + " tables, " + fks + " foreign keys from " + scripts.size()
                    + " SQL script(s)" + (changelogs > 0 ? " and " + changelogs + " Liquibase changelog(s)" : "")
                    + " (no database needed)");
        }
        return tables;
    }

    /**
     * Replays DDL scripts, in the given order.
     *
     * @param scripts script texts, oldest migration first
     * @return the resulting tables
     */
    public static List<DbTable> parse(List<String> scripts) {
        Map<String, Table> tables = new LinkedHashMap<>();
        for (String script : scripts) {
            for (String statement : statements(script)) {
                apply(tables, statement.strip().replaceAll("\\s+", " "));
            }
        }
        List<DbTable> out = new ArrayList<>();
        for (Table t : tables.values()) {
            out.add(t.build(tables));
        }
        return out;
    }

    // ── ordering ────────────────────────────────────────────────────────────────────────────────────────

    /** schema.sql / Liquibase files (natural path order), then Flyway versioned by version, then repeatable. */
    private static final Comparator<Path> MIGRATION_ORDER = Comparator
            .comparingInt((Path p) -> {
                Matcher m = FLYWAY.matcher(p.getFileName().toString());
                return !m.matches() ? 0 : Character.toUpperCase(m.group(1).charAt(0)) == 'V' ? 1 : 2;
            })
            .thenComparing(p -> {
                Matcher m = FLYWAY.matcher(p.getFileName().toString());
                return m.matches() && m.group(2) != null ? m.group(2) : "";
            }, SqlSchemaReader::compareNatural)
            .thenComparing(Path::toString, SqlSchemaReader::compareNatural);

    /** Compares strings with digit runs compared as numbers ({@code V2} before {@code V10}). */
    static int compareNatural(String a, String b) {
        String[] x = a.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        String[] y = b.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c;
            if (!x[i].isEmpty() && !y[i].isEmpty() && Character.isDigit(x[i].charAt(0))
                    && Character.isDigit(y[i].charAt(0))) {
                c = new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i]));
            } else {
                c = x[i].compareTo(y[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    // ── lexing ──────────────────────────────────────────────────────────────────────────────────────────

    /** Splits a script on top-level semicolons, dropping comments; strings, quoted names and $$ bodies kept. */
    static List<String> statements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
                cur.append(' ');
            } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                cur.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n && (script.charAt(j) != c || j + 1 < n && script.charAt(j + 1) == c && c == '\'')) {
                    j += script.charAt(j) == c ? 2 : 1;
                }
                cur.append(script, i, Math.min(j + 1, n));
                i = j + 1;
            } else if (c == '$' && dollarTag(script, i) != null) {
                String tag = dollarTag(script, i);
                int end = script.indexOf(tag, i + tag.length());
                int stop = end < 0 ? n : end + tag.length();
                cur.append(script, i, stop);
                i = stop;
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
                i++;
            }
        }
        out.add(cur.toString());
        out.removeIf(s -> s.isBlank());
        return out;
    }

    private static @Nullable String dollarTag(String s, int i) {
        Matcher m = Pattern.compile("\\$[A-Za-z_]*\\$").matcher(s);
        return m.find(i) && m.start() == i ? m.group() : null;
    }

    /** Splits on commas outside parentheses and quotes. */
    private static List<String> topLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).strip());
                start = i + 1;
            }
        }
        out.add(s.substring(start).strip());
        out.removeIf(String::isEmpty);
        return out;
    }

    /** The text between the parenthesis at {@code open} and its match, or {@code null} if unbalanced. */
    private static @Nullable String parenthesized(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return s.substring(open + 1, i);
            }
        }
        return null;
    }

    private static String unquote(String ident) {
        String s = ident.strip();
        if (s.length() >= 2 && (s.startsWith("\"") || s.startsWith("`") || s.startsWith("["))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** {@code [schema, table]} of a possibly qualified name. */
    private static String[] qualified(String name) {
        Matcher m = Pattern.compile("(" + IDENT + ")(?:\\s*\\.\\s*(" + IDENT + "))?").matcher(name.strip());
        if (!m.matches()) {
            return new String[] {null, unquote(name)};
        }
        return m.group(2) == null ? new String[] {null, unquote(m.group(1))}
                : new String[] {unquote(m.group(1)), unquote(m.group(2))};
    }

    private static List<String> columnList(String cols) {
        List<String> out = new ArrayList<>();
        for (String c : topLevel(cols)) {
            String col = c.replaceAll("(?i)\\s+(asc|desc)$", "").replaceAll("\\(\\d+\\)$", "").strip();
            if (!col.matches(IDENT)) {
                return List.of(); // an expression index: not a column constraint
            }
            out.add(unquote(col));
        }
        return out;
    }

    // ── statements ──────────────────────────────────────────────────────────────────────────────────────

    private static void apply(Map<String, Table> tables, String sql) {
        Matcher m = CREATE_TABLE.matcher(sql);
        if (m.find()) {
            String body = parenthesized(sql, m.end() - 1);
            if (body != null) {
                String[] q = qualified(m.group(1));
                Table t = new Table(q[0], q[1]);
                for (String def : topLevel(body)) {
                    t.definition(def);
                }
                tables.put(key(q[1]), t);
            }
            return;
        }
        m = ALTER_TABLE.matcher(sql);
        if (m.matches()) {
            Table t = tables.get(key(qualified(m.group(1))[1]));
            if (t != null) {
                for (String action : topLevel(m.group(2))) {
                    t.alter(action, tables);
                }
            }
            return;
        }
        m = UNIQUE_INDEX.matcher(sql);
        if (m.find()) {
            Table t = tables.get(key(qualified(m.group(1))[1]));
            List<String> cols = columnList(m.group(2));
            if (t != null && cols.size() == 1) {
                t.unique.add(t.column(cols.getFirst()));
            }
            return;
        }
        m = RENAME_TABLE.matcher(sql);
        if (m.matches()) {
            Table t = tables.remove(key(qualified(m.group(1))[1]));
            if (t != null) {
                t.name = qualified(m.group(2))[1];
                tables.put(key(t.name), t);
            }
            return;
        }
        m = DROP_TABLE.matcher(sql);
        if (m.matches()) {
            for (String name : topLevel(m.group(1))) {
                tables.remove(key(qualified(name)[1]));
            }
        }
    }

    private static String key(String table) {
        return table.toLowerCase(Locale.ROOT);
    }

    /** A table being built from DDL. */
    private static final class Table {
        private final @Nullable String schema;
        private String name;
        private final Map<String, String> columns = new LinkedHashMap<>();
        private final List<String> pk = new ArrayList<>();
        private final Map<String, String[]> fks = new LinkedHashMap<>(); // column → {schema, table, column|null}
        private final Map<String, Integer> sizes = new LinkedHashMap<>();
        private final Set<String> unique = new LinkedHashSet<>();
        private final Set<String> notNull = new LinkedHashSet<>();
        private final Set<String> generated = new LinkedHashSet<>();
        /** Multi-column foreign keys: {local columns, target schema, target table, target columns or empty}. */
        private final List<Object[]> compositeFks = new ArrayList<>();

        Table(@Nullable String schema, String name) {
            this.schema = schema;
            this.name = name;
        }

        /** The declared spelling of a column (identifiers are case-insensitive unless quoted). */
        String column(String c) {
            for (String k : columns.keySet()) {
                if (k.equalsIgnoreCase(c)) {
                    return k;
                }
            }
            return c;
        }

        void definition(String def) {
            String first = def.split("[\\s(]", 2)[0].toLowerCase(Locale.ROOT);
            if (TABLE_LEVEL.contains(first)) {
                constraint(def.replaceFirst("(?i)^constraint\\s+" + IDENT + "\\s+", ""));
            } else {
                columnDefinition(def);
            }
        }

        void columnDefinition(String def) {
            Matcher m = COLUMN_DEF.matcher(def);
            if (!m.matches()) {
                return;
            }
            String col = unquote(m.group(1));
            String rest = m.group(2);
            sizes.remove(col);
            // the type: words up to a size or a column keyword (character varying(255), double precision)
            StringBuilder typeName = new StringBuilder();
            Matcher w = WORD.matcher(rest);
            int at = 0;
            while (w.find(at) && (typeName.isEmpty()
                    || !COLUMN_KEYWORDS.contains(w.group(1).toLowerCase(Locale.ROOT)))) {
                typeName.append(typeName.isEmpty() ? "" : " ").append(w.group(1).toLowerCase(Locale.ROOT));
                at = w.end();
                Matcher size = SIZE.matcher(rest);
                if (size.find(at)) {
                    if (typeName.toString().contains("char")) {
                        sizes.put(col, Integer.parseInt(size.group(1)));
                    }
                    break;
                }
            }
            columns.put(col, typeName.toString());
            String flags = rest.replaceAll("'(?:[^']|'')*'", "''").toLowerCase(Locale.ROOT);
            notNull.remove(col);
            generated.remove(col);
            if (flags.matches(".*\\bnot\\s+null\\b.*")) {
                notNull.add(col);
            }
            if (typeName.toString().contains("serial") || flags.matches(
                    ".*\\b(default|auto_increment|autoincrement|identity|generated)\\b.*")) {
                generated.add(col); // the database fills it: an insert may leave it out
            }
            if (flags.matches(".*\\bprimary\\s+key\\b.*")) {
                pk.clear();
                pk.add(col);
            }
            if (flags.matches(".*\\bunique\\b.*")) {
                unique.add(col);
            }
            Matcher ref = REFERENCES.matcher(rest);
            if (ref.find()) {
                String[] q = qualified(ref.group(1));
                fks.put(col, new String[] {q[0], q[1], ref.group(2) == null ? null : unquote(ref.group(2))});
            }
        }

        void constraint(String def) {
            Matcher m = PRIMARY_KEY.matcher(def);
            if (m.find()) {
                pk.clear();
                columnList(m.group(1)).forEach(c -> pk.add(column(c)));
                return;
            }
            m = UNIQUE.matcher(def);
            if (m.find()) {
                List<String> cols = columnList(m.group(1));
                if (cols.size() == 1) {
                    unique.add(column(cols.getFirst()));
                }
                return;
            }
            m = FOREIGN_KEY.matcher(def);
            if (m.find()) {
                List<String> cols = columnList(m.group(1));
                List<String> targets = m.group(3) == null ? List.of() : columnList(m.group(3));
                String[] q = qualified(m.group(2));
                if (cols.size() == 1) {
                    fks.put(column(cols.getFirst()),
                            new String[] {q[0], q[1], targets.size() == 1 ? targets.getFirst() : null});
                } else if (!cols.isEmpty()) { // filled together from one parent row (tuple pool)
                    compositeFks.add(new Object[] {cols.stream().map(this::column).toList(), q[0], q[1], targets});
                }
            }
        }

        void alter(String action, Map<String, Table> tables) {
            String a = action.strip();
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.matches("^add\\s+(constraint\\s+.*|primary\\s+key.*|unique.*|foreign\\s+key.*)$")) {
                constraint(a.replaceFirst("(?i)^add\\s+", "").replaceFirst("(?i)^constraint\\s+" + IDENT + "\\s+", ""));
            } else if (lower.startsWith("add ")) {
                columnDefinition(a.replaceFirst("(?i)^add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?", ""));
            } else if (lower.matches("^drop\\s+(column\\s+)?(if\\s+exists\\s+)?(?!constraint\\b|index\\b|key\\b|"
                    + "primary\\b|foreign\\b|unique\\b|check\\b)\\S+.*")) {
                String col = column(unquote(a.replaceFirst("(?i)^drop\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?", "")
                        .split("\\s+")[0]));
                columns.remove(col);
                notNull.remove(col);
                generated.remove(col);
                pk.remove(col);
                fks.remove(col);
                sizes.remove(col);
                unique.remove(col);
            } else if (lower.matches("^rename\\s+column\\s+.+\\s+to\\s+.+$")) {
                Matcher m = Pattern.compile("(?i)^rename\\s+column\\s+(" + IDENT + ")\\s+to\\s+(" + IDENT + ")$")
                        .matcher(a);
                if (m.matches()) {
                    rename(column(unquote(m.group(1))), unquote(m.group(2)));
                }
            } else if (lower.matches("^rename\\s+to\\s+.+$")) {
                tables.remove(key(name));
                name = qualified(a.replaceFirst("(?i)^rename\\s+to\\s+", ""))[1];
                tables.put(key(name), this);
            } else if (lower.matches("^(modify|change)\\s+.*")) {
                // MySQL: MODIFY [COLUMN] c type …  /  CHANGE [COLUMN] old new type …
                String def = a.replaceFirst("(?i)^(modify|change)\\s+(?:column\\s+)?", "");
                if (lower.startsWith("change")) {
                    String[] parts = def.split("\\s+", 2);
                    String old = column(unquote(parts[0]));
                    if (parts.length == 2) {
                        Matcher nm = COLUMN_DEF.matcher(parts[1]);
                        if (nm.matches()) {
                            rename(old, unquote(nm.group(1)));
                        }
                        def = parts[1];
                    }
                }
                retype(def);
            } else if (lower.matches("^alter\\s+(column\\s+)?\\S+\\s+(set|drop)\\s+(not\\s+null|default)\\b.*")) {
                Matcher m = Pattern.compile("(?i)^alter\\s+(?:column\\s+)?(" + IDENT
                        + ")\\s+(set|drop)\\s+(not\\s+null|default)\\b.*$").matcher(a);
                if (m.matches()) {
                    String col = column(unquote(m.group(1)));
                    boolean set = m.group(2).equalsIgnoreCase("set");
                    Set<String> target = m.group(3).toLowerCase(Locale.ROOT).startsWith("not") ? notNull : generated;
                    if (set) {
                        target.add(col);
                    } else {
                        target.remove(col);
                    }
                }
            } else if (lower.matches("^alter\\s+(column\\s+)?\\S+\\s+(set\\s+data\\s+)?type\\s+.*")) {
                Matcher m = Pattern.compile("(?i)^alter\\s+(?:column\\s+)?(" + IDENT
                        + ")\\s+(?:set\\s+data\\s+)?type\\s+(.*)$").matcher(a);
                if (m.matches()) {
                    retype(m.group(1) + " " + m.group(2));
                }
            }
        }

        /** A new type for an existing column, keeping its key, foreign key and uniqueness. */
        private void retype(String def) {
            Matcher m = COLUMN_DEF.matcher(def);
            if (m.matches()) {
                String col = column(unquote(m.group(1)));
                boolean wasUnique = unique.contains(col);
                boolean wasNotNull = notNull.contains(col);
                boolean wasGenerated = generated.contains(col);
                String[] fk = fks.get(col);
                List<String> key = new ArrayList<>(pk);
                columnDefinition(col + " " + m.group(2));
                if (wasUnique) {
                    unique.add(col);
                }
                boolean pgStyle = !m.group(2).toLowerCase(Locale.ROOT).matches(".*\\b(null|default)\\b.*");
                if (pgStyle && wasNotNull) {
                    notNull.add(col); // ALTER … TYPE changes the type only
                }
                if (pgStyle && wasGenerated) {
                    generated.add(col);
                }
                if (fk != null && !fks.containsKey(col)) {
                    fks.put(col, fk);
                }
                if (!key.equals(pk) && !m.group(2).toLowerCase(Locale.ROOT).contains("primary")) {
                    pk.clear();
                    pk.addAll(key);
                }
            }
        }

        private void rename(String from, String to) {
            Map<String, String> renamed = new LinkedHashMap<>();
            columns.forEach((k, v) -> renamed.put(k.equals(from) ? to : k, v));
            columns.clear();
            columns.putAll(renamed);
            pk.replaceAll(c -> c.equals(from) ? to : c);
            if (fks.containsKey(from)) {
                fks.put(to, fks.remove(from));
            }
            if (sizes.containsKey(from)) {
                sizes.put(to, sizes.remove(from));
            }
            if (unique.remove(from)) {
                unique.add(to);
            }
            if (notNull.remove(from)) {
                notNull.add(to);
            }
            if (generated.remove(from)) {
                generated.add(to);
            }
            for (Object[] fk : compositeFks) {
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) fk[0];
                fk[0] = cols.stream().map(c -> c.equals(from) ? to : c).toList();
            }
        }

        /** {@code user_id} → table {@code user}/{@code users}, when it has a single-column primary key. */
        private static @Nullable Table implied(String column, Map<String, Table> all) {
            Matcher m = Pattern.compile("(?i)^(.+?)_?id$").matcher(column);
            if (!m.matches() || column.equalsIgnoreCase("id")) {
                return null;
            }
            String base = m.group(1).replaceAll("_$", "").toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>(List.of(base, base + "s", base + "es"));
            if (base.endsWith("y")) {
                names.add(base.substring(0, base.length() - 1) + "ies");
            }
            List<Table> found = new ArrayList<>();
            for (String n : names) {
                Table t = all.get(n);
                if (t != null && t.pk.size() == 1 && !found.contains(t)) {
                    found.add(t);
                }
            }
            return found.size() == 1 ? found.getFirst() : null;
        }

        DbTable build(Map<String, Table> all) {
            for (String col : columns.keySet()) {
                if (!fks.containsKey(col) && !(pk.size() == 1 && pk.contains(col)) && !col.equalsIgnoreCase("id")) {
                    Table target = implied(col, all);
                    if (target != null && target != this) {
                        fks.put(col, new String[] {target.schema, target.name, target.pk.getFirst()});
                    }
                }
            }
            Map<String, PoolRef> refs = new LinkedHashMap<>();
            fks.forEach((col, target) -> {
                if (!columns.containsKey(col)) {
                    return;
                }
                Table t = all.get(key(target[1]));
                String column = target[2] != null ? (t != null ? t.column(target[2]) : target[2])
                        : t != null && t.pk.size() == 1 ? t.pk.getFirst() : null;
                if (column != null) {
                    refs.put(col, new PoolRef(t != null ? t.schema : target[0], t != null ? t.name : target[1],
                            column));
                }
            });
            Set<String> uniqueNonKey = new LinkedHashSet<>(unique);
            uniqueNonKey.retainAll(columns.keySet());
            if (pk.size() == 1) {
                uniqueNonKey.remove(pk.getFirst());
            }
            List<DbTable.CompositeForeignKey> composite = new ArrayList<>();
            for (Object[] fk : compositeFks) {
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) fk[0];
                @SuppressWarnings("unchecked")
                List<String> targetCols = (List<String>) fk[3];
                Table t = all.get(key((String) fk[2]));
                List<String> resolved = !targetCols.isEmpty() ? targetCols.stream()
                        .map(c -> t != null ? t.column(c) : c).toList()
                        : t != null && t.pk.size() == cols.size() ? List.copyOf(t.pk) : List.of();
                if (resolved.size() == cols.size() && columns.keySet().containsAll(cols)) {
                    composite.add(new DbTable.CompositeForeignKey(cols, new PoolRef(
                            t != null ? t.schema : (String) fk[1], t != null ? t.name : (String) fk[2],
                            String.join(",", resolved))));
                }
            }
            Set<String> required = new LinkedHashSet<>(notNull);
            required.addAll(pk);
            required.removeAll(generated);
            required.retainAll(columns.keySet());
            Set<String> gen = new LinkedHashSet<>(generated);
            gen.retainAll(columns.keySet());
            return new DbTable(schema, name, columns, pk, refs, sizes, uniqueNonKey, composite, required, gen);
        }
    }
}
