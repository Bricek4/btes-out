CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    email VARCHAR(320) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, email)
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    owner_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL CHECK (char_length(btrim(idempotency_key)) BETWEEN 1 AND 255),
    input_reference TEXT NOT NULL,
    result_reference TEXT,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (project_id, idempotency_key)
);

CREATE TABLE task_events (
    task_id UUID NOT NULL REFERENCES tasks(id),
    sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_reference TEXT,
    failure_code VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (task_id, sequence)
);
