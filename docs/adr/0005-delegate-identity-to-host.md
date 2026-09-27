# ADR-0005: Delegate identity to host Spring Security; map groups to framework roles
- Status: Proposed · Date: 2026-09-27

## Context
Teams already manage access in corporate IdPs (Entra ID, Okta, Keycloak, LDAP/AD). A second user store would drift and fail audits.

## Decision
Consume `Authentication` from the host; `AuthorityMapper` SPI maps claims/authorities/LDAP groups → framework roles per workspace; membership stored as external subject/group IDs. Own credentials only for service-account API keys.

## Consequences
+ Joiner/mover/leaver handled by IdP; SSO for free. − Depends on quality of group claims (Entra overage → GroupResolver SPI).
