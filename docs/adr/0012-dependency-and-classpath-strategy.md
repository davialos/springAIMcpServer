# ADR-0012: Dependency & classpath strategy — inherit Spring-managed libraries, shade only internal-only ones
- Status: Proposed · Date: 2026-09-27

## Context
Embedded libraries can clash with host dependency versions. Shading was proposed for Jackson, JSqlParser, and Guava.

## Options considered
1. Shade everything — relocated Jackson would not be the host's `JsonMapper`, would break Spring MVC/Spring AI message conversion and auto-configuration, and would double heap/classes.
2. **Depend only on libraries managed by the Spring Boot 4.1 / Spring AI 2.0 BOMs, at BOM versions; minimize third-party deps; relocate only small internal-only libraries (no Spring integration, never in our public API) into `<base>.internal.shaded`.**

## Decision
Option 2. No Guava. JSqlParser not needed (no SQL/JPQL strings, ADR-0004). JSON-schema validation is the main shading candidate.
Our BOM publishes the tested version set; CI runs the sample hosts against the lowest and highest supported Boot 4.x minor versions.

## Reaffirmed 2026-09-28 (second design note proposed shading Jackson/Netty/OkHttp/JSqlParser)
- Spring Boot 4 uses **Jackson 3** (`tools.jackson.*`); a shaded copy would not be the `JsonMapper` Spring MVC and Spring AI use, so our request/response and tool-schema handling would diverge from the host's.
- We don't depend on Netty (servlet hosts, ADR-0015), OkHttp (provider SDKs bring their own clients, managed by Spring AI's BOM), or JSqlParser (ADR-0004).
- The conflict risk is handled instead by: dependencies only from the Spring Boot/Spring AI BOMs; Maven Enforcer `dependencyConvergence` + `requireUpperBoundDeps` in our build; a published compatibility matrix; CI running sample hosts against the lowest and highest supported Boot 4.x/Spring AI 2.x versions; relocation only for small internal-only libraries.

## Consequences
+ No duplicate frameworks, auto-configuration keeps working. − Hosts must be on a supported Boot 4.x line (documented compatibility matrix).
