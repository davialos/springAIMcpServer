-- CEL rule engine: schema (PostgreSQL 15+).
-- sys_*  : the parameter library (objects/attributes become CEL variables such as `customer.age`) and message bundles
-- re_*   : tenants, modules, rules, rule groups, trigger points, channels, actions and the evaluation audit

-- ───────────────────────── tenancy and modules ─────────────────────────

create table re_tenant (
    id               bigserial primary key,
    code             varchar(64)  not null unique,
    name             varchar(200) not null,
    default_language varchar(10)  not null default 'en',
    active           boolean      not null default true,
    created_at       timestamptz  not null default now()
);

create table re_organization (
    id         bigserial primary key,
    tenant_id  bigint       not null references re_tenant (id),
    parent_id  bigint       references re_organization (id),
    code       varchar(64)  not null,
    name       varchar(200) not null,
    active     boolean      not null default true,
    created_at timestamptz  not null default now(),
    unique (tenant_id, code)
);

create table re_module (
    id          bigserial primary key,
    code        varchar(64)  not null unique,
    name        varchar(200) not null,
    description varchar(500),
    active      boolean      not null default true
);

-- the modules a tenant has selected
create table re_tenant_module (
    tenant_id bigint  not null references re_tenant (id),
    module_id bigint  not null references re_module (id),
    enabled   boolean not null default true,
    primary key (tenant_id, module_id)
);

-- ───────────────────────── multilingual messages ─────────────────────────

create table sys_language (
    code        varchar(10)  primary key,
    name        varchar(100) not null,
    native_name varchar(100) not null,
    rtl         boolean      not null default false
);

-- one bundle = one message; its texts exist once per language. Placeholders: {customer.age}, {rule.code}
create table sys_bundle (
    id          bigserial primary key,
    code        varchar(100) not null unique,
    description varchar(500)
);

create table sys_bundle_text (
    bundle_id     bigint      not null references sys_bundle (id) on delete cascade,
    language_code varchar(10) not null references sys_language (code),
    text          text        not null,
    primary key (bundle_id, language_code)
);

-- ───────────────────────── parameter library ─────────────────────────

create table sys_object (
    id          bigserial primary key,
    code        varchar(64)  not null unique check (code ~ '^[a-z][A-Za-z0-9_]*$'),
    name        varchar(200) not null,
    description varchar(500),
    module_id   bigint       references re_module (id),
    active      boolean      not null default true,
    updated_at  timestamptz  not null default now()
);

