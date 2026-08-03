#!/usr/bin/env bash
# Bring the local stack up or down, and clean up the one-shot init containers.
#
# WHY THIS EXISTS
# The Compose specification has no declarative auto-remove: there is no
# auto_remove/rm/oneshot key (all rejected by schema validation) and no
# `docker compose up` flag equivalent to `docker run --rm`. Auto-removal only
# exists on `docker run --rm` and `docker compose run --rm`, and using the latter
# would mean dropping the `depends_on: condition: service_completed_successfully`
# gating that makes the stack correct for anyone running plain `docker compose up`.
#
# So the compose file stays self-sufficient and correct on its own, and this
# wrapper removes the init containers once they have SUCCEEDED. Removal is safe
# because all three jobs are idempotent: Liquibase re-applies nothing, the cert
# job reuses existing certificates, and the user job re-sets the same password.
#
# Usage:
#   tools/local/stack.sh up        # start everything, then remove finished init jobs
#   tools/local/stack.sh down      # stop, KEEPING volumes (data and certs survive)
#   tools/local/stack.sh down -v   # stop and destroy volumes - DESTRUCTIVE
#   tools/local/stack.sh clean     # only remove finished init jobs
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${PROJECT_ROOT}"

# One-shot jobs that should not linger in `docker ps -a` once they have run.
INIT_SERVICES=(photo-app-liquibase photo-app-elk-certs photo-app-elk-users photo-app-logs-init)

log() { printf '[stack] %s\n' "$*"; }

# How long to wait for an init job to finish before giving up on it.
# Liquibase against an empty database with a cold Maven cache is the slow case.
INIT_TIMEOUT="${INIT_TIMEOUT:-300}"

# Block until an init container reaches a terminal state.
#
# `docker compose up -d` returns as soon as every DECLARED dependency is
# satisfied, so it only waits for a job that something depends on. Any job with
# no dependents is not waited for and can still be running when we get here -
# photo-app-liquibase was exactly that case until it was given dependents in
# docker-compose.yml. Polling makes this correct regardless of the dependency
# graph, so the script does not silently depend on that edge existing.
#
# Returns 0 if the container reached a terminal state (or vanished), 1 on timeout.
wait_for_exit() {
  local cid="$1" deadline=$(( SECONDS + INIT_TIMEOUT ))
  while (( SECONDS < deadline )); do
    case "$(docker inspect "${cid}" --format '{{.State.Status}}' 2>/dev/null || echo gone)" in
      exited|dead|gone) return 0 ;;
    esac
    sleep 1
  done
  return 1
}

# Remove an init container only when it exited 0. A non-zero exit is left in
# place on purpose so the failure is still inspectable with `docker logs`.
#
# Every branch below reports what it did. The previous version had no final
# `else`, so a job still in `running` matched neither branch and was skipped in
# total silence - no removal and no message.
clean_init() {
  for svc in "${INIT_SERVICES[@]}"; do
    # container_name is fixed per service, so there is at most one; head -n1
    # keeps a multi-line result from corrupting the docker inspect arguments.
    cid=$(docker compose ps -aq "${svc}" 2>/dev/null | head -n1 || true)
    [[ -z "${cid}" ]] && continue

    if ! wait_for_exit "${cid}"; then
      log "KEEPING ${svc}: still running after ${INIT_TIMEOUT}s - inspect with 'docker logs ${svc}'"
      continue
    fi

    state=$(docker inspect "${cid}" --format '{{.State.Status}}' 2>/dev/null || echo gone)
    [[ "${state}" == "gone" ]] && continue   # already removed elsewhere

    code=$(docker inspect "${cid}" --format '{{.State.ExitCode}}' 2>/dev/null || echo 1)
    if [[ "${code}" != "0" ]]; then
      log "KEEPING ${svc}: exited ${code} - inspect with 'docker logs ${svc}'"
      continue
    fi

    # Check the removal actually happened rather than announcing it on faith.
    if docker rm -f "${cid}" >/dev/null 2>&1; then
      log "removed completed init job ${svc}"
    else
      log "FAILED to remove ${svc} (${cid:0:12}) - still present, remove by hand"
    fi
  done
}

case "${1:-up}" in
  up)
    log "starting stack"
    docker compose up -d
    # `up -d` returns once dependencies are satisfied, so the init jobs have
    # already run to completion by this point.
    clean_init
    log "running services:"
    docker compose ps --format 'table {{.Service}}\t{{.Status}}'
    ;;
  down)
    shift || true
    if [[ "${1:-}" == "-v" ]]; then
      read -r -p "This DESTROYS all volumes (MySQL data, ES data, certs). Type yes to confirm: " c
      [[ "${c}" == "yes" ]] || { echo "aborted" >&2; exit 1; }
      docker compose down -v
    else
      # No -v: volumes are preserved, so database contents and generated
      # certificates survive a restart.
      docker compose down
    fi
    ;;
  clean)
    clean_init
    ;;
  *)
    echo "usage: $0 {up|down [-v]|clean}" >&2
    exit 2
    ;;
esac
