#!/usr/bin/env bash
# Bootstrap the local photo_app database.
#
# Works for a NATIVE (non-Docker) local run, and complements the one-shot
# photo-app-liquibase container used by docker-compose. There is deliberately no
# production equivalent - this is a developer convenience only.
#
# Usage:
#   tools/local/setup.sh                 # create schema/user if needed, then migrate
#   tools/local/setup.sh --status        # show pending changesets, change nothing
#   tools/local/setup.sh --drop          # DESTRUCTIVE: drop every object, then migrate
#
# Connection settings come from the environment, with the same defaults the
# database module's pom uses:
#   DB_HOST (localhost)  DB_PORT (3306)  DB_NAME (photo_app)
#   DB_USER (photo_app_user)  DB_PASSWORD (password)
#   DB_ROOT_USER (root)  DB_ROOT_PASSWORD  - only needed to create schema/user
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-photo_app}"
DB_USER="${DB_USER:-photo_app_user}"
DB_PASSWORD="${DB_PASSWORD:-password}"
DB_ROOT_USER="${DB_ROOT_USER:-root}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# tools/local -> tools -> photo-app-api
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

MODE=migrate
case "${1:-}" in
  --status) MODE=status ;;
  --drop)   MODE=drop ;;
  "")       ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

log() { printf '[setup] %s\n' "$*"; }

# --------------------------------------------------------------- preflight
command -v mvn >/dev/null || { echo "mvn not found on PATH" >&2; exit 1; }

log "target ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"

# --------------------------------------------- schema and user (optional)
# Only attempted when a root password is supplied. Without it we assume the
# schema and user already exist, which is the normal case after first run.
if [[ -n "${DB_ROOT_PASSWORD:-}" ]]; then
  if command -v mysql >/dev/null; then
    log "ensuring schema and user exist"
    mysql -h "${DB_HOST}" -P "${DB_PORT}" -u "${DB_ROOT_USER}" -p"${DB_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
SQL
  else
    log "mysql client not on PATH - skipping schema/user creation"
  fi
else
  log "DB_ROOT_PASSWORD not set - assuming schema and user already exist"
fi

# ------------------------------------------------------------- liquibase
# Delegates to the database module, which owns the changelogs. Its pom already
# parameterises db.host/db.port/db.name/db.username/db.password.
#
# Must run WITH the module as the working directory: the pom's searchPath is the
# relative path src/main/resources, which Liquibase resolves against the current
# directory rather than the pom's location. Using -f from elsewhere makes the
# changelog unresolvable.
cd "${PROJECT_ROOT}/database"

MVN_ARGS=(
  -q
  "-Ddb.host=${DB_HOST}" "-Ddb.port=${DB_PORT}" "-Ddb.name=${DB_NAME}"
  "-Ddb.username=${DB_USER}" "-Ddb.password=${DB_PASSWORD}"
)

case "${MODE}" in
  status)
    log "pending changesets:"
    mvn "${MVN_ARGS[@]}" liquibase:status
    ;;
  drop)
    read -r -p "This DROPS every object in ${DB_NAME}. Type the database name to confirm: " confirm
    [[ "${confirm}" == "${DB_NAME}" ]] || { echo "aborted" >&2; exit 1; }
    log "dropping all objects"
    mvn "${MVN_ARGS[@]}" liquibase:dropAll
    log "re-applying changelogs"
    mvn "${MVN_ARGS[@]}" liquibase:update
    ;;
  migrate)
    log "applying changelogs"
    mvn "${MVN_ARGS[@]}" liquibase:update
    ;;
esac

log "done"
