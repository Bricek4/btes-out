CREATE TABLE task_workflow_dispatch (
    task_id UUID PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    workflow_id VARCHAR(128) NOT NULL UNIQUE,
    project_id UUID NOT NULL REFERENCES projects(id),
    task_type VARCHAR(32) NOT NULL,
    source_reference VARCHAR(512) NOT NULL,
    template_version_reference VARCHAR(512) NOT NULL,
    parameters_reference VARCHAR(512) NOT NULL,
    provider_profile_reference VARCHAR(512) NOT NULL,
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (task_type IN ('PROJECT_DOCS','USER_GUIDE','HTML','SCREENSHOT')),
    CHECK (source_reference LIKE 'source://%'),
    CHECK (template_version_reference LIKE 'template://%'),
    CHECK (parameters_reference LIKE 'parameters://%'),
    CHECK (provider_profile_reference LIKE 'provider://%')
);

CREATE INDEX task_workflow_dispatch_pending
    ON task_workflow_dispatch(next_attempt_at, created_at)
    WHERE dispatched_at IS NULL;
