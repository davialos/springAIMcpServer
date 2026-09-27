# ADR-0002: Hexagonal core with technology adapters
- Status: Proposed · Date: 2026-09-27

## Context
The engine touches Spring MVC, JPA, Spring AI, MCP, Spring Security — all fast-moving (Spring AI 1.x→2.x broke APIs).

## Decision
`core` holds domain model, use cases and ports only; no imports of `org.springframework.web`, `jakarta.persistence`, `org.springframework.ai`. Adapters per technology. ArchUnit enforces.

## Consequences
+ Spring AI upgrades are contained in `ai` module; core testable without infra. − Some mapping boilerplate.
