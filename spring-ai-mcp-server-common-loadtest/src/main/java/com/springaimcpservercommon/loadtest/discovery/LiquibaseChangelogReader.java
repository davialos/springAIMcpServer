package com.springaimcpservercommon.loadtest.discovery;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Turns Liquibase changelogs (XML, YAML, JSON, and formatted SQL reached through {@code include}) into the DDL
 * statements they stand for, in changelog order, so {@link SqlSchemaReader} replays them like Flyway scripts.
 * Supported changes: {@code createTable}, {@code addColumn}, {@code dropColumn}, {@code renameColumn},
 * {@code renameTable}, {@code dropTable}, {@code addPrimaryKey}, {@code addUniqueConstraint},
 * {@code addForeignKeyConstraint}, {@code createIndex} (unique), {@code modifyDataType},
 * {@code addNotNullConstraint}/{@code dropNotNullConstraint}, {@code addDefaultValue}/{@code dropDefaultValue},
 * {@code addAutoIncrement}, {@code sql} and {@code sqlFile}. Rollback blocks and preconditions are ignored; other
 * changes are skipped (a tolerant reader, like the SQL one).
 */
public final class LiquibaseChangelogReader {

    /** A changelog element, from XML or YAML/JSON alike. */
    record Node(String name, Map<String, String> attrs, List<Node> children, String text) {

        String attr(String key) {
            return attrs.getOrDefault(key, "");
        }

        List<Node> named(String child) {
            return children.stream().filter(c -> c.name().equals(child)).toList();
        }
    }

    /**
     * What was read.
     *
     * @param statements DDL statements in changelog order
     * @param files      every file read (changelogs and included SQL), so the SQL scan can skip them
     */
    public record Result(List<String> statements, Set<Path> files) {
    }

    private final Path resources;
    private final Consumer<String> log;
    private final Set<Path> visited = new LinkedHashSet<>();
    private final List<String> statements = new ArrayList<>();

    private LiquibaseChangelogReader(Path resources, Consumer<String> log) {
        this.resources = resources;
        this.log = log;
    }

