#!/usr/bin/env bash
set -euo pipefail

# Runs the four Java services from local jars while Compose supplies only stateful
# infrastructure. This shortens the edit-test loop without changing production Compose.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env}"
COMMAND="${2:-start}"
STATE_DIR="${AGENT_STUDIO_LOCAL_STATE_DIR:-$ROOT_DIR/.local-runtime}"
LOG_DIR="$STATE_DIR/logs"
PID_DIR="$STATE_DIR/pids"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "environment file not found: $ENV_FILE" >&2
  echo "create one with: cp deploy/.env.example .env && ./deploy/generate-local-env.sh .env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/docker-compose.yml"
  -f "$ROOT_DIR/deploy/local-infra.override.yml")

mkdir -p "$LOG_DIR" "$PID_DIR"

service_pid() {
  local service="$1"
  [[ -f "$PID_DIR/$service.pid" ]] && cat "$PID_DIR/$service.pid" || true
}

running_pid() {
  local service="$1"
  local pid
  pid="$(service_pid "$service")"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

start_service() {
  local service="$1"
  local jar="$2"
  shift 2
  if running_pid "$service"; then
    echo "$service already running (pid $(service_pid "$service"))"
    return
  fi
  if [[ ! -f "$ROOT_DIR/$jar" ]]; then
    echo "jar not found: $jar" >&2
    exit 1
  fi
  (
    cd "$ROOT_DIR"
    nohup env "$@" java -jar "$ROOT_DIR/$jar" \
      >"$LOG_DIR/$service.log" 2>&1 &
    echo "$!" >"$PID_DIR/$service.pid"
  )
  echo "started $service"
}

stop_service() {
  local service="$1"
  local pid
  pid="$(service_pid "$service")"
  if [[ -z "$pid" ]]; then
    return
  fi
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_DIR/$service.pid"
  echo "stopped $service"
}

wait_for_platform() {
  for _ in {1..60}; do
    if curl --max-time 2 -fsS http://127.0.0.1:8080/actuator/health \
      | grep -q '"status":"UP"'; then
      return
    fi
    sleep 1
  done
  echo "platform API did not become healthy; see $LOG_DIR/platform-api.log" >&2
  exit 1
}

case "$COMMAND" in
  start)
    "${COMPOSE[@]}" up -d postgres minio minio-init mailpit temporal-postgres \
      temporal-admin-tools temporal temporal-create-namespace
    (cd "$ROOT_DIR" && ./mvnw -DskipTests package)
    start_service platform-api platform-api/target/platform-api-0.1.0-SNAPSHOT.jar \
      SERVER_PORT=8080 DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB" \
      DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
      SETUP_TOKEN="$SETUP_TOKEN" ENCRYPTION_KEY="$ENCRYPTION_KEY" \
      AGENT_WORKER_TOKEN="$AGENT_WORKER_TOKEN" BROWSER_WORKER_TOKEN="$BROWSER_WORKER_TOKEN" \
      WORKFLOW_SERVICE_URL=http://127.0.0.1:8081 WORKFLOW_SERVICE_TOKEN="$WORKFLOW_SERVICE_TOKEN" \
      AGENT_WORKER_BASE_URL=http://127.0.0.1:8082 \
      S3_ENDPOINT=http://127.0.0.1:9000 S3_REGION=us-east-1 S3_BUCKET="$S3_BUCKET" \
      S3_ACCESS_KEY="$S3_ACCESS_KEY" S3_SECRET_KEY="$S3_SECRET_KEY" S3_PATH_STYLE=true \
      PUBLIC_BASE_URL=http://localhost:4177 MAIL_FROM="$MAIL_FROM" MAIL_HOST=127.0.0.1 \
      MAIL_PORT=1025 MAIL_USERNAME= MAIL_PASSWORD= MAIL_AUTH=false MAIL_STARTTLS=false
    wait_for_platform
    start_service workflow-service workflow-service/target/workflow-service-0.1.0-SNAPSHOT.jar \
      SERVER_PORT=8081 TEMPORAL_TARGET=127.0.0.1:7233 TEMPORAL_NAMESPACE=default \
      TEMPORAL_TASK_QUEUE=agent-studio-tasks PLATFORM_API_URL=http://127.0.0.1:8080 \
      AGENT_WORKER_BASE_URL=http://127.0.0.1:8082 AGENT_WORKER_TOKEN="$AGENT_WORKER_TOKEN" \
      WORKFLOW_SERVICE_TOKEN="$WORKFLOW_SERVICE_TOKEN"
    start_service browser-worker browser-worker/target/browser-worker-0.1.0-SNAPSHOT.jar \
      SERVER_PORT=8083 PLATFORM_API_URL=http://127.0.0.1:8080 \
      AGENT_WORKER_TOKEN="$AGENT_WORKER_TOKEN" BROWSER_WORKER_TOKEN="$BROWSER_WORKER_TOKEN" \
      BROWSER_LOCAL_MODE="${BROWSER_LOCAL_MODE:-false}" \
      BROWSER_ALLOWED_HOSTS="${BROWSER_ALLOWED_HOSTS:-}" \
      BROWSER_ALLOWED_ORIGINS="${BROWSER_ALLOWED_ORIGINS:-}"
    start_service agent-worker agent-worker/target/agent-worker-0.1.0-SNAPSHOT.jar \
      SERVER_PORT=8082 PLATFORM_API_URL=http://127.0.0.1:8080 \
      BROWSER_WORKER_URL=http://127.0.0.1:8083 AGENT_WORKER_TOKEN="$AGENT_WORKER_TOKEN"
    echo "local services are starting; frontend: npm run dev -- --host 127.0.0.1 --port 4177"
    ;;
  stop)
    stop_service agent-worker
    stop_service browser-worker
    stop_service workflow-service
    stop_service platform-api
    ;;
  status)
    for service in platform-api workflow-service browser-worker agent-worker; do
      if running_pid "$service"; then
        echo "$service: running (pid $(service_pid "$service"))"
      else
        echo "$service: stopped"
      fi
    done
    "${COMPOSE[@]}" ps
    ;;
  *)
    echo "usage: $0 [env-file] [start|stop|status]" >&2
    exit 2
    ;;
esac
