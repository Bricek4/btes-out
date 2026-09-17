CREATE TABLE source_read_runs (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    revision_sha256 CHAR(64) NOT NULL,
    scope VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLANNED','READING','COMPLETE','FAILED')),
    total_entries INTEGER NOT NULL DEFAULT 0 CHECK (total_entries >= 0),
    total_files INTEGER NOT NULL DEFAULT 0 CHECK (total_files >= 0),
    total_chunks INTEGER NOT NULL DEFAULT 0 CHECK (total_chunks >= 0),
    analyzed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (analyzed_chunks >= 0),
    failed_chunks INTEGER NOT NULL DEFAULT 0 CHECK (failed_chunks >= 0),
    skipped_files INTEGER NOT NULL DEFAULT 0 CHECK (skipped_files >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE source_read_files (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES source_read_runs(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    category VARCHAR(16) NOT NULL CHECK (category IN ('SOURCE','CONFIG','DOCUMENT','GENERATED','DEPENDENCY','BINARY')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    analyzed_chunk_count INTEGER NOT NULL DEFAULT 0 CHECK (analyzed_chunk_count >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLANNED','READING','ANALYZED','SKIPPED','FAILED')),
    skip_reason VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(run_id, path)
);

CREATE TABLE source_read_chunks (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES source_read_runs(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES source_read_files(id) ON DELETE CASCADE,
    chunk_id VARCHAR(128) NOT NULL,
    file_path TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    start_offset BIGINT NOT NULL CHECK (start_offset >= 0),
    end_offset BIGINT NOT NULL CHECK (end_offset >= start_offset),
    chunk_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLANNED','READING','ANALYZED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    summary JSONB,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(run_id, chunk_id)
);

CREATE INDEX source_read_files_run_status ON source_read_files(run_id, status, path);
CREATE INDEX source_read_chunks_run_status ON source_read_chunks(run_id, status, file_path, ordinal);
