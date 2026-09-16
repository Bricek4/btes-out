ALTER TABLE users ADD COLUMN password_hash TEXT;
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN disabled_at TIMESTAMPTZ;

CREATE UNIQUE INDEX users_email_ci ON users (organization_id, lower(email));

CREATE TABLE setup_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    completed_at TIMESTAMPTZ NOT NULL,
    completed_by UUID NOT NULL REFERENCES users(id)
);

CREATE TABLE auth_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('EMAIL_VERIFY','PASSWORD_RESET','SESSION')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE project_revisions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('GIT','ZIP')),
    source_url TEXT,
    source_branch TEXT,
    source_commit VARCHAR(128),
    source_sha256 CHAR(64) NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(project_id, ordinal)
);

CREATE TABLE skills (
    id UUID PRIMARY KEY,
    code VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE templates (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    owner_id UUID REFERENCES users(id),
    skill_id UUID NOT NULL REFERENCES skills(id),
    name VARCHAR(255) NOT NULL,
    visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PERSONAL','PUBLIC')),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((visibility='PERSONAL' AND owner_id IS NOT NULL) OR (visibility='PUBLIC' AND owner_id IS NULL))
);

CREATE TABLE template_versions (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    output_format VARCHAR(32) NOT NULL,
    parameter_schema JSONB NOT NULL,
    form_layout JSONB NOT NULL,
    allowed_sections JSONB NOT NULL,
    markdown_template TEXT,
    html_template TEXT,
    css TEXT,
    validation_rules JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(template_id, ordinal)
);

CREATE TABLE provider_profiles (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    base_url TEXT NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    options JSONB NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(owner_id, name)
);

CREATE UNIQUE INDEX provider_one_default_per_owner ON provider_profiles(owner_id) WHERE is_default;

CREATE TABLE provider_models (
    provider_profile_id UUID NOT NULL REFERENCES provider_profiles(id) ON DELETE CASCADE,
    model_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    capabilities JSONB NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(provider_profile_id, model_id)
);

CREATE UNIQUE INDEX provider_model_one_default ON provider_models(provider_profile_id) WHERE is_default;

CREATE TABLE login_profiles (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reference VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    login_url TEXT NOT NULL,
    login_path TEXT,
    username_locator TEXT NOT NULL,
    password_locator TEXT NOT NULL,
    submit_locator TEXT NOT NULL,
    encrypted_username TEXT NOT NULL,
    encrypted_password TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(owner_id, reference)
);

ALTER TABLE tasks ADD COLUMN owner_id UUID REFERENCES users(id);
ALTER TABLE tasks ADD COLUMN project_revision_id UUID REFERENCES project_revisions(id);
ALTER TABLE tasks ADD COLUMN template_version_id UUID REFERENCES template_versions(id);
ALTER TABLE tasks ADD COLUMN parameters JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE tasks ADD COLUMN provider_profile_id UUID REFERENCES provider_profiles(id);
ALTER TABLE tasks ADD COLUMN model_id VARCHAR(255);
ALTER TABLE tasks ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE task_events ADD COLUMN event_type VARCHAR(64) NOT NULL DEFAULT 'STATUS';
ALTER TABLE task_events ADD COLUMN progress INTEGER;
ALTER TABLE task_events ADD COLUMN message TEXT;
ALTER TABLE task_events ADD COLUMN details JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE task_approvals (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CHOICE','TEXT')),
    prompt TEXT NOT NULL,
    choices JSONB NOT NULL,
    response_text TEXT,
    decision VARCHAR(32),
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    decided_by UUID REFERENCES users(id)
);

CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    current_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE artifact_versions (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    media_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    manifest JSONB NOT NULL,
    verification_report JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(artifact_id, ordinal)
);

CREATE TABLE shares (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('PROJECT','ARTIFACT')),
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(member_id, resource_type, resource_id),
    CHECK(owner_id <> member_id)
);

CREATE TABLE object_reservations (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    artifact_id UUID REFERENCES artifacts(id) ON DELETE CASCADE,
    object_key TEXT NOT NULL UNIQUE,
    media_type VARCHAR(255) NOT NULL,
    expected_size BIGINT NOT NULL CHECK (expected_size >= 0),
    expected_sha256 CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

INSERT INTO skills(id, code, display_name, task_type, active) VALUES
 ('00000000-0000-0000-0000-000000000101','project-docs','Project documentation','PROJECT_DOCS',TRUE),
 ('00000000-0000-0000-0000-000000000102','user-guide','User guide','USER_GUIDE',TRUE),
 ('00000000-0000-0000-0000-000000000103','html','HTML publication','HTML',TRUE),
 ('00000000-0000-0000-0000-000000000104','screenshot','Screenshot set','SCREENSHOT',TRUE);
