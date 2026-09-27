# ADR-0004: Dynamic queries as structured AST compiled to JPA Criteria API
- Status: Proposed · Date: 2026-09-27

## Context
Admins define queries at runtime; the brief suggests Criteria API or parameterized JPQL.

## Options considered
1. Admin-authored JPQL strings with parameters — flexible, but injection-by-admin, hard to validate against allow-lists and row policies.
2. Structured AST (entity, projections, filter tree, sort, page) → Criteria API with bound parameters.
3. Querydsl/jOOQ — extra dependency and codegen in host.

## Decision
Option 2. Row policies appended as mandatory predicates; only `CriteriaQuery` (no update/delete).

## Consequences
+ Validatable, safe by construction, UI-friendly. − Less expressive (no arbitrary subqueries in v1); reporting needs → reviewed native SQL in v2.
