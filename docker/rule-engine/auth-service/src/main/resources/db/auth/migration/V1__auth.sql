-- Auth microservice schema (ADR-0026). Owned by the auth service, in its own schema with its own Flyway history, so it can
-- never collide with the host application or with the library's dynamic_ai schema.
--
-- tenant_id / organization_id are the ids the rule-engine service scopes everything by (dai_re_*.tenant_id,
-- organization_id). They are generated here and travel to the other services only inside the signed access token.

CREATE TABLE re_auth_tenant
(
    id         uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_re_auth_tenant PRIMARY KEY (id),
    CONSTRAINT uq_re_auth_tenant_code UNIQUE (code)
);

CREATE TABLE re_auth_organization
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_re_auth_organization PRIMARY KEY (id),
    CONSTRAINT uq_re_auth_organization_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_re_auth_organization_tenant FOREIGN KEY (tenant_id) REFERENCES re_auth_tenant (id)
);

-- organization_id NULL = a tenant-wide user (works across every organization of the tenant)
CREATE TABLE re_auth_user
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    organization_id uuid,
    username        text        NOT NULL,
    display_name    text        NOT NULL,
    password_hash   text        NOT NULL,
    role            text        NOT NULL,
    enabled         boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_login_at   timestamptz,
    CONSTRAINT pk_re_auth_user PRIMARY KEY (id),
    CONSTRAINT ck_re_auth_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT fk_re_auth_user_tenant FOREIGN KEY (tenant_id) REFERENCES re_auth_tenant (id),
    CONSTRAINT fk_re_auth_user_organization FOREIGN KEY (organization_id) REFERENCES re_auth_organization (id)
);
CREATE UNIQUE INDEX uq_re_auth_user_username ON re_auth_user (lower(username));

-- One row per login attempt. Never holds a password or a token. Feeds the lockout and the operational dashboards.
CREATE TABLE re_auth_login_event
(
    id          bigint GENERATED ALWAYS AS IDENTITY,
    username    text        NOT NULL,
    user_id     uuid,
    outcome     text        NOT NULL,
    remote_addr text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_re_auth_login_event PRIMARY KEY (id),
    CONSTRAINT ck_re_auth_login_event_outcome CHECK (outcome IN ('SUCCESS', 'BAD_CREDENTIALS', 'LOCKED', 'DISABLED'))
);
CREATE INDEX ix_re_auth_login_event_user ON re_auth_login_event (lower(username), occurred_at DESC);
CREATE INDEX ix_re_auth_login_event_time ON re_auth_login_event (occurred_at DESC);