    /**
     * Reads every root changelog under the project's {@code src/main/resources} (a changelog no other changelog
     * includes), following includes.
     *
     * @param projectDir project root
     * @param log        notes on unreadable files
     * @return statements and files read; empty when the project has no Liquibase changelog
     */
    public static Result read(Path projectDir, Consumer<String> log) {
        List<Path> changelogs = ProjectFiles.liquibaseChangelogs(projectDir);
        if (changelogs.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        Set<Path> all = new LinkedHashSet<>();
        List<String> statements = new ArrayList<>();
        // roots first: read each candidate in a dry pass to learn what it includes
        Set<Path> included = new LinkedHashSet<>();
        for (Path c : changelogs) {
            LiquibaseChangelogReader probe = new LiquibaseChangelogReader(resourcesOf(c), s -> { });
            probe.readFile(c);
            probe.visited.remove(c.toAbsolutePath().normalize());
            included.addAll(probe.visited);
        }
        for (Path c : changelogs) {
            if (included.contains(c.toAbsolutePath().normalize())) {
                continue;
            }
            LiquibaseChangelogReader r = new LiquibaseChangelogReader(resourcesOf(c), log);
            r.readFile(c);
            statements.addAll(r.statements);
            all.addAll(r.visited);
        }
        return new Result(statements, all);
    }

    /**
     * The statements one changelog text stands for (tests, single files).
     *
     * @param text      changelog content
     * @param xml       {@code true} for XML, else YAML/JSON
     * @return DDL statements
     */
    static List<String> statementsOf(String text, boolean xml) {
        LiquibaseChangelogReader r = new LiquibaseChangelogReader(Path.of("."), s -> { });
        Node root = xml ? xmlNode(text) : jsonNode("databaseChangeLog", Documents.parse(text)
                .path("databaseChangeLog"));
        r.changelog(root, Path.of("."));
        return r.statements;
    }

    private static Path resourcesOf(Path changelog) {
        Path p = changelog.toAbsolutePath().normalize();
        for (Path cur = p.getParent(); cur != null; cur = cur.getParent()) {
            if (cur.getFileName() != null && cur.getFileName().toString().equals("resources")
                    && cur.getParent() != null && cur.getParent().getFileName().toString().equals("main")) {
                return cur;
            }
        }
        return p.getParent();
    }

    private void readFile(Path file) {
        Path f = file.toAbsolutePath().normalize();
        if (!visited.add(f)) {
            return;
        }
        String text;
        try {
            text = Files.readString(f);
        } catch (IOException e) {
            log.accept("liquibase: cannot read " + f + " (" + e.getMessage() + ")");
            return;
        }
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".sql")) {
                statements.addAll(SqlSchemaReader.statements(text));
            } else if (name.endsWith(".xml")) {
                changelog(xmlNode(text), f.getParent());
            } else {
                JsonNode root = Documents.parse(text);
                changelog(jsonNode("databaseChangeLog", root.path("databaseChangeLog")), f.getParent());
            }
        } catch (RuntimeException e) {
            log.accept("liquibase: cannot parse " + f + " (" + e.getMessage() + ")");
        }
    }

    private void changelog(Node root, Path dir) {
        for (Node n : root.children()) {
            switch (n.name()) {
                case "changeSet" -> {
                    for (Node change : n.children()) {
                        if (!Set.of("rollback", "preConditions", "comment", "validCheckSum").contains(change.name())) {
                            change(change, dir);
                        }
                    }
                }
                case "include" -> {
                    String file = n.attr("file");
                    if (!file.isBlank()) {
                        readFile(resolve(file, Boolean.parseBoolean(n.attr("relativeToChangelogFile")), dir));
                    }
                }
                case "includeAll" -> {
                    Path folder = resolve(n.attr("path"), Boolean.parseBoolean(n.attr("relativeToChangelogFile")),
                            dir);
                    if (Files.isDirectory(folder)) {
                        try (Stream<Path> files = Files.list(folder)) {
                            files.filter(p -> p.getFileName().toString().matches("(?i).*\\.(xml|ya?ml|json|sql)$"))
                                    .sorted().forEach(this::readFile);
                        } catch (IOException e) {
                            log.accept("liquibase: cannot list " + folder);
                        }
                    }
                }
                default -> { }
            }
        }
    }

    private Path resolve(String file, boolean relativeToChangelog, Path dir) {
        String f = file.replaceFirst("^classpath\\*?:", "").replaceFirst("^/", "");
        if (relativeToChangelog) {
            return dir.resolve(f).normalize();
        }
        Path inResources = resources.resolve(f).normalize();
        return Files.exists(inResources) ? inResources : dir.resolve(f).normalize();
    }

    // ── change → DDL ───────────────────────────────────────────────────────────────────────────────────

    private void change(Node c, Path dir) {
        String table = table(c, "tableName");
        switch (c.name()) {
            case "createTable" -> {
                List<String> defs = new ArrayList<>();
                List<String> pk = new ArrayList<>();
                for (Node col : columns(c)) {
                    defs.add(column(col));
                    Node cons = constraints(col);
                    if (cons != null && Boolean.parseBoolean(cons.attr("primaryKey"))) {
                        pk.add(q(col.attr("name")));
                    }
                }
                if (!pk.isEmpty()) {
                    defs.add("PRIMARY KEY (" + String.join(", ", pk) + ")");
                }
                emit("CREATE TABLE " + table + " (" + String.join(", ", defs) + ")");
            }
            case "addColumn" -> {
                for (Node col : columns(c)) {
                    emit("ALTER TABLE " + table + " ADD COLUMN " + column(col));
                }
            }
            case "dropColumn" -> {
                if (!c.attr("columnName").isBlank()) {
                    emit("ALTER TABLE " + table + " DROP COLUMN " + q(c.attr("columnName")));
                }
                for (Node col : columns(c)) {
                    emit("ALTER TABLE " + table + " DROP COLUMN " + q(col.attr("name")));
                }
            }
            case "renameColumn" -> emit("ALTER TABLE " + table + " RENAME COLUMN " + q(c.attr("oldColumnName"))
                    + " TO " + q(c.attr("newColumnName")));
            case "renameTable" -> emit("ALTER TABLE " + table(c, "oldTableName") + " RENAME TO "
                    + q(c.attr("newTableName")));
            case "dropTable" -> emit("DROP TABLE " + table);
            case "addPrimaryKey" -> emit("ALTER TABLE " + table + " ADD PRIMARY KEY (" + names(c.attr("columnNames"))
                    + ")");
            case "addUniqueConstraint" -> emit("ALTER TABLE " + table + " ADD UNIQUE (" + names(c.attr("columnNames"))
                    + ")");
            case "addForeignKeyConstraint" -> emit("ALTER TABLE " + table(c, "baseTableName") + " ADD FOREIGN KEY ("
                    + names(c.attr("baseColumnNames")) + ") REFERENCES " + table(c, "referencedTableName") + " (" + names(c.attr("referencedColumnNames")) + ")");
            case "createIndex" -> {
                if (Boolean.parseBoolean(c.attr("unique"))) {
                    List<String> cols = columns(c).stream().map(col -> q(col.attr("name"))).toList();
                    emit("CREATE UNIQUE INDEX " + q(c.attr("indexName").isBlank() ? "ix" : c.attr("indexName"))
                            + " ON " + table + " (" + String.join(", ", cols) + ")");
                }
            }
            case "modifyDataType" -> emit("ALTER TABLE " + table + " ALTER COLUMN " + q(c.attr("columnName"))
                    + " TYPE " + c.attr("newDataType"));
            case "addNotNullConstraint" -> emit("ALTER TABLE " + table + " ALTER COLUMN " + q(c.attr("columnName"))
                    + " SET NOT NULL");
            case "dropNotNullConstraint" -> emit("ALTER TABLE " + table + " ALTER COLUMN " + q(c.attr("columnName"))
                    + " DROP NOT NULL");
            case "addDefaultValue", "addAutoIncrement" -> emit("ALTER TABLE " + table + " ALTER COLUMN "
                    + q(c.attr("columnName")) + " SET DEFAULT 0");
            case "dropDefaultValue" -> emit("ALTER TABLE " + table + " ALTER COLUMN " + q(c.attr("columnName"))
                    + " DROP DEFAULT");
            case "sql" -> statements.addAll(SqlSchemaReader.statements(c.text()));
            case "sqlFile" -> readFile(resolve(c.attr("path"), Boolean.parseBoolean(c.attr("relativeToChangelogFile")),
                    dir));
            default -> { }
        }
    }

    private void emit(String sql) {
        statements.add(sql);
    }

    private static List<Node> columns(Node c) {
        List<Node> out = new ArrayList<>(c.named("column"));
        for (Node group : c.named("columns")) {
            out.addAll(group.named("column"));
        }
        return out;
    }

    private static @Nullable Node constraints(Node column) {
        List<Node> c = column.named("constraints");
        return c.isEmpty() ? null : c.getFirst();
    }

    private static String column(Node col) {
        StringBuilder sb = new StringBuilder(q(col.attr("name"))).append(' ')
                .append(col.attr("type").isBlank() ? "varchar" : col.attr("type"));
        if (Boolean.parseBoolean(col.attr("autoIncrement"))) {
            sb.append(" GENERATED BY DEFAULT AS IDENTITY");
        }
        for (String d : List.of("defaultValue", "defaultValueNumeric", "defaultValueBoolean", "defaultValueDate",
                "defaultValueComputed", "defaultValueSequenceNext")) {
            if (!col.attr(d).isBlank()) {
                sb.append(" DEFAULT 0");
                break;
            }
        }
        Node cons = constraints(col);
        if (cons != null) {
            if ("false".equalsIgnoreCase(cons.attr("nullable")) || Boolean.parseBoolean(cons.attr("primaryKey"))) {
                sb.append(" NOT NULL");
            }
            if (Boolean.parseBoolean(cons.attr("unique"))) {
                sb.append(" UNIQUE");
            }
            String references = cons.attr("references");
            if (!references.isBlank()) {
                sb.append(" REFERENCES ").append(references);
            } else if (!cons.attr("referencedTableName").isBlank()) {
                sb.append(" REFERENCES ").append(q(cons.attr("referencedTableName")));
                if (!cons.attr("referencedColumnNames").isBlank()) {
                    sb.append(" (").append(names(cons.attr("referencedColumnNames"))).append(')');
                }
            }
        }
        return sb.toString();
    }

    private static String table(Node c, String attr) {
        String schemaAttr = switch (attr) {
            case "baseTableName" -> "baseTableSchemaName";
            case "referencedTableName" -> "referencedTableSchemaName";
            default -> "schemaName";
        };
        String schema = c.attr(schemaAttr);
        return (schema.isBlank() ? "" : q(schema) + ".") + q(c.attr(attr));
    }

    private static String names(String csv) {
        List<String> out = new ArrayList<>();
        for (String n : csv.split(",")) {
            if (!n.isBlank()) {
                out.add(q(n.trim()));
            }
        }
        return String.join(", ", out);
    }

    private static String q(String identifier) {
        return "\"" + identifier.trim().replace("\"", "") + "\"";
    }

    // ── documents → nodes ──────────────────────────────────────────────────────────────────────────────

    private static Node xmlNode(String text) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return element(b.parse(new org.xml.sax.InputSource(new StringReader(text))).getDocumentElement());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("not a Liquibase XML changelog: " + e.getMessage(), e);
        }
    }

    private static Node element(Element e) {
        Map<String, String> attrs = new LinkedHashMap<>();
        NamedNodeMap a = e.getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            String n = a.item(i).getLocalName() != null ? a.item(i).getLocalName() : a.item(i).getNodeName();
            attrs.put(n, a.item(i).getNodeValue());
        }
        List<Node> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) {
                children.add(element(child));
            } else if (c.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    || c.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                text.append(c.getNodeValue());
            }
        }
        String name = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
        return new Node(name, attrs, children, text.toString().strip());
    }

    /** YAML/JSON: scalars become attributes, objects and single-key array items become children. */
    private static Node jsonNode(String name, JsonNode n) {
        Map<String, String> attrs = new LinkedHashMap<>();
        List<Node> children = new ArrayList<>();
        String text = "";
        if (n.isArray()) {
            for (JsonNode item : n) {
                addItem(item, children);
            }
        } else if (n.isObject()) {
            for (var e : n.properties()) {
                JsonNode v = e.getValue();
                if (v.isValueNode()) {
                    attrs.put(e.getKey(), v.asString());
                    if (e.getKey().equals("sql")) {
                        text = v.asString();
                    }
                } else if (v.isArray()) {
                    Node group = new Node(e.getKey(), Map.of(), new ArrayList<>(), "");
                    for (JsonNode item : v) {
                        addItem(item, group.children());
                    }
                    children.add(group);
                } else {
                    children.add(jsonNode(e.getKey(), v));
                }
            }
        } else if (n.isValueNode()) {
            text = n.asString();
        }
        if (name.equals("changeSet")) { // changes: [ {createTable: …}, … ] → the changes are the children
            List<Node> flat = new ArrayList<>();
            for (Node c : children) {
                flat.addAll(c.name().equals("changes") ? c.children() : List.of(c));
            }
            return new Node(name, attrs, flat, text);
        }
        if (name.equals("sql") && text.isEmpty()) {
            text = attrs.getOrDefault("sql", "");
        }
        return new Node(name, attrs, children, text);
    }

    private static void addItem(JsonNode item, List<Node> out) {
        if (item.isObject() && item.size() == 1) {
            var e = item.properties().iterator().next();
            out.add(jsonNode(e.getKey(), e.getValue()));
        } else if (item.isObject()) {
            out.add(jsonNode("item", item));
        }
    }
}
