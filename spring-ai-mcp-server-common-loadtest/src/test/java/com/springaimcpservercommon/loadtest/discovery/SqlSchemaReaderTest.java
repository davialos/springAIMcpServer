package com.springaimcpservercommon.loadtest.discovery;

import com.springaimcpservercommon.loadtest.data.DbTable;
import com.springaimcpservercommon.loadtest.data.PoolRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tables, keys and relationships from Flyway / Liquibase-SQL / schema.sql DDL. */
class SqlSchemaReaderTest {

    private static DbTable table(List<DbTable> tables, String name) {
        return tables.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + tables.stream().map(DbTable::name).toList()));
    }

    private static Map<String, String> fks(DbTable t) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        t.foreignKeys().forEach((k, v) -> out.put(k, v.key()));
        return out;
    }

    @Test
    void postgresInlineAndTableLevelConstraints() {
        List<DbTable> tables = SqlSchemaReader.parse(List.of("""
                -- customers; with a comment containing CREATE TABLE nope (x int);
                CREATE TABLE IF NOT EXISTS public.customers (
                    id          bigserial PRIMARY KEY,
                    email       character varying(120) NOT NULL UNIQUE,
                    "Full Name" varchar(80),
                    note        text DEFAULT 'unique; not a constraint',
                    created_at  timestamp with time zone DEFAULT now()
                );
                /* block
                   comment */
                CREATE TABLE orders (
                    id bigint GENERATED ALWAYS AS IDENTITY,
                    customer_id bigint NOT NULL REFERENCES customers,
                    ref char(12),
                    amount numeric(12, 2),
                    CONSTRAINT orders_pk PRIMARY KEY (id),
                    CONSTRAINT orders_ref_uq UNIQUE (ref),
                    CONSTRAINT orders_pair UNIQUE (customer_id, ref)
                );
                CREATE TABLE order_lines (
                    order_id bigint, line_no int, product_sku varchar(20),
                    PRIMARY KEY (order_id, line_no),
                    FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
                );
                """));
        DbTable customers = table(tables, "customers");
        assertThat(customers.schema()).isEqualTo("public");
        assertThat(customers.primaryKey()).containsExactly("id");
        assertThat(customers.columns()).containsKeys("id", "email", "Full Name", "note", "created_at")
                .containsEntry("email", "character varying").containsEntry("created_at", "timestamp with time zone");
        assertThat(customers.columnSizes()).containsEntry("email", 120).containsEntry("Full Name", 80)
                .doesNotContainKey("note");
        assertThat(customers.uniqueColumns()).containsExactly("email"); // the default's text says nothing

        DbTable orders = table(tables, "orders");
        assertThat(orders.primaryKey()).containsExactly("id");
        assertThat(orders.uniqueColumns()).containsExactly("ref"); // the two-column constraint is not per column
        assertThat(orders.columnSizes()).containsEntry("ref", 12).doesNotContainKey("amount");
        assertThat(fks(orders)).containsExactly(Map.entry("customer_id", "customers.id")); // to its PK

        DbTable lines = table(tables, "order_lines");
        assertThat(lines.primaryKey()).containsExactly("order_id", "line_no");
        assertThat(fks(lines)).containsExactly(Map.entry("order_id", "orders.id"));
    }

    @Test
    void migrationsAreReplayedInOrder() {
        List<DbTable> tables = SqlSchemaReader.parse(List.of("""
                create table authors (id int primary key, name varchar(50));
                create table books (id int primary key, title varchar(100), author int);
                create table legacy (id int primary key);
                """, """
                ALTER TABLE books ADD CONSTRAINT fk_books_author FOREIGN KEY (author) REFERENCES authors (id);
                ALTER TABLE books ADD COLUMN isbn varchar(17), ADD UNIQUE (isbn);
                ALTER TABLE authors RENAME COLUMN name TO full_name;
                ALTER TABLE authors ALTER COLUMN full_name TYPE varchar(200);
                CREATE UNIQUE INDEX books_title_uq ON books USING btree (title);
                CREATE UNIQUE INDEX books_lower_uq ON books (lower(title));
                DROP TABLE IF EXISTS legacy CASCADE;
                ALTER TABLE books DROP COLUMN IF EXISTS isbn;
                ALTER TABLE books ADD COLUMN isbn13 varchar(13);
                """));
        assertThat(tables).extracting(DbTable::name).containsExactly("authors", "books");
        DbTable books = table(tables, "books");
        assertThat(fks(books)).containsExactly(Map.entry("author", "authors.id"));
        assertThat(books.columns()).containsOnlyKeys("id", "title", "author", "isbn13");
        assertThat(books.uniqueColumns()).containsExactly("title");
        assertThat(table(tables, "authors").columnSizes()).containsExactly(Map.entry("full_name", 200));
    }

    @Test
    void mysqlAndSqlServerDialects() {
        List<DbTable> tables = SqlSchemaReader.parse(List.of("""
                CREATE TABLE `user` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                  `login` VARCHAR(40) CHARACTER SET utf8mb4 NOT NULL,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_login` (`login`),
                  KEY `idx_x` (`login`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                CREATE TABLE `post` (`id` BIGINT PRIMARY KEY, `user_ref` BIGINT, `title` VARCHAR(90),
                  CONSTRAINT `fk_post_user` FOREIGN KEY (`user_ref`) REFERENCES `user` (`id`));
                ALTER TABLE `post` MODIFY COLUMN `title` VARCHAR(150) NOT NULL;
                ALTER TABLE `post` CHANGE `user_ref` `author_ref` BIGINT;
                CREATE TABLE [dbo].[audit] ([id] INT IDENTITY(1,1) PRIMARY KEY, [who] NVARCHAR(64) NULL);
                """));
        DbTable user = table(tables, "user");
        assertThat(user.primaryKey()).containsExactly("id");
        assertThat(user.uniqueColumns()).containsExactly("login");
        assertThat(user.columnSizes()).containsEntry("login", 40);
        DbTable post = table(tables, "post");
        assertThat(post.columnSizes()).containsEntry("title", 150);
        assertThat(fks(post)).containsExactly(Map.entry("author_ref", "user.id")); // followed the rename
        DbTable audit = table(tables, "audit");
        assertThat(audit.schema()).isEqualTo("dbo");
        assertThat(audit.columnSizes()).containsEntry("who", 64);
    }

    @Test
    void foreignKeysAreInferredFromNamingWhenNoneAreDeclared() {
        List<DbTable> tables = SqlSchemaReader.parse(List.of("""
                create table users (id varchar(255) primary key, username varchar(255) UNIQUE);
                create table categories (id int primary key);
                create table articles (id varchar(255) primary key, user_id varchar(255), category_id int,
                                       follow_id varchar(255));
                create table article_favorites (article_id varchar(255) not null, user_id varchar(255) not null,
                                                primary key(article_id, user_id));
                """));
        assertThat(fks(table(tables, "articles")))
                .containsExactly(Map.entry("user_id", "users.id"), Map.entry("category_id", "categories.id"));
        assertThat(fks(table(tables, "article_favorites")))
                .containsExactly(Map.entry("article_id", "articles.id"), Map.entry("user_id", "users.id"));
        assertThat(table(tables, "users").uniqueColumns()).containsExactly("username");
    }

    @Test
    void functionBodiesAndStringsDoNotSplitStatements() {
        List<String> statements = SqlSchemaReader.statements("""
                CREATE FUNCTION touch() RETURNS trigger AS $$
                BEGIN NEW.updated_at = now(); RETURN NEW; END;
                $$ LANGUAGE plpgsql;
                INSERT INTO t VALUES ('a;b', 'it''s; fine');
                create table t2 (id int primary key);
                """);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("RETURN NEW; END;");
        assertThat(statements.get(1)).contains("'it''s; fine'");
    }

    @Test
    void readsTheProjectsScriptsInFlywayVersionOrder(@TempDir Path dir) throws IOException {
        Path migrations = Files.createDirectories(dir.resolve("src/main/resources/db/migration"));
        Files.writeString(migrations.resolve("V10__add_fk.sql"),
                "alter table pets add constraint fk_owner foreign key (owner) references owners(id);");
        Files.writeString(migrations.resolve("V2__pets.sql"), "create table pets (id int primary key, owner int);");
        Files.writeString(migrations.resolve("V1__owners.sql"), "create table owners (id int primary key);");
        Files.writeString(migrations.resolve("U2__undo.sql"), "drop table pets;");
        Files.writeString(dir.resolve("src/main/resources/data.sql"), "create table not_schema (id int);");
        Path tests = Files.createDirectories(dir.resolve("src/test/resources/db/migration"));
        Files.writeString(tests.resolve("V99__test_only.sql"), "create table test_only (id int primary key);");
        List<String> log = new ArrayList<>();
        List<DbTable> tables = new SqlSchemaReader(log::add).read(dir);
        assertThat(tables).extracting(DbTable::name).containsExactly("owners", "pets");
        assertThat(table(tables, "pets").foreignKeys()).containsEntry("owner", new PoolRef(null, "owners", "id"));
        assertThat(log).containsExactly("schema: 2 tables, 1 foreign keys from 3 SQL script(s) (no database needed)");
        assertThat(SqlSchemaReader.compareNatural("1_10", "1_9")).isPositive();
    }

    @Test
    void compositeForeignKeysRequiredAndGeneratedColumns() {
        List<DbTable> tables = SqlSchemaReader.parse(List.of("""
                create table orders (id bigserial primary key, placed_at timestamp not null default now(),
                                     note varchar(40));
                create table order_lines (order_id bigint not null references orders, line_no int not null,
                                          sku varchar(20) not null, primary key (order_id, line_no));
                create table shipment_items (id int generated always as identity primary key,
                                             order_id bigint, line_no int, qty int not null,
                                             foreign key (order_id, line_no) references order_lines (order_id, line_no));
                alter table orders alter column note set not null;
                alter table shipment_items alter column qty set default 1;
                """));
        DbTable items = table(tables, "shipment_items");
        assertThat(items.compositeForeignKeys()).singleElement().satisfies(k -> {
            assertThat(k.columns()).containsExactly("order_id", "line_no");
            assertThat(k.target().key()).isEqualTo("order_lines.order_id,line_no");
        });
        assertThat(items.compositeKeyOf("LINE_NO")).hasValueSatisfying(e -> assertThat(e.getValue()).isEqualTo(1));
        assertThat(items.generatedColumns()).containsExactlyInAnyOrder("id", "qty");
        assertThat(items.requiredColumns()).isEmpty(); // qty got a default, id is an identity
        DbTable orders = table(tables, "orders");
        assertThat(orders.generatedColumns()).containsExactlyInAnyOrder("id", "placed_at");
        assertThat(orders.requiredColumns()).containsExactly("note");
        assertThat(table(tables, "order_lines").requiredColumns()).containsExactlyInAnyOrder("order_id", "line_no",
                "sku");
    }

    @Test
    void liquibaseXmlYamlAndJsonChangelogs() {
        List<String> xml = LiquibaseChangelogReader.statementsOf("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="1" author="a">
                    <createTable tableName="customer">
                      <column name="id" type="BIGINT" autoIncrement="true">
                        <constraints primaryKey="true" nullable="false"/>
                      </column>
                      <column name="email" type="VARCHAR(120)"><constraints unique="true" nullable="false"/></column>
                    </createTable>
                    <createTable tableName="purchase">
                      <column name="id" type="BIGINT"><constraints primaryKey="true"/></column>
                      <column name="customer_ref" type="BIGINT">
                        <constraints nullable="false" foreignKeyName="fk_p_c" references="customer(id)"/>
                      </column>
                    </createTable>
                    <rollback><dropTable tableName="purchase"/></rollback>
                  </changeSet>
                  <changeSet id="2" author="a">
                    <addColumn tableName="purchase"><column name="code" type="VARCHAR(12)"/></addColumn>
                    <createIndex tableName="purchase" indexName="ux_code" unique="true">
                      <column name="code"/>
                    </createIndex>
                    <renameColumn tableName="customer" oldColumnName="email" newColumnName="mail"/>
                    <sql>CREATE TABLE audit (id int primary key);</sql>
                  </changeSet>
                </databaseChangeLog>
                """, true);
        List<DbTable> fromXml = SqlSchemaReader.parse(List.of(String.join(";\n", xml)));
        assertThat(fromXml).extracting(DbTable::name).containsExactly("customer", "purchase", "audit");
        DbTable customer = table(fromXml, "customer");
        assertThat(customer.uniqueColumns()).containsExactly("mail");
        assertThat(customer.columnSizes()).containsEntry("mail", 120);
        assertThat(customer.generatedColumns()).containsExactly("id");
        DbTable purchase = table(fromXml, "purchase");
        assertThat(fks(purchase)).containsExactly(Map.entry("customer_ref", "customer.id"));
        assertThat(purchase.uniqueColumns()).containsExactly("code");
        assertThat(purchase.requiredColumns()).containsExactlyInAnyOrder("id", "customer_ref");

        List<String> yaml = LiquibaseChangelogReader.statementsOf("""
                databaseChangeLog:
                  - changeSet:
                      id: 1
                      author: a
                      changes:
                        - createTable:
                            tableName: header
                            columns:
                              - column: {name: a, type: int, constraints: {primaryKey: true}}
                              - column: {name: b, type: int, constraints: {primaryKey: true}}
                        - createTable:
                            tableName: detail
                            columns:
                              - column: {name: id, type: int, constraints: {primaryKey: true}}
                              - column: {name: ha, type: int}
                              - column: {name: hb, type: int}
                        - addForeignKeyConstraint:
                            baseTableName: detail
                            baseColumnNames: ha, hb
                            referencedTableName: header
                            referencedColumnNames: a, b
                            constraintName: fk_d_h
                        - addNotNullConstraint: {tableName: detail, columnName: ha}
                """, false);
        DbTable detail = table(SqlSchemaReader.parse(List.of(String.join(";\n", yaml))), "detail");
        assertThat(detail.compositeForeignKeys()).singleElement()
                .satisfies(k -> assertThat(k.target().key()).isEqualTo("header.a,b"));
        assertThat(detail.requiredColumns()).containsExactlyInAnyOrder("id", "ha");

        List<String> json = LiquibaseChangelogReader.statementsOf("""
                {"databaseChangeLog": [{"changeSet": {"id": "1", "author": "a", "changes": [
                  {"createTable": {"tableName": "tag", "columns": [
                    {"column": {"name": "id", "type": "int", "constraints": {"primaryKey": true}}},
                    {"column": {"name": "label", "type": "varchar(30)"}}]}},
                  {"dropColumn": {"tableName": "tag", "columnName": "label"}}]}}]}
                """, false);
        assertThat(table(SqlSchemaReader.parse(List.of(String.join(";\n", json))), "tag").columns())
                .containsOnlyKeys("id");
    }

    @Test
    void aProjectsLiquibaseMasterIsFollowedThroughItsIncludes(@TempDir Path dir) throws IOException {
        Path changelog = Files.createDirectories(dir.resolve("src/main/resources/db/changelog"));
        Files.writeString(changelog.resolve("db.changelog-master.yaml"), """
                databaseChangeLog:
                  - include: {file: db/changelog/001-users.xml}
                  - include: {file: 002-posts.sql, relativeToChangelogFile: true}
                """);
        Files.writeString(changelog.resolve("001-users.xml"), """
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="1" author="a">
                    <createTable tableName="users">
                      <column name="id" type="bigint"><constraints primaryKey="true"/></column>
                    </createTable>
                  </changeSet>
                </databaseChangeLog>
                """);
        Files.writeString(changelog.resolve("002-posts.sql"), """
                --liquibase formatted sql
                --changeset a:2
                create table posts (id bigint primary key, author_id bigint references users(id));
                """);
        List<String> log = new ArrayList<>();
        List<DbTable> tables = new SqlSchemaReader(log::add).read(dir);
        assertThat(tables).extracting(DbTable::name).containsExactly("users", "posts"); // include order
        assertThat(fks(table(tables, "posts"))).containsExactly(Map.entry("author_id", "users.id"));
        assertThat(log).singleElement().asString().contains("2 tables").contains("2 Liquibase changelog(s)");
    }
}
