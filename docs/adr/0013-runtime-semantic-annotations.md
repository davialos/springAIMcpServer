# ADR-0013: Runtime semantic annotations + JSON policy layers as the source of AI metadata
- Status: Accepted (product owner, 2026-09-28) · Supersedes ADR-0003

## Context
ADR-0003 captured Javadoc with a build-time annotation processor. Build-time integration differs across
Maven, Gradle and IDE builds, Javadoc quality is uneven, and comments are written for developers, not for an LLM.
Production also needs to disable or re-describe a tool instantly without a rebuild.

## Options considered
1. Build-time Javadoc processor (ADR-0003).
2. **Runtime-retained annotations (`@AiContext`, `@AiEntityProperty`, `@AiExposedAction`, `@AiParam`,
   `@AiQueryConstraints`) scanned once at startup from the bean factory and JPA metamodel, merged with a
   policy chain: annotations → classpath/file JSON → dashboard overlays → kill switches.**
3. Pure external configuration (no annotations) — loses developer ownership and compile-time refactoring safety.

## Decision
Option 2 (LLD-02, LLD-03). Only code can expose an element; configuration layers can only disable,
restrict, or re-describe. `readOnly` defaults to `true`.

## Consequences
+ Build-tool agnostic; deterministic opt-in; LLM-oriented descriptions; instant disable without a redeploy; works for Kotlin hosts.
− Host developers must write descriptions (Javadoc isn't reused unless the optional enricher is added); CI diffing moves to a golden-file test (LLD-02 §5).
