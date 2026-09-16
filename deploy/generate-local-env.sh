#!/usr/bin/env bash
set -euo pipefail

output="${1:-.env}"
umask 077

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required" >&2
  exit 1
fi

hex() { openssl rand -hex "$1"; }
base64_key() { openssl rand -base64 32 | tr -d '\n'; }

cat > "$output" <<EOF
POSTGRES_DB=agent_studio
POSTGRES_USER=agent_studio
POSTGRES_PASSWORD=$(hex 24)
TEMPORAL_DB_PASSWORD=$(hex 24)
TEMPORAL_VERSION=1.29.6
TEMPORAL_ADMINTOOLS_VERSION=1.29.6
SETUP_TOKEN=$(hex 32)
ENCRYPTION_KEY=$(base64_key)
AGENT_WORKER_TOKEN=$(hex 32)
BROWSER_WORKER_TOKEN=$(hex 32)
WORKFLOW_SERVICE_TOKEN=$(hex 32)
WORKFLOW_SERVICE_URL=http://workflow-service:8081
S3_BUCKET=agent-studio
S3_ACCESS_KEY=$(hex 16)
S3_SECRET_KEY=$(hex 32)
BROWSER_LOCAL_MODE=false
BROWSER_ALLOWED_HOSTS=
BROWSER_ALLOWED_ORIGINS=
GIT_ALLOWED_HOSTS=github.com,gitlab.com,bitbucket.org
GIT_MAX_ARCHIVE_BYTES=104857600
GIT_MAX_FILES=20000
GIT_TIMEOUT_SECONDS=90
EOF
chmod 600 "$output"
echo "wrote $output with generated local-only credentials"
