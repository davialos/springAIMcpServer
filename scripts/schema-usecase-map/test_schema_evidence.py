"""Unit tests for schema_evidence.py: small synthetic projects per stack. Run: python3 -m unittest -v (in this folder)."""
import os
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import schema_evidence as se  # noqa: E402


def project(files: dict):
    root = tempfile.mkdtemp(prefix="schema-evidence-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(textwrap.dedent(content).lstrip("\n"))
    return root


def analyze(files: dict, **kw):
    a = se.Analyzer(project(files), se.DEFAULT_EXCLUDES, kw.get("depth", 5), False, kw.get("prefix"), True)
    return a.run()


def ops(ev, table):
    return {a["method"]: a["ops"] + a["inferred_ops"] for a in ev["tables"][table]["accessors"]}


def entry_routes(ev, table):
    return {(e["kind"], e["route"]) for e in ev["tables"][table]["entry_points"]}


class SqlDdlTest(unittest.TestCase):

    def test_flyway_order_columns_foreign_keys_partitions_and_dollar_quoted_bodies(self):
        ev = analyze({
            "db/migration/V1__init.sql": """
                CREATE TABLE shop_customer (id uuid PRIMARY KEY, email text NOT NULL);
                CREATE FUNCTION shop_touch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.updated_at = now(); RETURN NEW; END; $$;
                CREATE TABLE shop_order (
                    id uuid NOT NULL,
                    customer_id uuid NOT NULL,
                    created_at timestamptz NOT NULL,
                    CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES shop_customer (id)
                ) PARTITION BY RANGE (created_at);
                CREATE TABLE shop_order_p202601 PARTITION OF shop_order FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
                CREATE INDEX ix_order_customer ON shop_order (customer_id);
                CREATE TRIGGER trg_touch BEFORE UPDATE ON shop_order FOR EACH ROW EXECUTE FUNCTION shop_touch();
                COMMENT ON TABLE shop_order IS 'One row per checkout';
            """,
            "db/migration/V2__more.sql": """
                ALTER TABLE shop_order ADD COLUMN total_micros bigint;
                CREATE TABLE shop_tmp (id int);
                DROP TABLE shop_tmp;
                INSERT INTO shop_customer (id, email) VALUES ('00000000-0000-0000-0000-000000000000', 'x@y');
            """,
        })
        order = ev["tables"]["shop_order"]
        self.assertEqual([c["name"] for c in order["columns"]], ["id", "customer_id", "created_at", "total_micros"])
        self.assertEqual(order["references"], ["shop_customer"])
        self.assertEqual(ev["tables"]["shop_customer"]["referenced_by"], ["shop_order"])
        self.assertEqual(order["partitioned_by"], "RANGE (created_at)")
        self.assertEqual(order["partitions"], 1)
        self.assertEqual(order["indexes"], 1)
        self.assertEqual(order["triggers"][0]["function"], "shop_touch")
        self.assertEqual(order["comment"], "One row per checkout")
        self.assertNotIn("shop_order_p202601", ev["tables"])
        self.assertIn("dropped", ev["tables"]["shop_tmp"]["flags"])
        self.assertEqual(ev["tables"]["shop_customer"]["seeds"][0]["op"], "C")

    def test_rename_moves_the_table(self):
        ev = analyze({"schema.sql": "CREATE TABLE a_old (id int); ALTER TABLE a_old RENAME TO a_new;"})
        self.assertIn("a_new", ev["tables"])
        self.assertNotIn("a_old", ev["tables"])
        self.assertEqual(ev["tables"]["a_new"]["renamed_from"], ["a_old"])

    def test_classify_sql_takes_the_verb_governing_this_occurrence(self):
        window = 'insert = "INSERT INTO t_net (id)";\nselect = "SELECT network FROM t_net"'
        self.assertEqual(se.classify_sql(window, "t_net", len(window) - 1), "R")
        self.assertEqual(se.classify_sql(window, "t_net", window.index("t_net") + 5), "C")
        self.assertEqual(se.classify_sql('"DELETE FROM " + schema + ".t_x WHERE"', "t_x"), "D")


class JavaJpaTest(unittest.TestCase):
    FILES = {
        "src/main/resources/db/migration/V1__orders.sql": """
            CREATE TABLE app_order (id bigint PRIMARY KEY, status text);
            CREATE TABLE app_order_line (id bigint PRIMARY KEY, order_id bigint REFERENCES app_order (id));
            CREATE TABLE app_audit (id bigint PRIMARY KEY, what text);
        """,
        "src/main/java/com/acme/order/Order.java": """
            package com.acme.order;

            import jakarta.persistence.*;
            import java.util.List;

            @Entity
            @Table(name = "app_order")
            public class Order {
                @Id Long id;
                String status;
                @OneToMany(mappedBy = "order")
                List<OrderLine> lines;
                public void cancel() { status = "CANCELLED"; }
            }
        """,
        "src/main/java/com/acme/order/OrderLine.java": """
            package com.acme.order;

            import jakarta.persistence.*;

            @Entity
            @Table(name = "app_order_line")
            public class OrderLine {
                @Id Long id;
                @ManyToOne Order order;
            }
        """,
        "src/main/java/com/acme/order/OrderRepository.java": """
            package com.acme.order;

            import org.springframework.data.jpa.repository.JpaRepository;

            public interface OrderRepository extends JpaRepository<Order, Long> {
            }
        """,
        "src/main/java/com/acme/order/OrderService.java": """
            package com.acme.order;

            import org.springframework.jdbc.core.JdbcTemplate;

            public class OrderService {
                private final OrderRepository orders;
                private final JdbcTemplate jdbc;

                public OrderService(OrderRepository orders, JdbcTemplate jdbc) {
                    this.orders = orders;
                    this.jdbc = jdbc;
                }

                public Order find(long id) {
                    return orders.findById(id).orElseThrow();
                }

                public void cancel(long id) {
                    Order order = orders.findById(id).orElseThrow();
                    order.cancel();
                    jdbc.update("INSERT INTO app_audit (what) VALUES (?)", "cancel " + id);
                }
            }
        """,
        "src/main/java/com/acme/web/OrderController.java": """
            package com.acme.web;

            import com.acme.order.OrderService;
            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                private final OrderService service;

                OrderController(OrderService service) { this.service = service; }

                @GetMapping("/{id}")
                public Object get(@PathVariable("id") long id, @RequestParam(required = false) String view) {
                    return service.find(id);
                }

                @PostMapping("/{id}:cancel")
                public void cancel(@PathVariable long id) {
                    service.cancel(id);
                }
            }
        """,
        "src/main/java/com/acme/jobs/Cleanup.java": """
            package com.acme.jobs;

            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.scheduling.annotation.Scheduled;

            public class Cleanup {
                private final JdbcTemplate jdbc;
                Cleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

                @Scheduled(cron = "0 0 * * * *")
                public void purge() {
                    jdbc.update("DELETE FROM app_audit WHERE id < 0");
                }
            }
        """,
        "src/test/java/com/acme/order/OrderServiceTest.java": """
            package com.acme.order;

            import org.junit.jupiter.api.Test;

            class OrderServiceTest {
                @Test
                void cancels() {
                    new OrderService(null, null).cancel(1);
                }
            }
        """,
        "docs/orders.md": """
            # Orders

            ## Checkout (F-12)

            `app_order` holds one row per checkout; see REQ-7.
        """,
    }

    @classmethod
    def setUpClass(cls):
        cls.ev = analyze(cls.FILES)

    def test_entity_mapping_and_association(self):
        mappings = self.ev["tables"]["app_order"]["mappings"]
        self.assertEqual(mappings[0]["type"], "Order")
        self.assertFalse(mappings[0]["inferred"])
        origins = {m["origin"] for m in self.ev["tables"]["app_order_line"]["mappings"]}
        self.assertIn("jpa-association", origins)

    def test_repository_reads_jdbc_writes_and_dirty_checking_updates(self):
        order_ops = ops(self.ev, "app_order")
        self.assertIn("R", order_ops["OrderService#find"])
        self.assertIn("U", order_ops["OrderService#cancel"])
        audit_ops = ops(self.ev, "app_audit")
        self.assertEqual(audit_ops["OrderService#cancel"], "C")
        self.assertEqual(audit_ops["Cleanup#purge"], "D")

    def test_entry_points_through_annotated_parameters_and_schedulers(self):
        routes = entry_routes(self.ev, "app_order")
        self.assertIn(("http", "GET /api/orders/{id}"), routes)
        self.assertIn(("http", "POST /api/orders/{id}:cancel"), routes)
        self.assertIn(("scheduled", None), entry_routes(self.ev, "app_audit"))
        cancel = next(e for e in self.ev["tables"]["app_audit"]["entry_points"] if e["kind"] == "http")
        self.assertEqual(cancel["via"], ["OrderService#cancel"])
        self.assertEqual(cancel["strength"], "strong")

    def test_tests_and_documents_are_linked(self):
        self.assertIn("src/test/java/com/acme/order/OrderServiceTest.java", self.ev["tables"]["app_audit"]["tests"])
        doc = self.ev["tables"]["app_order"]["docs"][0]
        self.assertEqual(doc["heading"], "Checkout (F-12)")
        self.assertIn("REQ-7", doc["ids"])

    def test_flags(self):
        self.assertNotIn("no-entry-point-found", self.ev["tables"]["app_order"]["flags"])
        self.assertIn("never-read-by-code", self.ev["tables"]["app_audit"]["flags"])


class SpringWiringTest(unittest.TestCase):

    def test_method_reference_handed_to_a_bean_reaches_the_beans_scheduled_entry(self):
        ev = analyze({
            "V1__x.sql": "CREATE TABLE ops_node (id text PRIMARY KEY);",
            "src/main/java/a/NodeStore.java": """
                package a;
                public class NodeStore {
                    public void heartbeat() { jdbc.update("UPDATE ops_node SET seen = now()"); }
                }
            """,
            "src/main/java/a/Runner.java": """
                package a;
                public class Runner {
                    private final Runnable beat;
                    private final java.util.concurrent.ScheduledExecutorService pool;
                    Runner(Runnable beat) { this.beat = beat; }
                    public void start() { pool.scheduleWithFixedDelay(this::tick, 1, 1, SECONDS); }
                    void tick() { beat.run(); }
                }
            """,
            "src/main/java/a/Config.java": """
                package a;
                import org.springframework.context.annotation.Bean;
                public class Config {
                    @Bean
                    Runner runner(NodeStore store) {
                        return new Runner(store::heartbeat);
                    }
                }
            """,
        })
        entries = ev["tables"]["ops_node"]["entry_points"]
        self.assertEqual([(e["entry"], e["kind"]) for e in entries], [("Runner#tick", "scheduled")])
        self.assertEqual(entries[0]["via"], ["Config#runner", "NodeStore#heartbeat"])

    def test_same_simple_name_from_another_package_is_not_the_entity(self):
        ev = analyze({
            "V1__x.sql": "CREATE TABLE cfg_resource (id int);",
            "src/main/java/a/config/Resource.java": """
                package a.config;
                @Entity @Table(name = "cfg_resource")
                public class Resource { }
            """,
            "src/main/java/b/Loader.java": """
                package b;
                import org.springframework.core.io.Resource;
                class Loader {
                    Object load(Resource r) { return r.getInputStream(); }
                }
            """,
        })
        self.assertEqual(ev["tables"]["cfg_resource"]["accessors"], [])

    def test_framework_callback_is_a_terminal(self):
        ev = analyze({
            "V1__x.sql": "CREATE TABLE mem_message (id text);",
            "src/main/java/a/MemoryRepo.java": """
                package a;
                import org.springframework.ai.chat.memory.ChatMemoryRepository;
                final class MemoryRepo implements ChatMemoryRepository {
                    public java.util.List<String> findByConversationId(String id) {
                        return jdbc.queryForList("SELECT id FROM mem_message", String.class);
                    }
                }
            """,
        })
        entries = ev["tables"]["mem_message"]["entry_points"]
        self.assertEqual(entries[0]["kind"], "framework")
        self.assertIn("ChatMemoryRepository", entries[0]["route"])


class OtherStacksTest(unittest.TestCase):

    def test_django_model_view_and_url(self):
        ev = analyze({
            "shop/models.py": """
                from django.db import models

                class Invoice(models.Model):
                    number = models.CharField(max_length=20)
                    customer = models.ForeignKey("Customer", on_delete=models.CASCADE)

                class Customer(models.Model):
                    name = models.TextField()
            """,
            "shop/views.py": """
                from .models import Invoice

                def invoice_list(request):
                    return Invoice.objects.filter(number__startswith="A")

                def invoice_create(request):
                    return Invoice.objects.create(number="A1")
            """,
            "shop/urls.py": """
                from django.urls import path
                from . import views

                urlpatterns = [path("invoices/", views.invoice_list), path("invoices/new", views.invoice_create)]
            """,
        })
        self.assertIn("shop_invoice", ev["tables"])
        self.assertIn("shop_customer", ev["tables"]["shop_invoice"]["references"])
        self.assertEqual(ops(ev, "shop_invoice"), {"views#invoice_list": "R", "views#invoice_create": "C"})
        self.assertIn(("http", "ANY /invoices/"), entry_routes(ev, "shop_invoice"))

    def test_sqlalchemy_and_fastapi(self):
        ev = analyze({
            "app/models.py": """
                from sqlalchemy import Column, Integer, String, ForeignKey
                from .db import Base

                class Ticket(Base):
                    __tablename__ = "tickets"
                    id = Column(Integer, primary_key=True)
                    title = Column(String)
                    project_id = Column(Integer, ForeignKey("projects.id"))
            """,
            "app/api.py": """
                from fastapi import APIRouter
                from .models import Ticket

                router = APIRouter()

                @router.get("/tickets")
                def list_tickets(session):
                    return session.query(Ticket).all()
            """,
        })
        t = ev["tables"]["tickets"]
        self.assertEqual([c["name"] for c in t["columns"]], ["id", "title", "project_id"])
        self.assertEqual(t["references"], ["projects"])
        self.assertIn(("http", "GET /tickets"), entry_routes(ev, "tickets"))

    def test_prisma_and_express(self):
        ev = analyze({
            "prisma/schema.prisma": """
                model User {
                  id    Int    @id
                  email String @unique
                  posts Post[]
                  @@map("users")
                }

                model Post {
                  id       Int  @id
                  author   User @relation(fields: [authorId], references: [id])
                  authorId Int
                }
            """,
            "src/routes.ts": """
                import express from "express";
                import { prisma } from "./db";

                const router = express.Router();

                async function listUsers(req, res) {
                    res.json(await prisma.user.findMany());
                }

                router.get("/users", listUsers);
            """,
        })
        self.assertIn("users", ev["tables"]["post"]["references"])
        self.assertEqual(ops(ev, "users"), {"routes#listUsers": "R"})
        self.assertIn(("http", "GET /users"), entry_routes(ev, "users"))

    def test_typeorm_entity_name(self):
        ev = analyze({
            "src/invoice.entity.ts": """
                import { Entity, Column } from "typeorm";

                @Entity({ name: "billing_invoice" })
                export class Invoice {
                    @Column() total: number;
                }
            """,
        })
        self.assertEqual(ev["tables"]["billing_invoice"]["mappings"][0]["type"], "Invoice")

    def test_rails_schema_model_and_controller(self):
        ev = analyze({
            "db/schema.rb": """
                ActiveRecord::Schema.define(version: 1) do
                  create_table "accounts" do |t|
                    t.string "name"
                    t.references :owner
                  end
                end
            """,
            "app/models/account.rb": """
                class Account < ApplicationRecord
                end
            """,
            "app/controllers/accounts_controller.rb": """
                class AccountsController < ApplicationController
                  def index
                    @accounts = Account.where(active: true)
                  end
                end
            """,
        })
        t = ev["tables"]["accounts"]
        self.assertEqual([c["name"] for c in t["columns"]], ["name", "owner_id"])
        self.assertEqual(ops(ev, "accounts"), {"AccountsController#index": "R"})
        self.assertIn(("http", "AccountsController#index"), entry_routes(ev, "accounts"))


class HeaderAndStaleTest(unittest.TestCase):

    def test_method_header_with_parameter_annotations(self):
        h = '@GetMapping("    ") public ResponseEntity<?> nodes(@RequestParam(required = false) @Nullable Integer s, X r)'
        self.assertEqual(se.classify_header(h, "java")[:2], ("method", "nodes"))
        self.assertEqual(se.classify_header("if (x > 0)", "java")[0], "block")
        self.assertEqual(se.classify_header("new Runnable()", "java")[0], "anon")

    def test_stale_references_skip_fragments_partitions_roles_and_tests(self):
        ev = analyze({
            "V1__x.sql": "CREATE TABLE app_a (id int); CREATE TABLE app_b (id int); CREATE TABLE app_c (id int);",
            "docs/notes.md": "Old `app_legacy` table. Partitions app_a_p202601. Grant to role app_reader:\n"
                             "GRANT SELECT ON app_a TO app_reader;\n",
            "src/main/java/a/A.java": 'class A { String f = "app_" + x; String g = "app_drop_" + y; }',
            "src/test/java/a/ATest.java": 'class ATest { String k = "app_test_key"; }',
        })
        self.assertEqual(ev["table_prefix"], "app_")
        self.assertEqual(list(ev["stale_references"]), ["app_legacy"])


class CliTest(unittest.TestCase):

    def test_writes_json_and_markdown(self):
        root = project({"schema.sql": "CREATE TABLE t_one (id int);"})
        out = tempfile.mkdtemp()
        rc = se.main([root, "--json", os.path.join(out, "e.json"), "--markdown", os.path.join(out, "e.md"),
                      "--quiet"])
        self.assertEqual(rc, 0)
        with open(os.path.join(out, "e.md"), encoding="utf-8") as fh:
            md = fh.read()
        self.assertIn("### t_one", md)
        self.assertIn("no-code-access", md)


if __name__ == "__main__":
    unittest.main()
