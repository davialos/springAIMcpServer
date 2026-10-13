--liquibase formatted sql

--changeset platform:20261013-0001-create-app-audit-note
--comment: Example change — replace/delete once real changes exist.
CREATE TABLE app_audit_note (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    note       VARCHAR(500) NOT NULL,
    created_at TIMESTAMP    NOT NULL
);
--rollback DROP TABLE app_audit_note;
