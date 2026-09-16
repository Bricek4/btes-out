# Backup and recovery runbook

The production target is a single Linux host with PostgreSQL, Temporal and an external S3-compatible object store. The default recovery objective is **RPO 15 minutes** and **RTO 4 hours** with 30-day backup retention. Backups contain the Platform PostgreSQL database and the object-store bucket; Temporal history is backed up separately through its configured PostgreSQL database.

Before taking or restoring a backup, record the host, database name, S3 bucket, UTC timestamp and operator. Do not put credentials in this document or in a backup directory. Keep the backup directory owner-only (`0700`) and transfer it over an encrypted channel.

## Create a backup

Install `pg_dump` and the AWS CLI on the operations host. For local Compose, run the command from the repository root with `PGHOST=localhost` only when PostgreSQL is published; otherwise run it from a utility container on the Compose network.

```sh
PGHOST=postgres PGPORT=5432 PGUSER=agent_studio PGDATABASE=agent_studio \
PGPASSWORD='(secret from the deployment secret store)' \
S3_ENDPOINT='https://s3.example.com' S3_ACCESS_KEY='(secret)' S3_SECRET_KEY='(secret)' \
S3_BUCKET='agent-studio' ./deploy/backup/backup.sh /var/backups/agent-studio
```

The command creates a UTC-stamped directory containing `platform.dump`, an `objects/` mirror and `SHA256SUMS`. Schedule it every 15 minutes, retain 30 days, and monitor both exit status and resulting object count/bytes. Take a matching dump of the Temporal PostgreSQL database using the same `pg_dump --format=custom` method; restoring Platform data without Temporal history can leave running workflows without their source task row.

## Restore to a staging target

Restore into a fresh staging database and an isolated S3 bucket first. Confirm the migration version, object count, checksum and a sample artifact preview before touching the production target.

```sh
CONFIRM_RESTORE=YES \
PGHOST=staging-db PGPORT=5432 PGUSER=agent_studio PGDATABASE=agent_studio_restore \
PGPASSWORD='(staging secret)' \
S3_ENDPOINT='https://staging-s3.example.com' S3_ACCESS_KEY='(secret)' S3_SECRET_KEY='(secret)' \
S3_BUCKET='agent-studio-restore' ./deploy/backup/restore.sh /var/backups/agent-studio/20260916T060000Z agent_studio_restore
```

The restore script requires the explicit `CONFIRM_RESTORE=YES` guard and uses `pg_restore --clean --if-exists` only against the database named in the command. After restoring, start Platform first so Flyway validates the schema, then start Workflow/Agent/Browser and verify health endpoints, a read-only artifact request and a no-op workflow query. Do not accept new work until those checks pass.

For a production recovery, freeze task creation, snapshot the current volumes, restore PostgreSQL and S3, restore Temporal PostgreSQL, start the services in dependency order from `docker-compose.yml`, and reconcile tasks whose Temporal state is terminal but whose Platform event is missing. Record the measured restore duration and data-loss window in the incident report. Never delete the old volumes until the restored stack has passed the smoke checks and the retention policy permits it.
