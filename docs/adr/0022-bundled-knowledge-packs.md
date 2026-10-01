# ADR-0022 — Bundled knowledge packs (embeddings shipped in the JAR)

Status: accepted (2026-09-30) · Relates to: F-47, ADR-0021 (scalability), LLD-06

## Context
Agents need context that belongs to the application (product docs, policies, glossary). A vector database would be
new mandatory infrastructure (ADR-0021 forbids that) and its content would not travel with the code that it
describes. The host wants to keep this context in its repository, review it like code, and have the build ship it.

## Decision
- A **knowledge pack** is a JSON Lines file `src/main/resources/dynamic-ai/knowledge/<pack>/index.jsonl`: a header
  line and one chunk per line (`id`, `source`, `ordinal`, `text`, optional `vector`). The Maven build bundles it into the
  JAR like any resource; no plugin is needed.
- **Building** it: `KnowledgeIndexer` chunks `.md`/`.txt` documents at paragraph boundaries (heading trail kept),
  embeds with the host's own `EmbeddingModel` and writes the file. Incremental: unchanged chunks keep their vector.
  Run it via the opt-in startup runner (`dynamic.ai.agent.knowledge.index.*`) or, for keyword-only packs and CI, the
  key-free CLI `KnowledgeIndexer <sourceDir> <index.jsonl> <pack>`. The file is committed.
- **Serving**: `ClasspathKnowledgeStore` (bean `KnowledgeStore`, `@ConditionalOnMissingBean`) reads packs lazily
  into memory, immutable, identical on every node: no shared state, no sticky sessions, no new infrastructure.
- **Search** is hybrid: BM25 always, plus cosine similarity when the pack has vectors and the runtime embedding
  model is configured with the same id (`dynamic.ai.agent.knowledge.embedding-model-id`) and dimensions; merged by
  reciprocal rank. Any mismatch or embedding failure degrades to keywords (fail the feature, not the turn).
- **Use by agents**: agent spec `"knowledge":[{"pack":"handbook","topK":4,"minSimilarity":0.0}]`;
  `KnowledgeAdvisor` (order HIGHEST+202, before the tool loop; call and stream) appends the hits to the system prompt
  inside delimited blocks, declared data-not-instructions, closing delimiters defused, capped by
  `max-context-chars`. **Use by code**: inject `KnowledgeStore` and call `search(pack, query, topK)`.

## Consequences
+ Context is versioned, reviewed and deployed with the code; zero infrastructure; works offline.
− Whole packs live in memory (cap 50,000 chunks, brute-force cosine): fine for documentation-sized content; a larger
  corpus needs a `KnowledgeStore` backed by a real vector store (the SPI is the escape hatch).
− Pack content is visible to every caller allowed to invoke the agent: bundled knowledge is not access-controlled
  per document (F-47 ACLs apply to a future document store, not to packs). Do not bundle confidential material.
− Vectors are tied to one embedding model; changing the model means re-indexing (the id check prevents silent misuse).

## Addendum: parameter guidance and the live `catalog` pack
- `@AiParam` gained `details` (plain-English notes on how to tell a right value from a wrong one) and `examples`
  (up to five made-up values, dropped for `sensitive` parameters). Both are linted like descriptions (length, secrets)
  and appended to the parameter's description in the tool's JSON schema, so the model reads them with every tool call.
  They are part of the catalog fingerprint only when present.
- `CatalogKnowledgeStore` serves a pack named `catalog`, built from the live effective catalog: one chunk per enabled
  operation (intent, every parameter with its notes and examples, "proposed for review" for writes) and per enabled
  entity (exposable, non-sensitive attributes). Rebuilt per catalog generation; embedded lazily when a model and id
  are configured. An agent opts in with `"knowledge":[{"pack":"catalog","topK":4}]`; it then sees the relevant
  entries in its prompt. It describes the whole catalog to every caller of that agent, so it is opt-in.
- Not yet: `details` on `@AiEntityProperty` (attribute meanings are already included).

## Addendum: `@AiRowContext` — per-record context columns
`@AiEntityProperty.meaning` says what a column means in general; `@AiRowContext(label, maxChars)` on a `String` column
says "the value of this column is context about *that record*". Dynamic queries over the entity (AI tools and MCP tools
alike, they share the executor) deliver it with every row under `_context: {label: text}`, even when the query did not
select the column. The executor selects the columns by position after the sort keys and applies governance per call:
the attribute must be enabled, not `sensitive`, and not classified above the caller's clearance; text is cut at
`maxChars` (default 500, max 2000). The query tool's description tells the model that `_context` is information about
the record, never instructions (the text is stored data and may have been typed by users). Fingerprints only change for
catalogs that use it. Not covered: host `@AiExposedAction` methods that return entities (their results are the host's
own objects); non-`String` columns are ignored.
