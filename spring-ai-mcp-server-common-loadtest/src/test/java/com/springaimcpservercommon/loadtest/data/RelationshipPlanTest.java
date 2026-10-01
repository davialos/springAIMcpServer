package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.Fixtures;
import com.springaimcpservercommon.loadtest.discovery.CatalogMerger;
import com.springaimcpservercommon.loadtest.discovery.SpringSourceScanner;
import com.springaimcpservercommon.loadtest.discovery.SqlSchemaReader;
import com.springaimcpservercommon.loadtest.model.ApiCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Payload fields bound through the entity relationship graph, column facts, and the seeding order. */
class RelationshipPlanTest {

    private static final ApiCatalog CRM =
            CatalogMerger.merge(List.of(new SpringSourceScanner(s -> { }).scan(Fixtures.sampleCrm())));
    private static final TableIndex CRM_INDEX = new TableIndex(CRM.entities(), List.of());
    private static final DataPlan CRM_PLAN = DataPlan.build(CRM, new RealDataBinder(CRM_INDEX, Map.of()));

    private static FieldPlan field(DataPlan plan, String key) {
        FieldPlan f = plan.field(key);
        assertThat(f).as(key).isNotNull();
        return f;
    }

    @Test
    void referencesFollowTheJpaRelationshipsEvenWhenNamesDoNotMatchTables() {
        // Deal.owner is a @ManyToOne AppUser mapped to table "users": no table is called "owner"
        assertThat(field(CRM_PLAN, "CreateDealRequest.ownerId").pool().key()).isEqualTo("users.id");
        assertThat(field(CRM_PLAN, "CreateDealRequest.contactId").pool().key()).isEqualTo("contacts.id");
        // Contact.company is sent as {"company": {"id": …}}
        assertThat(field(CRM_PLAN, "CompanyRef.id").pool().key()).isEqualTo("companies.id");
        assertThat(field(CRM_PLAN, "getDeal.path.dealId").pool().key()).isEqualTo("deals.id");
    }

    @Test
    void columnFactsTravelWithTheFields() {
        assertThat(field(CRM_PLAN, "Company.registrationNo").unique()).isTrue();
        assertThat(field(CRM_PLAN, "Company.registrationNo").maxLength()).isEqualTo(20);
        assertThat(field(CRM_PLAN, "Contact.firstName").maxLength()).isEqualTo(40);
        assertThat(field(CRM_PLAN, "Contact.email").unique()).isTrue();
        assertThat(field(CRM_PLAN, "Company.name").unique()).isFalse();
    }

    @Test
    void seedingCreatesParentsBeforeChildrenAndKnowsHowToCleanUp() {
        List<String> log = new ArrayList<>();
        SeedPlan seed = SeedPlan.build(CRM, CRM_PLAN, CRM_INDEX, log::add);
        List<String> tables = seed.steps().stream().map(SeedPlan.Step::table).toList();
        assertThat(tables).containsExactlyInAnyOrder("companies", "contacts", "users", "deals", "task");
        assertThat(tables.indexOf("companies")).isLessThan(tables.indexOf("contacts"));
        assertThat(tables.indexOf("contacts")).isLessThan(tables.indexOf("deals"));
        assertThat(tables.indexOf("users")).isLessThan(tables.indexOf("deals"));
        // Task @ManyToOne Deal: not in its Data REST payload, but the row still belongs after its deal
        assertThat(tables.indexOf("deals")).isLessThan(tables.indexOf("task"));
        SeedPlan.Step deals = seed.steps().get(tables.indexOf("deals"));
        assertThat(deals.api()).isEqualTo("createDeal");
        assertThat(deals.dependsOn()).containsExactlyInAnyOrder("contacts.id", "users.id");
        assertThat(deals.deleteApi()).isEqualTo("deleteDeal");
        assertThat(deals.idFromRequest()).isFalse();
        assertThat(seed.steps().get(tables.indexOf("contacts")).deleteApi()).isEqualTo("deleteContact");
        assertThat(seed.steps().get(tables.indexOf("users")).deleteApi()).isNull();
        assertThat(log).isEmpty();
    }

