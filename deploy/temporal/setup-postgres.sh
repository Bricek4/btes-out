#!/bin/sh
set -eu

: "${POSTGRES_SEEDS:?POSTGRES_SEEDS is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PWD:?POSTGRES_PWD is required}"

# temporal-sql-tool uses lib/pq's PGPASSWORD convention for authentication. Keep it
# process-local; it is never written to the mounted scripts or emitted by this script.
export PGPASSWORD="${POSTGRES_PWD}"

until nc -z -w 10 "${POSTGRES_SEEDS}" "${DB_PORT:-5432}"; do sleep 2; done

temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT:-5432}" --db temporal create || true
temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT:-5432}" --db temporal setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT:-5432}" --db temporal update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned

temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT:-5432}" --db temporal_visibility create || true
temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT:-5432}" --db temporal_visibility setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep "${POSTGRES_SEEDS}" -u "${POSTGRES_USER}" -p "${DB_PORT:-5432}" --db temporal_visibility update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned
