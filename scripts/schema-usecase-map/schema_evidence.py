#!/usr/bin/env python3
"""Map database tables to the code, entry points, tests and documents that touch them.

schema_evidence collects facts, not conclusions. For every table it reports where the table is defined (SQL DDL,
migrations, ORM mappings), its columns, foreign keys and storage traits, the code that reads or writes it (raw SQL
and ORM usage, each with file:line and the enclosing method), the callers of that code up to entry points (HTTP
routes, schedulers, message listeners, startup hooks), the tests that exercise it and the documents that mention it.
Every fact carries a file:line so a person or an agent can verify it before explaining what the table is for.

It is heuristic by design (regular expressions over source text, no compiler): treat call edges marked "weak" and
operations marked "inferred" as leads to confirm, not as proof. Standard library only; Python 3.9+.

Usage:
    python3 schema_evidence.py [ROOT] [--json FILE] [--markdown FILE] [--depth N] [--exclude DIR ...]
                               [--include-test-callers] [--prefix PREFIX] [--max-sites N] [--quiet]

Definition sources: SQL DDL in any dialect (Flyway, Liquibase formatted SQL, plain schema files), Liquibase
XML/YAML, JPA/Hibernate and Spring Data (Java, Kotlin), Prisma, TypeORM, Sequelize, Knex, Drizzle, Django,
SQLAlchemy and Alembic, Rails (schema.rb, migrations, models), Laravel and Doctrine, EF Core, GORM, Ecto.
"""
from __future__ import annotations

import argparse
import bisect
import datetime as dt
import io
import json
import os
import re
import sys
import tokenize
from collections import defaultdict, deque
from dataclasses import dataclass, field

VERSION = "1.0.0"

EXT_LANG = {
    ".java": "java", ".kt": "kotlin", ".kts": "kotlin", ".scala": "scala", ".groovy": "groovy",
    ".py": "python", ".rb": "ruby", ".rake": "ruby", ".js": "js", ".jsx": "js", ".mjs": "js", ".cjs": "js",
    ".ts": "ts", ".tsx": "ts", ".mts": "ts", ".cts": "ts", ".go": "go", ".cs": "csharp", ".php": "php",
    ".rs": "rust", ".ex": "elixir", ".exs": "elixir", ".sql": "sql", ".prisma": "prisma", ".xml": "xml",
    ".yaml": "yaml", ".yml": "yaml", ".md": "doc", ".markdown": "doc", ".adoc": "doc", ".rst": "doc",
}
BRACE = {"java", "kotlin", "scala", "groovy", "js", "ts", "go", "csharp", "php", "rust"}
JVM = {"java", "kotlin", "scala", "groovy"}
INDENTED = {"python", "ruby", "elixir"}
CODE = BRACE | INDENTED
DEFAULT_EXCLUDES = {
    ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out", ".gradle", ".idea", ".vscode",
    ".venv", "venv", ".env", "__pycache__", "vendor", ".next", ".nuxt", "coverage", "bin", "obj", ".terraform",
    ".m2", "offline-repo", ".tox", ".mypy_cache", ".pytest_cache", "site-packages", ".dart_tool", "Pods",
}
MAX_FILE_BYTES = 1_500_000
TEST_DIR_RE = re.compile(r"(^|/)(src/test|src/it|src/integrationTest|src/testFixtures|tests?|__tests__|specs?|"
                         r"testdata|test-fixtures)/", re.I)
TEST_FILE_RE = re.compile(r"(Test|Tests|IT|Spec|TestCase)\.(java|kt|scala|groovy|cs)$|(_test|_spec)\.(py|rb|go|exs?)$"
                          r"|(^|/)test_[^/]*\.py$|\.(test|spec)\.[cm]?[jt]sx?$")
BOOKKEEPING_RE = re.compile(r"schema_history|flyway|databasechangelog|efmigrationshistory|schema_migrations|"
                            r"ar_internal_metadata|django_migrations|alembic_version|knex_migrations|_prisma_migrations",
                            re.I)
REQ_ID_RE = re.compile(r"\b([A-Z][A-Z0-9]{0,9}-\d{1,5}[a-z]?)\b")
REQ_ID_STOP = {"UTF", "SHA", "ISO", "AES", "RSA", "TLS", "SSL", "IPV", "PKCS", "JSR", "HTTP", "UUID", "BASE", "RS",
               "HS", "ES", "PS", "GCM", "CBC", "MD", "CRC", "ECDSA", "P", "X", "V", "T", "UTC", "GMT", "RFC", "CP",
               "WCAG", "EC", "HMAC", "ED"}

HTTP_VERBS = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT", "DeleteMapping": "DELETE",
              "PatchMapping": "PATCH", "RequestMapping": "ANY", "GET": "GET", "POST": "POST", "PUT": "PUT",
              "DELETE": "DELETE", "PATCH": "PATCH", "HttpGet": "GET", "HttpPost": "POST", "HttpPut": "PUT",
              "HttpDelete": "DELETE", "HttpPatch": "PATCH", "Get": "GET", "Post": "POST", "Put": "PUT",
              "Delete": "DELETE", "Patch": "PATCH", "QueryMapping": "GRAPHQL", "MutationMapping": "GRAPHQL",
              "SubscriptionMapping": "GRAPHQL", "MessageMapping": "WS", "Query": "GRAPHQL", "Mutation": "GRAPHQL"}
SCHED_ANN = {"Scheduled", "Schedules", "Cron", "Interval", "Timeout", "SchedulerLock"}
MSG_ANN = {"KafkaListener", "RabbitListener", "JmsListener", "SqsListener", "EventListener",
           "TransactionalEventListener", "StreamListener", "Incoming", "EventHandler", "Subscribe",
           "ServiceActivator", "NatsListener", "PulsarListener", "EventPattern", "MessagePattern", "Process",
           "OnEvent", "Processor"}
STARTUP_ANN = {"PostConstruct", "Startup", "OnApplicationBootstrap"}
TEST_ANN = {"Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "Fact", "Theory",
            "TestMethod"}
LIFECYCLE = {("SmartLifecycle", "start"), ("Lifecycle", "start"), ("ApplicationRunner", "run"),
             ("CommandLineRunner", "run"), ("SmartInitializingSingleton", "afterSingletonsInstantiated"),
             ("InitializingBean", "afterPropertiesSet"), ("ApplicationListener", "onApplicationEvent"),
             ("OncePerRequestFilter", "doFilterInternal"), ("Filter", "doFilter"),
             ("HandlerInterceptor", "preHandle"), ("IHostedService", "StartAsync"),
             ("BackgroundService", "ExecuteAsync"), ("OnModuleInit", "onModuleInit")}
TERMINAL = {"http", "scheduled", "messaging", "startup", "main", "cli"}

# ------------------------------------------------------------------------------------------------------------
# small helpers
# ------------------------------------------------------------------------------------------------------------


class LineIndex:
    """Offset to 1-based line number."""

    def __init__(self, text: str):
        self.starts = [0] + [m.end() for m in re.finditer("\n", text)]

    def line(self, offset: int) -> int:
        return bisect.bisect_right(self.starts, offset)


def snake(name: str) -> str:
    name = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", name)
    return re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", name).lower()


def plural(word: str) -> str:
    if re.search(r"(s|x|z|ch|sh)$", word):
        return word + "es"
    if re.search(r"[^aeiou]y$", word):
        return word[:-1] + "ies"
    return word + "s"


def singular(word: str) -> str:
    if word.endswith("ies"):
        return word[:-3] + "y"
    if re.search(r"(s|x|z|ch|sh)es$", word):
        return word[:-2]
    return word[:-1] if word.endswith("s") else word


def match_paren(s: str, i: int, open_ch: str = "(", close_ch: str = ")") -> int:
    """Index of the bracket closing the one at s[i], or -1. Expects strings/comments already blanked."""
    depth = 0
    for j in range(i, len(s)):
        c = s[j]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return j
    return -1


def req_ids(text: str) -> list:
    out = []
    for m in REQ_ID_RE.finditer(text):
        prefix = m.group(1).split("-")[0]
        if prefix not in REQ_ID_STOP and m.group(1) not in out:
            out.append(m.group(1))
    return out


def is_test_path(rel: str) -> bool:
    return bool(TEST_DIR_RE.search(rel) or TEST_FILE_RE.search(rel))


def first_string(args: str, keys=("value", "name", "path")):
    for key in keys:
        m = re.search(r"\b" + key + r"\s*[=:]\s*(?:\[\s*|\{\s*)?[\"']([^\"']*)[\"']", args)
        if m:
            return m.group(1)
    m = re.match(r"\s*\(?\s*(?:\[\s*|\{\s*)?[\"']([^\"']*)[\"']", args)
    return m.group(1) if m else None


# ------------------------------------------------------------------------------------------------------------
# lexical scanning: blank strings and comments, keep offsets; mask 0 = code, 1 = string, 2 = comment
# ------------------------------------------------------------------------------------------------------------

def _brace_token_re(lang: str):
    parts = [r"//[^\n]*", r"/\*[\s\S]*?(?:\*/|$)"]
    if lang in ("java", "kotlin", "scala", "groovy", "csharp"):
        parts.append(r'"""[\s\S]*?(?:"""|$)')
    if lang == "php":
        parts.append(r"#(?!\[)[^\n]*")
    parts.append(r'"(?:\\.|[^"\\\n])*"?')
    parts.append(r"'(?:\\.|[^'\\\n])'" if lang == "rust" else r"'(?:\\.|[^'\\\n])*'?")
    if lang in ("js", "ts", "go"):
        parts.append(r"`(?:\\.|[^`\\])*`?")
    return re.compile("|".join(parts))


_TOKEN_RES = {}


def _fill(chars, mask, a, b, kind):
    for k in range(a, b):
        if chars[k] != "\n":
            chars[k] = " "
        mask[k] = kind


def scan_brace(text: str, lang: str):
    rx = _TOKEN_RES.setdefault(lang, _brace_token_re(lang))
    chars, mask = list(text), bytearray(len(text))
    for m in rx.finditer(text):
        tok, a, b = m.group(), m.start(), m.end()
        if tok.startswith("//") or tok.startswith("/*") or (lang == "php" and tok.startswith("#")):
            _fill(chars, mask, a, b, 2)
        elif tok.startswith('"""'):
            _fill(chars, mask, a + 3, max(a + 3, b - 3 if tok.endswith('"""') and len(tok) >= 6 else b), 1)
        else:
            end = b - 1 if len(tok) > 1 and tok[-1] == tok[0] else b
            _fill(chars, mask, a + 1, end, 1)
    return "".join(chars), mask


_HASH_TOKEN_RE = re.compile(r'"""[\s\S]*?(?:"""|$)|\'\'\'[\s\S]*?(?:\'\'\'|$)|#[^\n]*|"(?:\\.|[^"\\\n])*"?'
                            r"|'(?:\\.|[^'\\\n])*'?")
_HEREDOC_RE = re.compile(r"<<[~-]?(['\"]?)([A-Z_][A-Z0-9_]*)\1")


def scan_hash(text: str, lang: str):
    """Ruby / Elixir / fallback for Python: '#' comments, quotes, triple quotes, Ruby heredocs."""
    chars, mask = list(text), bytearray(len(text))
    for m in _HASH_TOKEN_RE.finditer(text):
        tok, a, b = m.group(), m.start(), m.end()
        if tok.startswith("#") and not (lang == "ruby" and tok.startswith("#{")):
            _fill(chars, mask, a, b, 2)
        else:
            _fill(chars, mask, a, b, 1)
    if lang == "ruby":
        for m in _HEREDOC_RE.finditer(text):
            start = text.find("\n", m.end())
            if start < 0:
                continue
            end = re.compile(r"^\s*" + m.group(2) + r"\s*$", re.M).search(text, start + 1)
            _fill(chars, mask, start + 1, end.start() if end else len(text), 1)
    return "".join(chars), mask


def scan_python(text: str):
    chars, mask = list(text), bytearray(len(text))
    starts = [0] + [m.end() for m in re.finditer("\n", text)]
    string_types = {tokenize.STRING}
    for name in ("FSTRING_START", "FSTRING_MIDDLE", "FSTRING_END"):
        if hasattr(tokenize, name):
            string_types.add(getattr(tokenize, name))
    try:
        for tok in tokenize.generate_tokens(io.StringIO(text).readline):
            if tok.type == tokenize.COMMENT or tok.type in string_types:
                a = starts[tok.start[0] - 1] + tok.start[1]
                b = starts[tok.end[0] - 1] + tok.end[1]
                _fill(chars, mask, a, b, 2 if tok.type == tokenize.COMMENT else 1)
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return scan_hash(text, "python")
    return "".join(chars), mask


def scan(text: str, lang: str):
    if lang in BRACE:
        return scan_brace(text, lang)
    if lang == "python":
        return scan_python(text)
    if lang in ("ruby", "elixir"):
        return scan_hash(text, lang)
    if lang == "xml":  # SQL mappers: content is data, comments are comments
        chars, mask = list(text), bytearray([1]) * len(text)
        for m in re.finditer(r"<!--[\s\S]*?-->", text):
            _fill(chars, mask, m.start(), m.end(), 2)
        return text, mask
    return text, bytearray([1]) * len(text)


# ------------------------------------------------------------------------------------------------------------
# schema model
# ------------------------------------------------------------------------------------------------------------

@dataclass
class Table:
    name: str
    kind: str = "table"
    schema: str | None = None
    definitions: list = field(default_factory=list)
    columns: dict = field(default_factory=dict)
    comment: str | None = None
    column_comments: dict = field(default_factory=dict)
    references: set = field(default_factory=set)
    partitioned_by: str | None = None
    partitions: int = 0
    indexes: int = 0
    unique_indexes: int = 0
    triggers: list = field(default_factory=list)
    dropped: dict | None = None
    renamed_from: list = field(default_factory=list)
    view_sources: set = field(default_factory=set)
    mappings: list = field(default_factory=list)


class Model:
    def __init__(self):
        self.tables: dict[str, Table] = {}
        self.functions: set = set()
        self.index_names: set = set()
        self.constraint_names: set = set()
        self.sequences: set = set()
        self.partition_names: set = set()
        self.seeds: list = []
        self.pending_refs: list = []  # (table, target type or table name, path, line)
        self.orm_types: dict[str, list] = defaultdict(list)  # type name -> [table]
        self.repos: dict[str, str] = {}  # repository type -> entity type
        self.prisma_accessors: dict[str, str] = {}  # prisma client accessor -> table
        self.associations: list = []  # (owner type, child type, path, line): child rows reached through the owner

    def table(self, name: str, kind: str = "table") -> Table:
        name = name.lower()
        t = self.tables.get(name)
        if t is None:
            t = self.tables[name] = Table(name, kind)
        return t

    def add_definition(self, name, rel, line, origin, test, kind="table", schema=None):
        t = self.table(name, kind)
        t.kind = kind if t.kind == "table" else t.kind
        t.schema = t.schema or schema
        t.definitions.append({"path": rel, "line": line, "origin": origin, "test": test})
        t.dropped = None
        return t

    def add_mapping(self, table, type_name, rel, line, origin, inferred, test):
        t = self.table(table)
        entry = {"type": type_name, "path": rel, "line": line, "origin": origin, "inferred": inferred, "test": test}
        if entry not in t.mappings:
            t.mappings.append(entry)
        if t.name not in self.orm_types[type_name]:
            self.orm_types[type_name].append(t.name)
        return t


# ------------------------------------------------------------------------------------------------------------
# SQL DDL
# ------------------------------------------------------------------------------------------------------------

IDENT = r'(?:"[^"]+"|`[^`]+`|\[[^\]]+\]|[A-Za-z_][\w$]*)'
QNAME = IDENT + r"(?:\s*\.\s*" + IDENT + r")*"
_SQL_COMMENTS = re.compile(r"--[^\n]*|/\*[\s\S]*?(?:\*/|\Z)|'(?:''|[^'])*'?|\$\$[\s\S]*?(?:\$\$|\Z)"
                           r"|\$([A-Za-z_]\w*)\$[\s\S]*?(?:\$\1\$|\Z)")
_SQL_SPLIT = re.compile(r"'(?:''|[^'])*'?|\$\$[\s\S]*?(?:\$\$|\Z)|\$([A-Za-z_]\w*)\$[\s\S]*?(?:\$\1\$|\Z)"
                        r"|\"(?:\"\"|[^\"])*\"?|;|^[ \t]*GO[ \t]*$", re.M | re.I)
RE_CT = re.compile(r"\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:(?:GLOBAL|LOCAL)\s+)?(?:(?:TEMP|TEMPORARY|UNLOGGED)\s+)?"
                   r"TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(" + QNAME + ")", re.I)
RE_CV = re.compile(r"\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:(?:TEMP|TEMPORARY)\s+)?(MATERIALIZED\s+)?VIEW\s+"
                   r"(?:IF\s+NOT\s+EXISTS\s+)?(" + QNAME + ")", re.I)
RE_AT = re.compile(r"\s*ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?(" + QNAME + r")\s+([\s\S]*)", re.I)
RE_CI = re.compile(r"\s*CREATE\s+(UNIQUE\s+)?(?:CLUSTERED\s+|NONCLUSTERED\s+)?INDEX\s+(?:CONCURRENTLY\s+)?"
                   r"(?:IF\s+NOT\s+EXISTS\s+)?(?:(" + QNAME + r")\s+)?ON\s+(?:ONLY\s+)?(" + QNAME + ")", re.I)
RE_DT = re.compile(r"\s*DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?([\s\S]+?)(?:\s+(?:CASCADE|RESTRICT))?\s*$", re.I)
RE_COMMENT = re.compile(r"\s*COMMENT\s+ON\s+(TABLE|COLUMN|VIEW)\s+(" + QNAME + r")\s+IS\s+'((?:''|[^'])*)'", re.I)
RE_TRG = re.compile(r"\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:CONSTRAINT\s+)?TRIGGER\s+(" + IDENT + r")\s+([\s\S]*?)"
                    r"\bON\s+(" + QNAME + r")([\s\S]*)", re.I)
RE_FN = re.compile(r"\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:FUNCTION|PROCEDURE)\s+(" + QNAME + ")", re.I)
RE_SEQ = re.compile(r"\s*CREATE\s+SEQUENCE\s+(?:IF\s+NOT\s+EXISTS\s+)?(" + QNAME + ")", re.I)
RE_SEED = re.compile(r"\s*(?:WITH\b[\s\S]*?\)\s*)?(INSERT\s+(?:IGNORE\s+)?INTO|UPDATE(?:\s+ONLY)?|DELETE\s+FROM(?:\s+ONLY)?|"
                     r"MERGE\s+INTO)\s+(" + QNAME + ")", re.I)
RE_REFS = re.compile(r"\bREFERENCES\s+(" + QNAME + ")", re.I)
TYPE_SPLIT = re.compile(r"\s+(?:NOT\s+NULL|NULL|DEFAULT|CONSTRAINT|PRIMARY\s+KEY|REFERENCES|CHECK|UNIQUE|GENERATED|"
                        r"COLLATE|AUTO_INCREMENT|AUTOINCREMENT|COMMENT|IDENTITY|ON\s+UPDATE)\b", re.I)
CONSTRAINT_WORDS = {"CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE", "LIKE", "INDEX", "KEY",
                    "FULLTEXT", "SPATIAL", "PERIOD", "PARTITION"}


def unquote(s: str) -> str:
    s = s.strip()
    return s[1:-1] if s[:1] in "\"`[" and len(s) > 1 else s


def qname(q: str):
    parts = [unquote(p) for p in re.findall(IDENT, q)]
    return parts[-1].lower(), (parts[-2].lower() if len(parts) > 1 else None)


def blank_sql_comments(text: str) -> str:
    chars = list(text)
    for m in _SQL_COMMENTS.finditer(text):
        if m.group().startswith(("--", "/*")):
            for k in range(m.start(), m.end()):
                if chars[k] != "\n":
                    chars[k] = " "
    return "".join(chars)


def split_statements(text: str):
    stmts, start = [], 0
    for m in _SQL_SPLIT.finditer(text):
        tok = m.group()
        if tok == ";" or tok.strip().upper() == "GO":
            stmts.append((text[start:m.start()], start))
            start = m.end()
    if text[start:].strip():
        stmts.append((text[start:], start))
    return stmts


def split_top_level(s: str, base: int):
    """Split on commas at parenthesis depth 0, skipping quoted text. Yields (piece, absolute offset)."""
    depth, start, i, n = 0, 0, 0, len(s)
    while i < n:
        c = s[i]
        if c in "'\"`":
            j = s.find(c, i + 1)
            while j > 0 and c == "'" and j + 1 < n and s[j + 1] == "'":
                j = s.find(c, j + 2)
            i = n if j < 0 else j + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            yield s[start:i], base + start
            start = i + 1
        i += 1
    if s[start:].strip():
        yield s[start:], base + start


def sql_paren(s: str, i: int) -> int:
    depth, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in "'\"`":
            j = s.find(c, i + 1)
            i = n if j < 0 else j + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def add_column(t: Table, el: str, rel: str, line: int):
    m = re.match(r"(" + IDENT + r")\s+([\s\S]+)", el.strip())
    if not m:
        return
    col = unquote(m.group(1)).lower()
    typ = " ".join(TYPE_SPLIT.split(m.group(2), maxsplit=1)[0].split())[:60]
    t.columns.setdefault(col, {"type": typ, "path": rel, "line": line})
    for r in RE_REFS.finditer(m.group(2)):
        t.references.add(qname(r.group(1))[0])


def parse_sql(model: Model, rel: str, raw: str, test: bool, origin: str = "sql", base_offset: int = 0,
              li: LineIndex | None = None):
    text = blank_sql_comments(raw)
    li = li or LineIndex(raw)
    for stmt, off in split_statements(text):
        lead = len(stmt) - len(stmt.lstrip())
        s, base = stmt.lstrip(), base_offset + off + lead
        if not s:
            continue
        line = li.line(base)
        head = s[:40].upper()
        if head.startswith("CREATE") and (m := RE_CT.match(s)):
            name, schema = qname(m.group(1))
            rest = s[m.end():]
            pm = re.match(r"\s*PARTITION\s+OF\s+(" + QNAME + ")", rest, re.I)
            if pm:
                model.table(qname(pm.group(1))[0]).partitions += 1
                model.partition_names.add(name)
                continue
            t = model.add_definition(name, rel, line, origin, test, schema=schema)
            om = re.match(r"\s*\(", rest)
            if om:
                open_idx = m.end() + om.end() - 1
                close_idx = sql_paren(s, open_idx)
                close_idx = len(s) if close_idx < 0 else close_idx
                for el, el_off in split_top_level(s[open_idx + 1:close_idx], open_idx + 1):
                    wm = re.match(r"\s*([A-Za-z_]+)", el)
                    word = wm.group(1).upper() if wm else ""
                    if word in CONSTRAINT_WORDS:
                        for r in RE_REFS.finditer(el):
                            t.references.add(qname(r.group(1))[0])
                        cm = re.match(r"\s*CONSTRAINT\s+(" + IDENT + ")", el, re.I)
                        if cm:
                            model.constraint_names.add(unquote(cm.group(1)).lower())
                        continue
                    add_column(t, el, rel, li.line(base + el_off + len(el) - len(el.lstrip())))
                pb = re.search(r"\bPARTITION\s+BY\s+(RANGE|LIST|HASH|KEY)\s*\(([^)]*)\)", s[close_idx:], re.I)
                if pb:
                    t.partitioned_by = f"{pb.group(1).upper()} ({' '.join(pb.group(2).split())})"
            continue
        if head.startswith("CREATE") and (m := RE_CV.match(s)):
            name, schema = qname(m.group(2))
            t = model.add_definition(name, rel, line, origin, test,
                                     kind="materialized_view" if m.group(1) else "view", schema=schema)
            t.kind = "materialized_view" if m.group(1) else "view"
            for r in re.finditer(r"\b(?:FROM|JOIN)\s+(" + QNAME + ")", s[m.end():], re.I):
                t.view_sources.add(qname(r.group(1))[0])
            continue
        if head.startswith("ALTER") and (m := RE_AT.match(s)):
            name = qname(m.group(1))[0]
            t = model.table(name)
            for act, act_off in split_top_level(m.group(2), base + m.start(2)):
                a = act.strip()
                aline = li.line(act_off)
                if (rm := re.match(r"RENAME\s+TO\s+(" + QNAME + ")", a, re.I)):
                    new = qname(rm.group(1))[0]
                    model.tables.pop(t.name, None)
                    t.renamed_from.append(t.name)
                    t.name = new
                    model.tables[new] = t
                elif (rm := re.match(r"RENAME\s+(?:COLUMN\s+)?(" + IDENT + r")\s+TO\s+(" + IDENT + ")", a, re.I)):
                    col = t.columns.pop(unquote(rm.group(1)).lower(), {"type": "", "path": rel, "line": aline})
                    t.columns[unquote(rm.group(2)).lower()] = col
                elif re.match(r"ADD\s+(?:CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK|EXCLUDE|INDEX|KEY)\b", a, re.I):
                    for r in RE_REFS.finditer(a):
                        t.references.add(qname(r.group(1))[0])
                    cm = re.match(r"ADD\s+CONSTRAINT\s+(" + IDENT + ")", a, re.I)
                    if cm:
                        model.constraint_names.add(unquote(cm.group(1)).lower())
                elif (am := re.match(r"ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?([\s\S]+)", a, re.I)):
                    add_column(t, am.group(1), rel, aline)
                elif (dm := re.match(r"DROP\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?(" + IDENT + ")", a, re.I)):
                    if dm.group(1).upper() not in ("CONSTRAINT", "INDEX", "PRIMARY", "FOREIGN"):
                        t.columns.pop(unquote(dm.group(1)).lower(), None)
                elif re.match(r"ATTACH\s+PARTITION", a, re.I):
                    t.partitions += 1
            continue
        if head.startswith("CREATE") and (m := RE_CI.match(s)):
            t = model.table(qname(m.group(3))[0])
            t.indexes += 1
            t.unique_indexes += 1 if m.group(1) else 0
            if m.group(2):
                model.index_names.add(qname(m.group(2))[0])
            continue
        if head.startswith("DROP") and (m := RE_DT.match(s)):
            for part in m.group(1).split(","):
                if part.strip():
                    t = model.tables.get(qname(part)[0])
                    if t:
                        t.dropped = {"path": rel, "line": line}
            continue
        if head.startswith("COMMENT") and (m := RE_COMMENT.match(s)):
            parts = [unquote(p) for p in re.findall(IDENT, m.group(2))]
            text_ = m.group(3).replace("''", "'")
            if m.group(1).upper() in ("TABLE", "VIEW"):
                model.table(parts[-1]).comment = text_
            elif len(parts) >= 2:
                model.table(parts[-2]).column_comments[parts[-1].lower()] = text_
            continue
        if head.startswith("CREATE") and (m := RE_TRG.match(s)):
            fn = re.search(r"EXECUTE\s+(?:FUNCTION|PROCEDURE)\s+(" + QNAME + ")", m.group(4), re.I)
            model.table(qname(m.group(3))[0]).triggers.append(
                {"name": unquote(m.group(1)), "when": " ".join(m.group(2).split()),
                 "function": qname(fn.group(1))[0] if fn else None, "path": rel, "line": line})
            continue
        if head.startswith("CREATE") and (m := RE_FN.match(s)):
            model.functions.add(qname(m.group(1))[0])
            continue
        if head.startswith("CREATE") and (m := RE_SEQ.match(s)):
            model.sequences.add(qname(m.group(1))[0])
            continue
        m = RE_SEED.match(s)
        if m:
            verb = m.group(1).upper()
            op = "C" if verb.startswith("INSERT") else "D" if verb.startswith("DELETE") else "U"
            model.seeds.append({"table": qname(m.group(2))[0], "path": rel, "line": line, "op": op})


# ------------------------------------------------------------------------------------------------------------
# Liquibase XML / YAML
# ------------------------------------------------------------------------------------------------------------

def _attr(attrs: str, name: str):
    m = re.search(r"\b" + name + r'\s*=\s*"([^"]*)"', attrs)
    return m.group(1) if m else None


def parse_liquibase_xml(model: Model, rel: str, text: str, test: bool):
    li = LineIndex(text)
    for m in re.finditer(r"<createTable\b([^>]*)>([\s\S]*?)</createTable>", text):
        name = _attr(m.group(1), "tableName")
        if not name:
            continue
        t = model.add_definition(name, rel, li.line(m.start()), "liquibase", test, schema=_attr(m.group(1), "schemaName"))
        for c in re.finditer(r"<column\b([^>]*?)/?>", m.group(2)):
            cname = _attr(c.group(1), "name")
            if cname:
                t.columns.setdefault(cname.lower(), {"type": _attr(c.group(1), "type") or "", "path": rel,
                                                     "line": li.line(m.start(2) + c.start())})
        for r in re.finditer(r'references\s*=\s*"([\w.]+)\s*\(', m.group(2)):
            t.references.add(r.group(1).split(".")[-1].lower())
        for r in re.finditer(r'referencedTableName\s*=\s*"([\w.]+)"', m.group(2)):
            t.references.add(r.group(1).split(".")[-1].lower())
    for m in re.finditer(r"<addColumn\b([^>]*)>([\s\S]*?)</addColumn>", text):
        name = _attr(m.group(1), "tableName")
        if name:
            t = model.table(name)
            for c in re.finditer(r"<column\b([^>]*?)/?>", m.group(2)):
                cname = _attr(c.group(1), "name")
                if cname:
                    t.columns.setdefault(cname.lower(), {"type": _attr(c.group(1), "type") or "", "path": rel,
                                                         "line": li.line(m.start(2) + c.start())})
    for m in re.finditer(r"<addForeignKeyConstraint\b([^>]*)/?>", text):
        base, ref = _attr(m.group(1), "baseTableName"), _attr(m.group(1), "referencedTableName")
        if base and ref:
            model.table(base).references.add(ref.lower())
    for m in re.finditer(r"<createView\b([^>]*)>", text):
        name = _attr(m.group(1), "viewName")
        if name:
            model.add_definition(name, rel, li.line(m.start()), "liquibase", test, kind="view").kind = "view"
    for m in re.finditer(r"<createIndex\b([^>]*)>", text):
        name = _attr(m.group(1), "tableName")
        if name:
            model.table(name).indexes += 1
    for m in re.finditer(r"<dropTable\b([^>]*)/?>", text):
        name = _attr(m.group(1), "tableName")
        if name and name.lower() in model.tables:
            model.tables[name.lower()].dropped = {"path": rel, "line": li.line(m.start())}
    for m in re.finditer(r"<sql\b[^>]*>([\s\S]*?)</sql>", text):
        body = re.sub(r"<!\[CDATA\[|\]\]>", lambda x: " " * len(x.group()), m.group(1))
        parse_sql(model, rel, text[:m.start(1)] + body + text[m.end(1):], test, "liquibase", 0, li)


def parse_liquibase_yaml(model: Model, rel: str, text: str, test: bool):
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if not re.match(r"\s*-?\s*createTable:\s*$", line):
            continue
        indent = len(line) - len(line.lstrip())
        t, j = None, i + 1
        pending_col = None
        while j < len(lines) and (not lines[j].strip() or len(lines[j]) - len(lines[j].lstrip()) > indent):
            s = lines[j].strip()
            if (m := re.match(r"-?\s*tableName:\s*['\"]?([\w.]+)", s)) and t is None:
                t = model.add_definition(m.group(1).split(".")[-1], rel, i + 1, "liquibase", test)
            elif (m := re.match(r"-?\s*name:\s*['\"]?(\w+)", s)):
                pending_col = (m.group(1).lower(), j + 1)
            elif (m := re.match(r"-?\s*type:\s*['\"]?([^'\"]+)", s)) and pending_col and t is not None:
                t.columns.setdefault(pending_col[0], {"type": m.group(1).strip(), "path": rel, "line": pending_col[1]})
                pending_col = None
            elif (m := re.match(r"-?\s*references:\s*['\"]?([\w.]+)\s*\(", s)) and t is not None:
                t.references.add(m.group(1).split(".")[-1].lower())
            j += 1


# ------------------------------------------------------------------------------------------------------------
# code index: classes, methods, annotations, imports, calls
# ------------------------------------------------------------------------------------------------------------

@dataclass
class Cls:
    name: str
    qual: str
    start: int
    end: int
    line: int
    bases: list
    annotations: list
    kind: str
    header: str
    decl_methods: set = field(default_factory=set)


@dataclass
class Meth:
    name: str
    owner: str | None
    start: int
    body: int
    end: int
    line: int
    annotations: list
    header: str
    ids: list
    entry: dict | None = None
    name_offset: int = -1

    def display(self, rel: str) -> str:
        if self.owner:
            return f"{self.owner}#{self.name}"
        return f"{os.path.splitext(os.path.basename(rel))[0]}#{self.name}"


CLASS_RE = re.compile(r"\b(class|interface|enum|record|object|struct|trait|namespace|module|impl)\s+([A-Za-z_]\w*)")
GO_TYPE_RE = re.compile(r"\btype\s+([A-Za-z_]\w*)\s+(?:struct|interface)\b")
FUNC_KW_RE = re.compile(r"\b(?:fun|func|function|def)\s*\*?\s+(?:\([^)]*\)\s*)?(?:<[^>]*>\s*)?(?:[A-Za-z_]\w*\.)*"
                        r"([A-Za-z_]\w*)\s*[(<]")
ARROW_RE = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_]\w*)\s*(?::[^=]*)?=\s*(?:async\s+)?(?:\([^)]*\)|[A-Za-z_]\w*)"
                      r"\s*(?::\s*[^=]+?)?\s*=>$")
CALLISH_RE = re.compile(r"([A-Za-z_]\w*)\s*\(")
TAIL_RE = re.compile(r"^\s*(?:throws\s+[\w.,\s<>]+|:\s*[^{};=]*|->\s*[^{};]*|where\b[^{]*|const|override|noexcept|"
                     r"async)?\s*$")
KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "try", "else", "do", "return", "new", "when",
            "foreach", "using", "lock", "fixed", "unsafe", "checked", "unchecked", "finally", "static", "get", "set",
            "init", "super", "this", "match", "loop", "select", "go", "defer", "with", "assert", "throw", "yield",
            "await", "typeof", "sizeof", "elif", "except", "unless", "until", "case", "default", "function"}
STRUCT_RE = re.compile(r"[(){};]")


def parse_bases(rest: str) -> list:
    rest = rest.strip()
    if rest.startswith("("):
        close = match_paren(rest, 0)
        rest = rest[close + 1:] if close >= 0 else ""
    while True:
        new = re.sub(r"<[^<>]*>", "", rest)
        if new == rest:
            break
        rest = new
    rest = re.sub(r"\([^()]*\)", "", rest)
    names = []
    for part in re.split(r"\b(?:extends|implements|with)\b|:|,|<", rest):
        m = re.match(r"\s*([A-Za-z_][\w.]*)", part)
        if m and m.group(1) not in ("where", "public", "private", "protected", "internal", "sealed", "permits"):
            names.append(m.group(1).split(".")[-1])
    return names


def classify_header(h: str, lang: str):
    h = " ".join(h.split())
    if not h:
        return "block", None, [], -1
    if h.endswith("=>") or h.endswith("->"):
        m = ARROW_RE.search(h)
        return ("method", m.group(1), [], m.start(1)) if m else ("block", None, [], -1)
    if lang == "go" and (m := GO_TYPE_RE.search(h)):
        return "class", m.group(1), [], m.start(1)
    m = CLASS_RE.search(h)
    if m and not re.search(r"\bnew\b", h[:m.start()]) and not re.search(r"[=(]\s*$", h[:m.start()]):
        return "class", m.group(2), parse_bases(h[m.end():]), m.start(2)
    m = FUNC_KW_RE.search(h)
    if m and m.group(1) not in KEYWORDS:
        return "method", m.group(1), [], m.start(1)
    for c in reversed(list(CALLISH_RE.finditer(h))):
        before = h[:c.start()].rstrip()
        # parameter annotations (@RequestParam(...)) sit inside the parameter list; annotations are not callables
        if before.count("(") > before.count(")") or before.endswith("@"):
            continue
        close = match_paren(h, c.end() - 1)
        if close < 0:
            continue
        name = c.group(1)
        if name in KEYWORDS and name not in ("get", "set", "init"):  # get(...) is a method; C# get { } has no parens
            return "block", None, [], -1
        if re.search(r"\bnew\s*$", before) or re.search(r"\bnew\s+[\w.<>]*$", before + " " + name):
            return "anon", name, [], -1
        if TAIL_RE.match(h[close + 1:]) and not before.endswith((".", "=", "(", ",", "return")):
            return "method", name, [], c.start(1)
        return "block", None, [], -1
    return "block", None, [], -1


def annotations_in(blank_header: str, orig_header: str, lang: str) -> list:
    out = []
    if lang == "csharp":
        for m in re.finditer(r"\[\s*([A-Za-z_][\w.]*)\s*(\()?", blank_header):
            args = ""
            if m.group(2):
                close = match_paren(blank_header, m.end() - 1)
                args = orig_header[m.end() - 1:close + 1] if close >= 0 else ""
            out.append((m.group(1).split(".")[-1].removesuffix("Attribute"), args))
        return out
    for m in re.finditer(r"@([A-Za-z_][\w.]*)(\s*\()?", blank_header):
        args = ""
        if m.group(2):
            open_idx = m.end() - 1
            close = match_paren(blank_header, open_idx)
            args = orig_header[open_idx:close + 1] if close >= 0 else ""
        out.append((m.group(1).split(".")[-1], args))
    return out