    @Test
    void withoutJpaTheDatabaseForeignKeysDecide(@TempDir Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("ArticlesApi.java"), """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/articles")
                class ArticlesApi {
                    @PostMapping
                    Object create(@RequestBody NewArticle a) { return null; }
                }
                record NewArticle(String title, Long authorId) { }
                """);
        ApiCatalog catalog = new SpringSourceScanner(s -> { }).scan(dir);
        DbTable users = new DbTable(null, "users", Map.of("id", "int8", "username", "varchar"), List.of("id"));
        DbTable articles = new DbTable(null, "articles", Map.of("id", "int8", "title", "varchar", "author_id", "int8"),
                List.of("id"), Map.of("author_id", new PoolRef(null, "users", "id")), Map.of("title", 120),
                Set.of("title"));
        TableIndex index = new TableIndex(List.of(), List.of(users, articles));
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(index, Map.of()));
        assertThat(field(plan, "NewArticle.authorId").pool().key()).isEqualTo("users.id");
        assertThat(field(plan, "NewArticle.title").maxLength()).isEqualTo(120);
        assertThat(field(plan, "NewArticle.title").unique()).isTrue();
        SeedPlan seed = SeedPlan.build(catalog, plan, index, s -> { });
        assertThat(seed.steps()).singleElement().satisfies(s -> {
            assertThat(s.table()).isEqualTo("articles");
            assertThat(s.dependsOn()).containsExactly("users.id");
        });
    }

    @Test
    void ddlOnlyProjectsAddressRowsByNaturalKeysAndSeedThem(@TempDir Path dir) throws IOException {
        // a MyBatis-style app: no JPA, tables only in a Flyway script without foreign-key constraints
        Path migration = Files.createDirectories(dir.resolve("src/main/resources/db/migration"));
        Files.writeString(migration.resolve("V1__create_tables.sql"), """
                create table users (id varchar(255) primary key, username varchar(255) UNIQUE, email varchar(255));
                create table articles (id varchar(255) primary key, user_id varchar(255), slug varchar(255) UNIQUE,
                                       title varchar(120));
                create table comments (id varchar(255) primary key, body text, article_id varchar(255),
                                       user_id varchar(255));
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Api.java"), """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                class Api {
                    @PostMapping("/users") Object register(@RequestBody NewUser u) { return null; }
                    @GetMapping("/profiles/{username}") Object profile(@PathVariable String username) { return null; }
                    @PostMapping("/articles") Object create(@RequestBody NewArticle a) { return null; }
                    @GetMapping("/articles/{slug}") Object article(@PathVariable String slug) { return null; }
                    @DeleteMapping("/articles/{slug}") Object delete(@PathVariable String slug) { return null; }
                    @PostMapping("/articles/{slug}/comments")
                    Object comment(@PathVariable String slug, @RequestBody NewComment c) { return null; }
                }
                record NewUser(String username, String email, String password) { }
                record NewArticle(String title, String body) { }
                record NewComment(String body) { }
                """);
        ApiCatalog catalog = new SpringSourceScanner(s -> { }).scan(dir);
        TableIndex index = new TableIndex(catalog.entities(), new SqlSchemaReader(s -> { }).read(dir), false);
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(index, Map.of()));
        assertThat(field(plan, "article.path.slug").pool().key()).isEqualTo("articles.slug");
        assertThat(field(plan, "profile.path.username").pool().key()).isEqualTo("users.username"); // no profiles table
        assertThat(field(plan, "NewArticle.title").maxLength()).isEqualTo(120);

        SeedPlan seed = SeedPlan.build(catalog, plan, index, s -> { });
        assertThat(seed.steps()).extracting(SeedPlan.Step::table).containsExactly("users", "articles", "comments");
        SeedPlan.Step articles = seed.steps().get(1);
        assertThat(articles.dependsOn()).containsExactly("users.id"); // the author comes from the logged-in user
        assertThat(articles.captures()).containsExactly(Map.entry("articles.slug", "slug"));
        assertThat(articles.deleteApi()).isEqualTo("delete");
        assertThat(articles.deletePool()).isEqualTo("articles.slug");
        assertThat(seed.steps().get(0).captures()).containsEntry("users.username", "username");
        assertThat(seed.steps().get(2).dependsOn()).containsExactlyInAnyOrder("articles.id", "users.id");
        assertThat(seed.toJson().get(1).get("captures").get("articles.slug").asString()).isEqualTo("slug");
    }

    @Test
    void naturalKeysAreReadFromTheRequest(@TempDir Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Product.java"), """
                package x;
                import jakarta.persistence.*;
                @Entity
                class Product { @Id String sku; String name; }
                """);
        Files.writeString(src.resolve("ProductController.java"), """
                package x;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/products")
                class ProductController {
                    @PostMapping
                    Object create(@RequestBody Product p) { return null; }
                }
                """);
        ApiCatalog catalog = new SpringSourceScanner(s -> { }).scan(dir);
        TableIndex index = new TableIndex(catalog.entities(), List.of());
        DataPlan plan = DataPlan.build(catalog, new RealDataBinder(index, Map.of()));
        assertThat(catalog.schemas().get("Product").properties()).containsKey("sku"); // client-assigned id is sent
        SeedPlan seed = SeedPlan.build(catalog, plan, index, s -> { });
        assertThat(seed.steps()).singleElement().satisfies(s -> {
            assertThat(s.pool()).isEqualTo("product.sku");
            assertThat(s.idField()).isEqualTo("sku");
            assertThat(s.idFromRequest()).isTrue();
        });
    }
}
