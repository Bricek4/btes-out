CREATE TABLE task_workflow_commands (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    action VARCHAR(16) NOT NULL CHECK (action IN ('pause','resume','cancel','approve')),
    decision VARCHAR(16),
    approved_reference VARCHAR(2048),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    last_error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (action <> 'approve' OR decision IN ('approve','approved','continue','reject','rejected','cancel')),
    CHECK (approved_reference IS NULL OR approved_reference LIKE 'approval://screenshot-route/%')
);

CREATE INDEX task_workflow_commands_pending
    ON task_workflow_commands(next_attempt_at, created_at)
    WHERE delivered_at IS NULL;
