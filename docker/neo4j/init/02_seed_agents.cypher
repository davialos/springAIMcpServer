// ──────────────────────────────────────────────────────────────────────────────
// Seed: built-in agent nodes
// Kept minimal — real agents are registered by the app via the admin API.
// This seed exists so Cypher queries in dev have at least one agent to join on.
// ──────────────────────────────────────────────────────────────────────────────

MERGE (:Agent {
  slug:        'demo-agent',
  name:        'Demo Agent',
  description: 'Built-in demo agent for local development',
  version:     1,
  createdAt:   datetime()
});
