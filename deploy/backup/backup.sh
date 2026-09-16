#!/usr/bin/env bash
set -euo pipefail

# Creates a point-in-time PostgreSQL dump and an object-store mirror.  The script is
# intentionally explicit about its target so an operator cannot accidentally back up
# a different environment by relying on a shell's default connection.
: "${PGHOST:?PGHOST is required (for Compose use postgres)}"
: "${PGPORT:=5432}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${S3_ENDPOINT:?S3_ENDPOINT is required}"
: "${S3_ACCESS_KEY:?S3_ACCESS_KEY is required}"
: "${S3_SECRET_KEY:?S3_SECRET_KEY is required}"
: "${S3_BUCKET:?S3_BUCKET is required}"

backup_root="${1:-./backups}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="${backup_root}/${stamp}"
mkdir -p "$destination/objects"
chmod 700 "$destination" "$destination/objects"

export AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY"
export AWS_EC2_METADATA_DISABLED=true

pg_dump --format=custom --no-owner --no-privileges \
  --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" --dbname="$PGDATABASE" \
  --file="$destination/platform.dump"

if command -v aws >/dev/null 2>&1; then
  aws --endpoint-url "$S3_ENDPOINT" s3 sync "s3://${S3_BUCKET}/" "$destination/objects/" \
    --no-progress --only-show-errors
else
  echo "aws CLI is required to mirror the configured S3/MinIO bucket" >&2
  exit 1
fi

sha256sum "$destination/platform.dump" > "$destination/SHA256SUMS"
printf 'created backup %s\n' "$destination"
