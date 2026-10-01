# schema-usecase-map

`schema_evidence.py` collects **facts** about every database table in a repository: where it is defined, which
code reads or writes it, which entry points (HTTP routes, schedulers, listeners, framework callbacks, startup)
reach that code, which tests exercise it and which documents mention it. Each fact comes with a `file:line`.

The script does not decide what a table is *for*. The [`schema-usecase-mapper`](../../.claude/agents/schema-usecase-mapper.md)
agent does that: it runs this script, reads the code and documents the evidence points to, and writes the
table → use case → business flow → requirement map. [`docs/data/schema-usecase-map.md`](../../docs/data/schema-usecase-map.md)
is the result for this repository.

## Run

```bash
python3 scripts/schema-usecase-map/schema_evidence.py [ROOT] --json evidence.json --markdown evidence.md
```

| Option | Meaning |
|---|---|
| `ROOT` | Repository to scan (default `.`) |
| `--json FILE` | Full evidence, machine-readable (input for the agent) |
| `--markdown FILE` | Readable report; without `--json`/`--markdown` the report goes to stdout |
| `--depth N` | Caller hops followed towards entry points (default 5) |
| `--exclude DIR` | Extra directory name to skip (repeatable; build output, `node_modules`, `offline-repo`… are skipped by default) |
| `--include-test-callers` | Walk through test code as callers (tests are otherwise listed, not walked) |
| `--prefix PREFIX` | Table prefix for the unknown-name check (auto-detected when ≥ 60 % of tables share one) |
| `--max-sites N` | Accessors / entry points listed per table in Markdown (default 12) |

Python 3.9+, standard library only. On this repository a run takes about 10 s.

## What it understands

- **Definitions**: SQL DDL in any dialect (Flyway order `V1__`, `V2__`… respected; `CREATE/ALTER/DROP TABLE`,
  `RENAME`, views, partitions, indexes, triggers, `COMMENT ON`, seeds), Liquibase XML/YAML, JPA/Hibernate
  (`@Table`, `@CollectionTable`, `@JoinTable`, `@OneToMany` children), Spring Data repositories, Prisma, TypeORM,
  Sequelize, Knex, Drizzle, Django, SQLAlchemy/Alembic, Rails, Laravel/Doctrine, EF Core, GORM, Ecto.
- **Access**: SQL in strings (verb governing *that* occurrence → C/R/U/D), ORM usage (`new Entity`, static
  factories in methods that persist, JPQL/HQL entity names, repositories, Prisma client), JPA dirty-checking
  updates (marked *inferred*), MyBatis mapper XML.
- **Entry points**: mapping annotations (Spring, JAX-RS, ASP.NET, NestJS, FastAPI/Flask), route registrations
  (Express, Go, minimal APIs), Django `urls.py`, Rails controllers/jobs, Next.js routes, `@Scheduled` and
  methods handed to schedulers, message listeners, lifecycle callbacks, `main`, `@Bean` factories (code passed
  to a bean reaches that bean's own entry points), and **framework callbacks** (a class implementing an
  interface from outside the repository that nothing in the repository calls, e.g. a Spring AI
  `ChatMemoryRepository`).

## Reading the output

| Flag | Meaning — verify before reporting |
|---|---|
| `no-code-access` / `seed-only` | Nothing in code touches the table (only migrations seed it) |
| `never-written-by-code` / `never-read-by-code` | Writes or reads are missing — a dead table, a write-only log, or a gap |
| `no-entry-point-found` | Code touches it but nothing reachable calls that code — often an unwired feature |
| `mapped-without-ddl` / `ddl-without-mapping` | ORM and DDL disagree (views are usually read with native SQL) |
| `test-only`, `dropped` | Defined only in tests / dropped by a later migration |

*Weak* call edges and *inferred* operations are leads, not proof. The heuristics are regular expressions over
source text, not a compiler: dynamic dispatch, reflection, table names assembled at run time (see “Name in
other strings”) and calls more than `--depth` hops away can be missed. The unknown-name list mixes stale
references with database roles and other names that share the prefix.

## Tests

```bash
cd scripts/schema-usecase-map && python3 -m unittest -v
```

Synthetic fixtures cover SQL/Flyway, JPA + Spring (annotated parameters, schedulers, bean wiring, framework
callbacks), Django, SQLAlchemy/FastAPI, Prisma/Express, TypeORM and Rails.
