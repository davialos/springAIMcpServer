// ──────────────────────────────────────────────────────────────────────────────
// springAIMcpServerCommon — Neo4j graph schema bootstrap
//
// Run once on first start (mounted as an init script).
// All operations are idempotent: safe to re-run after a container rebuild.
//
// Graph model
// ─────────────────────────────────────────────────────────────────────────────
//
//   (:User)──[:HAS_CONVERSATION]──▶(:Conversation)
//      │                                 │
//      │                         [:HAS_TURN {seq}]
//      │                                 ▼
//      │                             (:Turn)──[:REFERENCED_AGENT]──▶(:Agent)
//      │                                 │
//      │                       [:CONTAINS_MESSAGE]
//      │                                 ▼
//      │                           (:Message {embedding})
//      │
//      └──[:OWNS_RULE_GROUP]──▶(:RuleGroup)──[:HAS_REVISION]──▶(:RuleRevision)
//                                                                      │
//                                                           [:INSPIRED_BY_TURN]
//                                                                      ▼
//                                                                  (:Turn)
//
//   (:Context {embedding})──[:CAPTURED_FROM]──▶(:Turn)
//   (:Context)──────────────[:APPLIES_TO]───────▶(:RuleGroup)
//
// Design rationale
//   • Conversation turns are ordered by :HAS_TURN.seq — no separate list node.
//   • RuleRevisions carry a JSON snapshot of the before/after state so the
//     complete edit history is queryable without joining back to Postgres.
//   • Context nodes hold a vector embedding for semantic retrieval: "find the
//     conversations that shaped rule group X."
//   • All node IDs are UUIDv7 strings matching the Postgres dai_* tables so
//     cross-store joins stay cheap.
// ──────────────────────────────────────────────────────────────────────────────

// ── Uniqueness constraints (also create a backing index) ─────────────────────

CREATE CONSTRAINT user_id_unique IF NOT EXISTS
  FOR (u:User) REQUIRE u.id IS UNIQUE;

CREATE CONSTRAINT agent_slug_unique IF NOT EXISTS
  FOR (a:Agent) REQUIRE a.slug IS UNIQUE;

CREATE CONSTRAINT conversation_id_unique IF NOT EXISTS
  FOR (c:Conversation) REQUIRE c.id IS UNIQUE;

CREATE CONSTRAINT turn_id_unique IF NOT EXISTS
  FOR (t:Turn) REQUIRE t.id IS UNIQUE;

CREATE CONSTRAINT message_id_unique IF NOT EXISTS
  FOR (m:Message) REQUIRE m.id IS UNIQUE;

CREATE CONSTRAINT rule_group_id_unique IF NOT EXISTS
  FOR (rg:RuleGroup) REQUIRE rg.id IS UNIQUE;

CREATE CONSTRAINT rule_revision_id_unique IF NOT EXISTS
  FOR (rr:RuleRevision) REQUIRE rr.id IS UNIQUE;

CREATE CONSTRAINT context_id_unique IF NOT EXISTS
  FOR (ctx:Context) REQUIRE ctx.id IS UNIQUE;

// ── Lookup indexes ────────────────────────────────────────────────────────────

// User external (IdP) subject id — used on every inbound request
CREATE INDEX user_external_id IF NOT EXISTS
  FOR (u:User) ON (u.externalId);

// Conversation → agent slug — list all conversations for an agent
CREATE INDEX conversation_agent_slug IF NOT EXISTS
  FOR (c:Conversation) ON (c.agentSlug);

// Conversation time range queries
CREATE INDEX conversation_created_at IF NOT EXISTS
  FOR (c:Conversation) ON (c.createdAt);

// Turn sequence within a conversation (path traversal hot-path)
CREATE INDEX turn_seq IF NOT EXISTS
  FOR ()-[r:HAS_TURN]-() ON (r.seq);

// Message role filter (USER / ASSISTANT / SYSTEM / TOOL)
CREATE INDEX message_role IF NOT EXISTS
  FOR (m:Message) ON (m.role);

// RuleGroup slug — referenced by the admin API
CREATE INDEX rule_group_slug IF NOT EXISTS
  FOR (rg:RuleGroup) ON (rg.slug);

// RuleGroup workspace — multi-tenant isolation
CREATE INDEX rule_group_workspace IF NOT EXISTS
  FOR (rg:RuleGroup) ON (rg.workspaceId);

// RuleRevision timestamp — audit / history queries
CREATE INDEX rule_revision_created_at IF NOT EXISTS
  FOR (rr:RuleRevision) ON (rr.createdAt);

// ── Full-text indexes ─────────────────────────────────────────────────────────

// Search message content (keyword) — complements the vector similarity index
CREATE FULLTEXT INDEX message_content_fulltext IF NOT EXISTS
  FOR (m:Message) ON EACH [m.content];

// Search rule group names / descriptions
CREATE FULLTEXT INDEX rule_group_fulltext IF NOT EXISTS
  FOR (rg:RuleGroup) ON EACH [rg.name, rg.description];

// ── Retention / cleanup indexes ───────────────────────────────────────────────
// Required so turn- and context-retention sweep queries don't full-scan.

CREATE INDEX turn_created_at IF NOT EXISTS
  FOR (t:Turn) ON (t.createdAt);

CREATE INDEX context_created_at IF NOT EXISTS
  FOR (ctx:Context) ON (ctx.createdAt);

// ── Vector indexes (Neo4j 5.11+ native vector search) ────────────────────────
//
// Default dimension: 768 = Ollama nomic-embed-text (local dev default).
// If you switch to a cloud embedding model update vector.dimensions to match
// and re-create the indexes (DROP INDEX ... / CREATE VECTOR INDEX ...) or
// wipe + re-seed the database:
//   1536 = OpenAI text-embedding-3-small / ada-002
//   3072 = OpenAI text-embedding-3-large
//   1024 = Anthropic voyage-3
//
// NEO4J_EMBEDDING_DIMENSION in .env must match this value AND
// spring.ai.vectorstore.neo4j.embedding-dimension in application-graph.yml.
//
// cosine similarity is standard for text embeddings.

// Message-level semantic search — "find messages similar to this query"
CREATE VECTOR INDEX message_embedding IF NOT EXISTS
  FOR (m:Message) ON (m.embedding)
  OPTIONS {
    indexConfig: {
      `vector.dimensions`: 768,
      `vector.similarity_function`: 'cosine'
    }
  };

// Context-level semantic search — "what context shaped rule group X"
CREATE VECTOR INDEX context_embedding IF NOT EXISTS
  FOR (ctx:Context) ON (ctx.embedding)
  OPTIONS {
    indexConfig: {
      `vector.dimensions`: 768,
      `vector.similarity_function`: 'cosine'
    }
  };

// ── Helper: mark schema version ───────────────────────────────────────────────
// Lets the application assert that the schema is at the expected version
// without running SHOW CONSTRAINTS or SHOW INDEXES.
MERGE (:SchemaVersion {version: '1', appliedAt: datetime()});