create table sys_object_attribute (
    id            bigserial primary key,
    sys_object_id bigint       not null references sys_object (id) on delete cascade,
    code          varchar(64)  not null check (code ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    name          varchar(200) not null,
    data_type     varchar(20)  not null
        check (data_type in ('STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP',
                             'STRING_LIST', 'INTEGER_LIST', 'DECIMAL_LIST')),
    required      boolean      not null default false,
    description   varchar(500),
    unique (sys_object_id, code)
);

-- ───────────────────────── rules and rule groups ─────────────────────────

create table re_rule (
    id              bigserial primary key,
    tenant_id       bigint       not null references re_tenant (id),
    organization_id bigint       references re_organization (id),   -- null = every organization of the tenant
    module_id       bigint       not null references re_module (id),
    code            varchar(100) not null,
    name            varchar(200) not null,
    description     varchar(1000),
    expression      text         not null,                          -- CEL, must evaluate to a boolean
    true_bundle_id  bigint       references sys_bundle (id),        -- message when the expression is true
    false_bundle_id bigint       references sys_bundle (id),        -- message when it is false
    true_action     varchar(10)  not null default 'ALLOW' check (true_action in ('ALLOW', 'WARN', 'BLOCK')),
    false_action    varchar(10)  not null default 'ALLOW' check (false_action in ('ALLOW', 'WARN', 'BLOCK')),
    status          varchar(10)  not null default 'ACTIVE' check (status in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    version         integer      not null default 1,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now()
);
create unique index ux_re_rule_code on re_rule (tenant_id, coalesce(organization_id, 0), code);

create table re_rule_group (
    id                bigserial primary key,
    tenant_id         bigint       not null references re_tenant (id),
    organization_id   bigint       references re_organization (id),
    module_id         bigint       not null references re_module (id),
    code              varchar(100) not null,
    name              varchar(200) not null,
    description       varchar(1000),
    -- FIRST_MATCH: first rule (by sequence) whose result equals match_on; ALL_MATCH: every rule whose result equals
    -- match_on; EVALUATE_ALL: every rule, true and false messages; COMPOSITE: all rules combined into one result
    evaluation_policy varchar(20)  not null check (evaluation_policy in ('FIRST_MATCH', 'ALL_MATCH', 'EVALUATE_ALL', 'COMPOSITE')),
    match_on          varchar(5)   not null default 'TRUE' check (match_on in ('TRUE', 'FALSE')),
    composite_mode    varchar(10)  not null default 'ALL_TRUE' check (composite_mode in ('ALL_TRUE', 'ANY_TRUE')),
    on_error          varchar(10)  not null default 'AS_FALSE' check (on_error in ('AS_FALSE', 'SKIP')),
    true_bundle_id    bigint       references sys_bundle (id),
    false_bundle_id   bigint       references sys_bundle (id),
    true_action       varchar(10)  not null default 'ALLOW' check (true_action in ('ALLOW', 'WARN', 'BLOCK')),
    false_action      varchar(10)  not null default 'ALLOW' check (false_action in ('ALLOW', 'WARN', 'BLOCK')),
    status            varchar(10)  not null default 'ACTIVE' check (status in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    version           integer      not null default 1,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now()
);
create unique index ux_re_rule_group_code on re_rule_group (tenant_id, coalesce(organization_id, 0), code);

create table re_rule_group_member (
    id       bigserial primary key,
    group_id bigint  not null references re_rule_group (id) on delete cascade,
    rule_id  bigint  not null references re_rule (id),
    sequence integer not null,
    active   boolean not null default true,
    unique (group_id, rule_id)
);
create index ix_re_member_group on re_rule_group_member (group_id, sequence);

-- ───────────────────────── trigger points ─────────────────────────

-- where an integrating application asks for an evaluation: a form action, optionally on one field
create table re_trigger_point (
    id           bigserial primary key,
    tenant_id    bigint       not null references re_tenant (id),
    module_id    bigint       not null references re_module (id),
    code         varchar(100) not null,
    name         varchar(200) not null,
    trigger_type varchar(20)  not null default 'FORM' check (trigger_type in ('FORM')),
    form_code    varchar(100) not null,
    action_type  varchar(30)  not null,          -- SUBMIT, APPROVE, ADD, BUY, CHANGE …
    field_code   varchar(100),                   -- null = the whole form
    active       boolean      not null default true,
    unique (tenant_id, code)
);
create index ix_re_trigger_lookup on re_trigger_point (tenant_id, module_id, form_code, action_type);

create table re_trigger_binding (
    id               bigserial primary key,
    trigger_point_id bigint  not null references re_trigger_point (id) on delete cascade,
    rule_group_id    bigint  not null references re_rule_group (id),
    sequence         integer not null default 1,
    active           boolean not null default true,
    unique (trigger_point_id, rule_group_id)
);

-- ───────────────────────── channels and actions ─────────────────────────

-- the caller's e-mail templates: the id the caller's mail service knows and the name shown in the UI
create table re_email_template (
    id                   bigserial primary key,
    tenant_id            bigint       not null references re_tenant (id),
    external_template_id varchar(100) not null,
    name                 varchar(200) not null,
    description          varchar(500),
    active               boolean      not null default true,
    unique (tenant_id, external_template_id)
);

-- API channels call these; the environment class says how the URL relates to this deployment
create table re_api_endpoint (
    id                bigserial primary key,
    tenant_id         bigint        not null references re_tenant (id),
    name              varchar(200)  not null,
    url               varchar(1000) not null,
    http_method       varchar(10)   not null default 'POST',
    headers           text,                                  -- JSON object
    environment_class varchar(20)   not null check (environment_class in ('SAME_ENVIRONMENT', 'EXTERNAL')),
    external_confirmed boolean      not null default false,  -- EXTERNAL endpoints are used only after a confirmation
    confirmed_by      varchar(200),
    confirmed_at      timestamptz,
    active            boolean       not null default true,
    unique (tenant_id, name)
);

create table re_channel (
    id                bigserial primary key,
    tenant_id         bigint       not null references re_tenant (id),
    name              varchar(200) not null,
    channel_type      varchar(10)  not null check (channel_type in ('EMAIL', 'PUSH', 'API')),
    email_template_id bigint       references re_email_template (id),
    api_endpoint_id   bigint       references re_api_endpoint (id),
    config            text,                                  -- JSON: recipients, recipientPath, topic, title …
    active            boolean      not null default true,
    unique (tenant_id, name),
    check ((channel_type = 'EMAIL') = (email_template_id is not null)),
    check ((channel_type = 'API') = (api_endpoint_id is not null))
);

-- "when this rule / group evaluates to TRUE (FALSE), use this channel"
create table re_action_binding (
    id            bigserial primary key,
    tenant_id     bigint      not null references re_tenant (id),
    rule_id       bigint      references re_rule (id) on delete cascade,
    rule_group_id bigint      references re_rule_group (id) on delete cascade,
    on_outcome    varchar(5)  not null check (on_outcome in ('TRUE', 'FALSE')),
    channel_id    bigint      not null references re_channel (id),
    check ((rule_id is null) <> (rule_group_id is null))
);

-- ───────────────────────── audit ─────────────────────────

create table re_evaluation_log (
    id              uuid primary key,
    tenant_id       bigint       not null references re_tenant (id),
    organization_id bigint,
    module_code     varchar(64)  not null,
    trigger_ref     varchar(300),
    overall_result  varchar(5)   not null,
    action          varchar(10)  not null,
    language        varchar(10)  not null,
    context_keys    text,                                    -- names of the supplied attributes, never their values
    results         text         not null,                   -- JSON: group and rule outcomes
    created_at      timestamptz  not null default now()
);
create index ix_re_eval_tenant_time on re_evaluation_log (tenant_id, created_at desc);

create table re_dispatch_log (
    id            bigserial primary key,
    evaluation_id uuid         not null,
    channel_id    bigint       not null references re_channel (id),
    channel_type  varchar(10)  not null,
    status        varchar(10)  not null check (status in ('SENT', 'FAILED', 'SKIPPED')),
    detail        text,
    created_at    timestamptz  not null default now()
);
create index ix_re_dispatch_eval on re_dispatch_log (evaluation_id);