class CodeFile:
    def __init__(self, rel: str, lang: str, text: str, test: bool):
        self.rel, self.lang, self.text, self.test = rel, lang, text, test
        self.blank, self.mask = scan(text, lang)
        self.li = LineIndex(text)
        self.classes: list[Cls] = []
        self.methods: list[Meth] = []
        self.package = None
        self.imports: set = set()
        self.fq_imports: set = set()  # JVM: fully qualified imports, to tell same-named types apart
        self.wild: set = set()
        self.words: set = set()
        self.calls: dict = defaultdict(list)
        self.decl_offsets: set = set()
        self.routes: list = []
        self._vars: dict = {}
        if lang in CODE:
            self._index()

    # ---- indexing ----
    def _index(self):
        if self.lang in BRACE:
            self._index_brace()
        else:
            self._index_indented()
        self.methods.sort(key=lambda m: m.start)
        self.words = set(re.findall(r"[A-Za-z_]\w*", self.blank))
        for m in CALLISH_RE.finditer(self.blank):
            self.calls[m.group(1)].append((m.start(1), "call"))
        for m in re.finditer(r"::\s*([A-Za-z_]\w*)", self.blank):
            self.calls[m.group(1)].append((m.start(1), "ref"))
        if self.lang == "ruby":
            for m in re.finditer(r"\.([A-Za-z_]\w*[?!]?)(?!\s*\()", self.blank):
                self.calls[m.group(1)].append((m.start(1), "call"))
        self._imports()

    def _imports(self):
        b = self.blank
        if self.lang in JVM:
            pm = re.search(r"^\s*package\s+([\w.]+)", b, re.M)
            self.package = pm.group(1) if pm else ""
            for m in re.finditer(r"^\s*import\s+(?:static\s+)?([\w.]+?)(\.\*|\._)?\s*;?\s*$", b, re.M):
                if m.group(2):
                    self.wild.add(m.group(1))
                else:
                    parts = m.group(1).split(".")
                    self.imports.update(parts[-2:])
                    self.fq_imports.add(m.group(1))
            for m in re.finditer(r"^\s*import\s+([\w.]+)\.\{([^}]*)\}", b, re.M):
                self.imports.update(x.strip().split(" ")[0] for x in m.group(2).split(","))
        elif self.lang == "csharp":
            pm = re.search(r"^\s*namespace\s+([\w.]+)", b, re.M)
            self.package = pm.group(1) if pm else ""
            self.wild.update(re.findall(r"^\s*using\s+(?:static\s+)?([\w.]+)\s*;", b, re.M))
        elif self.lang in ("js", "ts"):
            for m in re.finditer(r"\bimport\s+(?:type\s+)?([\s\S]*?)\s+from\s+", b):
                self.imports.update(re.findall(r"[A-Za-z_]\w*", m.group(1)))
            for m in re.finditer(r"(?:const|let|var)\s+(\{[^}]*\}|[A-Za-z_]\w*)\s*=\s*require\(", b):
                self.imports.update(re.findall(r"[A-Za-z_]\w*", m.group(1)))
        elif self.lang == "python":
            for m in re.finditer(r"^\s*from\s+[\w.]+\s+import\s+\(?([^)\n]*(?:\n[^)\n]*)*?)\)?\s*$", b, re.M):
                self.imports.update(re.findall(r"[A-Za-z_]\w*", m.group(1)))
            for m in re.finditer(r"^\s*import\s+([\w., ]+)", b, re.M):
                self.imports.update(x.strip().split(".")[-1] for x in m.group(1).split(","))

    def _index_brace(self):
        b, text = self.blank, self.text
        stack, boundary, paren = [], 0, 0
        for m in STRUCT_RE.finditer(b):
            ch, i = m.group(), m.start()
            if ch == "(":
                paren += 1
                continue
            if ch == ")":
                paren = max(0, paren - 1)
                continue
            if paren > 0:
                continue
            if ch == ";":
                boundary = i + 1
                continue
            if ch == "{":
                blank_header = b[boundary:i]
                kind, name, bases, name_at = classify_header(blank_header, self.lang)
                lead = len(blank_header) - len(blank_header.lstrip())
                hstart = boundary + lead
                entry = {"kind": kind, "name": name, "bases": bases, "hstart": hstart, "body": i,
                         "header": " ".join(blank_header.split()),
                         "ann": annotations_in(blank_header, text[boundary:i], self.lang),
                         "ids": req_ids("".join(text[k] for k in range(boundary, i) if self.mask[k] == 2)),
                         "name_at": -1}
                if name_at >= 0 and name:
                    m2 = re.search(r"\b" + re.escape(name) + r"\b", b[hstart:i])
                    entry["name_at"] = hstart + m2.start() if m2 else -1
                if kind == "class":
                    owners = [s["qual"] for s in stack if s["kind"] == "class"]
                    entry["qual"] = ".".join(owners[-1:] + [name]) if owners else name
                stack.append(entry)
                boundary = i + 1
                continue
            # '}'
            if stack:
                self._finish(stack.pop(), i, stack)
            boundary = i + 1
        while stack:
            self._finish(stack.pop(), len(b), stack)
        # interface/abstract members without bodies
        for c in self.classes:
            body = b[c.start:c.end]
            depth = 0
            for mm in re.finditer(r"[{}]|([A-Za-z_]\w*)\s*\([^(){};]*\)\s*(?:throws[\w.,\s]*)?(?::\s*[\w<>\[\]?,. ]+)?\s*;",
                                  body):
                tok = mm.group()
                if tok == "{":
                    depth += 1
                elif tok == "}":
                    depth -= 1
                elif depth == 1 and mm.group(1) and mm.group(1) not in KEYWORDS:
                    c.decl_methods.add(mm.group(1))

    def _finish(self, e: dict, end: int, stack: list):
        line = self.li.line(e["hstart"])
        if e["kind"] == "class":
            self.classes.append(Cls(e["name"], e["qual"], e["body"], end, line, e["bases"], e["ann"], "class",
                                    e["header"]))
            return
        if e["kind"] != "method":
            return
        owner = None
        for s in reversed(stack):
            if s["kind"] == "class":
                owner = s["qual"]
                break
        if self.lang == "go":
            rm = re.search(r"\bfunc\s*\(\s*\w*\s*\*?\s*([A-Za-z_]\w*)", e["header"])
            owner = rm.group(1) if rm else owner
        meth = Meth(e["name"], owner, e["hstart"], e["body"], end, line, e["ann"], e["header"], e["ids"],
                    name_offset=e["name_at"])
        if e["name_at"] >= 0:
            self.decl_offsets.add(e["name_at"])
        self.methods.append(meth)
        for c in self.classes:
            if c.qual == owner:
                c.decl_methods.add(e["name"])

    def _index_indented(self):
        b, text, lang = self.blank, self.text, self.lang
        lines = b.split("\n")
        starts = self.li.starts
        if lang == "python":
            hdr = re.compile(r"^([ \t]*)(?:async\s+)?(def|class)\s+([A-Za-z_]\w*)\s*(\([^)]*\)?)?")
        elif lang == "ruby":
            hdr = re.compile(r"^([ \t]*)(def|class|module)\s+(?:self\.)?([\w:]+[?!=]?)\s*(<\s*[\w:]+)?")
        else:
            hdr = re.compile(r"^([ \t]*)(defmodule|defp|def|defmacro)\s+([\w.?!]+)")

        def indent(s):
            return len(s.expandtabs(8)) - len(s.expandtabs(8).lstrip())

        headers = []
        for i, line in enumerate(lines):
            m = hdr.match(line)
            if m:
                headers.append((i, indent(line), m.group(2), m.group(3), m.group(4) or ""))
        for idx, (i, ind, kind, name, extra) in enumerate(headers):
            end = len(lines) - 1
            for j in range(i + 1, len(lines)):
                s = lines[j]
                if not s.strip():
                    continue
                if lang == "python":
                    if indent(s) <= ind and not s.strip().startswith(")"):
                        end = j - 1
                        break
                elif indent(s) == ind and re.match(r"\s*end\b", s):
                    end = j
                    break
                elif indent(s) < ind:
                    end = j - 1
                    break
            ann = []
            k = i - 1
            while k >= 0 and lines[k].strip().startswith("@") and lang == "python":
                am = re.match(r"\s*@([\w.]+)\s*(\()?", lines[k])
                if am:
                    orig_line = text[starts[k]:starts[k + 1] - 1 if k + 1 < len(starts) else len(text)]
                    args = orig_line[orig_line.find("(", am.start(1)):] if am.group(2) else ""
                    ann.append((am.group(1), args))
                k -= 1
            ids = []
            if k >= 0:
                ids = req_ids(text[starts[max(0, k - 5)]:starts[i]])
            start_off = starts[i]
            end_off = starts[end + 1] - 1 if end + 1 < len(starts) else len(text)
            owners = [c for c in self.classes if c.start <= start_off <= c.end]
            if kind in ("class", "module", "defmodule"):
                bases = [x.split(".")[-1] for x in re.findall(r"[A-Za-z_][\w.:]*", extra)] if extra else []
                bases = [x.split("::")[-1] for x in bases]
                qual = ".".join([owners[-1].qual, name]) if owners else name
                self.classes.append(Cls(name, qual, start_off, end_off, i + 1, bases, ann, "class", lines[i].strip()))
            else:
                owner = owners[-1].qual if owners else None
                nm = re.search(r"\b" + re.escape(name.rstrip("?!=")), lines[i])
                name_off = start_off + nm.start() if nm else -1
                meth = Meth(name.rstrip("=") if lang == "ruby" else name, owner, start_off, start_off, end_off, i + 1,
                            ann, lines[i].strip(), ids, name_offset=name_off)
                if name_off >= 0:
                    self.decl_offsets.add(name_off)
                self.methods.append(meth)
                if owners:
                    owners[-1].decl_methods.add(meth.name)
        self.classes.sort(key=lambda c: c.start)

    # ---- queries ----
    def enclosing_method(self, off: int):
        best = None
        for m in self.methods:
            if m.start <= off <= m.end and (best is None or m.start >= best.start):
                best = m
        return best

    def enclosing_class(self, off: int):
        best = None
        for c in self.classes:
            if c.start <= off <= c.end and (best is None or c.start >= best.start):
                best = c
        return best

    def cls(self, qual: str):
        for c in self.classes:
            if c.qual == qual:
                return c
        return None

    def vars_of(self, t: str) -> set:
        """Names of fields, parameters and locals whose declared type is (or wraps) t."""
        if t in self._vars:
            return self._vars[t]
        b, out = self.blank, set()
        te = re.escape(t)
        if self.lang in ("java", "csharp", "groovy", "scala", "php", "js", "ts"):
            for m in re.finditer(r"\b(?:[\w.]*\.)?" + te + r"\s*(?:<[^;(){}=]*?>)?(?:\[\])?\s+([A-Za-z_]\w*)\s*[;=,)]", b):
                out.add(m.group(1))
            for m in re.finditer(r"<[^;(){}=]*\b" + te + r"\b[^;(){}=]*>\s+([A-Za-z_]\w*)\s*[;=,)]", b):
                out.add(m.group(1))
            for m in re.finditer(r"\bvar\s+([A-Za-z_]\w*)\s*=\s*new\s+" + te + r"\b", b):
                out.add(m.group(1))
        if self.lang in ("kotlin", "ts", "python", "scala", "rust", "go", "php"):
            for m in re.finditer(r"\b([A-Za-z_]\w*)\s*:\s*(?:[\w.]*\.)?" + te + r"\b", b):
                out.add(m.group(1))
            for m in re.finditer(r"\b([A-Za-z_]\w*)\s*:\s*[\w.]+<[^>=]*\b" + te + r"\b", b):
                out.add(m.group(1))
        if self.lang == "go":
            for m in re.finditer(r"\b([A-Za-z_]\w*)\s+\*?(?:\w+\.)?" + te + r"\b", b):
                out.add(m.group(1))
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*=\s*(?:new\s+)?" + te + r"\s*\(", b):
            out.add(m.group(1))
        out -= KEYWORDS
        self._vars[t] = out
        return out


# ------------------------------------------------------------------------------------------------------------
# entry points
# ------------------------------------------------------------------------------------------------------------

def join_route(base, path):
    base, path = (base or "").strip(), (path or "").strip()
    if not base:
        return path or "/"
    if not path:
        return base
    return base.rstrip("/") + "/" + path.lstrip("/")


def detect_entries(cf: CodeFile):
    ann_http_class = {}
    for c in cf.classes:
        names = {a for a, _ in c.annotations}
        base = None
        for a, args in c.annotations:
            if a in ("RequestMapping", "Path", "Route") or (a == "Controller" and cf.lang == "ts"):
                base = first_string(args)
        ann_http_class[c.qual] = (base, names, c)
    # method references / lambdas passed to schedulers
    scheduled = set()
    for m in re.finditer(r"\b(?:schedule\w*|setInterval|setTimeout|cron\.schedule|every)\s*\(", cf.blank):
        close = match_paren(cf.blank, m.end() - 1)
        span = cf.blank[m.end():close if close > 0 else m.end() + 400]
        for r in re.finditer(r"(?:this|self|[A-Za-z_]\w*)\s*::\s*([A-Za-z_]\w*)", span):
            scheduled.add(r.group(1))
        if "->" in span or "=>" in span or "lambda" in span:
            outer = cf.enclosing_method(m.start())
            if outer:
                outer.entry = {"kind": "scheduled", "route": None, "why": "registers a scheduled lambda"}
    route_calls = []
    if cf.lang in ("js", "ts", "go", "csharp"):
        for m in re.finditer(r"\b(app|router|server|api|routes?|r|e|g|mux|engine|group|grp|web|admin|v\d+|\w*Router|"
                             r"\w*Routes|\w*App|\w*Group)\s*\.\s*(get|post|put|patch|delete|all|route|HandleFunc|Handle|GET|"
                             r"POST|PUT|PATCH|DELETE|MapGet|MapPost|MapPut|MapDelete|MapPatch)\s*\(\s*[\"'`](/[^\"'`]*)[\"'`]"
                             r"(?:\s*,\s*([^)]*))?", cf.text):
            verb = m.group(2).upper().replace("MAP", "").replace("HANDLEFUNC", "ANY").replace("HANDLE", "ANY")
            verb = "ANY" if verb in ("ALL", "ROUTE") else verb
            handler = re.findall(r"([A-Za-z_]\w*)\s*$", (m.group(4) or "").split("=>")[0].strip())
            route_calls.append((verb, m.group(3), handler[0] if handler else None, m.start()))
    for meth in cf.methods:
        if meth.entry:
            continue
        names = {a for a, _ in meth.annotations}
        base, cnames, cobj = ann_http_class.get(meth.owner, (None, set(), None))
        http = [(HTTP_VERBS[a], args) for a, args in meth.annotations if a in HTTP_VERBS]
        if cf.lang in JVM | {"csharp"} and "Path" in names and not http:
            verbs = [a for a in names if a in ("GET", "POST", "PUT", "DELETE", "PATCH")]
            if verbs:
                http = [(verbs[0], dict(meth.annotations).get("Path", ""))]
        if http:
            verb, args = http[0]
            path = first_string(args) if args else None
            if verb == "ANY":
                vm = re.search(r"RequestMethod\.(\w+)", args or "")
                verb = vm.group(1) if vm else "ANY"
            meth.entry = {"kind": "http", "route": f"{verb} {join_route(base, path)}", "why": "mapping annotation"}
        elif names & SCHED_ANN or meth.name in scheduled:
            meth.entry = {"kind": "scheduled", "route": None,
                          "why": "scheduled annotation" if names & SCHED_ANN else "passed to a scheduler"}
        elif names & MSG_ANN:
            meth.entry = {"kind": "messaging", "route": None, "why": ",".join(sorted(names & MSG_ANN))}
        elif names & STARTUP_ANN:
            meth.entry = {"kind": "startup", "route": None, "why": ",".join(sorted(names & STARTUP_ANN))}
        elif names & TEST_ANN or (cf.lang == "python" and meth.name.startswith("test_")):
            meth.entry = {"kind": "test", "route": None, "why": "test"}
        elif "Bean" in names:
            meth.entry = {"kind": "wiring", "route": None, "why": "@Bean factory"}
        elif cobj and any((b, meth.name) in LIFECYCLE for b in cobj.bases):
            meth.entry = {"kind": "startup", "route": None, "why": "lifecycle callback"}
        elif meth.name == "main" and ("static" in meth.header or cf.lang in ("go", "kotlin")):
            meth.entry = {"kind": "main", "route": None, "why": "program entry"}
        elif cf.lang in JVM and "HttpServletRequest" in meth.header and "HttpServletResponse" in meth.header:
            meth.entry = {"kind": "http", "route": "(programmatic mapping)", "why": "servlet request/response"}
        elif cf.lang == "python":
            dec = [(a, args) for a, args in meth.annotations]
            for a, args in dec:
                last = a.split(".")[-1]
                if last in ("route", "get", "post", "put", "patch", "delete", "api_route", "websocket"):
                    verb = last.upper() if last not in ("route", "api_route") else "ANY"
                    meth.entry = {"kind": "http", "route": f"{verb} {first_string(args) or '?'}", "why": a}
                    break
                if last in ("task", "shared_task", "periodic_task", "scheduled_job", "cron", "job", "actor"):
                    meth.entry = {"kind": "scheduled", "route": None, "why": a}
                    break
                if last in ("receiver", "subscriber", "consumer", "listener", "on_event"):
                    meth.entry = {"kind": "messaging", "route": None, "why": a}
                    break
                if last == "command":
                    meth.entry = {"kind": "cli", "route": None, "why": a}
                    break
            if not meth.entry and meth.name in ("get", "post", "put", "patch", "delete") and cobj and any(
                    b.endswith(("View", "ViewSet", "APIView", "Resource", "Handler")) for b in cobj.bases):
                meth.entry = {"kind": "http", "route": f"{meth.name.upper()} ?", "why": "class-based view"}
            if not meth.entry and meth.name == "handle" and cobj and "BaseCommand" in cobj.bases:
                meth.entry = {"kind": "cli", "route": None, "why": "management command"}
        elif cf.lang == "ruby":
            if "/app/controllers/" in "/" + cf.rel and meth.owner:
                meth.entry = {"kind": "http", "route": f"{meth.owner}#{meth.name}", "why": "Rails controller action"}
            elif meth.name == "perform" and re.search(r"/app/(jobs|workers)/", "/" + cf.rel):
                meth.entry = {"kind": "scheduled", "route": None, "why": "background job"}
        elif cf.lang in ("ts", "js") and re.search(r"(^|/)(app/.*/route|pages/api/.*)\.[jt]sx?$", cf.rel) and \
                meth.name in ("GET", "POST", "PUT", "PATCH", "DELETE", "handler"):
            meth.entry = {"kind": "http", "route": f"{meth.name} /{cf.rel}", "why": "Next.js route"}
    for verb, path, handler, off in route_calls:
        target = None
        if handler:
            target = next((m for m in cf.methods if m.name == handler), None)
        if target is None:
            target = cf.enclosing_method(off)
        if target is not None and not target.entry:
            target.entry = {"kind": "http", "route": f"{verb} {path}", "why": "route registration"}


# ------------------------------------------------------------------------------------------------------------
# ORM mappings
# ------------------------------------------------------------------------------------------------------------

REPO_BASES = r"(?:JpaRepository|CrudRepository|ListCrudRepository|PagingAndSortingRepository|" \
             r"ListPagingAndSortingRepository|Repository|ReactiveCrudRepository|ReactiveSortingRepository|" \
             r"R2dbcRepository|CoroutineCrudRepository|CoroutineSortingRepository|JpaSpecificationExecutor|" \
             r"QuerydslPredicateExecutor|JdbcRepository|PageableRepository|GenericRepository)"


def parse_mappings(model: Model, cf: CodeFile):
    lang, text, rel, test = cf.lang, cf.text, cf.rel, cf.test
    li = cf.li
    if lang in JVM:
        for c in cf.classes:
            names = {a for a, _ in c.annotations}
            args = dict(c.annotations)
            if names & {"Entity", "Table", "MappedEntity"} and "Embeddable" not in names:
                table = first_string(args.get("Table", ""), ("name", "value")) if "Table" in names else None
                if not table and "MappedEntity" in names:
                    table = first_string(args.get("MappedEntity", ""), ("value",))
                inferred = not table
                table = table or snake(first_string(args.get("Entity", ""), ("name",)) or c.name)
                model.add_mapping(table, c.name, rel, c.line, "jpa" if "Entity" in names else "spring-data",
                                  inferred, test)
                for fm in re.finditer(r"@(?:\w+\.)*(?:ManyToOne|OneToOne)\b[\s\S]{0,300}?\b(?:private|protected|public|val|var)?"
                                      r"\s*(?:[\w.]+\.)?([A-Z]\w*)\s+\w+\s*[;=]", cf.blank[c.start:c.end]):
                    model.pending_refs.append((table.lower(), fm.group(1), rel, li.line(c.start + fm.start())))
            if c.header.startswith(("interface", "public interface")) or "interface" in c.header.split(" ")[:3]:
                rm = re.search(r"\b" + REPO_BASES + r"\s*<\s*([\w.]+)", c.header)
                if rm:
                    model.repos[c.name] = rm.group(1).split(".")[-1]
        for c in cf.classes:
            if "Entity" not in {a for a, _ in c.annotations}:
                continue
            for am in re.finditer(r"@(?:\w+\.)*(OneToMany|ManyToMany)\b[\s\S]{0,400}?\b(?:List|Set|Collection|SortedSet|"
                                  r"MutableList|MutableSet)\s*<\s*([A-Z]\w*)\s*>", cf.blank[c.start:c.end]):
                model.associations.append((c.name, am.group(2), rel, li.line(c.start + am.start())))
        for m in re.finditer(r"@(?:\w+\.)*(CollectionTable|JoinTable|SecondaryTable)\s*\(", cf.blank):
            close = match_paren(cf.blank, m.end() - 1)
            name = first_string(text[m.end() - 1:close + 1], ("name",))
            owner = cf.enclosing_class(m.start())
            if name and owner:
                model.add_mapping(name, owner.name, rel, li.line(m.start()), "jpa-" + snake(m.group(1)).split("_")[0],
                                  False, test)
    elif lang == "python":
        for c in cf.classes:
            body = text[c.start:c.end]
            m = re.search(r"^\s*__tablename__\s*=\s*[\"']([^\"']+)[\"']", body, re.M)
            if m and cf.mask[c.start + m.end(1) - len(m.group(1)) - 1] == 1 and \
                    cf.mask[c.start + m.start() + len(m.group()) - len(m.group().lstrip())] == 0:
                model.add_mapping(m.group(1), c.name, rel, c.line, "sqlalchemy", False, test)
                t = model.table(m.group(1))
                for col in re.finditer(r"^\s*(\w+)\s*(?::[^=\n]+)?=\s*(?:sa\.|db\.|sqlalchemy\.)?(?:Column|mapped_column)"
                                       r"\(\s*(?:[\"'](\w+)[\"']\s*,\s*)?(?:sa\.|db\.)?(\w+)?", body, re.M):
                    t.columns.setdefault((col.group(2) or col.group(1)).lower(),
                                         {"type": col.group(3) or "", "path": rel,
                                          "line": li.line(c.start + col.start())})
                for fk in re.finditer(r"ForeignKey\(\s*[\"'](\w+)\.", body):
                    t.references.add(fk.group(1).lower())
                continue
            if "SQLModel" in c.bases and "table=True" in c.header.replace(" ", ""):
                model.add_mapping(c.name.lower(), c.name, rel, c.line, "sqlmodel", True, test)
                continue
            django = re.search(r"^\s*(?:from\s+django\b|import\s+django\b)", text, re.M)
            if django and c.bases and (rel.endswith("models.py") or "/models/" in rel) and \
                    ("models.Model" in c.header or any(b.endswith("Model") for b in c.bases)):
                meta = re.search(r"^[ \t]+class\s+Meta\b[^\n]*:\n((?:[ \t]+[^\n]*\n?)*)", body, re.M)
                meta_body = meta.group(1) if meta else ""
                if re.search(r"abstract\s*=\s*True", meta_body):
                    continue
                dbt = re.search(r"db_table\s*=\s*[\"']([^\"']+)[\"']", meta_body)
                parts = rel.split("/")
                app = parts[-2] if parts[-1] == "models.py" else (parts[parts.index("models") - 1]
                                                                  if "models" in parts and parts.index("models") else "")
                table = dbt.group(1) if dbt else f"{app}_{c.name.lower()}"
                model.add_mapping(table, c.name, rel, c.line, "django", not dbt, test)
                t = model.table(table)
                for col in re.finditer(r"^\s*(\w+)\s*=\s*models\.(\w+)\(([^)]*)", body, re.M):
                    t.columns.setdefault(col.group(1).lower(), {"type": col.group(2), "path": rel,
                                                                "line": li.line(c.start + col.start())})
                    if col.group(2) in ("ForeignKey", "OneToOneField"):
                        target = re.match(r"\s*[\"']?([\w.]+)", col.group(3))
                        if target:
                            model.pending_refs.append((table, target.group(1).split(".")[-1], rel,
                                                       li.line(c.start + col.start())))
        for m in re.finditer(r"^(\w+)\s*=\s*(?:sa\.|sqlalchemy\.|db\.)?Table\(\s*[\"']([^\"']+)[\"']", text, re.M):
            model.add_definition(m.group(2), rel, li.line(m.start()), "sqlalchemy-core", test)
            model.add_mapping(m.group(2), m.group(1), rel, li.line(m.start()), "sqlalchemy-core", False, test)
        for m in re.finditer(r"op\.create_table\(\s*[\"']([^\"']+)[\"']([\s\S]*?)\n\s*\)", text):
            t = model.add_definition(m.group(1), rel, li.line(m.start()), "alembic", test)
            for col in re.finditer(r"sa\.Column\(\s*[\"']([^\"']+)[\"']\s*,\s*(?:sa\.)?(\w+)", m.group(2)):
                t.columns.setdefault(col.group(1).lower(), {"type": col.group(2), "path": rel,
                                                            "line": li.line(m.start(2) + col.start())})
            for fk in re.finditer(r"ForeignKey(?:Constraint)?\(\s*\[?[\"'](\w+)\.", m.group(2)):
                t.references.add(fk.group(1).lower())
        for m in re.finditer(r"op\.add_column\(\s*[\"']([^\"']+)[\"']\s*,\s*sa\.Column\(\s*[\"']([^\"']+)[\"']\s*,\s*"
                             r"(?:sa\.)?(\w+)", text):
            model.table(m.group(1)).columns.setdefault(m.group(2).lower(), {"type": m.group(3), "path": rel,
                                                                            "line": li.line(m.start())})
    elif lang == "ruby":
        for m in re.finditer(r"create_table\s*\(?\s*[:\"'](\w+)[\"']?([\s\S]*?)\n\s*end\b", text):
            t = model.add_definition(m.group(1), rel, li.line(m.start()), "rails", test)
            for col in re.finditer(r"\bt\.(\w+)\s+[:\"'](\w+)", m.group(2)):
                if col.group(1) in ("index", "timestamps"):
                    continue
                if col.group(1) in ("references", "belongs_to"):
                    t.columns.setdefault(col.group(2) + "_id", {"type": "bigint", "path": rel,
                                                                "line": li.line(m.start(2) + col.start())})
                    t.references.add(plural(col.group(2)))
                else:
                    t.columns.setdefault(col.group(2).lower(), {"type": col.group(1), "path": rel,
                                                                "line": li.line(m.start(2) + col.start())})
        for m in re.finditer(r"add_foreign_key\s+[:\"'](\w+)[\"']?\s*,\s*[:\"'](\w+)", text):
            model.table(m.group(1)).references.add(m.group(2).lower())
        for m in re.finditer(r"add_column\s+[:\"'](\w+)[\"']?\s*,\s*[:\"'](\w+)[\"']?\s*,\s*:(\w+)", text):
            model.table(m.group(1)).columns.setdefault(m.group(2).lower(), {"type": m.group(3), "path": rel,
                                                                            "line": li.line(m.start())})
        for c in cf.classes:
            if any(b in ("ApplicationRecord", "Base") for b in c.bases) or "ActiveRecord::Base" in c.header:
                body = text[c.start:c.end]
                tn = re.search(r"self\.table_name\s*=\s*[\"'](\w+)[\"']", body)
                table = tn.group(1) if tn else plural(snake(c.name))
                model.add_mapping(table, c.name, rel, c.line, "rails", not tn, test)
                for bt in re.finditer(r"belongs_to\s+:(\w+)", body):
                    model.table(table).references.add(plural(bt.group(1)))
    elif lang in ("ts", "js"):
        for c in cf.classes:
            args = dict(c.annotations)
            if "Entity" in args or "Table" in args:
                raw = args.get("Entity") or args.get("Table") or ""
                table = first_string(raw, ("name", "tableName"))
                model.add_mapping(table or snake(c.name), c.name, rel, c.line, "typeorm", not table, test)
                body = cf.blank[c.start:c.end]
                for rm in re.finditer(r"@(?:ManyToOne|OneToOne)\s*\(\s*\(\)\s*=>\s*(\w+)", body):
                    model.pending_refs.append(((table or snake(c.name)).lower(), rm.group(1), rel,
                                               li.line(c.start + rm.start())))
        for m in re.finditer(r"(?:export\s+)?const\s+(\w+)\s*=\s*(?:pg|mysql|sqlite)Table\(\s*[\"'`](\w+)[\"'`]", text):
            model.add_definition(m.group(2), rel, li.line(m.start()), "drizzle", test)
            model.add_mapping(m.group(2), m.group(1), rel, li.line(m.start()), "drizzle", False, test)
        for m in re.finditer(r"\.createTable\(\s*[\"'`](\w+)[\"'`]", text):
            model.add_definition(m.group(1), rel, li.line(m.start()), "knex", test)
        for m in re.finditer(r"\b(?:sequelize|db)\.define\(\s*[\"'`](\w+)[\"'`]([\s\S]{0,4000}?)\)\s*;", text):
            tn = re.search(r"tableName\s*:\s*[\"'`](\w+)[\"'`]", m.group(2))
            model.add_mapping(tn.group(1) if tn else plural(m.group(1)), m.group(1)[:1].upper() + m.group(1)[1:], rel,
                              li.line(m.start()), "sequelize", not tn, test)
        for m in re.finditer(r"\b([A-Z]\w*)\.init\(\s*\{[\s\S]*?\}\s*,\s*\{([\s\S]*?)\}\s*\)", text):
            tn = re.search(r"tableName\s*:\s*[\"'`](\w+)[\"'`]", m.group(2))
            model.add_mapping(tn.group(1) if tn else plural(snake(m.group(1))), m.group(1), rel, li.line(m.start()),
                              "sequelize", not tn, test)
    elif lang == "csharp":
        for c in cf.classes:
            args = dict(c.annotations)
            if "Table" in args:
                table = first_string(args["Table"], ("Name",))
                if table:
                    model.add_mapping(table, c.name, rel, c.line, "efcore", False, test)
        for m in re.finditer(r"Entity<(\w+)>\s*\(\s*\)[^;]*?\.ToTable\(\s*\"(\w+)\"", text):
            model.add_mapping(m.group(2), m.group(1), rel, li.line(m.start()), "efcore", False, test)
        for m in re.finditer(r"IEntityTypeConfiguration<(\w+)>[\s\S]*?\.ToTable\(\s*\"(\w+)\"", text):
            model.add_mapping(m.group(2), m.group(1), rel, li.line(m.start()), "efcore", False, test)
        for m in re.finditer(r"DbSet<(\w+)>\s+(\w+)\s*\{", text):
            if not model.orm_types.get(m.group(1)):
                model.add_mapping(m.group(2), m.group(1), rel, li.line(m.start()), "efcore", True, test)
            model.orm_types.setdefault(m.group(2), list(model.orm_types.get(m.group(1), [m.group(2).lower()])))
    elif lang == "go":
        for m in re.finditer(r"func\s*\(\s*\w*\s*\*?(\w+)\s*\)\s*TableName\(\)\s*string\s*\{\s*return\s*\"(\w+)\"", text):
            model.add_mapping(m.group(2), m.group(1), rel, li.line(m.start()), "gorm", False, test)
    elif lang == "php":
        for m in re.finditer(r"Schema::create\(\s*[\"'](\w+)[\"']", text):
            model.add_definition(m.group(1), rel, li.line(m.start()), "laravel", test)
        for m in re.finditer(r"class\s+(\w+)\s+extends\s+Model\b([\s\S]*?)(?=\nclass\s|\Z)", text):
            tn = re.search(r"\$table\s*=\s*[\"'](\w+)[\"']", m.group(2))
            model.add_mapping(tn.group(1) if tn else plural(snake(m.group(1))), m.group(1), rel, li.line(m.start()),
                              "eloquent", not tn, test)
        for m in re.finditer(r"(?:#\[ORM\\Table|@ORM\\Table)\(\s*name\s*[:=]\s*[\"'](\w+)[\"'][\s\S]*?class\s+(\w+)", text):
            model.add_mapping(m.group(1), m.group(2), rel, li.line(m.start()), "doctrine", False, test)
    elif lang == "elixir":
        for m in re.finditer(r"schema\s+\"(\w+)\"\s+do", text):
            mod = cf.enclosing_class(m.start())
            model.add_mapping(m.group(1), mod.name if mod else m.group(1), rel, li.line(m.start()), "ecto", False, test)
        for m in re.finditer(r"create\s+table\(\s*:(\w+)", text):
            model.add_definition(m.group(1), rel, li.line(m.start()), "ecto", test)


def parse_prisma(model: Model, rel: str, text: str, test: bool):
    li = LineIndex(text)
    models = {}
    for m in re.finditer(r"^model\s+(\w+)\s*\{([\s\S]*?)^\}", text, re.M):
        mm = re.search(r"@@map\(\s*\"([^\"]+)\"", m.group(2))
        table = mm.group(1) if mm else m.group(1)
        models[m.group(1)] = (table, m)
    for name, (table, m) in models.items():
        t = model.add_definition(table, rel, li.line(m.start()), "prisma", test)
        model.add_mapping(table, name, rel, li.line(m.start()), "prisma", False, test)
        model.prisma_accessors[name[:1].lower() + name[1:]] = table.lower()
        for f in re.finditer(r"^\s+(\w+)\s+(\w+)(\[\])?\??([^\n]*)", m.group(2), re.M):
            if f.group(1).startswith("@@"):
                continue
            if f.group(2) in models:
                if "@relation" in f.group(4) and "fields" in f.group(4):
                    t.references.add(models[f.group(2)][0].lower())
                continue
            col = re.search(r"@map\(\s*\"([^\"]+)\"", f.group(4))
            t.columns.setdefault((col.group(1) if col else f.group(1)).lower(),
                                 {"type": f.group(2), "path": rel, "line": li.line(m.start(2) + f.start())})


# ------------------------------------------------------------------------------------------------------------
# access detection
# ------------------------------------------------------------------------------------------------------------

def _gap(verb):
    return verb + r"\s+(?:[^;()]{0,80}?|[^;()]{0,80}?\w+\(\s*[\"'`]?)"


def classify_sql(window: str, name: str, at: int | None = None) -> str:
    """CRUD verb governing the table name; `at` = offset where the name ends in window (default: anywhere)."""
    w, n = window.lower(), re.escape(name.lower())
    tail = r"[\"`\]]?\b" + n + r"\b"
    for op, verb in (("C", r"insert\s+(?:ignore\s+)?into"), ("C", r"copy"), ("U", r"\bupdate(?:\s+only)?"),
                     ("D", r"delete\s+from(?:\s+only)?"), ("U", r"merge\s+into"), ("D", r"truncate(?:\s+table)?"),
                     ("R", r"\b(?:from|join)(?:\s+only)?")):
        rx = re.compile(_gap(verb) + tail)
        if at is None:
            if rx.search(w):
                return op
        elif any(m.end() == at for m in rx.finditer(w)):
            return op
    if re.search(r"on\s+conflict", w) and "insert" in w:
        return "C"
    return "?"


WRITE_CALL = re.compile(r"\.(persist|save|saveAll|saveAndFlush|insert\w*|add|add_all|bulk_create|bulkCreate|"
                        r"createMany|create|upsert)\s*\(")
DELETE_CALL = re.compile(r"\.(remove|delete\w*|destroy\w*|purge\w*)\s*\(")
UPDATE_CALL = re.compile(r"\.(merge|update\w*)\s*\(")
READ_CALL = re.compile(r"\.(find\w*|get\w*|query|where|filter|select\w*|all|first|last|count|exists\w*|fetch\w*|"
                       r"load\w*|list\w*|search\w*|read\w*|stream\w*|aggregate|group_by|order_by|values)\s*\(")
MUTATORS = re.compile(r"^(set|mark|update|change|apply|approve|reject|submit|publish|retire|suspend|resume|revoke|"
                      r"confirm|cancel|close|open|erase|expire|touch|record|add|remove|delete|increment|decrement|enable|"
                      r"disable|archive|restore|assign|clear|reset|rename|move|lock|unlock|grant|register|shred|release|"
                      r"place|edit|replace|append|put|withdraw|accept|decline|fail|complete|finish|start|stop|end|attach|"
                      r"detach|link|unlink|merge|save|persist|refresh|bump|upsert|patch|modify|transition|advance|"
                      r"promote|demote|deprecate|supersede|reopen|schedule|claim|consume|charge|pay|refund|ship)")


def classify_orm_code(line: str, name: str, static_factories: bool = True) -> str:
    n = re.escape(name)
    if re.search(r"^\s*(import|using|require|from\s+\S+\s+import|package)\b", line):
        return ""
    if re.search(r"\bnew\s+" + n + r"\s*[({<]", line) or \
            re.search(r"\b" + n + r"\s*\.\s*(create|of|builder|build|new|register|open|record|draft|init|objects\.create|"
                                  r"objects\.bulk_create|insert\w*)\w*\s*\(", line):
        return "C"
    if re.search(r"\b" + n + r"\s*\.\s*objects\s*\.\s*(filter|get|all|exclude|values|count|exists|first|last)", line):
        return "R"
    if re.search(r"\b" + n + r"\s*\.\s*(where|find\w*|all|first|last|count|exists|any|select|pluck|findAll|findOne|"
                             r"findByPk|query|single\w*|tolist\w*|firstordefault\w*)\b", line, re.I):
        return "R"
    if re.search(r"\b" + n + r"\s*\.\s*(update\w*|upsert)\b", line, re.I):
        return "U"
    if re.search(r"\b" + n + r"\s*\.\s*(destroy\w*|delete\w*|remove\w*)\b", line, re.I):
        return "D"
    if re.search(r"\b" + n + r"\s*\.\s*(add\w*|create\w*|insert\w*)\b", line, re.I):
        return "C"
    if static_factories and re.search(r"\b" + n + r"\s*\.\s*[a-z]\w*\s*\(", line):
        return "S"  # static factory with a domain name (Consent.grant(...)): a create if the method persists
    if DELETE_CALL.search(line):
        return "D"
    if WRITE_CALL.search(line):
        return "C"
    if UPDATE_CALL.search(line):
        return "U"
    if re.search(r"\b" + n + r"\s*(\.\s*class|::class)\b", line) or READ_CALL.search(line):
        return "R"
    return ""


def classify_orm_string(window: str, name: str) -> str:
    n = re.escape(name)
    if re.search(r"\binsert\s+into\s+" + n + r"\b", window, re.I):
        return "C"
    if re.search(r"\bupdate\s+" + n + r"\b", window, re.I):
        return "U"
    if re.search(r"\bdelete\s+(?:from\s+)?" + n + r"\b", window, re.I):
        return "D"
    if re.search(r"\b(?:from|join(?:\s+fetch)?)\s+" + n + r"\b", window, re.I):
        return "R"
    return ""


def repo_op(method: str) -> set:
    m = method.lower()
    if m.startswith(("save", "persist", "insert", "create", "add")):
        return {"C", "U"} if m.startswith("save") else {"C"}
    if m.startswith(("delete", "remove", "destroy")):
        return {"D"}
    if m.startswith(("update", "modify", "merge", "upsert")):
        return {"U"}
    if m.startswith(("find", "get", "exists", "count", "read", "query", "search", "stream", "list", "load")):
        return {"R"}
    return set()


class Analyzer:
    def __init__(self, root: str, excludes: set, depth: int, include_test_callers: bool, prefix: str | None,
                 quiet: bool):
        self.root = os.path.abspath(root)
        self.excludes = excludes
        self.depth = depth
        self.include_test_callers = include_test_callers
        self.prefix = prefix
        self.quiet = quiet
        self.model = Model()
        self.files: list[CodeFile] = []
        self.docs: list = []
        self.sql_usage: list = []
        self.accesses: dict = defaultdict(list)  # table -> [access]
        self.string_mentions: dict = defaultdict(list)
        self.comment_mentions: dict = defaultdict(list)
        self.type_index: dict = defaultdict(list)  # simple type name -> [(cf, cls)]
        self.word_index: dict = defaultdict(list)
        self._callers_cache: dict = {}
        self.synthetic: dict = {}
        self.warnings: list = []

    def log(self, msg):
        if not self.quiet:
            print(msg, file=sys.stderr)

    # ---- collection ----
    def walk(self):
        out = []
        for dirpath, dirnames, filenames in os.walk(self.root):
            dirnames[:] = sorted(d for d in dirnames if d not in self.excludes and not d.startswith(".git"))
            for fn in sorted(filenames):
                ext = os.path.splitext(fn)[1].lower()
                lang = EXT_LANG.get(ext)
                if not lang:
                    continue
                full = os.path.join(dirpath, fn)
                try:
                    if os.path.getsize(full) > MAX_FILE_BYTES:
                        continue
                except OSError:
                    continue
                rel = os.path.relpath(full, self.root).replace(os.sep, "/")
                out.append((rel, lang, full))
        return out

    def load(self):
        entries = self.walk()

        def migration_key(item):
            rel = item[0]
            m = re.search(r"(?:^|/)[VvRrUu](\d+(?:[._]\d+)*)__", rel)
            if m:
                return (0, [int(x) for x in re.split(r"[._]", m.group(1))], rel)
            return (1, [], rel)

        for rel, lang, full in sorted(entries, key=migration_key):
            try:
                with open(full, encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            test = is_test_path(rel)
            if lang == "doc":
                self.docs.append((rel, text))
            elif lang == "sql":
                if re.search(r"\b(CREATE|ALTER|DROP)\s+(TABLE|VIEW|MATERIALIZED\s+VIEW|INDEX)\b", text, re.I):
                    parse_sql(self.model, rel, text, test)
                else:
                    self.sql_usage.append(CodeFile(rel, "sql", text, test))
            elif lang == "xml":
                if "<createTable" in text or "<changeSet" in text:
                    parse_liquibase_xml(self.model, rel, text, test)
                elif re.search(r"<mapper\b|<sqlMap\b|<hibernate-mapping\b|<entity-mappings\b", text):
                    self.sql_usage.append(CodeFile(rel, "xml", text, test))
                    for m in re.finditer(r"<(?:class|entity)\b[^>]*\bname=\"([\w.]+)\"[^>]*\btable=\"(\w+)\"", text):
                        self.model.add_mapping(m.group(2), m.group(1).split(".")[-1], rel, LineIndex(text).line(m.start()),
                                               "hibernate-xml", False, test)
            elif lang == "yaml":
                if "createTable:" in text and ("databaseChangeLog" in text or "changeSet" in text):
                    parse_liquibase_yaml(self.model, rel, text, test)
            elif lang == "prisma":
                parse_prisma(self.model, rel, text, test)
            elif lang in CODE:
                self.files.append(CodeFile(rel, lang, text, test))
        self.log(f"schema_evidence: {len(self.files)} code files, {len(self.sql_usage)} SQL/XML usage files, "
                 f"{len(self.docs)} documents")
        django_urls = {}  # view name -> path, from every urls.py (views live in other modules)
        for cf in self.files:
            if cf.lang == "python" and cf.rel.endswith("urls.py"):
                for m in re.finditer(r"\b(?:re_)?path\(\s*r?[\"']([^\"']*)[\"']\s*,\s*(?:[\w.]+\.)?(\w+)", cf.text):
                    django_urls.setdefault(m.group(2), m.group(1))
        for cf in self.files:
            detect_entries(cf)
            if cf.lang == "python":
                for meth in cf.methods:
                    if not meth.entry and meth.name in django_urls:
                        meth.entry = {"kind": "http", "route": f"ANY /{django_urls[meth.name]}", "why": "urls.py"}
            for c in cf.classes:
                self.type_index[c.name].append((cf, c))
            for w in cf.words:
                self.word_index[w].append(cf)
        for cf in self.files:
            parse_mappings(self.model, cf)
        for owner, child, rel, line in self.model.associations:
            for table in list(self.model.orm_types.get(child, [])):
                t = self.model.table(table)
                entry = {"type": owner, "path": rel, "line": line, "origin": "jpa-association", "inferred": False,
                         "test": is_test_path(rel)}
                if entry not in t.mappings:
                    t.mappings.append(entry)
                if table not in self.model.orm_types[owner]:
                    self.model.orm_types[owner].append(table)
        for table, target, rel, line in self.model.pending_refs:
            for tt in self.model.orm_types.get(target, []):
                if tt != table:
                    self.model.table(table).references.add(tt)
        for t in self.model.tables.values():
            t.view_sources &= set(self.model.tables)

    # ---- access ----
    def _synthetic(self, cf, owner, name, line):
        key = (cf.rel, owner.qual, name)
        meth = self.synthetic.get(key)
        if meth is None:
            meth = Meth(name, owner.qual, -(len(self.synthetic) + 1), -1, -1, line, [], "", [])
            self.synthetic[key] = meth
        return meth

    def _declared_method_at(self, cf, off):
        """Bodyless interface method declared after `off` (Spring Data @Query, MyBatis @Select, ...)."""
        owner = cf.enclosing_class(off)
        if owner is None or "interface" not in owner.header.split(" ")[:4]:
            return None
        m = re.compile(r"([A-Za-z_]\w*)\s*\([^;{}]*\)\s*(?:throws[^;{}]*)?;").search(cf.blank, off, owner.end)
        return self._synthetic(cf, owner, m.group(1), cf.li.line(m.start(1))) if m else None

    def _mapper_method(self, cf, off):
        """MyBatis XML: the <select|insert|update|delete id=...> around `off` maps to namespace#id."""
        ns = re.search(r"<mapper\b[^>]*\bnamespace=\"([\w.$]+)\"", cf.text)
        stmt = None
        for m in re.finditer(r"<(select|insert|update|delete)\b[^>]*\bid=\"(\w+)\"", cf.text):
            if m.start() > off:
                break
            stmt = m
        if not ns or not stmt:
            return None
        tname = ns.group(1).split(".")[-1].split("$")[-1]
        for dcf, c in self.type_index.get(tname, []):
            return dcf, self._synthetic(dcf, c, stmt.group(2), c.line)
        return None

    def _record(self, table, cf, off, op, via, inferred=False):
        meth = cf.enclosing_method(off) if cf.lang in CODE else None
        line = cf.li.line(off)
        if cf.lang == "xml":
            mapped = self._mapper_method(cf, off)
            if mapped:
                dcf, meth = mapped
                acc = {"table": table, "cf": dcf, "off": off, "line": meth.line, "op": op, "via": via + " (mapper xml)",
                       "inferred": inferred, "snippet": f"{cf.rel}:{line}", "methods": [meth]}
                self.accesses[table].append(acc)
                return
        snippet = cf.text[cf.li.starts[line - 1]:cf.li.starts[line] - 1 if line < len(cf.li.starts) else len(cf.text)]
        targets = [meth] if meth else []
        if meth is None and cf.lang in JVM:
            decl = self._declared_method_at(cf, off)
            targets = [decl] if decl else []
        if meth is None and not targets and cf.lang in CODE:
            targets = self._field_users(cf, off)
        elif meth is not None and meth.owner and meth.name == meth.owner.split(".")[-1]:
            targets = self._field_users(cf, off) or targets  # SQL assembled in a constructor, run elsewhere
        acc = {"table": table, "cf": cf, "off": off, "line": line, "op": op, "via": via, "inferred": inferred,
               "snippet": " ".join(snippet.split())[:160], "methods": targets}
        self.accesses[table].append(acc)

    def _field_users(self, cf, off):
        """A SQL string held in a field/constant: the methods that use the field are the accessors."""
        start = max(cf.blank.rfind(";", 0, off), cf.blank.rfind("{", 0, off), cf.blank.rfind("}", 0, off)) + 1
        m = re.search(r"([A-Za-z_]\w*)\s*[:=][^=]", cf.blank[start:off])
        if not m:
            m = re.search(r"([A-Za-z_]\w*)\s*$", cf.blank[start:off].split("=")[0].strip() + " ")
        if not m:
            return []
        field_name = m.group(1)
        users = []
        for u in re.finditer(r"\b" + re.escape(field_name) + r"\b", cf.blank):
            meth = cf.enclosing_method(u.start())
            if meth and meth not in users and not (meth.start <= off <= meth.end):
                users.append(meth)
        return users

    def scan_access(self):
        names = [n for n in self.model.tables]
        if not names:
            return
        table_re = re.compile(r"(?<![\w$])(" + "|".join(map(re.escape, sorted(names, key=len, reverse=True))) +
                              r")(?![\w$])", re.I)
        for cf in self.files + self.sql_usage:
            text = cf.text
            for m in table_re.finditer(text):
                name = m.group(1).lower()
                kind = cf.mask[m.start()] if m.start() < len(cf.mask) else 1
                if kind == 2:
                    if cf.lang in CODE and len(self.comment_mentions[name]) < 200:
                        meth = cf.enclosing_method(m.start())
                        self.comment_mentions[name].append((cf.rel, cf.li.line(m.start()),
                                                            meth.display(cf.rel) if meth else None))
                    continue
                if kind == 0 and cf.lang in CODE:
                    continue  # an identifier that happens to equal a table name
                line = cf.li.line(m.start())
                lo = cf.li.starts[max(0, line - 3)]
                window = text[lo:m.end() + 40]
                op = classify_sql(window, name, m.end() - lo)
                if op == "?":
                    lead = text[cf.li.starts[line - 1]:m.start()]
                    if re.search(r"@[\w.]+\s*\(|<[\w:-]+\b|^\s*[\[(]", lead):
                        continue  # mapping annotation or markup attribute: already reported as a mapping
                    if "_" in name or len(name) > 10:
                        self.string_mentions[name].append((cf, m.start()))
                    continue
                self._record(name, cf, m.start(), op, "sql")
        # ORM symbols
        type_tables = {t: tabs for t, tabs in self.model.orm_types.items() if tabs}
        repo_tables = {r: type_tables.get(e, []) for r, e in self.model.repos.items() if type_tables.get(e)}
        decl_files = defaultdict(set)
        for tname in type_tables:
            for cf, _ in self.type_index.get(tname, []):
                decl_files[tname].add(cf.rel)
        for r in repo_tables:
            for cf, _ in self.type_index.get(r, []):
                decl_files[r].add(cf.rel)
        if type_tables:
            sym_re = re.compile(r"(?<![\w$])(" + "|".join(map(re.escape, sorted(type_tables, key=len, reverse=True)))
                                + r")(?![\w$])")
            for cf in self.files:
                for m in sym_re.finditer(cf.text):
                    tname = m.group(1)
                    if cf.rel in decl_files[tname]:
                        continue
                    kind = cf.mask[m.start()]
                    if kind == 2:
                        continue
                    if kind == 0 and not self.visible(cf, tname):
                        continue  # query strings (JPQL/HQL) name entities globally, no import needed
                    line = cf.li.line(m.start())
                    if kind == 1:
                        lo = cf.li.starts[max(0, line - 3)]
                        op = classify_orm_string(cf.text[lo:m.end() + 20], tname)
                    else:
                        lstart = cf.li.starts[line - 1]
                        lend = cf.li.starts[line] - 1 if line < len(cf.li.starts) else len(cf.text)
                        op = classify_orm_code(cf.text[lstart:lend], tname)
                    if op == "S":
                        meth = cf.enclosing_method(m.start())
                        body = cf.blank[meth.body:meth.end] if meth else ""
                        op = "C" if re.search(r"(?:\.|::)(persist|save\w*|add|insert\w*|create|merge|writeNew|write)\b",
                                              body) else classify_orm_code(cf.text[lstart:lend], tname, False)
                    if not op:
                        continue
                    for table in type_tables[tname]:
                        self._record(table, cf, m.start(), op, "orm:" + tname)
        # repository calls (Spring Data and alike)
        for rname, tables in repo_tables.items():
            for cf in self.word_index.get(rname, []):
                if cf.rel in decl_files[rname] or not self.visible(cf, rname):
                    continue
                vs = cf.vars_of(rname)
                if not vs:
                    continue
                for m in re.finditer(r"\b(?:" + "|".join(map(re.escape, vs)) + r")\s*\.\s*(\w+)\s*\(", cf.blank):
                    ops = repo_op(m.group(1))
                    for table in tables:
                        for op in sorted(ops):
                            self._record(table, cf, m.start(), op, "repo:" + rname)
        # Prisma client
        if self.model.prisma_accessors:
            prx = re.compile(r"\.(" + "|".join(map(re.escape, self.model.prisma_accessors)) + r")\s*\.\s*(findMany|"
                             r"findUnique\w*|findFirst\w*|create\w*|update\w*|upsert|delete\w*|count|aggregate|"
                             r"groupBy)\s*\(")
            for cf in self.files:
                for m in prx.finditer(cf.blank):
                    op = {"f": "R", "c": "C" if m.group(2).startswith("create") else "R", "u": "U",
                          "d": "D", "a": "R", "g": "R"}[m.group(2)[0]]
                    self._record(self.model.prisma_accessors[m.group(1)], cf, m.start(), op, "prisma")
        self._infer_dirty_updates(type_tables)

    def _infer_dirty_updates(self, type_tables):
        """JPA-style updates: an entity loaded in a method and then mutated through its own methods."""
        seen = set()
        for table, accs in list(self.accesses.items()):
            for a in list(accs):
                if not a["via"].startswith("orm:"):
                    continue
                tname = a["via"][4:]
                for meth in a["methods"]:
                    key = (a["cf"].rel, meth.start, table, tname)
                    if key in seen:
                        continue
                    seen.add(key)
                    body = a["cf"].blank[meth.body:meth.end]
                    vars_ = set(re.findall(r"\b" + re.escape(tname) + r"\s+([a-z]\w*)\s*[=:;,)]", body))
                    vars_ |= set(re.findall(r"\b(?:var|val|let|const)\s+([a-z]\w*)\s*=\s*[^;\n]*\b" + re.escape(tname)
                                            + r"\b", body))
                    vars_ -= KEYWORDS
                    for v in vars_:
                        for mm in re.finditer(r"\b" + re.escape(v) + r"\s*\.\s*([A-Za-z_]\w*)\s*\(", body):
                            if MUTATORS.match(mm.group(1)):
                                self._record(table, a["cf"], meth.body + mm.start(), "U", "orm:" + tname, True)
                                break
                        for mm in re.finditer(r"\.(persist|remove|merge|save|delete|destroy)\s*\(\s*" + re.escape(v)
                                              + r"\b", body):
                            op = {"persist": "C", "save": "C", "remove": "D", "delete": "D", "destroy": "D",
                                  "merge": "U"}[mm.group(1)]
                            self._record(table, a["cf"], meth.body + mm.start(), op, "orm:" + tname, True)

    def visible(self, cf: CodeFile, tname: str) -> bool:
        decls = self.type_index.get(tname)
        if not decls:
            return True
        if tname not in cf.words:
            return False
        for dcf, c in decls:
            if dcf is cf:
                return True
            outer = c.qual.split(".")[0]
            if cf.lang in JVM and dcf.lang in JVM:
                fq = (dcf.package + "." + outer) if dcf.package else outer
                if cf.package == dcf.package or fq in cf.fq_imports or dcf.package in cf.wild or \
                        any(i.startswith(fq + ".") for i in cf.fq_imports):
                    return True
                if (tname in cf.imports or outer in cf.imports) and not any(
                        i.endswith("." + outer) or i.endswith("." + tname) for i in cf.fq_imports):
                    return True  # Kotlin aliases and the like: imported by a form we do not parse
                if dcf.package and (dcf.package + "." + outer) in cf.text:
                    return True
            elif cf.lang == "csharp" and dcf.lang == "csharp":
                if cf.package == dcf.package or dcf.package in cf.wild:
                    return True
            elif cf.lang in ("js", "ts") and dcf.lang in ("js", "ts"):
                if tname in cf.imports or outer in cf.imports:
                    return True
            elif cf.lang == "python" and dcf.lang == "python":
                mod = os.path.splitext(os.path.basename(dcf.rel))[0]
                if tname in cf.imports or mod in cf.imports:
                    return True
            else:
                return True
        return False

    # ---- call graph ----
    def callers(self, cf: CodeFile, meth: Meth):
        key = (cf.rel, meth.start)
        if key in self._callers_cache:
            return self._callers_cache[key]
        edges = []
        targets = []
        if meth.entry and meth.entry["kind"] == "wiring":
            rt = re.search(r"([A-Za-z_]\w*)(?:<[^()]*>)?\s+" + re.escape(meth.name) + r"\s*\(", meth.header)
            if rt:
                for dcf, c in self.type_index.get(rt.group(1), []):
                    for mn in sorted(c.decl_methods):
                        targets.append((rt.group(1), mn, dcf))
        else:
            owner = cf.cls(meth.owner) if meth.owner else None
            names = [owner.name] if owner else []
            if owner:
                names += [b for b in owner.bases if b in self.type_index and b != owner.name]
            for n in names:
                targets.append((n, meth.name, cf))
            if not owner and cf.lang in ("python", "js", "ts", "go", "ruby", "elixir"):
                targets.append((None, meth.name, cf))
        seen_sites = set()
        for tname, mname, decl in targets:
            if tname is None:
                mod = os.path.splitext(os.path.basename(cf.rel))[0]
                candidates = [f for f in self.word_index.get(mname, []) if f is cf or mod in f.words]
            else:
                candidates = list(self.word_index.get(tname, []))
                if cf not in candidates:
                    candidates.append(cf)
            for other in candidates:
                if tname and other is not cf and not self.visible(other, tname):
                    continue
                vs = other.vars_of(tname) if tname else set()
                for off, kind in other.calls.get(mname, []):
                    if off in other.decl_offsets or (other.rel, off) in seen_sites:
                        continue
                    strength = self._qualify(other, off, kind, tname, vs, other is cf, cf, meth)
                    if not strength:
                        continue
                    caller = other.enclosing_method(off)
                    if caller is meth and other is cf:
                        continue
                    seen_sites.add((other.rel, off))
                    edges.append((other, caller, strength, other.li.line(off)))
        self._callers_cache[key] = edges
        return edges

    _QUAL_RE = re.compile(r"((?:[A-Za-z_]\w*(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*(?:\?\.|\.)\s*)*[A-Za-z_]\w*"
                          r"(?:\s*\((?:[^()]|\([^()]*\))*\))?)\s*(?:\?\.|\.|::|->)\s*$")

    def _qualify(self, other, off, kind, tname, vs, same_file, decl_cf, meth):
        """Is the call at `off` a call of meth? strong = typed receiver or same class; weak = plausible."""
        ctx = other.blank[max(0, off - 240):off]
        q = self._QUAL_RE.search(ctx)
        if q:
            names = [re.sub(r"\s*\(.*", "", x).strip() for x in re.split(r"\??\.", q.group(1)) if x.strip()]
            if names and names[0] in ("this", "self", "super", "cls") and len(names) > 1:
                names = names[1:]
            qual = names[0] if names else ""
            if qual in ("this", "self", "super", "cls"):
                return "strong" if same_file else None
            if tname and qual == tname:
                return "strong"
            if qual in vs:
                return "strong"
            if same_file or tname is None:
                return None
            return "weak" if not vs else None
        if re.search(r"(?:^|[^\w.$:>])\s*$", ctx):
            if same_file:
                owner = other.enclosing_class(off)
                if owner is None or meth.owner is None or owner.qual.split(".")[0] == meth.owner.split(".")[0]:
                    return "strong"
                return None
            if tname is None:
                return "weak"
        return None

    def reach(self, cf: CodeFile, meth: Meth, max_nodes=600):
        """Breadth-first walk from an accessor method up to entry points."""
        start = (cf, meth)
        parents = {(cf.rel, meth.start): None}
        queue = deque([(cf, meth, 0, "strong")])
        entries, tests, frontier = [], set(), []
        visited = 0
        while queue and visited < max_nodes:
            ccf, cm, depth, strength = queue.popleft()
            visited += 1
            if cm is not None and cm.entry and cm.entry["kind"] in TERMINAL:
                entries.append((ccf, cm, depth, strength, self._path(parents, (ccf.rel, cm.start)), cm.entry))
                continue
            if cm is None or depth >= self.depth:
                if cm is not None and depth >= self.depth:
                    frontier.append((ccf.rel, cm.display(ccf.rel)))
                continue
            if cm.entry and cm.entry["kind"] == "wiring" and depth > 0:
                # a lambda or method reference handed to a bean factory runs when the bean's own entry points run
                bean_entries = self._bean_entries(cm)
                via = [cm.display(ccf.rel)] + self._path(parents, (ccf.rel, cm.start))
                for bcf, bm in bean_entries:
                    entries.append((bcf, bm, depth + 1, "weak", via[:] , bm.entry))
                if not bean_entries:
                    # code in the factory body runs while the application context starts
                    entries.append((ccf, cm, depth, "weak", self._path(parents, (ccf.rel, cm.start)),
                                    {"kind": "startup", "route": None, "why": "runs while the @Bean is created"}))
                continue
            edges = self.callers(ccf, cm)
            live = [e for e in edges if self.include_test_callers or not e[0].test]
            if not live and (depth > 0 or cm.owner):
                # nothing in the repository calls it: a framework does, through an interface it implements
                ext = self._external_bases(ccf, cm)
                if ext:
                    tests.update(o.rel for o, *_ in edges if o.test)
                    cm_entry = {"kind": "framework", "route": f"via {', '.join(ext[:3])}",
                                "why": "implements an interface from outside the repository"}
                    entries.append((ccf, cm, depth, "weak", self._path(parents, (ccf.rel, cm.start)), cm_entry))
                    continue
            for other, caller, s, line in edges:
                if other.test and not self.include_test_callers:
                    tests.add(other.rel)
                    continue
                if caller is None:
                    continue
                k = (other.rel, caller.start)
                if k in parents:
                    continue
                parents[k] = ((ccf.rel, cm.start), (ccf, cm), s)
                queue.append((other, caller, depth + 1, "weak" if "weak" in (s, strength) else "strong"))
        return entries, tests, frontier, start

    def _bean_entries(self, meth):
        """Entry-point methods of the type a @Bean factory method returns."""
        rt = re.search(r"([A-Za-z_]\w*)(?:<[^()]*>)?\s+" + re.escape(meth.name) + r"\s*\(", meth.header)
        out = []
        for dcf, c in self.type_index.get(rt.group(1), []) if rt else []:
            for m in dcf.methods:
                if m.owner == c.qual and m.entry and m.entry["kind"] in TERMINAL:
                    out.append((dcf, m))
        return out

    def _external_bases(self, cf, meth):
        owner = cf.cls(meth.owner) if meth.owner else None
        if owner is None:
            return []
        return [b for b in owner.bases if b not in self.type_index and b not in ("Object", "Record", "Enum")]

    @staticmethod
    def _path(parents, key):
        path = []
        cur = parents.get(key)
        while cur is not None:
            pkey, (pcf, pm), _ = cur
            path.append(pm.display(pcf.rel))
            cur = parents.get(pkey)
        return path

    # ---- docs ----
    def scan_docs(self):
        out = defaultdict(list)
        names = list(self.model.tables)
        if not names:
            return out
        plain = [n for n in names if "_" in n or len(n) > 10]
        ticked = [n for n in names if n not in plain]
        rx_plain = re.compile(r"(?<![\w$])(" + "|".join(map(re.escape, sorted(plain, key=len, reverse=True))) +
                              r")(?![\w$])") if plain else None
        rx_tick = re.compile(r"`(" + "|".join(map(re.escape, ticked)) + r")`", re.I) if ticked else None
        for rel, text in self.docs:
            lines = text.split("\n")
            heading = ""
            para_start = 0
            for i, line in enumerate(lines):
                hm = re.match(r"^(#{1,6}|=+)\s+(.*)", line)
                if hm:
                    heading = hm.group(2).strip()
                if not line.strip():
                    para_start = i + 1
                hits = set()
                for rx in (rx_plain, rx_tick):
                    if rx:
                        hits.update(h.lower() for h in rx.findall(line))
                if not hits:
                    continue
                para_end = i
                while para_end + 1 < len(lines) and lines[para_end + 1].strip():
                    para_end += 1
                ids = req_ids("\n".join(lines[para_start:para_end + 1]) if para_end - para_start < 40 else line)
                for h in hits:
                    out[h].append({"path": rel, "line": i + 1, "heading": heading[:100], "ids": ids[:12]})
        return out

    # ---- stale references ----
    def stale_references(self):
        tables = [n for n, t in self.model.tables.items() if t.definitions or t.mappings]
        if len(tables) < 3:
            return self.prefix, {}
        prefix = self.prefix
        if prefix is None:
            counts = defaultdict(int)
            for n in tables:
                if "_" in n:
                    counts[n.split("_")[0] + "_"] += 1
            if counts:
                best, c = max(counts.items(), key=lambda kv: kv[1])
                if c >= 0.6 * len(tables):
                    prefix = best
        if not prefix:
            return None, {}
        known = set(self.model.tables) | self.model.functions | self.model.index_names | self.model.sequences | \
            self.model.constraint_names | self.model.partition_names
        for t in self.model.tables.values():
            known.update(tr["name"].lower() for tr in t.triggers)
            known.update(t.renamed_from)
        rx = re.compile(r"(?<![\w$])" + re.escape(prefix) + r"[a-z0-9_]+\b")
        found = defaultdict(list)
        sources = [(cf.rel, cf.text) for cf in self.files if not cf.test] + self.docs
        # database roles and users share the prefix but are not tables
        role_rx = re.compile(r"\b(?:ROLE|USER|TO|OWNER\s+TO|GRANTED\s+BY)\s+(" + re.escape(prefix) + r"\w+)", re.I)
        for _, text in sources + [(cf.rel, cf.text) for cf in self.sql_usage]:
            known.update(m.group(1).lower() for m in role_rx.finditer(text))
        for rel, text in sources:
            li = None
            for m in rx.finditer(text):
                name = m.group().lower()
                base = re.sub(r"_(p\d{6}|pdefault|seq|pkey|idx|fkey|key)$", "", name)
                if name in known or base in known or BOOKKEEPING_RE.search(name):
                    continue
                # fragments (dai_ensure_, name built by concatenation) and generated partition names (dai_x_p...)
                if name.endswith("_") or re.search(r"_p\d*$", name):
                    continue
                # opaque tokens (API keys, hashes) that merely start with the prefix
                if re.search(r"[a-z0-9]{20,}", name[len(prefix):]) and re.search(r"\d", name):
                    continue
                li = li or LineIndex(text)
                if len(found[name]) < 5:
                    found[name].append(f"{rel}:{li.line(m.start())}")
        return prefix, dict(found)

    # ---- assemble ----
    def run(self):
        self.load()
        self.scan_access()
        docs = self.scan_docs()
        prefix, stale = self.stale_references()
        tables_out = {}
        ddl_count = sum(1 for t in self.model.tables.values() if t.definitions)
        mapped_count = sum(1 for t in self.model.tables.values() if t.mappings)
        seeds = defaultdict(list)
        for s in self.model.seeds:
            seeds[s["table"]].append(s)
        for name in sorted(self.model.tables):
            t = self.model.tables[name]
            if not t.definitions and not t.mappings:
                continue
            accs = self.accesses.get(name, [])
            by_method = {}
            for a in accs:
                targets = a["methods"] or [None]
                for meth in targets:
                    key = (a["cf"].rel, meth.start if meth else -1)
                    entry = by_method.setdefault(key, {
                        "method": meth.display(a["cf"].rel) if meth else f"{a['cf'].rel} (file level)",
                        "path": a["cf"].rel, "line": meth.line if meth else a["line"], "ops": set(),
                        "inferred_ops": set(), "sites": [], "test": a["cf"].test, "ids": meth.ids if meth else [],
                        "_cf": a["cf"], "_m": meth})
                    (entry["inferred_ops"] if a["inferred"] else entry["ops"]).add(a["op"])
                    if len(entry["sites"]) < 6:
                        entry["sites"].append({"line": a["line"], "op": a["op"], "via": a["via"],
                                               "snippet": a["snippet"]})
            accessors, entries, tests, frontier = [], {}, set(), set()
            for key, e in sorted(by_method.items(), key=lambda kv: (kv[1]["test"], kv[0])):
                cf, meth = e.pop("_cf"), e.pop("_m")
                e["ops"] = "".join(o for o in "CRUD" if o in e["ops"])
                e["inferred_ops"] = "".join(o for o in "CRUD" if o in e["inferred_ops"] and o not in e["ops"])
                if e["test"]:
                    tests.add(e["path"])
                    continue
                accessors.append(e)
                if meth is None:
                    continue
                found, t_tests, t_frontier, _ = self.reach(cf, meth)
                tests |= t_tests
                frontier |= {f"{p} ({d})" for p, d in t_frontier}
                for ecf, em, depth, strength, path, ent in found:
                    k = (ecf.rel, em.start)
                    rec = entries.get(k)
                    if rec is None or depth < rec["depth"]:
                        entries[k] = {"entry": em.display(ecf.rel), "kind": ent["kind"],
                                      "route": ent.get("route"), "why": ent.get("why"), "path": ecf.rel,
                                      "line": em.line, "depth": depth, "strength": strength,
                                      "via": path, "reaches": e["method"], "ids": em.ids}
            ops_all = set("".join(a["ops"] + a["inferred_ops"] for a in accessors))
            seed_ops = {s["op"] for s in seeds.get(name, [])}
            flags = []
            defs = t.definitions
            if defs and all(d["test"] for d in defs) or (not defs and t.mappings and all(m["test"] for m in t.mappings)):
                flags.append("test-only")
            if t.dropped:
                flags.append("dropped")
            if t.kind == "table" and not accessors:
                flags.append("seed-only" if seed_ops else "no-code-access")
            elif t.kind == "table":
                if not ops_all & {"C", "U"} and not seed_ops & {"C", "U"}:
                    flags.append("never-written-by-code")
                if "R" not in ops_all:
                    flags.append("never-read-by-code")
                if not entries:
                    flags.append("no-entry-point-found")
            if t.mappings and not t.definitions and ddl_count:
                flags.append("mapped-without-ddl")
            if t.definitions and not t.mappings and mapped_count >= 0.5 * max(1, ddl_count) and not accs:
                flags.append("ddl-without-mapping")
            referenced_by = sorted(n for n, o in self.model.tables.items() if name in o.references and n != name)
            tables_out[name] = {
                "kind": t.kind, "schema": t.schema, "definitions": t.definitions, "comment": t.comment,
                "columns": [{"name": c, **v, "comment": t.column_comments.get(c)} for c, v in t.columns.items()],
                "references": sorted(r for r in t.references if r != name), "referenced_by": referenced_by,
                "view_sources": sorted(t.view_sources), "partitioned_by": t.partitioned_by,
                "partitions": t.partitions, "indexes": t.indexes, "unique_indexes": t.unique_indexes,
                "triggers": t.triggers, "dropped": t.dropped, "renamed_from": t.renamed_from,
                "mappings": t.mappings, "seeds": seeds.get(name, []), "accessors": accessors,
                "entry_points": sorted(entries.values(), key=lambda x: (x["kind"], x["depth"], x["entry"])),
                "unresolved_callers": sorted(frontier)[:20], "tests": sorted(tests),
                "comment_mentions": [{"path": p, "line": ln, "method": mm} for p, ln, mm in
                                     self.comment_mentions.get(name, [])[:20]],
                "string_mentions": [{"path": cf.rel, "line": cf.li.line(off)} for cf, off in
                                    self.string_mentions.get(name, [])[:20]],
                "docs": docs.get(name, [])[:60], "flags": flags,
            }
        groups_file = defaultdict(list)
        for name, t in tables_out.items():
            for d in t["definitions"][:1]:
                groups_file[d["path"]].append(name)
        components = self._components(tables_out)
        return {
            "tool": f"schema_evidence.py {VERSION}", "root": self.root,
            "generated_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
            "stats": {"code_files": len(self.files), "usage_files": len(self.sql_usage), "documents": len(self.docs),
                      "tables": sum(1 for t in tables_out.values() if t["kind"] == "table"),
                      "views": sum(1 for t in tables_out.values() if t["kind"] != "table"),
                      "access_sites": sum(len(v) for v in self.accesses.values()),
                      "entry_points": len({(e["path"], e["line"]) for t in tables_out.values()
                                           for e in t["entry_points"]})},
            "tables": tables_out,
            "groups": {"by_definition_file": dict(sorted(groups_file.items())), "by_foreign_keys": components},
            "table_prefix": prefix, "stale_references": stale,
            "functions": sorted(self.model.functions), "warnings": self.warnings,
        }

    @staticmethod
    def _components(tables_out):
        adj = defaultdict(set)
        for n, t in tables_out.items():
            if "test-only" in t["flags"]:
                continue
            adj[n]
            for r in t["references"] + t["view_sources"]:
                if r in tables_out:
                    adj[n].add(r)
                    adj[r].add(n)
        seen, comps = set(), []
        for n in sorted(adj):
            if n in seen:
                continue
            comp, stack = [], [n]
            while stack:
                x = stack.pop()
                if x in seen:
                    continue
                seen.add(x)
                comp.append(x)
                stack.extend(adj[x] - seen)
            comps.append(sorted(comp))
        return sorted(comps, key=lambda c: (-len(c), c))


# ------------------------------------------------------------------------------------------------------------
# Markdown rendering
# ------------------------------------------------------------------------------------------------------------

def render_markdown(ev: dict, max_sites: int) -> str:
    out = []
    w = out.append
    s = ev["stats"]
    w(f"# Schema evidence — `{os.path.basename(ev['root'])}`\n")
    w(f"Generated {ev['generated_at']} by {ev['tool']}. Facts with file:line, gathered heuristically: verify before "
      f"relying on them; edges marked *weak* and operations marked *inferred* are leads, not proof.\n")
    w("## Summary\n")
    w(f"- {s['tables']} tables, {s['views']} views; {s['code_files']} code files, {s['usage_files']} SQL/XML usage "
      f"files, {s['documents']} documents scanned")
    w(f"- {s['access_sites']} access sites; {s['entry_points']} distinct entry points reach table code")
    flags = defaultdict(list)
    for n, t in ev["tables"].items():
        for f in t["flags"]:
            flags[f].append(n)
    for f, ns in sorted(flags.items()):
        w(f"- **{f}** ({len(ns)}): {', '.join(ns[:25])}{' …' if len(ns) > 25 else ''}")
    if ev["stale_references"]:
        w(f"- **Unknown `{ev['table_prefix']}*` names referenced** (stale or external?): " +
          "; ".join(f"`{k}` at {', '.join(v[:2])}" for k, v in sorted(ev["stale_references"].items())[:20]))
    w("\n## Groups\n")
    w("### By definition file\n")
    for path, names in ev["groups"]["by_definition_file"].items():
        w(f"- `{path}`: {', '.join(names)}")
    w("\n### By foreign-key connectivity\n")
    for comp in ev["groups"]["by_foreign_keys"]:
        w(f"- {', '.join(comp)}")
    w("\n## Tables\n")
    for name, t in ev["tables"].items():
        w(f"### {name}{'' if t['kind'] == 'table' else ' (' + t['kind'] + ')'}\n")
        if t["comment"]:
            w(f"> {t['comment']}\n")
        defs = "; ".join(f"`{d['path']}:{d['line']}` ({d['origin']}{', test' if d['test'] else ''})"
                         for d in t["definitions"][:4])
        if defs:
            w(f"- **Defined**: {defs}")
        if t["mappings"]:
            w("- **Mapped by**: " + "; ".join(f"`{m['type']}` `{m['path']}:{m['line']}` ({m['origin']}"
                                               f"{', name inferred' if m['inferred'] else ''})" for m in t["mappings"][:4]))
        if t["columns"]:
            cols = ", ".join(f"{c['name']} {c['type']}".strip() for c in t["columns"][:14])
            w(f"- **Columns** ({len(t['columns'])}): {cols}{' …' if len(t['columns']) > 14 else ''}")
        rel = []
        if t["references"]:
            rel.append("→ " + ", ".join(t["references"]))
        if t["referenced_by"]:
            rel.append("← " + ", ".join(t["referenced_by"]))
        if t["view_sources"]:
            rel.append("reads " + ", ".join(t["view_sources"]))
        if rel:
            w(f"- **Relations**: {' · '.join(rel)}")
        store = []
        if t["partitioned_by"]:
            store.append(f"partitioned by {t['partitioned_by']} ({t['partitions']} partitions declared)")
        if t["indexes"]:
            store.append(f"{t['indexes']} indexes ({t['unique_indexes']} unique)")
        if t["triggers"]:
            store.append("triggers: " + ", ".join(f"{x['name']} {x['when']} → {x['function']}" for x in t["triggers"]))
        if t["dropped"]:
            store.append(f"dropped at `{t['dropped']['path']}:{t['dropped']['line']}`")
        if store:
            w(f"- **Storage**: {'; '.join(store)}")
        if t["seeds"]:
            w("- **Seeded by migrations**: " + ", ".join(f"{x['op']} `{x['path']}:{x['line']}`" for x in t["seeds"][:4]))
        if t["accessors"]:
            w(f"- **Accessors** ({len(t['accessors'])}):")
            for a in t["accessors"][:max_sites]:
                inf = f" +{a['inferred_ops']}?" if a["inferred_ops"] else ""
                ids = f" [{', '.join(a['ids'][:6])}]" if a["ids"] else ""
                w(f"  - `{a['method']}` **{a['ops'] or '-'}**{inf} — `{a['path']}:{a['line']}`{ids}")
            if len(t["accessors"]) > max_sites:
                w(f"  - … {len(t['accessors']) - max_sites} more")
        if t["entry_points"]:
            w(f"- **Entry points** ({len(t['entry_points'])}):")
            for e in t["entry_points"][:max_sites]:
                route = f" `{e['route']}`" if e["route"] else ""
                chain = " → ".join([e["entry"]] + e["via"])
                weak = " *(weak)*" if e["strength"] == "weak" else ""
                w(f"  - {e['kind']}{route} — `{chain}` (`{e['path']}:{e['line']}`){weak}")
            if len(t["entry_points"]) > max_sites:
                w(f"  - … {len(t['entry_points']) - max_sites} more")
        prod_strings = [m for m in t["string_mentions"] if not is_test_path(m["path"])]
        if prod_strings:
            w("- **Name in other strings** (table name held in a variable or built SQL — read these): " +
              ", ".join(f"`{m['path']}:{m['line']}`" for m in prod_strings[:6]))
        if t["unresolved_callers"]:
            w(f"- **Call chains cut at depth limit**: {', '.join(t['unresolved_callers'][:6])}")
        if t["tests"]:
            w(f"- **Tests** ({len(t['tests'])}): {', '.join('`' + os.path.basename(x) + '`' for x in t['tests'][:10])}")
        if t["docs"]:
            docs = defaultdict(list)
            for d in t["docs"]:
                docs[d["path"]].append(d)
            parts = []
            for path, ds in list(docs.items())[:8]:
                ids = sorted({i for d in ds for i in d["ids"]})[:8]
                parts.append(f"`{path}` ({len(ds)}×; e.g. §{ds[0]['heading'][:50]}){' [' + ', '.join(ids) + ']' if ids else ''}")
            w(f"- **Documents**: {'; '.join(parts)}")
        if t["flags"]:
            w(f"- **Flags**: {', '.join(t['flags'])}")
        w("")
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--json", help="write the full evidence as JSON")
    ap.add_argument("--markdown", help="write a readable evidence report")
    ap.add_argument("--depth", type=int, default=5, help="caller hops to follow towards entry points (default 5)")
    ap.add_argument("--exclude", action="append", default=[], help="directory name to skip (repeatable)")
    ap.add_argument("--include-test-callers", action="store_true", help="walk through test code as callers")
    ap.add_argument("--prefix", help="table-name prefix used to detect stale references (default: auto)")
    ap.add_argument("--max-sites", type=int, default=12, help="accessors/entry points listed per table in Markdown")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    analyzer = Analyzer(args.root, DEFAULT_EXCLUDES | set(args.exclude), args.depth, args.include_test_callers,
                        args.prefix, args.quiet)
    ev = analyzer.run()
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(ev, fh, indent=1, default=lambda o: sorted(o) if isinstance(o, set) else str(o))
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as fh:
            fh.write(render_markdown(ev, args.max_sites))
    if not args.json and not args.markdown:
        sys.stdout.write(render_markdown(ev, args.max_sites))
    s = ev["stats"]
    analyzer.log(f"schema_evidence: {s['tables']} tables, {s['views']} views, {s['access_sites']} access sites, "
                 f"{s['entry_points']} entry points")
    return 0


if __name__ == "__main__":
    sys.exit(main())
