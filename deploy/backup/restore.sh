#!/usr/bin/env bash
set -euo pipefail

if [[ "${CONFIRM_RESTORE:-}" != "YES" ]]; then
  echo 'restore is destructive; set CONFIRM_RESTORE=YES after checking the target' >&2
  exit 2
fi
: "${PGHOST:?PGHOST is required}"
: "${PGPORT:=5432}"
: "${PGUSER:?PGUSER is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${S3_ENDPOINT:?S3_ENDPOINT is required}"
: "${S3_ACCESS_KEY:?S3_ACCESS_KEY is required}"
: "${S3_SECRET_KEY:?S3_SECRET_KEY is required}"
: "${S3_BUCKET:?S3_BUCKET is required}"

source_dir="${1:?usage: CONFIRM_RESTORE=YES ./restore.sh <backup-directory> [target-database]}"
dump="$source_dir/platform.dump"
[[ -r "$dump" ]] || { echo "missing $dump" >&2; exit 1; }
[[ -f "$source_dir/SHA256SUMS" ]] && (cd "$source_dir" && sha256sum --check SHA256SUMS)

target_database="${2:-$PGDATABASE}"
pg_restore --clean --if-exists --no-owner --no-privileges \
  --host="$PGHOST" --port="$PGPORT" --username="$PGUSER" --dbname="$target_database" "$dump"

export AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY"
export AWS_EC2_METADATA_DISABLED=true
command -v aws >/dev/null 2>&1 || { echo 'aws CLI is required to restore objects' >&2; exit 1; }
aws --endpoint-url "$S3_ENDPOINT" s3 sync "$source_dir/objects/" "s3://${S3_BUCKET}/" \
  --no-progress --only-show-errors
printf 'restored backup %s into database %s\n' "$source_dir" "$target_database"
