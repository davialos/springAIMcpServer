# ADR-0001: Starter + autoconfigure packaging with AutoConfiguration.imports
- Status: Proposed · Date: 2026-09-27

## Context
The library must bootstrap by classpath presence in any Spring Boot 4.1 host, and let the host override/disable everything.

## Options considered
1. Single JAR with `@Enable…` annotation the host must add.
2. Split `-autoconfigure` + `-spring-boot-starter` (Spring Boot convention), `@AutoConfiguration` registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Decision
Option 2. All beans `@ConditionalOnMissingBean`; every feature behind `dynamic.ai.agent.<feature>.enabled` (default off except core). No component scanning of host packages.

## Consequences
+ Zero-code enablement, idiomatic, overridable. − More modules to release; need BOM.
Open: final groupId/artifactId/base package (OQ-02).
