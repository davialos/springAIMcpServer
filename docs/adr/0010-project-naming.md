# ADR-0010: Project naming
- Status: Accepted (product owner, 2026-09-27) · Resolves OQ-02 (partially)

## Decision
- Project / product name: **springAIMcpServerCommon**
- Maven artifactIds: `spring-ai-mcp-server-common-<module>` (e.g. `spring-ai-mcp-server-common-core`, `-spring-boot-starter`, `-bom`)
- Base Java package: `<org-domain>.springaimcpservercommon` — org reverse-domain / groupId still TBD (OQ-02b)
- Runtime namespaces unchanged (from the original brief): properties `dynamic.ai.agent.*`, tables `dai_*`, paths `/dynamic-ai/**`. Web Component tag prefix: `saimcp-`.

## Consequences
Renaming runtime namespaces later is a breaking change for hosts — decide before first release whether to align them with the product name (OQ-17).
